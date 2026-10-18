(ns wtc.app
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [wtc.chart :as chart]
            [wtc.flags :refer [flag]]
            [wtc.panels :as panels]))

(def max-selected 8)
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
                     :expanded nil    ; country code whose runners are shown in the table
                     :show-out? false ; list eliminated countries too
                     :show-out-runners? false ; list eliminated runners too
                     :view :countries ; menu view, mirrors the URL hash
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
                                                             :show-out (:show-out? @db)
                                                             :show-out-runners (:show-out-runners? @db)
                                                             :runner-filter (vec (:runner-filter @db))
                                                             :starred-only (:starred-only? @db)
                                                             :favourites-only (:favourites-only? @db)
                                                             :variant (:variant @db)
                                                             :sim-poll-ms (:sim-poll-ms @db)
                                                             :team (:team @db)})))
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
    (doseq [{:keys [id metric per-country?]} (map effective panels/panels)]
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

(defn- status-badge [{:keys [status reason]}]
  (case status
    "running" [:span.pill.in [:span.dot.in] "in race"]
    "finished" [:span.pill.win "★ country winner"]
    [:span.pill.out [:span.icon "✕"] (str "out · " (reason-text reason reason))]))

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
            (str "Demo race at lap " current-lap " of " end ", "
                 (if playing? (str "playing one lap every " (/ every 1000) " s.") "paused.")))]
         (when controls
           [:div.sim-controls
            [:div.transport
             [:button.ghost {:title "Previous lap" :aria-label "Previous lap"
                             :disabled (<= current-lap 1) :on-click #(pause-and! {:action "step" :by -1})} "⏮"]
             [:button.ghost.play {:title (if playing? "Pause" "Play") :aria-label (if playing? "Pause" "Play")
                                  :disabled (and (not playing?) (>= current-lap end))
                                  :on-click toggle-play!}
              (if playing? "⏸" "▶")]
             [:button.ghost {:title "Next lap" :aria-label "Next lap"
                             :disabled (>= current-lap end) :on-click #(pause-and! {:action "step" :by 1})} "⏭"]]
            [:input.scrub {:type "range" :min 1 :max end :step 1 :value lap :aria-label "Race lap"
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
            [:span.sim-lap (str "Lap " lap " / " end)]
            [:span.sim-label "Lap every"]
            [:div.segmented {:role "group" :aria-label "Step one lap every"}
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
       [stat "lap" "Lap" (:current-lap race "–")]
       [stat "Runners in race" (:running race "–")]
       [stat "Countries in race" (count (filter #(pos? (:active %)) countries))]]]
     [:p.disclaimer "Unofficial live stats, computed from the published results. May differ from the official standings."
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
             " (your local time). Charts fill in after the first lap.")])
     (when (= "finished" (:phase race))
       (let [podium (take 3 (sort-by :position countries))]
         [:div.finished
          [:h2 (str "Race finished after " (:race-laps race) " laps")]
          [:ol.podium
           (for [{:keys [code name rank tied total-laps]} podium]
             ^{:key code}
             [:li [:span.pos (str (when tied "=") rank ".")] [:b [country-label code]] [:span (str total-laps " laps")]])]]))
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

(defn- mini-status [{:keys [status reason]}]
  (case status
    "running" [:span.dot.in {:title "in race"}]
    "finished" [:span.win {:title "country winner"} "★"]
    [:span.icon {:title (str "out · " (reason-text reason reason))} "✕"]))

;; --- countries ---

(defn- country-runners [{:keys [code name]}]
  (let [rs (->> (:runners @db)
                (filter #(= code (:country %)))
                (sort-by (juxt (comp - :laps) #(if (running? %) 0 1) :rank)))]
    [:tr.detail
     [:td {:col-span 8}
      [:ul.squad
       (for [{:keys [id rank laps] :as r} rs]
         ^{:key id}
         [:li {:class (when-not (running? r) "out")}
          [:span.seat {:title "seat"} rank] [:span.who (:name r)] [:b laps] [mini-status r]])]
      [:a.change {:href "#runners"
                  :on-click #(do (swap! db assoc :runner-filter #{code} :query "")
                                 (save-prefs!) (fetch-panels!))}
       (str "Show " name " in the runner leaderboard →")]]]))

(defn- countries-table []
  (let [{:keys [countries selected favourites favourites-only? show-out? expanded]} @db
        lead (reduce max 0 (map :total-laps countries))
        fav? #(contains? favourites (:code %))
        candidates (cond->> countries favourites-only? (filter fav?))
        n-out (count (filter out? candidates))
        ;; compared and favourite countries stay visible, so they can still be unstarred
        show-out? (or show-out? (finished?))
        shown (filter #(or show-out? (not (out? %)) (contains? selected (:code %)) (fav? %)) candidates)]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Countries"]
       [:p.sub "Ranked by team score. Star (☆) countries to keep them at hand with \"★ only\". Click a row for its runners."]]
      ;; "★ only" last: right-aligned, it stays put when "Show eliminated" hides
      [:div.head-actions
       (when (and (pos? n-out) (not favourites-only?) (not (finished?)))
         [:label.toggle
          [:input {:type "checkbox" :checked show-out?
                   :on-change #(do (swap! db update :show-out? not) (save-prefs!))}]
          (str "Show eliminated (" n-out ")")])
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
         [:thead [:tr [:th [:span.sr-only "Favourite"]] [:th.num "#"] [:th "Country"] [:th.num "In race"] [:th.num "Team score"]
                  [:th.num {:title "Laps behind the leading country's team score"} "Gap"]
                  [:th "Status"] [:th]]]
         [:tbody
          (for [{:keys [code name rank tied active runners total-laps eliminated-after winner] :as c} shown
                :let [fav? (contains? favourites code)
                      open? (= expanded code)
                      toggle-open #(swap! db update :expanded (fn [e] (when-not (= e code) code)))]]
            ^{:key code}
            [:<>
             [:tr.clickable {:class [(when (contains? selected code) "selected") (when eliminated-after "out")]
                             :on-click toggle-open}
              [:td [:button.star {:aria-label (str (if fav? "Unfavourite " "Favourite ") name)
                                  :aria-pressed fav?
                                  :on-click (fn [e] (.stopPropagation e) (toggle-favourite! code))}
                    (if fav? "★" "☆")]]
              [:td.num (when tied "=") rank]
              [:td.name [country-label code]]
              [:td.num active " / " runners]
              [:td.num total-laps]
              [:td.num.muted (let [gap (- total-laps lead)] (if (zero? gap) "–" (str "−" (- gap))))]
              [:td (cond
                     winner [:span.pill.win (str "★ " (:name winner) " · lap " eliminated-after)]
                     eliminated-after [:span.pill.out [:span.icon "✕"] (str "out after lap " eliminated-after)]
                     :else
                     [:span.pill.in [:span.dot.in] "in race"])]
              [:td [:button.expand {:aria-expanded open? :aria-label (str "Runners of " name)
                                    :on-click (fn [e] (.stopPropagation e) (toggle-open))}
                    (if open? "▾" "▸")]]]
             (when open? [country-runners c])])]]])]))

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
                 [stat-tile "Laps" laps]
                 [stat-tile "Distance" (str (.toFixed (* laps lap-km) 1) " km")]
                 [stat-tile "Fastest lap" (mmss (:seconds fastest)) (some->> (:lap fastest) (str "lap "))]
                 [stat-tile "Average lap" (mmss average)
                  (when average (str (mmss (js/Math.round (/ average lap-km))) " /km"))]
                 [stat-tile "Last lap" (mmss (:seconds last)) (some->> (:lap last) (str "lap "))]
                 [stat-tile "Margin" (mmss margin) "rest per lap, average of the last 3"]
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
                  :y-zero? false :y-step 300 :fmt-y mmss :x-label "lap" :y-label "lap time" :size :wide}]
                [:ul.legend.runner-legend
                 [:li [:span.key-line {:style {:background "var(--series-1)"}}] name]
                 [:li [:span.key-line.dash] "field median"]
                 [:li [:span.key-line.dash.limit] "limit 60:00"]
                 [:li [:span.dot {:style {:background "var(--good)"}}] "fastest lap"]]
                [:details.all-laps
                 [:summary "All laps"]
                 [:div.table-wrap
                  [:table
                   [:thead [:tr [:th.num "Lap"] [:th.num "Time"] [:th.num "Rest"]
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
       [:p.sub "All runners of all countries. Star (☆) runners to keep them at hand with \"★ only\". Click a row for the runner's figures and lap times."]]]
     [runner-filters n-out show-out?]
     (if (empty? shown)
       [:div.empty (cond
                     (seq candidates) "Everyone here is out. Tick \"Show eliminated\" to see them."
                     starred-only? "No starred runners here yet. Star (☆) a runner to add them."
                     :else "No runners match")]
       [:div.table-wrap.full
        ;; fixed columns, so filtering or "★ only" never moves them
        [:table.runners
         [:colgroup [:col.pos] [:col] [:col.country] [:col.n] [:col.n] [:col.n] [:col.status]
          [:col.time] [:col.time] [:col.time] [:col.total] [:col.spark]]
         [:thead [:tr [:th.num "Pos"] [:th "Runner"] [:th "Country"] [:th.num "Seat"] [:th.num "Laps"]
                  [:th.num {:title "Best backyard result (laps) in the 2026 qualifying period"} "Best"]
                  [:th "Status"]
                  [:th.num "Last lap"] [:th.num "Fastest"]
                  [:th.num {:title "Average of the timed laps"} "Avg"]
                  [:th.num {:title "Sum of the timed laps"} "Total"]
                  [:th "Lap times"]]]
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
             [:td.num (or (:best-yards runner) "–")]
             [:td [status-badge runner]]
             [:td.num (mmss (:last-seconds runner))]
             [:td.num (mmss (:fastest-seconds runner))]
             [:td.num (mmss (:avg-seconds runner))]
             [:td.num (hmmss (:total-seconds runner))]
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
   [:thead [:tr [:th.num "Seat"] [:th "Leader"] [:th "Country"] [:th.num "Laps"] [:th "State"]]]
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
   [:thead [:tr [:th.num "Pos"] [:th "Runner"] [:th "Country"] [:th.num "Laps"]
            [:th.num {:title "Best backyard result (laps) in the 2026 qualifying period"} "Best"]
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

(defn- place-text [{:keys [rank tied]}] (str (when tied "=") rank))

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

(defn- team-rule-text [{:keys [active eliminated-after winner]}]
  (cond
    winner [:span.pill.win (str "★ won by " (:name winner) " on lap " eliminated-after)]
    eliminated-after [:span.pill.out [:span.icon "✕"] (str "out after lap " eliminated-after ", no winner")]
    (= active 2) [:span.pill.danger "2 left: the next drop ends the race"]
    :else [:span.pill.in [:span.dot.in] (str "safe: " active " still in")]))

(defn- reporting-text [{:keys [reported-through eliminated-after]} current-lap]
  (when (and reported-through (not eliminated-after))
    (if (< reported-through (dec current-lap))
      [:span.muted (str "still reporting lap " (inc reported-through) "…")]
      [:span.muted (str "lap " reported-through " reported")])))

(defn- team-header [{:keys [country current-lap chase tied-with]}]
  (let [{:keys [code name total-laps active runners division utc-offset site rank]} country
        ahead (first (:ahead chase))
        n (count (:countries @db))]
    [:section.card.wide.team-head
     [:div.team-title
      [flag code]
      [:div
       [:h2 name " " [:span.code code]]
       [:div.team-rule [team-rule-text country]]
       [:p.sub
        (when division [:span (get class-names division division)])
        (when site [:span site])
        (when utc-offset [:span (str (local-time utc-offset) " local")])
        [reporting-text country current-lap]]]]
     [:div.rstats
      [stat-tile "Place" [:<> (place-text country) [:small (str " / " n)]]
       (when (seq tied-with) (str "tied with " (count tied-with)))]
      [stat-tile "Team score" total-laps
       (cond
         ahead (str (:gap ahead) " behind " (:name ahead))
         (seq (:behind chase)) (str (- (:gap (first (:behind chase)))) " ahead of " (:name (first (:behind chase))))
         :else nil)]
      [stat-tile "Still in" [:<> active [:small (str " / " runners)]] nil]
      [stat-tile "Lap" current-lap nil]]
     ;; the neighbourhood: the same countries as in the chase below
     (let [{:keys [ahead level behind]} chase
           line (fn [label items]
                  (when (seq items)
                    [:div.nb-line
                     [:span.label label]
                     (for [{c :code nm :name gap :gap active :active} items]
                       ^{:key c}
                       [:a.tied {:href (str "#team/" c)
                                 :title (str nm " " c (when active (str ", " active " still in")))}
                        [flag c] [:span c]
                        (when (and gap (not (zero? gap)))
                          [:span.gap (if (pos? gap) (str "+" gap) (str "−" (- gap)))])])]))]
       (when (or (seq ahead) (seq level) (seq behind))
         [:div.neighbours
          [line "Chasing" ahead]
          [line (str "Level on " total-laps) (map (fn [c] {:code c :name (country-name c)}) level)]
          [line "Chased by" behind]]))]))

(defn- rival-sentence
  "The chase in words, for one rival `r` seen from `ours`."
  [ours {:keys [code name gap gain laps-to-pass active eliminated-after total-laps]}]
  (let [we-out? (:eliminated-after ours)
        who [:b name]
        ins (str " with " active " in")]
    (cond
      (pos? gap)
      (cond
        we-out? [:<> who " is " gap " ahead: out of reach now."]
        eliminated-after [:<> who " finished on " total-laps ". You pass them in " (plural laps-to-pass "lap")
                          ", as long as 2 of yours keep going."]
        (pos? gain) [:<> who " is " gap " ahead" ins "; you have " (:active ours) ". You gain " gain
                     " a lap and pass them in " (plural laps-to-pass "lap") " if nobody drops."]
        (zero? gain) [:<> who " is " gap " ahead" ins ", as many as you: the gap stays."]
        :else [:<> who " is " gap " ahead" ins " and pulls away by " (- gain) " a lap."])
      :else
      (let [behind (- gap)]
        (cond
          eliminated-after [:<> who " finished on " total-laps ", " behind " behind: can't catch you any more."]
          (neg? gain) [:<> who " is " behind " behind" ins ". They gain " (- gain) " a lap and pass you in "
                       (plural laps-to-pass "lap") " if nobody drops."]
          (zero? gain) [:<> who " is " behind " behind" ins ", as many as you: the gap stays."]
          :else [:<> who " is " behind " behind" ins ". They can't catch you while you have more runners."])))))

(defn- danger? [{:keys [gap laps-to-pass]}] (and (neg? gap) laps-to-pass (<= laps-to-pass 2)))

(defn- chase-card [{:keys [country chase]}]
  (let [{:keys [ahead level behind field-active]} chase
        rivals (concat (reverse ahead) behind)
        series (map-indexed (fn [i {:keys [code name series]}]
                              {:key code :label (str name " " code) :color (color (inc i)) :values series})
                            rivals)
        early? (and (empty? ahead) (empty? behind))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "The chase"]
       [:p.sub "Each lap a country gains as many laps as it has runners still in, so the gaps move by the difference in runners."]]]
     [:ul.chase
      (when (:eliminated-after country)
        [:li.level (str (:name country) "'s race is over: its team score is final at " (:total-laps country) ".")])
      (when (seq level)
        [:li.level (str "Level on " (:total-laps country) " with " (plural (count level) "other country") ".")
         (when field-active
           (str " You have " (:active country) " in; countries still going average " field-active "."))])
      (for [r ahead] ^{:key (:code r)} [:li [rival-sentence country r]])
      (for [r behind] ^{:key (:code r)} [:li {:class (when (danger? r) "danger")} [rival-sentence country r]])]
     (when-not early?
       [chart/line-chart {:series series :refs [{:y 0 :label (:name country) :neutral? true}]
                          :y-zero? false :x-label "lap" :y-label "laps ahead of you" :size :wide
                          :legend? true}])]))

(defn- team-runners [{:keys [runners settled-lap]}]
  (let [ranked-all (ranked (:runners @db))
        open (:open @db)
        open-runner (when open (some #(when (= open (:id %)) %) ranked-all))]
    [:section.card.wide
     (when open-runner ^{:key open} [runner-dialog open-runner (count (:runners @db))])
     [:div.card-head
      [:div [:h2 "Runners"]
       [:p.sub "Their seat, and their place among all runners in that seat (the race for the individual invites). Click a runner for their lap times."]]]
     [:div.table-wrap.full
      [:table.team-runners
       [:thead [:tr [:th.num "Seat"] [:th "Runner"] [:th.num "Laps"] [:th "Status"]
                [:th.num "Last lap"] [:th.num "Avg"]
                [:th.num {:title "Rest per lap, average of the last 3 timed laps"} "Margin"]
                [:th {:title "Place among all runners in this seat"} "Seat race"]]]
       [:tbody
        (for [{:keys [id name rank laps last-seconds avg-seconds margin-seconds seat-place seat-runners] :as r} runners]
          ^{:key id}
          [:tr.clickable {:class (when (= "out" (:status r)) "out")
                          :on-click #(do (fetch-runner-detail! id) (swap! db assoc :open id))}
           [:td.num rank]
           [:td.name name]
           [:td.num laps]
           [:td [status-badge r]]
           [:td.num (or (mmss last-seconds) "–")]
           [:td.num (or (mmss avg-seconds) "–")]
           [:td.num (or (mmss margin-seconds) "–")]
           [:td (when seat-place
                  [:a {:href "#seats" :on-click (fn [e] (.stopPropagation e) (swap! db assoc :seat rank))}
                   (str (ordinal seat-place) " of " seat-runners)])]])]]]]))

(defn- lap-shade
  "0-4 by lap time: under 46 min, 46-49, 49-53, 53-57, 57 or more."
  [s]
  (cond (< s 2760) 0 (< s 2940) 1 (< s 3180) 2 (< s 3420) 3 :else 4))

(defn- lap-grid [{:keys [runners settled-lap current-lap]}]
  (let [n (max 1 (if (pos? settled-lap) current-lap 1))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Every lap, every runner"]
       [:p.sub "One cell per lap, darker is slower. Red is the lap a runner didn't finish; striped is a finished lap without a time."]]]
     [:div.lap-grid-wrap
      [:div.lap-grid {:style {:grid-template-columns (str "minmax(110px, 160px) repeat(" n ", minmax(5px, 1fr))")}}
       (for [{:keys [id name history]} runners
             :let [by-lap (into {} (map (juxt :lap identity)) history)]]
         ^{:key id}
         [:<>
          [:span.lg-name name]
          (for [l (range 1 (inc n))
                :let [{:keys [status seconds inferred]} (by-lap l)]]
            ^{:key l}
            [:span {:class (cond
                             (= "dnf" status) "lg dnf"
                             seconds (str "lg s" (lap-shade seconds))
                             (= "finished" status) "lg notime"
                             :else "lg none")
                    :title (str name ", lap " l ": "
                                (cond (= "dnf" status) "did not finish"
                                      seconds (mmss seconds)
                                      (= "finished" status) (if inferred "finished (missed registration)" "finished, no time")
                                      :else "–"))}])])]]
     [:ul.legend.lg-legend
      (for [[i t] (map-indexed vector ["under 46:00" "46–49" "49–53" "53–57" "57:00 or more"])]
        ^{:key i} [:li [:span {:class (str "lg s" i)}] t])
      [:li [:span.lg.dnf] "did not finish"]
      [:li [:span.lg.notime] "no time"]]]))

(defn- team-over-time [{:keys [over-time country]}]
  [:section.card
   [:div.card-head [:div [:h2 "Runners still in"] [:p.sub "Runners starting each lap"]]]
   [chart/line-chart {:series [{:key "in" :label (:name country) :color (color 1) :values (:runners-in over-time)}]
                      :x-label "lap" :y-label "runners" :legend? false}]])

(defn- team-place [{:keys [over-time country]}]
  [:section.card
   [:div.card-head [:div [:h2 "Place"] [:p.sub "After each lap (1 is first; ties share a place)"]]]
   [chart/line-chart {:series [{:key "place" :label (:name country) :color (color 1) :values (:place over-time)}]
                      :x-label "lap" :y-label "place" :y-zero? false :legend? false}]])

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
  [{:id :countries :label "Countries"}
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
      :else {:view (or (some #(when (= v (name (:id %))) (:id %)) views) :countries)})))

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
      :countries (count (remove out? countries))
      :team nil
      :runners (count (filter running? runners))
      :seats (count seats))))

(defn- nav-title [id]
  (case id
    :countries "countries still in the race"
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
                [:<> label (when-let [t (:team @db)] [:span.count {:title "your team"} t])]
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
    ;; countries: the Compare chips, all charts (comparison row first), then the ranking
    (let [{compare true overall false} (group-by (comp boolean :per-country?) panels/panels)]
      [:<> [comparing]
       [:div.grid
        (for [p (concat compare overall)] ^{:key (:id p)} [panel p false])
        [countries-table]]])))

(defn- app []
  [:div.layout
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
  (when-let [{:keys [selected starred favourites show-out show-out-runners runner-filter starred-only favourites-only variant sim-poll-ms team]} (load-prefs)]
    (swap! db assoc
           :team team
           :sim-poll-ms (or sim-poll-ms default-sim-poll-ms)
           :variant (or variant {})
           :selected (into {} (map (fn [[k v]] [(clojure.core/name k) v])) selected)
           :starred (set starred)
           :favourites (set favourites)
           :runner-filter (set runner-filter)
           :starred-only? (true? starred-only)
           :favourites-only? (true? favourites-only)
           :show-out? (true? show-out)
           :show-out-runners? (true? show-out-runners)))
  (go-to-hash!)
  ;; a tab coming back to the front catches up at once
  (.addEventListener js/document "visibilitychange"
                     #(when (and (not js/document.hidden) (not (get-in @db [:race :simulation :controls])))
                        (refresh!) (schedule-poll!)))
  (.addEventListener js/window "hashchange"
                     #(do (go-to-hash!) (js/window.scrollTo 0 0)))
  (render)
  ;; the first poll waits for the first load, which says if it's a simulation
  (-> (refresh!) (.then schedule-poll!)))
