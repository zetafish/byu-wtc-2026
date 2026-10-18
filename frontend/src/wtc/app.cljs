(ns wtc.app
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [wtc.chart :as chart]
            [wtc.flags :refer [flag]]
            [wtc.panels :as panels]))

(def max-selected 8)
;; the Countries view's charts and Compare row, hidden for now: only the table
(def ^:private show-charts? false)
(def poll-ms 60000)
(def bell-poll-ms 20000)  ; around the bell, when runners finish and results are entered
(def default-sim-poll-ms 10000)  ; a playing demo race steps one lap this often

(defonce db (r/atom {:selected {}     ; compared countries: code -> color slot 1..8; empty until picked
                     :starred #{}
                     :favourites #{}  ; favourite country codes
                     :runner-filter #{} ; leaderboard: only these countries (empty = all)
                     :starred-only? false
                     :favourites-only? false ; countries table: only favourites
                     :query ""        ; leaderboard name search (not stored)
                     :show-out-runners? false ; list eliminated runners too
                     :view :race      ; menu view, mirrors the URL hash
                     :team nil        ; team page: country code (#team/CODE), remembered
                     :team-data nil   ; /api/team/CODE of that country
                     :seat nil        ; seat ranking: nil = all seats overview
                     :results {}      ; [panel id, metric] -> query result
                     :variant {}      ; panel id -> index into the panel's :variants
                     :zoom nil        ; panel id shown big in the zoom dialog
                     :open nil        ; runner id shown in the runner dialog
                     :history {}      ; runner id -> lap history, for the runner dialog
                     :median nil}))   ; the field's median lap time per lap [[lap s] ...]

;; --- persistence of viewer preferences (best effort) ---

(defn- load-prefs []
  (try (some-> (js/localStorage.getItem "wtc-prefs") js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- save-prefs! []
  (try (js/localStorage.setItem "wtc-prefs"
                                (js/JSON.stringify (clj->js {:selected (:selected @db)
                                                             :starred (vec (:starred @db))
                                                             :favourites (vec (:favourites @db))
                                                             :show-out-runners (:show-out-runners? @db)
                                                             :runner-filter (vec (:runner-filter @db))
                                                             :starred-only (:starred-only? @db)
                                                             :favourites-only (:favourites-only? @db)
                                                             :variant (:variant @db)
                                                             :sim-poll-ms (:sim-poll-ms @db)
                                                             :team (:team @db)
                                                             :chase-view (:chase-view @db)
                                                             :chase-range (:chase-range @db)
                                                             :team-sort (:team-sort @db)})))
       (catch :default _ nil)))

;; --- API ---

(defn- get-json [path params]
  (let [qs (->> params
                (keep (fn [[k v]] (when (and (some? v) (not= v ""))
                                    (str (name k) "=" (js/encodeURIComponent v)))))
                (str/join "&"))]
    (-> (js/fetch (cond-> path (seq qs) (str "?" qs)))
        (.then (fn [res] (if (.-ok res) (.json res) (throw (js/Error. (str path " " (.-status res)))))))
        (.then #(js->clj % :keywordize-keys true)))))

(defn- selected-codes [] (str/join "," (sort (keys (:selected @db)))))

(declare fetch-runner-times!)

(defn- effective
  "A panel with its chosen variant's keys merged in."
  [{:keys [id variants] :as p}]
  (if (seq variants)
    (merge p (nth variants (get-in @db [:variant id] 0) (first variants)))
    p))

(defonce ^:private fetch-seq (atom 0))

(defn- fetch-panels! []
  ;; quick clicks fire overlapping requests; only the latest round may land
  (let [n (swap! fetch-seq inc)
        latest? #(= n @fetch-seq)]
    (doseq [{:keys [id metric per-country?]} (when show-charts? (map effective panels/panels))]
      (-> (get-json "/api/query" (cond-> {:metric metric}
                                   per-country? (assoc :by "country" :countries (selected-codes))))
          (.then #(when (latest?) (swap! db assoc-in [:results [id metric]] (get-in % [:data :result]))))
          (.catch #(js/console.warn %))))
    (fetch-runner-times! latest?)))

(defn- fetch-runner-times!
  "Lap times for the sparklines: every runner when the leaderboard isn't
  filtered by country (~140 KB gzipped late in the race), else the filtered
  countries plus the starred runners."
  [latest?]
  (let [ids (str/join "," (:starred @db))
        codes (str/join "," (sort (:runner-filter @db)))]
    (-> (js/Promise.all
         (clj->js (if (seq codes)
                    [(get-json "/api/query" {:metric "lap_time_seconds" :countries codes})
                     (if (seq ids) (get-json "/api/query" {:metric "lap_time_seconds" :runners ids}) #js {})]
                    [(get-json "/api/query" {:metric "lap_time_seconds"})])))
        (.then (fn [rs]
                 (let [series (mapcat #(get-in (js->clj % :keywordize-keys true) [:data :result]) rs)]
                   (when (latest?)
                     (swap! db update :lap-times merge
                            (into {} (map (fn [s] [(get-in s [:metric :runner]) (:values s)])) series))))))
        (.catch #(js/console.warn %)))))

(defn- fetch-runner-detail!
  "The runner dialog's data: the runner's lap history and the field median."
  [id]
  (-> (js/Promise.all
       (clj->js [(get-json (str "/api/runners/" (js/encodeURIComponent id)) {})
                 (get-json "/api/query" {:metric "lap_time_median_seconds"})]))
      (.then (fn [[r m]]
               (swap! db #(-> %
                              (assoc-in [:history id] (:history r))
                              (assoc :median (get-in m [:data :result 0 :values]))))))
      (.catch #(js/console.warn %))))

(defn- fetch-team!
  "The team page's data, for the team in the URL."
  []
  (when-let [code (and (= :team (:view @db)) (:team @db))]
    (-> (get-json (str "/api/team/" (js/encodeURIComponent code)) {})
        (.then #(when (= code (:team @db)) (swap! db assoc :team-data %)))
        (.catch #(js/console.warn %)))))

(defn refresh! []
  ;; charts load alongside the rest instead of after it, so they don't pop in late
  (fetch-panels!)
  (fetch-team!)
  (some-> (:open @db) fetch-runner-detail!)
  (-> (js/Promise.all (clj->js [(get-json "/api/race" {})
                                (get-json "/api/countries" {})
                                (get-json "/api/runners" {})]))
      (.then (fn [[race countries runners]]
               (swap! db assoc :race race :countries countries :runners runners
                      :error nil :updated (js/Date.))))
      (.catch #(swap! db assoc :error (.-message %)))))

(defonce ^:private poll-timer (atom nil))

(declare control-sim!)

(defn- poll-delay
  "How long until the next reload of the real race: `bell-poll-ms` from 40
  minutes into a lap (runners start finishing), and after the bell for as
  long as a country still in the race hasn't reported the last lap (results
  can come in late); `poll-ms` otherwise."
  []
  (let [{:keys [race countries]} @db
        start (some-> (:start race) js/Date.parse)
        m (when start (mod (quot (- (.now js/Date) start) 60000) 60))
        last-lap (dec (:current-lap race 0))
        reporting? (some #(and (not (:eliminated-after %)) (:reported-through %)
                               (< (:reported-through %) last-lap))
                         countries)]
    (if (or (and m (>= m 40)) reporting?) bell-poll-ms poll-ms)))

(defn- schedule-poll!
  "(Re)starts the poll timer. The real race reloads every `poll-delay`,
  skipped while the tab is hidden (it reloads when shown again). A demo
  race stands still on the server: while it plays, each tick steps it one
  lap (and reloads); paused, or at the last lap, nothing is polled."
  []
  (js/clearTimeout @poll-timer)
  (let [{:keys [simulation current-lap]} (:race @db)
        {:keys [controls end-lap]} simulation]
    (when (and (:playing? @db) end-lap (>= current-lap end-lap))
      (swap! db assoc :playing? false))
    (cond
      (not controls)
      (reset! poll-timer (js/setTimeout #(do (when-not js/document.hidden (refresh!)) (schedule-poll!))
                                        (poll-delay)))
      (:playing? @db)
      (reset! poll-timer (js/setTimeout #(control-sim! {:action "step" :by 1})
                                        (:sim-poll-ms @db default-sim-poll-ms))))))

;; --- new version ---
;; A release build writes /version.json (a hash of what the page loads). The
;; page remembers the one it started with and checks again every
;; `version-check-ms` and when the tab comes back; after a deploy it offers a
;; reload. Without the file (hot reload in dev) it never checks.

(def ^:private version-check-ms (* 5 60 1000))

(defn- fetch-version []
  (-> (js/fetch "/version.json" #js {:cache "no-store"})
      (.then #(when (.-ok %) (.json %)))
      (.then #(some-> % .-version))
      (.catch (constantly nil))))

(defn- check-version! []
  (-> (fetch-version)
      (.then (fn [v]
               (when (and v (not= v (:version @db)))
                 (swap! db assoc :new-version? true))))))

(defn- watch-version! []
  (-> (fetch-version)
      (.then (fn [v]
               (when v
                 (swap! db assoc :version v)
                 (js/setInterval #(when-not js/document.hidden (check-version!)) version-check-ms)
                 (.addEventListener js/document "visibilitychange"
                                    #(when-not js/document.hidden (check-version!))))))))

;; --- actions ---

(defn- toggle-country! [code]
  (swap! db update :selected
         (fn [sel]
           (cond
             (contains? sel code) (dissoc sel code)
             (>= (count sel) max-selected) sel
             :else (assoc sel code (first (remove (set (vals sel)) (range 1 (inc max-selected))))))))
  (save-prefs!)
  (fetch-panels!))

(defn- select-countries!
  "Compares the first `max-selected` of `codes`, replacing the selection.
  Countries already compared keep their colour slot; the rest take free ones."
  [codes]
  (swap! db update :selected
         (fn [sel]
           (let [codes (take max-selected codes)
                 kept (select-keys sel codes)
                 free (remove (set (vals kept)) (range 1 (inc max-selected)))]
             (into kept (map vector (remove kept codes) free)))))
  (save-prefs!)
  (fetch-panels!))

(defn- ranked-codes [pred] (map :code (filter pred (sort-by :position (:countries @db)))))

(defn- toggle-favourite! [code]
  (swap! db update :favourites #(if (contains? % code) (disj % code) (conj % code)))
  (save-prefs!))

(defn- toggle-filter! [code]
  (swap! db update :runner-filter #(if (contains? % code) (disj % code) (conj % code)))
  (save-prefs!)
  (fetch-panels!))

(defn- toggle-star! [id]
  (swap! db update :starred #(if (contains? % id) (disj % id) (conj % id)))
  (save-prefs!)
  (fetch-panels!))

;; --- view helpers ---

(defn- color [slot] (str "var(--series-" slot ")"))

(defn- mmss [s]
  (when s (str (quot s 60) ":" (.padStart (str (mod s 60)) 2 "0"))))

(defn- hmmss [s]
  (when s (str (quot s 3600) ":" (.padStart (mmss (mod s 3600)) 5 "0"))))

(def ^:private reason-text
  {"dnf" "did not finish" "missing" "no result"})

;; the official stop codes, short for pills and long for tooltips
(def ^:private stop-short {"rtc" "RTC" "inc" "INC" "over" "OVER" "dns" "did not start"})
(def ^:private stop-long
  {"rtc" "RTC: didn't start the next yard"
   "inc" "INC: started a yard, didn't finish it"
   "over" "OVER: finished a yard over the hour"
   "dns" "did not start the race"})

(defn- out-text [{:keys [reason stop]}]
  (or (stop-long stop) (reason-text reason reason)))

(defn- status-badge [{:keys [status reason stop] :as r}]
  (case status
    "running" [:span.pill.in [:span.dot.in] "in race"]
    "finished" [:span.pill.win "★ country winner"]
    [:span.pill.out {:title (out-text r)} [:span.icon "✕"]
     (str "out · " (or (stop-short stop) (reason-text reason reason)))]))

(defn- finished? [] (= "finished" (get-in @db [:race :phase])))

(defn- country-name [code] (some #(when (= code (:code %)) (:name %)) (:countries @db)))

(defn- country-label
  "A country as [flag] name code, everywhere it's shown, linking to its team
  page. The click doesn't reach the row or chip around it."
  [code]
  (let [nm (or (country-name code) code)]
    [:a.clabel {:href (str "#team/" code) :title (str nm " " code ": team page")
                :on-click #(.stopPropagation %)}
     [flag code] [:span.cname nm] " " [:span.code code]]))

(defn- country-face
  "[flag] name code, without a link: for inside buttons and lists."
  [code]
  [:span.clabel [flag code] [:span.cname (or (country-name code) code)] " " [:span.code code]])

(defn- country-select
  "A dropdown of countries with their flags, which a native <select> can't
  show. The button shows `value` (or `placeholder`); the list has a search
  box, favourites first, and leaves out `exclude`. Picking calls `on-pick`
  with the code. Keys: ↑ ↓ move, Enter picks, Esc closes."
  [_]
  (let [open? (r/atom false)
        query (r/atom "")
        active (r/atom 0)
        close! #(do (reset! open? false) (reset! query "") (reset! active 0))]
    (fn [{:keys [value on-pick placeholder exclude label disabled]}]
      (let [{:keys [countries favourites]} @db
            q (str/lower-case (str/trim @query))
            matches? #(or (empty? q)
                          (str/includes? (str/lower-case (:name %)) q)
                          (str/includes? (str/lower-case (:code %)) q))
            pool (->> countries (remove #(contains? (or exclude #{}) (:code %))) (filter matches?) (sort-by :name))
            favs (filter #(contains? favourites (:code %)) pool)
            ;; a favourite shows once, in its group, while not searching
            items (vec (if (and (seq favs) (empty? q))
                         (concat (map #(assoc % :group :fav) favs) (remove #(contains? favourites (:code %)) pool))
                         pool))
            pick! (fn [code] (close!) (on-pick code))
            option (fn [i {:keys [code group]}]
                     ^{:key (str (name (or group :all)) code)}
                     [:li {:id (str "cs-" code) :role "option" :aria-selected (= i @active)
                           :class (when (= i @active) "active")
                           :on-mouse-move #(reset! active i)
                           :on-mouse-down (fn [e] (.preventDefault e) (pick! code))}
                      [country-face code]])]
        ;; focus moving inside (button -> search box) keeps it open; currentTarget,
        ;; unlike a ref, is never briefly nil while it re-renders
        [:div.csel {:on-blur (fn [e] (when-not (.contains (.-currentTarget e) (.-relatedTarget e)) (close!)))}
         [:button.csel-button {:type "button" :aria-haspopup "listbox" :aria-expanded @open? :aria-label label
                               :disabled disabled
                               :on-click #(swap! open? not)}
          (if value [country-face value] [:span.muted placeholder])
          [:span.csel-caret "▾"]]
         (when @open?
           [:div.csel-pop
            [:input.search {:type "search" :placeholder "Search country" :value @query :auto-focus true
                            :role "combobox" :aria-controls "csel-list" :aria-label "Search country"
                            :aria-activedescendant (some->> (get items @active) :code (str "cs-"))
                            :on-change #(do (reset! query (.. % -target -value)) (reset! active 0))
                            :on-key-down (fn [e]
                                           (case (.-key e)
                                             "ArrowDown" (do (.preventDefault e) (swap! active #(min (dec (count items)) (inc %))))
                                             "ArrowUp" (do (.preventDefault e) (swap! active #(max 0 (dec %))))
                                             "Enter" (some-> (get items @active) :code pick!)
                                             "Escape" (close!)
                                             nil))}]
            (if (empty? items)
              [:p.muted.csel-empty "No country matches"]
              [:ul.csel-list {:id "csel-list" :role "listbox"}
               (doall
                (for [[i it] (map-indexed vector items)]
                  [:<> {:key (str i (:code it))}
                   (when (and (= :fav (:group it)) (zero? i)) [:li.csel-group {:role "presentation"} "★ Favourites"])
                   (when (and (nil? (:group it)) (pos? i) (= :fav (:group (get items (dec i)))))
                     [:li.csel-group {:role "presentation"} "All countries"])
                   (option i it)]))])])]))))

(defn- to-series [panel result]
  (let [{:keys [per-country? series-label series-order series-names]} panel
        selected (:selected @db)]
    (cond
      per-country?
      ;; only countries still ticked: a stale response must not flash a
      ;; just-unticked country in a borrowed colour
      (for [{:keys [metric values]} (sort-by #(get selected (get-in % [:metric :country])) result)
            :let [code (:country metric)]
            :when (contains? selected code)]
        {:key code :label (str (or (country-name code) code) " " code) :color (color (get selected code 1)) :values values})

      series-label
      (let [by-key (into {} (map (fn [s] [(get-in s [:metric series-label]) s])) result)]
        (for [[i k] (map-indexed vector series-order)
              :let [s (by-key k)] :when s]
          {:key k :label (get series-names k k) :color (color (inc i)) :values (:values s)}))

      :else
      (for [{:keys [metric values]} result]
        {:key (:__name__ metric) :label (:title panel) :color (color 1) :values values}))))

;; --- components ---

(defn- stat
  ([label value] (stat nil label value))
  ([class label value]
   [:div.stat {:class class} [:span.label label] [:b value]]))

(defn- control-sim!
  "Move the demo race on the server, then reload everything."
  [cmd]
  (-> (js/fetch "/api/sim" #js {:method "POST"
                                :headers #js {"Content-Type" "application/json"}
                                :body (js/JSON.stringify (clj->js cmd))})
      (.then (fn [res] (if (.-ok res) (refresh!) (throw (js/Error. (str "/api/sim " (.-status res)))))))
      (.catch #(swap! db assoc :error (.-message %)))
      ;; the next step counts from this fresh load
      (.finally schedule-poll!)))

(defn- pause-and! [cmd]
  (swap! db assoc :playing? false)
  (js/clearTimeout @poll-timer)
  (control-sim! cmd))

(defn- toggle-play! []
  (if (:playing? (swap! db update :playing? not))
    (control-sim! {:action "step" :by 1})
    (schedule-poll!)))

(def ^:private sim-polls [[1000 "1s"] [2000 "2s"] [5000 "5s"] [10000 "10s"]])

(defn- set-sim-poll! [ms]
  (swap! db assoc :sim-poll-ms ms)
  (save-prefs!)
  (schedule-poll!))

(defn- sim-panel
  "Says the site shows a simulation. For a demo race, video-player
  controls: lap back, play/pause, lap forward, a slider over the race, and
  how often a playing race steps a lap."
  [_]
  (let [scrub (r/atom nil)   ; lap shown while dragging the slider
        timer (atom nil)]
    (fn [{{:keys [controls end-lap mock-data]} :simulation :keys [current-lap final-lap]}]
      (let [end (max 1 (or end-lap final-lap current-lap))
            lap (or @scrub current-lap)
            playing? (:playing? @db)
            every (:sim-poll-ms @db default-sim-poll-ms)]
        [:div.notice.simulation
         [:p [:span.icon.info "◎"] [:b "Simulation. "]
          (when mock-data "Mock data, not real results. ")
          (when controls
            (str "Demo race at yard " current-lap " of " end ", "
                 (if playing? (str "playing one yard every " (/ every 1000) " s.") "paused.")))]
         (when controls
           [:div.sim-controls
            [:div.transport
             [:button.ghost {:title "Previous yard" :aria-label "Previous yard"
                             :disabled (<= current-lap 1) :on-click #(pause-and! {:action "step" :by -1})} "⏮"]
             [:button.ghost.play {:title (if playing? "Pause" "Play") :aria-label (if playing? "Pause" "Play")
                                  :disabled (and (not playing?) (>= current-lap end))
                                  :on-click toggle-play!}
              (if playing? "⏸" "▶")]
             [:button.ghost {:title "Next yard" :aria-label "Next yard"
                             :disabled (>= current-lap end) :on-click #(pause-and! {:action "step" :by 1})} "⏭"]]
            [:input.scrub {:type "range" :min 1 :max end :step 1 :value lap :aria-label "Race yard"
                           :style {"--progress" (str (* 100 (/ (dec lap) (max 1 (dec end)))) "%")}
                           :on-change (fn [e]
                                        ;; keeps playing or paused; no step while dragging
                                        (let [n (js/parseInt (.. e -target -value))]
                                          (reset! scrub n)
                                          (js/clearTimeout @poll-timer)
                                          (js/clearTimeout @timer)
                                          (reset! timer (js/setTimeout
                                                         #(-> (control-sim! {:action "jump" :lap n})
                                                              (.then (fn [] (reset! scrub nil))))
                                                         300))))}]
            [:span.sim-lap (str "Yard " lap " / " end)]
            [:span.sim-label "Yard every"]
            [:div.segmented {:role "group" :aria-label "Step one yard every"}
             (for [[v text] sim-polls]
               ^{:key v}
               [:button {:aria-pressed (= v every) :on-click #(set-sim-poll! v)} text])]])]))))

(defn- header []
  (let [{:keys [race countries error updated]} @db
        failing (remove :ok (vals (:fetches race)))]
    [:<>
     [:header
      [:h1 [:small "Big's Backyard Ultra · Unofficial"] "World Team Championship 2026"]
      [:div.stats
       [stat "lap" "Yard" (:current-lap race "–")]
       [stat "Runners in race" (:running race "–")]
       [stat "Countries in race" (count (filter #(pos? (:active %)) countries))]]]
     [:p.disclaimer "Unofficial live stats, computed from the published results. May differ from the "
      [:a {:href "https://wtc2026.bigsbackyardultra.com/" :target "_blank" :rel "noopener"} "official standings"] "."
      (when updated [:span.muted " Updated " (.toLocaleTimeString updated) "."])]
     (when (:runners race)
       (let [by-status (frequencies (map :status (:runners @db)))]
         [:div.summary
          [:span.chip [:span.dot.in] (str (get by-status "running" 0) " in race")]
          [:span.chip [:span.dot.out] (str (get by-status "out" 0) " out")]
          (when (pos? (get by-status "finished" 0))
            [:span.chip (str "★ " (get by-status "finished") " country winners")])
          [:span.chip (str (count (filter :eliminated-after countries)) " of " (count countries) " countries "
                           (if (= "finished" (:phase race)) "done" "eliminated"))]]))
     (when (:simulation race) [sim-panel race])
     (when (= "pre" (:phase race))
       [:p.notice [:span.icon.info "◷"]
        (str "The race starts "
             (.toLocaleString (js/Date. (:start race)) js/undefined
                              #js {:weekday "long" :day "numeric" :month "long"
                                   :hour "2-digit" :minute "2-digit"})
             " (your local time). Charts fill in after the first yard.")])
     (when (= "finished" (:phase race))
       (let [podium (take 3 (sort-by :position countries))]
         [:div.finished
          [:h2 (str "Race finished after " (:race-laps race) " yards")]
          [:ol.podium
           (for [{:keys [code name rank tied total-laps]} podium]
             ^{:key code}
             [:li [:span.pos (str rank ".")] [:b [country-label code]] [:span (str total-laps " yards")]])]]))
     (when (or error (seq failing))
       [:p.notice [:span.icon "⚠"]
        (if error
          (str "Can't reach the server (" error "). Showing the last data we had.")
          (str "Data source problem (" (str/join ", " (map :error failing)) "). Showing the last good data."))])]))

;; panel :y-format -> tick/tooltip formatter
(def ^:private y-formats {:mmss mmss})

(defn- panel
  "A chart card. `big?` renders it in the zoom dialog: a larger chart, with
  its own legend."
  [_ _]
  (let [table? (r/atom false)]
    (fn [p0 big?]
      (let [{:keys [id title subtitle kind variants] :as p} (effective p0)
            chosen (get-in @db [:variant id] 0)
            results (:results @db)
            rkey [id (:metric p)]
            series (to-series p (get results rkey))
            opts {:series series :x-label (:x-label p) :y-label (:y-label p)
                  :fmt-y (y-formats (:y-format p)) :y-zero? (:y-zero? p true)
                  ;; the Compare chips above the charts are their shared legend
                  :legend? (or big? (not (:per-country? p)))
                  :size (if big? :big :normal)}
            zoomable? (and (not big?) (not @table?) (contains? results rkey))]
        [:section.card
         [:div.card-head
          [:div [:h2 title] (when subtitle [:p.sub subtitle])]
          [:div.head-actions
           (when (seq variants)
             [:div.segmented {:role "group" :aria-label (str title " view")}
              (for [[i {:keys [label]}] (map-indexed vector variants)]
                ^{:key label}
                [:button {:aria-pressed (= i chosen)
                          :on-click #(when-not (= i chosen)
                                       (swap! db assoc-in [:variant id] i)
                                       (save-prefs!)
                                       (fetch-panels!))}
                 label])])
           [:button.ghost {:on-click #(swap! table? not)} (if @table? "Chart" "Table")]
           (when-not big?
             [:button.ghost {:aria-label (str "Enlarge " title) :title "Enlarge"
                             :on-click #(swap! db assoc :zoom id)} "⤢"])]]
         [:div {:class (when zoomable? "zoomable")
                :on-click (when zoomable? #(swap! db assoc :zoom id))}
          (cond
            (and (:per-country? p) (empty? (:selected @db)))
            [chart/placeholder (str "Add countries above to compare them here (up to " max-selected ")")]
            (not (contains? results rkey)) [chart/placeholder ""]
            @table? [chart/data-table opts]
            (= kind :line) [chart/line-chart opts]
            :else [chart/bar-chart opts])]]))))

(defn- zoom-dialog
  "One panel, big, in a modal <dialog>. Esc, ✕ or a click on the backdrop
  closes it."
  [_]
  (let [el (atom nil)
        close #(some-> @el .close)]
    (r/create-class
     {:component-did-mount #(some-> @el .showModal)
      :reagent-render
      (fn [p]
        [:dialog.zoom {:ref #(reset! el %)
                       :on-close #(swap! db assoc :zoom nil)
                       ;; the dialog itself is only hit outside the content: the backdrop
                       :on-click #(when (= (.-target %) @el) (close))}
         [:div.zoom-body
          [:button.close {:aria-label "Close" :on-click close} "✕"]
          [panel p true]]])})))

;; --- ranking ---

(defn- out? [{:keys [eliminated-after]}] (some? eliminated-after))

(defn- running? [r] (= "running" (:status r)))

(defn- race-key [r] [(:laps r) (if (running? r) 1 0)])

(defn- ranked
  "Runners best first, each with :pos. Equal laps share a position, except
  that a runner still going is ahead of one who is out."
  [runners]
  (let [sorted (sort-by (juxt (comp - :laps) #(if (running? %) 0 1) :name) runners)]
    (first (reduce (fn [[acc prev-key prev-pos] [i r]]
                     (let [k (race-key r)
                           pos (if (= k prev-key) prev-pos (inc i))]
                       [(conj acc (assoc r :pos pos)) k pos]))
                   [[] nil 0]
                   (map-indexed vector sorted)))))

;; --- countries ---

(defn- pips
  "One dot per runner: filled for still in, empty for out, then the count.
  `slots` keeps the count in line for a team with fewer starters."
  [active runners slots]
  [:span.pips {:title (str active " of " runners " still in")
               :aria-label (str active " of " runners " still in")}
   (for [i (range (max runners slots))]
     ^{:key i} [:i {:class (cond (< i active) "in" (>= i runners) "none")}])
   [:b active]])

(defn- countries-table []
  (let [{:keys [countries selected favourites favourites-only?]} @db
        lead (reduce max 0 (map :total-laps countries))
        slots (reduce max 0 (map :runners countries))
        fav? #(contains? favourites (:code %))
        ;; eliminated countries always show, greyed: their score still counts
        shown (cond->> countries favourites-only? (filter fav?))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Standings"]
       [:p.sub "Ranked by team score. Star (☆) countries to keep them at hand with \"★ only\". Click a row for the team page."]]
      [:div.head-actions
       [:label.toggle
        [:input {:type "checkbox" :checked favourites-only?
                 :on-change #(do (swap! db update :favourites-only? not) (save-prefs!))}]
        "★ only"]]]
     (if (empty? shown)
       [:div.empty (if favourites-only?
                     "No favourite countries yet. Untick \"★ only\" and star (☆) a country to add it."
                     "No countries to show")]
       [:div.table-wrap.full
        [:table
         [:thead [:tr [:th [:span.sr-only "Favourite"]] [:th.num "#"] [:th "Country"] [:th "In race"] [:th.num "Team score"]
                  [:th.num {:title "Yards behind the leading country's team score"} "Gap"]
                  [:th "Status"]]]
         [:tbody
          (for [{:keys [code name rank tied active runners total-laps eliminated-after winner] :as c} shown
                :let [fav? (contains? favourites code)]]
            ^{:key code}
            ;; the whole row opens the team page (the name is a link for keyboard and new tabs)
            [:tr.clickable {:class [(when (and show-charts? (contains? selected code)) "selected") (when eliminated-after "out")]
                            :on-click #(set! (.-hash js/location) (str "team/" code))}
              [:td [:button.star {:aria-label (str (if fav? "Unfavourite " "Favourite ") name)
                                  :aria-pressed fav?
                                  :on-click (fn [e] (.stopPropagation e) (toggle-favourite! code))}
                    (if fav? "★" "☆")]]
              [:td.num {:title (when tied "shared place")} rank]
              [:td.name [country-label code]]
              [:td [pips active runners slots]]
              [:td.num total-laps]
              [:td.num.muted (let [gap (- total-laps lead)] (if (zero? gap) "–" (str "−" (- gap))))]
              [:td (cond
                     winner [:span.pill.win (str "★ " (:name winner) " · yard " eliminated-after)]
                     eliminated-after [:span.pill.out [:span.icon "✕"] (str "out after yard " eliminated-after)]
                     ;; down to 1 or 0 while the lap isn't settled: this lap decides the country's race
                     (<= active 1) [:span.pill.danger {:title "the team rule: a country goes on while 2 runners finish each yard"}
                                    (if (zero? active) "none left" "1 left")]
                     :else
                     [:span.pill.in [:span.dot.in] "in race"])]])]]])]))

;; --- runner leaderboard ---

;; a backyard lap: 4.167 miles
(def ^:private lap-km 6.7056)

(defn- hm [s] (when s (str (quot s 3600) "h " (.padStart (str (quot (mod s 3600) 60)) 2 "0") "m")))

(defn- runner-stats
  "Figures for the runner dialog, from the timed laps of a lap history."
  [history]
  (let [timed (filterv :seconds history)
        secs (mapv :seconds timed)
        fastest (when (seq timed) (apply min-key :seconds (rseq timed)))
        rests (mapv #(- 3600 %) secs)]
    {:fastest fastest
     :last (peek timed)
     :average (when (seq secs) (js/Math.round (/ (reduce + secs) (count secs))))
     :margin (when (seq rests) (let [l3 (take-last 3 rests)] (js/Math.round (/ (reduce + l3) (count l3)))))
     :rest (reduce + rests)}))

(defn- stat-tile [label value sub]
  [:div.rstat [:span.rstat-label label] [:b value] (when sub [:span.rstat-sub sub])])

(defn- runner-dialog
  "One runner's figures, lap-time chart and lap table in a modal <dialog>.
  Esc, ✕ or a click on the backdrop closes it."
  [_ _]
  (let [el (atom nil)
        close #(some-> @el .close)]
    (r/create-class
     {:component-did-mount #(some-> @el .showModal)
      :reagent-render
      (fn [{:keys [id name country laps pos] :as runner} n-runners]
        (let [{:keys [history median]} @db
              hist (get history id)
              {:keys [fastest last average margin rest]} (runner-stats hist)
              values (into [] (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds]))) hist)
              median-by-lap (into {} median)
              median-values (filterv #(<= (first %) (or (first (peek values)) 0)) median)]
          [:dialog.zoom {:ref #(reset! el %)
                         :on-close #(swap! db assoc :open nil)
                         :on-click #(when (= (.-target %) @el) (close))}
           [:div.zoom-body
            [:button.close {:aria-label "Close" :on-click close} "✕"]
            [:section.card.runner-card
             [:div.card-head
              [:div [:h2 name]
               [:p.sub [country-label country]
                (str " · seat " (:rank runner) (when (:bib runner) (str " · bib " (:bib runner))) " ")
                [status-badge runner]]]]
             (if-not hist
               [:div.loading "Loading…"]
               [:<>
                [:div.rstats
                 [stat-tile "Yards" laps]
                 [stat-tile "Distance" (str (.toFixed (* laps lap-km) 1) " km")]
                 [stat-tile "Fastest yard" (mmss (:seconds fastest)) (some->> (:lap fastest) (str "yard "))]
                 [stat-tile "Average yard" (mmss average)
                  (when average (str (mmss (js/Math.round (/ average lap-km))) " /km"))]
                 [stat-tile "Last yard" (mmss (:seconds last)) (some->> (:lap last) (str "yard "))]
                 [stat-tile "Margin" (mmss margin) "rest per yard, average of the last 3"]
                 [stat-tile "Total rest" (hm rest) "between finishing and the next start"]
                 [stat-tile "Position" [:<> pos [:small (str " / " n-runners)]] "on the leaderboard"]]
                [chart/line-chart
                 {:series [{:key id :label name :color "var(--series-1)" :values values :dots? true}
                           {:key "median" :label "field median" :color "var(--text-muted)"
                            :values median-values :dash? true}]
                  :refs [{:y 3600 :label "limit 60:00"}]
                  :marks (when fastest [{:x (:lap fastest) :y (:seconds fastest)
                                         :color "var(--good)" :label "fastest"}])
                  :legend? false :direct-labels? false
                  :y-zero? false :y-step 300 :fmt-y mmss :x-label "yard" :y-label "yard time" :size :wide}]
                [:ul.legend.runner-legend
                 [:li [:span.key-line {:style {:background "var(--series-1)"}}] name]
                 [:li [:span.key-line.dash] "field median"]
                 [:li [:span.key-line.dash.limit] "limit 60:00"]
                 [:li [:span.dot {:style {:background "var(--good)"}}] "fastest yard"]]
                [:details.all-laps
                 [:summary "All yards"]
                 [:div.table-wrap
                  [:table
                   [:thead [:tr [:th.num "Yard"] [:th.num "Time"] [:th.num "Rest"]
                            [:th.num "Field median"] [:th.num "vs median"]]]
                   [:tbody
                    (for [{:keys [lap seconds]} (reverse hist)
                          :let [med (median-by-lap lap)
                                d (when (and seconds med) (- seconds med))]]
                      ^{:key lap}
                      [:tr [:td.num lap] [:td.num (or (mmss seconds) "–")]
                       [:td.num (some->> seconds (- 3600) mmss)]
                       [:td.num (mmss med)]
                       [:td.num (when d (str (if (neg? d) "−" "+") (mmss (js/Math.abs d))))]])]]]]])]]]))})))

(defn- runner-filters [n-out show-out?]
  (let [{:keys [countries runner-filter starred-only? query]} @db]
    [:div.filters
     [country-select {:on-pick toggle-filter! :placeholder "+ Country" :exclude runner-filter
                      :label "Filter by country"}]
     (for [code (sort runner-filter)]
       ^{:key code}
       [:span.chip [country-label code]
        [:button.remove {:aria-label (str "Remove " code) :on-click #(toggle-filter! code)} "✕"]])
     [:input.search {:type "search" :placeholder "Search runner" :value query :aria-label "Search runner"
                     :on-change #(swap! db assoc :query (.. % -target -value))}]
     [:label.toggle
      [:input {:type "checkbox" :checked starred-only?
               :on-change #(do (swap! db update :starred-only? not) (save-prefs!))}]
      "★ only"]
     (when (and (pos? n-out) (not (finished?)))
       [:label.toggle
        [:input {:type "checkbox" :checked show-out?
                 :on-change #(do (swap! db update :show-out-runners? not) (save-prefs!))}]
        (str "Show eliminated (" n-out ")")])]))

(defn- runners-table []
  (let [{:keys [runners starred open lap-times show-out-runners? runner-filter starred-only? query]} @db
        starred? #(contains? starred (:id %))
        q (str/lower-case (str/trim query))
        candidates (cond->> (ranked runners)
                     (seq runner-filter) (filter #(contains? runner-filter (:country %)))
                     starred-only? (filter starred?)
                     (seq q) (filter #(str/includes? (str/lower-case (:name %)) q)))
        out-runner? #(= "out" (:status %))
        show-out? (or show-out-runners? (finished?))
        n-out (count (filter out-runner? candidates))
        ;; starred runners stay visible so they can still be unstarred
        shown (filter #(or show-out? (not (out-runner? %)) (starred? %)) candidates)
        open-runner (when open (some #(when (= open (:id %)) %) (ranked runners)))]
    [:section.card.wide
     (when open-runner ^{:key open} [runner-dialog open-runner (count runners)])
     [:div.card-head
      [:div [:h2 "Runner leaderboard"]
       [:p.sub "All runners of all countries. Star (☆) runners to keep them at hand with \"★ only\". Click a row for the runner's figures and yard times."]]]
     [runner-filters n-out show-out?]
     (if (empty? shown)
       [:div.empty (cond
                     (seq candidates) "Everyone here is out. Tick \"Show eliminated\" to see them."
                     starred-only? "No starred runners here yet. Star (☆) a runner to add them."
                     :else "No runners match")]
       [:div.table-wrap.full
        ;; fixed columns, so filtering or "★ only" never moves them
        [:table.runners
         ;; the columns it shares with the team page come in the same order and width
         [:colgroup [:col.pos] [:col] [:col.country] [:col.n] [:col.n] [:col.status]
          [:col.time] [:col.time] [:col.time] [:col.total] [:col.n] [:col.spark]]
         [:thead [:tr [:th.num "Pos"] [:th "Runner"] [:th "Country"] [:th.num "Seat"] [:th.num "Yards"]
                  [:th "Status"]
                  [:th.num "Last yard"]
                  [:th.num {:title "Average of the timed yards"} "Avg"]
                  [:th.num "Fastest"]
                  [:th.num {:title "Sum of the timed yards"} "Total"]
                  [:th.num {:title "Best backyard result (yards) in the 2026 qualifying period"} "Best"]
                  [:th "Yard times"]]]
         [:tbody
          (for [{:keys [id name country laps pos] :as runner} shown
                :let [times (get lap-times id)]]
            ^{:key id}
            [:tr.clickable {:class (when (out-runner? runner) "out")
                            :on-click (fn []
                                        (fetch-runner-detail! id)
                                        (swap! db assoc :open id))}
             [:td.num pos]
             [:td.name [:button.star {:aria-label (if (starred? runner) "Unstar" "Star")
                                      :aria-pressed (starred? runner)
                                      :on-click (fn [e] (.stopPropagation e) (toggle-star! id))}
                        (if (starred? runner) "★" "☆")]
              name]
             [:td [country-label country]]
             [:td.num (:rank runner)]
             [:td.num laps]
             [:td [status-badge runner]]
             [:td.num (mmss (:last-seconds runner))]
             [:td.num (mmss (:avg-seconds runner))]
             [:td.num (mmss (:fastest-seconds runner))]
             [:td.num (hmmss (:total-seconds runner))]
             [:td.num (or (:best-yards runner) "–")]
             [:td (when times [chart/sparkline times])]])]]])]))

;; --- seat ranking ---
;; Every runner has a seat (1-15, their roster rank in the team). All runners
;; with the same seat compete across countries; each seat's winner is invited
;; to the Individual Big's Backyard Ultra.

(def seats (range 1 16))

(defn- seat-ranking
  "Runners of seat n, best first, with :pos (see `ranked`)."
  [runners n]
  (ranked (filter #(= n (:rank %)) runners)))

(defn- seat-summary [runners n]
  (let [ranked (seat-ranking runners n)
        in (filter running? ranked)
        leaders (filter #(= 1 (:pos %)) ranked)]
    {:seat n
     :leaders leaders
     :total (count ranked)
     :running (count in)
     :state (cond
              (>= (count in) 2) :open
              (= 1 (count in)) :last-in
              (= 1 (count leaders)) :winner
              :else :tie)}))

(defn- seat-state-pill [{:keys [state running leaders]}]
  (case state
    :open [:span.pill.in [:span.dot.in] (str "open · " running " still in")]
    :last-in [:span.pill.in [:span.dot.in] "last one in"]
    :winner [:span.pill.win "★ winner"]
    :tie [:span.pill.out (str "tie · " (count leaders))]))

(defn- country-key [code]
  (when-let [slot (get (:selected @db) code)]
    [:span.key-box {:style {:background (color slot)}}]))

(defn- seats-overview [runners]
  [:table
   [:thead [:tr [:th.num "Seat"] [:th "Leader"] [:th "Country"] [:th.num "Yards"] [:th "State"]]]
   [:tbody
    (for [n seats
          :let [{:keys [leaders] :as sum} (seat-summary runners n)
                lead (first leaders)]]
      ^{:key n}
      [:tr.clickable {:on-click #(swap! db assoc :seat n)}
       [:td.num [:b n]]
       (if (> (count leaders) 1)
         [:<> [:td.muted (str (count leaders) " tied")] [:td]]
         [:<> [:td.name (:name lead "–")]
          [:td (when lead [:<> [country-key (:country lead)] [country-label (:country lead)]])]])
       [:td.num (:laps lead)]
       [:td [seat-state-pill sum]]])]])

(defn- seat-table [runners n]
  [:table
   [:thead [:tr [:th.num "Pos"] [:th "Runner"] [:th "Country"] [:th.num "Yards"]
            [:th.num {:title "Best backyard result (yards) in the 2026 qualifying period"} "Best"]
            [:th "Status"]]]
   [:tbody
    (for [{:keys [id name country laps best-yards pos] :as r} (seat-ranking runners n)]
      ^{:key id}
      [:tr {:class (when-not (running? r) "out")}
       [:td.num pos]
       [:td.name name]
       [:td [country-key country] [country-label country]]
       [:td.num laps]
       [:td.num (or best-yards "–")]
       [:td [status-badge r]]])]])

(defn- seats-card []
  (let [{:keys [runners seat]} @db]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Seat ranking"]
       [:p.sub "Every runner has a seat (1–15) in their team. Runners in the same seat compete across all countries; each seat's winner is invited to the Individual Big's Backyard Ultra. Click a seat for its full ranking."]]
      [:label.toggle "Seat "
       [:select.seat {:value (or seat "")
                      :on-change #(swap! db assoc :seat (some-> (.. % -target -value) not-empty parse-long))}
        [:option {:value ""} "All seats"]
        (for [n seats] ^{:key n} [:option {:value n} n])]]]
     (if (empty? runners)
       [:div.empty "No runners yet"]
       [:div.table-wrap.full
        (if seat [seat-table runners seat] [seats-overview runners])])]))

;; --- team page ---
;; One country: how it's doing, who's near it (the chase), its runners. All
;; from /api/team/CODE (see wtc.team on the backend).

(defn- ordinal [n]
  (str n (if (<= 11 (mod n 100) 13)
           "th"
           (case (mod n 10) 1 "st" 2 "nd" 3 "rd" "th"))))

(defn- plural [n word]
  (str n " " (cond (= 1 n) word
                   (str/ends-with? word "y") (str (subs word 0 (dec (count word))) "ies")
                   :else (str word "s"))))

(defn- local-time
  "The time now at a UTC offset in hours, as hh:mm."
  [offset]
  (let [d (js/Date. (+ (.now js/Date) (* offset 3600000)))]
    (str (.padStart (str (.getUTCHours d)) 2 "0") ":" (.padStart (str (.getUTCMinutes d)) 2 "0"))))

(def ^:private class-names {"SUPER" "Super Six"})

(defn- go-team! [code] (set! (.. js/window -location -hash) (str "team/" code)))

(defn- team-switcher [code]
  (let [{:keys [countries favourites]} @db
        ordered (vec (sort-by :position countries))
        i (first (keep-indexed #(when (= code (:code %2)) %1) ordered))
        prev (when (and i (pos? i)) (ordered (dec i)))
        next (when i (get ordered (inc i)))
        fav? (contains? favourites code)]
    [:div.team-switch
     [:button.ghost {:disabled (nil? prev) :on-click #(go-team! (:code prev))
                     :title (when prev (str "Ahead: " (:name prev))) :aria-label "Country ahead in the standings"} "←"]
     [country-select {:value code :on-pick go-team! :placeholder "Pick a country" :label "Team"}]
     [:button.ghost {:disabled (nil? next) :on-click #(go-team! (:code next))
                     :title (when next (str "Behind: " (:name next))) :aria-label "Country behind in the standings"} "→"]
     (when code
       [:button.star {:aria-label (if fav? "Unfavourite" "Favourite") :aria-pressed fav?
                      :title (if fav? "Remove from favourites" "Add to favourites")
                      :on-click #(toggle-favourite! code)}
        (if fav? "★" "☆")])]))

(defn- team-rule-text
  "The team rule's state. Until the current lap settles, a country can be down
  to 1 or 0 runners without being decided yet."
  [{:keys [active eliminated-after winner]} current-lap]
  (cond
    winner [:span.pill.win {:title "The last runner of this country to finish a yard alone"}
            (str "★ " (:name winner) " · yard " eliminated-after)]
    eliminated-after [:span.pill.out {:title "No runner finished a yard alone, so no winner"}
                      [:span.icon "✕"] (str "out after yard " eliminated-after)]
    (zero? active) [:span.pill.danger {:title "Nobody is still running this yard"} "none left"]
    (= active 1) [:span.pill.danger {:title "If this runner finishes the yard alone, they're the country's winner"} "1 left"]
    ;; 2 is no danger: two runners can carry a country for a long time (Belgium)
    :else [:span.pill.in {:title "A country continues while at least 2 runners finish each yard"}
           [:span.dot.in] (str active " in")]))

(defn- reporting-text [{:keys [reported-through eliminated-after]} current-lap]
  (when (and reported-through (not eliminated-after))
    (if (< reported-through (dec current-lap))
      [:span.muted (str "still reporting yard " (inc reported-through) "…")]
      [:span.muted (str "yard " reported-through " reported")])))

(defn- team-header [{:keys [country current-lap tied-with]}]
  (let [{:keys [code name total-laps active runners division utc-offset site rank]} country
        n (count (:countries @db))]
    [:section.card.wide.team-head
     [:div.team-title
      [flag code]
      [:div
       [:h2 name " " [:span.code code]]
       [:div.team-rule [team-rule-text country current-lap]]
       [:p.sub
        (when division [:span (get class-names division division)])
        (when site [:span site])
        (when utc-offset [:span (str (local-time utc-offset) " local")])
        [reporting-text country current-lap]]]]
     [:div.rstats
      ;; no "=": "tied with N" below says it's shared
      [stat-tile "Place" [:<> rank [:small (str " / " n)]]
       (when (seq tied-with) (str "tied with " (count tied-with)))]
      [stat-tile "Team score" total-laps nil]
      [stat-tile "Still in" [:<> active [:small (str " / " runners)]] nil]
      [stat-tile "Yard" current-lap nil]]]))

(defn- ahead-of?
  "Whether a rival ranks above country `ours`: more yards, or as many and
  ahead on the tie-break (its :position, the official order)."
  [{:keys [gap position]} ours]
  (or (pos? gap) (and (zero? gap) (< position (:position ours)))))

(defn- chase-bucket
  "Which way a rival goes, seen from our country: :catching-you (below us,
  gaining), :catching (above us, we gain), :cant-catch (below, not gaining),
  :out-of-reach (above, we're not gaining), or :level (as many yards and
  runners in). Above and below follow the place, tie-break included."
  [{:keys [gap gain] :as r} ours]
  (let [above? (ahead-of? r ours)]
    (cond
      (and (zero? gap) (zero? gain)) :level
      (and (not above?) (neg? gain)) :catching-you
      (and above? (pos? gain)) :catching
      above? :out-of-reach
      :else :cant-catch)))

;; The page is about one country and the viewer may be from anywhere, so the
;; chase says "this team", never "you".
(def ^:private buckets
  [[:catching-you "Catching this team" "danger"]
   [:catching "This team is catching" "chance"]
   [:level "Staying level" nil]
   [:cant-catch "Can't catch this team" nil]
   [:out-of-reach "Out of reach" nil]])

;; once this team is out its score is final: nobody is a danger any more,
;; the others will pass it or not
(def ^:private buckets-when-out
  [[:catching-you "Will pass this team" nil]
   [:level "Level" nil]
   [:cant-catch "Stays behind" nil]])

(defn- in-laps [n] (if (= 1 n) "next yard" (str "in " n " yards")))

(defn- rival-chip [{:keys [code name laps-to-pass]} with-laps?]
  [:a.tied {:href (str "#team/" code) :title (str name " " code)}
   [flag code] [:span code]
   (when (and with-laps? laps-to-pass) [:span.gap (in-laps laps-to-pass)])])

(defn- chase-card [{:keys [country chase]}]
  (let [{:keys [ahead level behind field-active]} chase
        ;; level countries go above or below this one by the tie-break
        [level-above level-below] ((juxt filter remove) #(ahead-of? % country) level)
        ;; once out, nothing ahead can change: only being overtaken can
        out? (:eliminated-after country)
        ahead (if out? [] ahead)
        level-above (if out? [] level-above)
        level (concat level-above level-below)
        c (:code country)
        nm (:name country)
        rivals (concat (reverse ahead) level behind)
        by-bucket (group-by #(chase-bucket % country) rivals)
        ;; 8 series colours: overtakes first (soonest first), then the nearest
        charted (set (map :code (take 8 (sort-by (juxt #(if (:laps-to-pass %) 0 1)
                                                        #(or (:laps-to-pass %) 0)
                                                        #(abs (:gap %)))
                                                  (concat ahead behind)))))
        view (:chase-view @db :numbers)
        series (map-indexed (fn [i {:keys [code name series finishers]}]
                              {:key code :label (str name " " code) :color (color (inc i))
                               :values (if (= view :runners) finishers series)})
                            (filter (comp charted :code) (concat (reverse ahead) behind)))
        ;; on the runners chart this team is a line too: muted and dashed, as the gap chart's zero
        series (cond-> (vec series)
                 (= view :runners) (conj {:key c :label (str nm " " c) :color "var(--text-muted)"
                                          :dash? true :values (:finishers chase)}))
        series (if (= :last-12 (:chase-range @db :all))
                 (let [to (reduce max 0 (map (comp first last :values) series))]
                   (map (fn [s] (update s :values #(filterv (fn [[x]] (> x (- to 12))) %))) series))
                 series)
        rivals-note (when (> (count (concat ahead behind)) 8)
                      " With more than 8 rivals, the chart shows those with an overtake first, then the nearest.")
        early? (and (empty? ahead) (empty? behind))
        many-level? (> (count level) 4)
        row (fn [{:keys [code name gap gain active laps-to-pass] :as r}]
              (let [b (chase-bucket r country)]
                ^{:key code}
                [:tr
                 [:td [country-label code]]
                 [:td.num (cond (pos? gap) (str "+" gap) (neg? gap) (str "−" (- gap)) :else "0")]
                 [:td.num (str active " vs " (:active country))]
                 [:td.num {:class (cond (pos? gain) "gain" (neg? gain) "loss")}
                  (cond (pos? gain) (str "+" gain " ▲") (neg? gain) (str "−" (- gain) " ▼") :else "")]
                 [:td {:class (if (:eliminated-after country) "muted" (case b :catching-you "danger" :catching "chance" "muted"))}
                  (if (:eliminated-after country)
                    (case b
                      :catching-you (str "passes this team " (in-laps laps-to-pass))
                      :level "level"
                      "stays behind")
                    (case b
                      :catching-you (str "passes this team " (in-laps laps-to-pass))
                      :catching (str "this team passes " (in-laps laps-to-pass))
                      :level "staying level"
                      :cant-catch "can't catch this team"
                      :out-of-reach "out of reach"))]]))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "The chase"]
       [:p.sub (if (:eliminated-after country)
                 (str "Who can still pass " nm ", if nobody drops.")
                 (str "Who " nm " can catch and who can catch " nm ", if nobody drops."))]]]
     (when (:eliminated-after country)
       [:p.chase-note (str (:name country) "'s race is over: its team score is final at " (:total-laps country) ".")])
     [:div.buckets
      (for [[k label cls] (if (:eliminated-after country) buckets-when-out buckets)
            :let [items (sort-by #(or (:laps-to-pass %) 999) (by-bucket k))]
            :when (seq items)]
        ^{:key k}
        [:div.nb-line {:class cls}
         [:span.label (case cls "danger" (str "⚠ " label) "chance" (str "✓ " label) label)]
         (for [r items] ^{:key (:code r)} [rival-chip r (#{:catching-you :catching} k)])])]
     (when (and early? field-active)
       [:p.chase-note (str nm " has " (:active country) " in; countries still going average " field-active ".")])
     (when-not early?
       [:div.segmented.chase-switch {:role "group" :aria-label "Chase details"}
        (for [[v label] [[:numbers "Numbers"] [:history "Gap over time"] [:runners "Runners in"]]]
          ^{:key v}
          [:button {:aria-pressed (= v view)
                    :on-click #(do (swap! db assoc :chase-view v) (save-prefs!))} label])])
     (when (and (seq rivals) (or early? (= :numbers view)))
       [:div.table-wrap
        [:table.chase-table
         [:thead [:tr [:th "Country"] [:th.num {:title "Their team score minus this team's"} "Gap"]
                  [:th.num {:title "Their runners still in vs this team's"} "Runners"]
                  [:th.num {:title "What this team gains on them each yard: its runners in minus theirs"} "This team gains / yard"]
                  [:th "Overtake"]]]
         [:tbody
          (map row (reverse ahead))
          (if many-level?
            [:tr.muted {:key "level"} [:td {:col-span 5} (str (count level) " countries level with " nm " on " (:total-laps country))]]
            (map row level-above))
          [:tr.you {:key "self"} [:td [country-label c]] [:td.num "0"]
           [:td.num (:active country)] [:td] [:td.muted "this team"]]
          (when-not many-level? (map row level-below))
          (map row behind)]]])
     (when (and (not early?) (#{:history :runners} view))
       [:<>
        [:div.segmented.chase-range {:role "group" :aria-label "Yards shown"}
         (for [[v label] [[:all "All yards"] [:last-12 "Last 12"]]]
           ^{:key v}
           [:button {:aria-pressed (= v (:chase-range @db :all))
                     :on-click #(do (swap! db assoc :chase-range v) (save-prefs!))} label])]
        (if (= view :runners)
          ^{:key "runners"}
          [chart/line-chart {:series series :y-zero? false :x-label "yard" :y-label "runners who finished the yard"
                             :size :wide :legend? true}]
          ^{:key "gap"}
          [chart/line-chart {:series series :refs [{:y 0 :label (:name country) :neutral? true}]
                             :y-zero? false :x-label "yard" :y-label "yards ahead of this team" :size :wide
                             :legend? true}])
        (if (= view :runners)
          [:p.chart-note
           "How many runners each country had finish each yard; " nm " is the dashed line. "
           "Each yard a country's team score grows by this number, so the gap to " nm
           " changes by the difference between its line and the dashed one. A line ends at the last yard the country has reported."
           rivals-note]
          [:p.chart-note
         "Each line is a country's team score minus " (:name country) "'s after each yard. "
         "Above the dashed line it's ahead, below it behind. A line going up is gaining on "
         (:name country) " (more runners in), going down is losing ground, flat means as many runners in; "
         "crossing the dashed line is an overtake. A line ends at the last yard both countries have reported."
         rivals-note])])]))

(defn- team-runners [{:keys [runners]}]
  (let [sort-by-key (:team-sort @db :seat)
        ;; seat order; most yards first; fastest average first (untimed last)
        runners (case sort-by-key
                  :yards (sort-by (juxt (comp - :laps) :rank) runners)
                  :avg (sort-by (juxt #(or (:avg-seconds %) js/Infinity) :rank) runners)
                  (sort-by :rank runners))
        ranked-all (ranked (:runners @db))
        open (:open @db)
        open-runner (when open (some #(when (= open (:id %)) %) ranked-all))]
    [:section.card.wide
     (when open-runner ^{:key open} [runner-dialog open-runner (count (:runners @db))])
     [:div.card-head
      [:div [:h2 "Runners"]
       [:p.sub "Their seat, and their place among all runners in that seat (the race for the individual invites). Click a runner for their yard times."]]
      [:div.segmented {:role "group" :aria-label "Sort runners by"}
       (for [[v label] [[:seat "Seat"] [:yards "Yards"] [:avg "Avg"]]]
         ^{:key v}
         [:button {:aria-pressed (= v sort-by-key)
                   :on-click #(do (swap! db assoc :team-sort v) (save-prefs!))} label])]]
     [:div.table-wrap.full
      ;; same order and widths as the runner leaderboard for the columns they share
      [:table.runners.team-runners
       [:colgroup [:col] [:col.n] [:col.n] [:col.status] [:col.time] [:col.time] [:col.time] [:col.seat-race] [:col.spark]]
       [:thead [:tr [:th "Runner"] [:th.num "Seat"] [:th.num "Yards"] [:th "Status"]
                [:th.num "Last yard"]
                [:th.num {:title "Average of the timed yards"} "Avg"]
                [:th.num {:title "Rest per yard, average of the last 3 timed yards"} "Margin"]
                [:th {:title "Place among all runners in this seat"} "Seat race"]
                [:th "Yard times"]]]
       [:tbody
        (for [{:keys [id name rank laps last-seconds avg-seconds margin-seconds seat-place seat-runners history] :as r} runners]
          ^{:key id}
          [:tr.clickable {:class (when (= "out" (:status r)) "out")
                          :on-click #(do (fetch-runner-detail! id) (swap! db assoc :open id))}
           [:td.name name]
           [:td.num rank]
           [:td.num laps]
           [:td [status-badge r]]
           [:td.num (or (mmss last-seconds) "–")]
           [:td.num (or (mmss avg-seconds) "–")]
           [:td.num (or (mmss margin-seconds) "–")]
           [:td (when seat-place
                  [:a {:href "#seats" :on-click (fn [e] (.stopPropagation e) (swap! db assoc :seat rank))}
                   (str (ordinal seat-place) " of " seat-runners)])]
           [:td [chart/sparkline (vec (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])) history))]]])]]]]))

(defn- lap-shade
  "0-4 by lap time: under 46 min, 46-49, 49-53, 53-57, 57 or more."
  [s]
  (cond (< s 2760) 0 (< s 2940) 1 (< s 3180) 2 (< s 3420) 3 :else 4))

(defn- lap-text
  "What a lap-grid cell says: the lap time and the rest, or what happened.
  Without a result, it depends on the runner: still in and on the current
  lap is running it, still in on an earlier lap is waiting for its result."
  [{:keys [status seconds inferred stop]} runner l current-lap]
  (cond (= "dnf" status) (case stop
                           "rtc" "didn't start it (RTC)"
                           "inc" "started, didn't finish (INC)"
                           "over" "finished over the hour (OVER)"
                           "dns" "did not start the race"
                           "did not finish")
        seconds (str (mmss seconds) " · rest " (mmss (- 3600 seconds)))
        (= "finished" status) (if inferred "finished (missed registration)" "finished, no time")
        (= "winner" (:reason runner)) (str "country winner after yard " (:laps runner))
        (not= "running" (:status runner)) (str "out after yard " (:laps runner))
        (= l current-lap) "running it now"
        :else "no result yet"))

(defn- lap-grid
  "Runners × laps. Hovering (or tapping) a cell shows its lap at once, in a
  tooltip fixed to the viewport, so the grid's scrolling box can't clip it."
  [_]
  (let [tip (r/atom nil)]  ; {:x :y :name :lap :text} of the cell under the pointer
    (fn [{:keys [runners settled-lap current-lap]}]
      (let [n (max 1 (if (pos? settled-lap) current-lap 1))
            show! (fn [e r l lap]
                    (let [rect (.getBoundingClientRect (.-currentTarget e))]
                      (reset! tip {:x (+ (.-left rect) (/ (.-width rect) 2)) :y (.-top rect)
                                   :name (:name r) :lap l :text (lap-text lap r l current-lap)})))]
        [:section.card.wide
         [:div.card-head
          [:div [:h2 "Every yard, every runner"]
           [:p.sub "One cell per yard. Hover or tap a cell for its yard time, or why the runner stopped."]]]
         [:div.lap-grid-wrap {:on-mouse-leave #(reset! tip nil) :on-scroll #(reset! tip nil)}
          [:div.lap-grid {:style {:grid-template-columns (str "minmax(110px, 160px) repeat(" n ", minmax(5px, 1fr))")}}
           (for [{:keys [id name history stop] :as r} runners
                 :let [by-lap (into {} (map (juxt :lap identity)) history)]]
             ^{:key id}
             [:<>
              [:span.lg-name name]
              (if (= "dns" stop)
               [:span.lg-dns {:style {:grid-column (str "span " n)}} "did not start"]
               (for [l (range 1 (inc n))
                    :let [{:keys [status seconds] :as lap} (by-lap l)]]
                ^{:key l}
                [:span {:class [(cond
                                  (= "dnf" status) "lg dnf"
                                  seconds (str "lg s" (lap-shade seconds))
                                  (= "finished" status) "lg notime"
                                  (and (= "running" (:status r)) (= l current-lap)) "lg running"
                                  :else "lg none")
                                (when (and (= name (:name @tip)) (= l (:lap @tip))) "hover")]
                        :aria-label (str name ", yard " l ": " (lap-text lap r l current-lap))
                        :on-mouse-enter #(show! % r l lap)
                        :on-click #(show! % r l lap)}]))])]]
         (when-let [{:keys [x y name lap text]} @tip]
           [:div.lg-tip {:style {:left x :top y}} [:b name] (str " · yard " lap " · " text)])
         [:ul.legend.lg-legend
          (for [[i t] (map-indexed vector ["under 46:00" "46–49" "49–53" "53–57" "57:00 or more"])]
            ^{:key i} [:li [:span {:class (str "lg s" i)}] t])
          [:li [:span.lg.dnf] "stopped"]
          [:li [:span.lg.notime] "no time"]
          [:li [:span.lg.running] "running now"]]]))))

(defn- team-over-time [{:keys [over-time country]}]
  [:section.card
   [:div.card-head [:div [:h2 "Runners still in"] [:p.sub "Runners starting each yard"]]]
   [chart/line-chart {:series [{:key "in" :label (:name country) :color (color 1) :values (:runners-in over-time)}]
                      :x-label "yard" :y-label "runners" :legend? false}]])

(defn- team-place [{:keys [over-time country]}]
  [:section.card
   [:div.card-head [:div [:h2 "Place"] [:p.sub "After each yard (1 is first; ties share a place)"]]]
   [chart/line-chart {:series [{:key "place" :label (:name country) :color (color 1) :values (:place over-time)}]
                      :x-label "yard" :y-label "place" :y-invert? true :legend? false
                      ;; the whole field, 1 to the number of countries
                      :y-domain [1 (max 2 (count (:countries @db)))]}]])

(defn- team-view []
  (let [{:keys [team team-data]} @db]
    [:<>
     [team-switcher team]
     (cond
       (nil? team) [:div.grid [:section.card.wide [:div.empty "Pick a country above to see its team page."]]]
       (nil? team-data) [:div.loading "Loading…"]
       :else [:div.grid
              [team-header team-data]
              [chase-card team-data]
              [team-runners team-data]
              [lap-grid team-data]
              [team-over-time team-data]
              [team-place team-data]])]))

;; --- views ---
;; The left menu switches between views. The view lives in the URL hash so
;; it survives a reload, can be linked and works with the back button.

(def views
  [{:id :race :label "Race"}
   {:id :team :label "Team"}
   {:id :runners :label "Runners"}
   {:id :seats :label "Seats"}])

(defn- parse-hash
  "{:view v} from the URL hash, plus :team for #team/CODE."
  []
  (let [h (subs (.. js/window -location -hash) 1)
        [v code] (str/split h #"/" 2)]
    (cond
      (= v "team") (cond-> {:view :team} (seq code) (assoc :team (str/upper-case code)))
      ;; #countries was the Race view's old name: old links still land there
      :else {:view (or (some #(when (= v (name (:id %))) (:id %)) views) :race)})))

(defn- go-to-hash! []
  (let [{:keys [view team]} (parse-hash)
        changed? (and team (not= team (:team @db)))]
    (swap! db #(cond-> (assoc % :view view)
                 team (assoc :team team)
                 changed? (assoc :team-data nil)))
    (when team (save-prefs!))
    (fetch-team!)))

(defn- nav-count [id]
  (let [{:keys [countries runners]} @db]
    (case id
      :race (count (remove out? countries))
      :team nil
      :runners (count (filter running? runners))
      :seats (count seats))))

(defn- nav-title [id]
  (case id
    :race "countries still in the race"
    :team nil
    :runners "runners still in the race"
    :seats "seats"))

(defn- nav []
  (let [view (:view @db)]
    [:nav.menu {:aria-label "Views"}
     [:div.brand "WTC 2026" [:span (if (get-in @db [:race :simulation]) "Sim" "Live")]]
     [:ul
      (for [{:keys [id label]} views]
        ^{:key id}
        [:li [:a {:href (if (= id :team) (str "#team/" (:team @db)) (str "#" (name id)))
                  :aria-current (when (= id view) "page")}
              (if (= id :team)
                [:<> label (when-let [t (:team @db)] [:span.count {:title "the team you looked at last"} t])]
                [:<> label [:span.count {:title (nav-title id)} (nav-count id)]])]])]]))

(defn- comparing []
  (let [{:keys [selected favourites]} @db]
    [:div.comparing
     [:span.label "Compare"]
     [country-select {:on-pick toggle-country! :exclude (set (keys selected)) :label "Add a country to compare"
                      :disabled (>= (count selected) max-selected)
                      :placeholder (if (>= (count selected) max-selected) (str max-selected " compared, the most") "Add a country")}]
     [:button.ghost {:on-click #(select-countries! (ranked-codes any?))} (str "Top " max-selected)]
     (when (seq favourites)
       [:button.ghost {:title (str "Your favourite countries, the best-ranked " max-selected " if more")
                       :on-click #(select-countries! (ranked-codes (comp favourites :code)))} "★ Favourites"])
     [:button.ghost {:on-click #(select-countries! []) :disabled (empty? selected)} "Clear"]
     (if (empty? selected)
       [:span.muted "no countries compared yet"]
       (for [[code slot] (sort-by val selected)]
         ^{:key code}
         [:span.chip [:span.key-box {:style {:background (color slot)}}] [country-label code]
          [:button.remove {:aria-label (str "Stop comparing " code) :on-click #(toggle-country! code)} "✕"]]))]))

(defn- view-body [view]
  (case view
    :team [team-view]
    :runners [:div.grid [runners-table]]
    :seats [:div.grid [seats-card]]
    ;; race: the Compare chips, all charts (comparison row first), then the ranking
    (if show-charts?
      (let [{compare true overall false} (group-by (comp boolean :per-country?) panels/panels)]
        [:<> [comparing]
         [:div.grid
          (for [p (concat compare overall)] ^{:key (:id p)} [panel p false])
          [countries-table]]])
      [:div.grid [countries-table]])))

(defn- new-version-bar []
  (when (:new-version? @db)
    [:div.new-version {:role "status"}
     [:span "A new version of this site is out."]
     [:button {:on-click #(.reload js/location)} "Reload"]]))

(defn- app []
  [:div.layout
   [new-version-bar]
   [nav]
   [:main
    ;; render the page once, with data, instead of filling in piece by piece
    (if (or (:race @db) (:error @db))
      [:<> [header] [view-body (:view @db)]]
      [:div.loading "Loading live data…"])
    [:footer "Unofficial. Data may lag the official results by a few minutes."]]
   (when-let [p (some #(when (= (:id %) (:zoom @db)) %) panels/panels)]
     ^{:key (:id p)} [zoom-dialog p])])

(defonce root (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load render []
  (rdc/render @root [app]))

(defn init []
  (when-let [{:keys [selected starred favourites show-out-runners runner-filter starred-only favourites-only variant sim-poll-ms team chase-view chase-range team-sort]} (load-prefs)]
    (swap! db assoc
           :team team
           :chase-view (case chase-view "history" :history "runners" :runners :numbers)
           :chase-range (if (= "last-12" chase-range) :last-12 :all)
           :team-sort (case team-sort "yards" :yards "avg" :avg :seat)
           :sim-poll-ms (or sim-poll-ms default-sim-poll-ms)
           :variant (or variant {})
           :selected (into {} (map (fn [[k v]] [(clojure.core/name k) v])) selected)
           :starred (set starred)
           :favourites (set favourites)
           :runner-filter (set runner-filter)
           :starred-only? (true? starred-only)
           :favourites-only? (true? favourites-only)
           :show-out-runners? (true? show-out-runners)))
  (go-to-hash!)
  (watch-version!)
  ;; a tab coming back to the front catches up at once
  (.addEventListener js/document "visibilitychange"
                     #(when (and (not js/document.hidden) (not (get-in @db [:race :simulation :controls])))
                        (refresh!) (schedule-poll!)))
  (.addEventListener js/window "hashchange"
                     #(do (go-to-hash!) (js/window.scrollTo 0 0)))
  (render)
  ;; the first poll waits for the first load, which says if it's a simulation
  (-> (refresh!) (.then schedule-poll!)))
