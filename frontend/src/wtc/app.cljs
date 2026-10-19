(ns wtc.app
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [reagent.ratom :as ratom]
            [wtc.chart :as chart]
            [wtc.flags :refer [flag]]
            [wtc.panels :as panels]))

(def top-n 10)  ; the Charts view's "Top 10" group
(def poll-ms 60000)
(def bell-poll-ms 20000)  ; around the bell, when runners finish and results are entered
(def default-sim-poll-ms 10000)  ; a playing demo race steps one lap this often

(defonce db (r/atom {:chart-group "ALL" ; the Charts view's countries (a first visit: all)
                     :favourites #{}  ; favourite country codes
                     :runner-filter #{} ; leaderboard: only these countries (empty = all)
                     :query ""        ; leaderboard name search (not stored)
                     :show-out-runners? false ; list eliminated runners too
                     :view :race      ; menu view, mirrors the URL hash
                     :team nil        ; team page: country code (#team/CODE), remembered
                     :team-data nil   ; /api/team/CODE of that country
                     :seed nil        ; seed ranking: nil = all seeds overview
                     :at nil          ; time travel: the yard looked back at, nil = live
                     :results {}      ; [panel id, metric] -> query result
                     :variant {}      ; panel id -> index into the panel's :variants
                     :open nil        ; runner id shown in the runner dialog
                     :history {}      ; runner id -> lap history, for the runner dialog
                     :median nil}))   ; the field's median lap time per lap [[lap s] ...]

;; --- persistence of viewer preferences (best effort) ---

(defn- load-prefs []
  (try (some-> (js/localStorage.getItem "wtc-prefs") js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- save-prefs! []
  (try (js/localStorage.setItem "wtc-prefs"
                                (js/JSON.stringify (clj->js {:favourites (vec (:favourites @db))
                                                             :show-out-runners (:show-out-runners? @db)
                                                             :runner-filter (vec (:runner-filter @db))
                                                             :variant (:variant @db)
                                                             :sim-poll-ms (:sim-poll-ms @db)
                                                             :team (:team @db)
                                                             :chase-view (:chase-view @db)
                                                             :chase-range (:chase-range @db)
                                                             :team-sort (:team-sort @db)
                                                             :runner-sort (:runner-sort @db)
                                                             :runners-view (:runners-view @db)
                                                             :yard-shade (:yard-shade @db)
                                                             :team-yard-shade (:team-yard-shade @db)
                                                             :race-view (:race-view @db)
                                                             :slot-metric (:slot-metric @db)
                                                             :theme (:theme @db)
                                                             :standings-filter (:standings-filter @db)
                                                             :chart-range (:chart-range @db)
                                                             :chart-group (:chart-group @db)})))
       (catch :default _ nil)))

;; --- API ---

(defn- get-json
  "GET path?params as Clojure data. While time travelling, every request
  asks for the race as of that yard (`at`)."
  [path params]
  (let [qs (->> (cond-> params (:at @db) (assoc :at (:at @db)))
                (keep (fn [[k v]] (when (and (some? v) (not= v ""))
                                    (str (name k) "=" (js/encodeURIComponent v)))))
                (str/join "&"))]
    (-> (js/fetch (cond-> path (seq qs) (str "?" qs)))
        (.then (fn [res] (if (.-ok res) (.json res) (throw (js/Error. (str path " " (.-status res)))))))
        (.then #(js->clj % :keywordize-keys true)))))

(declare group-codes pick-group! ordinal)

(defn- group-query
  "The countries the per-country charts query: the picked group's."
  []
  (str/join "," (sort (group-codes (:chart-group @db)))))

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
    ;; the charts only load on the Charts view
    (doseq [{:keys [id metric per-country?]} (when (= :charts (:view @db)) (map effective panels/panels))]
      (-> (get-json "/api/query" (cond-> {:metric metric}
                                   per-country? (assoc :by "country" :countries (group-query))))
          (.then #(when (latest?) (swap! db assoc-in [:results [id metric]] (get-in % [:data :result]))))
          (.catch #(js/console.warn %))))))

(defn- fetch-runner-times!
  "Yard times for the Runners view (sparklines, heatmap), the Seeds view and
  the Race view's runner margins.
  Past yards never change, so all of them load once, and later reloads only
  fetch from the last yard
  known on (that yard again, in case a time was corrected): a few KB instead
  of ~140 KB gzipped late in the race. Other views don't need them."
  []
  (when (let [{:keys [view race-view slot-metric]} @db]
          (or (#{:runners :seeds} view)
              (and (= :race view) (= :runners race-view) (= :margin slot-metric))))
    (let [through (:lap-times-through @db)
          from (when through (max 1 through))]
      (-> (get-json "/api/query" (cond-> {:metric "lap_time_seconds"} from (assoc :lap from)))
          (.then (fn [r]
                   (let [series (get-in (js->clj r :keywordize-keys true) [:data :result])
                         fresh (into {} (map (fn [s] [(get-in s [:metric :runner]) (:values s)])) series)
                         newest (reduce max (or through 0) (keep (comp first peek) (vals fresh)))]
                     (swap! db (fn [d]
                                 (-> d
                                     (update :lap-times
                                             (fn [old]
                                               (if-not from
                                                 fresh
                                                 ;; keep the yards before `from`, take the rest as sent
                                                 (merge-with (fn [o n] (into (filterv #(< (first %) from) o) n))
                                                             old fresh))))
                                     (assoc :lap-times-through newest)))))))
          (.catch #(js/console.warn %))))))

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

(defn- fetch-overtakes!
  "The coming overtakes across the field, for the Race view."
  []
  (when (= :race (:view @db))
    (-> (get-json "/api/overtakes" {})
        (.then #(swap! db assoc :overtakes %))
        (.catch #(js/console.warn %)))))

(defn refresh! []
  ;; charts load alongside the rest instead of after it, so they don't pop in late
  (fetch-panels!)
  (fetch-team!)
  (fetch-overtakes!)
  (fetch-runner-times!)
  (some-> (:open @db) fetch-runner-detail!)
  (-> (js/Promise.all (clj->js [(get-json "/api/race" {})
                                (get-json "/api/countries" {})
                                (get-json "/api/runners" {})]))
      (.then (fn [[race countries runners]]
               (let [first? (empty? (:countries @db))]
                 (swap! db assoc :race race :countries countries :runners runners
                        :error nil :updated (js/Date.))
                 ;; the charts' group needs the standings and classes to query
                 (when first? (fetch-panels!)))))
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

(defn- ranked-codes [pred] (map :code (filter pred (sort-by :position (:countries @db)))))

;; the classes (divisions), best first, and their names where it isn't the code
(def ^:private class-order ["SUPER" "AAA" "AA" "A"])
(def ^:private class-names {"SUPER" "Super Six"})

;; the quick selectors on the Charts view: a class (division) or everyone
(def ^:private chart-groups
  [["TOP" "Top 10"] ["SUPER" "Super Six"] ["AAA" "AAA"] ["AA" "AA"] ["A" "A"] ["ALL" "All"]])

(defn- group-codes [g]
  (case g
    nil nil
    "TOP" (take top-n (ranked-codes any?))
    (ranked-codes #(or (= g "ALL") (= g (:division %))))))

(defn- pick-group!
  "Shows one group on the per-country charts: all its countries as grey
  lines, its best-ranked one black."
  [g]
  (swap! db assoc :chart-group g)
  (save-prefs!)
  (fetch-panels!))

(defn- toggle-favourite! [code]
  (swap! db update :favourites #(if (contains? % code) (disj % code) (conj % code)))
  (save-prefs!))

(defn- toggle-filter! [code]
  (swap! db update :runner-filter #(if (contains? % code) (disj % code) (conj % code)))
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

(defonce ^:private names-cache (atom nil))

(defn- country-name
  "A country's name by its code. Read without making the calling component
  depend on the app state (the names only change with a reload, which
  redraws anyway): else every leaderboard row, through its country label,
  would redraw on any change at all, even opening the runner dialog."
  [code]
  (let [cs (binding [ratom/*ratom-context* nil] (:countries @db))
        [k names] @names-cache]
    (get (if (identical? k cs)
           names
           (let [names (into {} (map (juxt :code :name)) cs)]
             (reset! names-cache [cs names])
             names))
         code)))

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
  box, favourites first, then a section per class (Super Six, AAA, AA, A),
  and leaves out `exclude`. Picking calls `on-pick` with the code. Keys: ↑ ↓
  move, Enter picks, Esc closes."
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
            ;; a favourite shows once: in Favourites, or in its class while searching
            fav-first? (and (seq favs) (empty? q))
            by-class (group-by :division (cond->> pool fav-first? (remove #(contains? favourites (:code %)))))
            items (vec (concat (when fav-first? (map #(assoc % :group :fav) favs))
                               (for [c (concat class-order (remove (set class-order) (keys by-class)))
                                     country (get by-class c)]
                                 (assoc country :group (or c :other)))))
            group-name (fn [g] (case g :fav "★ Favourites" :other "Other" (get class-names g g)))
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
                   ;; a heading where a section starts
                   (when (or (zero? i) (not= (:group it) (:group (get items (dec i)))))
                     [:li.csel-group {:role "presentation"} (group-name (:group it))])
                   (option i it)]))])])]))))

(defn- to-series [panel result]
  (let [{:keys [per-country? series-label series-order series-names]} panel]
    (cond
      per-country?
      ;; every country of the group, muted; the group's leader (its best in the standings) stands out
      (let [codes (group-codes (:chart-group @db))
            group (set codes)
            leader (first codes)]
        (for [{:keys [metric values]} result
              :let [code (:country metric)]
              :when (contains? group code)]
          {:key code :label (str (or (country-name code) code) " " code) :lead? (= code leader) :values values}))

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

(defn- travel-to!
  "Look at the race as it stood once yard n was done; nil (or the live yard)
  goes back to live. The lap times start over for the new moment."
  [n]
  (let [live (get-in @db [:race :live-settled-lap] 0)
        n (when (and n (< 0 n live)) n)]
    (when (not= n (:at @db))
      (swap! db assoc :at n :lap-times nil :lap-times-through nil :history {} :team-data nil)
      ;; ?at=n in the address, so the moment can be shared
      (.replaceState js/history nil "" (str (.-pathname js/location) (when n (str "?at=" n)) (.-hash js/location)))
      (refresh!))))

(defn- time-travel
  "Look back at any finished yard, the whole site as it stood then, to see
  how the margins called the drops. Closed, a button; open, a player like
  the demo race's: yard back and on, a slider up to live, and back to live."
  [_]
  (let [scrub (r/atom nil)   ; yard shown while dragging the slider
        timer (atom nil)]
    (fn [{:keys [at live-settled-lap]}]
      (let [live live-settled-lap
            yard (or @scrub at live)]
        (if-not (or at (:travel-open? @db))
          [:button.chip.travel-open {:on-click #(swap! db assoc :travel-open? true)} "⏪ Time travel"]
          [:div.notice.travel
           [:p [:span.icon.info "⏪"] [:b "Time travel. "]
            (if at
              (str "The race as it stood after yard " at ", during yard " (inc at) ". Live: yard " live " done.")
              (str "Pick a finished yard to see the race as it stood then. Live: yard " live " done."))]
           [:div.sim-controls
            [:div.transport
             [:button.ghost {:title "Previous yard" :aria-label "Previous yard"
                             :disabled (<= yard 1) :on-click #(travel-to! (dec yard))} "⏮"]
             [:button.ghost {:title "Next yard" :aria-label "Next yard"
                             :disabled (>= yard live) :on-click #(travel-to! (inc yard))} "⏭"]]
            [:input.scrub {:type "range" :min 1 :max live :step 1 :value yard :aria-label "Yard to look back at"
                           :style {"--progress" (str (* 100 (/ (dec yard) (max 1 (dec live)))) "%")}
                           :on-change (fn [e]
                                        (let [n (js/parseInt (.. e -target -value))]
                                          (reset! scrub n)
                                          (js/clearTimeout @timer)
                                          (reset! timer (js/setTimeout #(do (reset! scrub nil) (travel-to! n)) 300))))}]
            [:span.sim-lap (str "After yard " yard)]
            [:button.ghost {:on-click #(do (travel-to! nil) (swap! db assoc :travel-open? false))}
             (if at "Back to live" "Close")]]])))))

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
                           (if (= "finished" (:phase race)) "done" "eliminated"))]
          (when (and (not (:at race)) (not (:travel-open? @db)) (not (get-in race [:simulation :controls]))
                     (> (:live-settled-lap race 0) 1))
            [time-travel race])]))
     (when (or (:at race) (:travel-open? @db))
       [time-travel race])
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
  "A chart card: title, view switch, Chart/Table toggle and the chart."
  [_]
  (let [table? (r/atom false)]
    (fn [p0]
      (let [{:keys [id title subtitle kind variants] :as p} (effective p0)
            chosen (get-in @db [:variant id] 0)
            results (:results @db)
            rkey [id (:metric p)]
            series (cond->> (to-series p (get results rkey))
                     ;; "Last 12", in the Compare countries section, caps its charts' yard axis
                     (and (:per-yard? p) (:per-country? p) (= :last-12 (:chart-range @db)))
                     (map (fn [s] (let [to (reduce max 0 (map first (:values s)))]
                                    (update s :values #(filterv (fn [[x]] (> x (- to 12))) %))))))
            opts {:series series :x-label (:x-label p) :y-label (:y-label p)
                  :fmt-y (y-formats (:y-format p)) :y-zero? (:y-zero? p true)
                  :y-invert? (:y-invert? p) :y-max (:y-max p) :y-reach (:y-reach p)
                  :y-domain (case (:y-field p)
                              :place [1 (max 2 (count (:countries @db)))]
                              :count [0 (max 1 (count (:countries @db)))]
                              :runners [0 (max 1 (count (:runners @db)))]
                              nil)
                  ;; one line needs no label at its end: the title names it
                  :direct-labels? (> (count series) 1)
                  :legend? true
                  :size (:size p :normal)}]
        [:section.card {:class (when (:size p) "wide")}
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
           [:button.ghost {:on-click #(swap! table? not)} (if @table? "Chart" "Table")]]]
         [:div
          (cond
            (and (:per-country? p) (empty? (group-codes (:chart-group @db))))
            [chart/placeholder ""]
            (not (contains? results rkey)) [chart/placeholder ""]
            @table? [chart/data-table opts]
            (:pick? p)
            [chart/pick-chart
             (assoc opts
                    :title-fn #(if (= :runners (:pick-text p)) (str "Yard " %) (str "After yard " %))
                    :label-fn (fn [{code :key}] [:span.tt-country [flag code] [:b (country-name code)] " " [:span.code code]])
                    :text-fn (fn [y]
                               (case (:pick-text p)
                                 :gap (if (zero? y) "the leader" (str (- y) (if (= -1 y) " yard" " yards") " behind"))
                                 :place (str (ordinal y) " place")
                                 :runners (str y (if (= 1 y) " runner" " runners"))
                                 (str y " yards"))))]
            (= kind :line) [chart/line-chart opts]
            :else [chart/bar-chart opts])]]))))

;; --- ranking ---

(defn- out? [{:keys [eliminated-after]}] (some? eliminated-after))

(defn- running? [r] (= "running" (:status r)))

(defn- ranked-laps
  "The laps a runner is ranked on: a runner still in counts up to the last
  bell, so finishing the current lap first doesn't put them ahead of the
  others still on it (they all start the next together). Read without
  making the caller depend on the app state: the race moves with a reload,
  which redraws anyway."
  [r]
  (let [cur (binding [ratom/*ratom-context* nil] (get-in @db [:race :current-lap]))]
    (if (and cur (running? r)) (min (:laps r) (max 0 (dec cur))) (:laps r))))

(defn- race-key [r] [(ranked-laps r) (if (running? r) 1 0)])

(declare rank-runners)

(defonce ^:private ranked-cache (atom nil))

(defn- ranked
  "Runners best first, each with :pos. Equal laps (up to the last bell, see
  `ranked-laps`) share a position, except that a runner still going is
  ahead of one who is out. Kept for the same
  `runners` (they change once a reload), so the leaderboard's rows stay
  identical between redraws and are skipped."
  [runners]
  (let [[rs result] @ranked-cache]
    (if (identical? rs runners)
      result
      (let [result (rank-runners runners)]
        (reset! ranked-cache [runners result])
        result))))

(defn- rank-runners [runners]
  (let [sorted (sort-by (juxt (comp - ranked-laps) #(if (running? %) 0 1) :name) runners)]
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

(defn- out-at
  "The yard whose bell a runner went out at: an RTC finished yard y and
  didn't start the next, an INC or OVER failed yard y itself, a missing
  result is the yard after the last. DNS is the start (0)."
  [{:keys [laps stop]}]
  (case stop "dns" 0 "rtc" laps (inc laps)))

(defn- drops-card
  "One line above the standings: who went out at one bell, with the stop
  codes. \"Show\" opens the full list by country (★ first, then ranking).
  Starts at the last completed yard; ← → step through the race."
  []
  (let [{:keys [runners countries race favourites drops-yard drops-open?]} @db
        last-bell (max 1 (dec (:current-lap race 1)))
        y (or drops-yard last-bell)
        out (filter #(and (= "out" (:status %)) (= y (out-at %))) runners)
        order (into {} (map (juxt :code :position)) countries)
        by-country (->> (group-by :country out)
                        (sort-by (fn [[code _]] [(if (contains? favourites code) 0 1) (order code 999)])))
        counts (frequencies (map #(or (stop-short (:stop %)) "no result") out))
        step! (fn [d] (swap! db assoc :drops-yard (-> (+ y d) (max 1) (min (:current-lap race 1)))))]
    [:div.drops.wide
     [:div.drops-line
      [:button.ghost.step {:aria-label "Previous yard" :disabled (<= y 1) :on-click #(step! -1)} "←"]
      [:b (str "Out at yard " y ":")]
      [:span (if (seq out)
               (str (count out) (if (= 1 (count out)) " runner" " runners") " · "
                    (str/join " · " (for [k ["RTC" "INC" "OVER" "no result"] :when (counts k)] (str (counts k) " " k)))
                    (when (= y (:current-lap race)) " · so far"))
               "nobody")]
      [:button.ghost.step {:aria-label "Next yard" :disabled (>= y (:current-lap race 1)) :on-click #(step! 1)} "→"]
      (when (seq out)
        [:button.link {:aria-expanded (boolean drops-open?) :on-click #(swap! db update :drops-open? not)}
         (if drops-open? "Hide ▴" "Show ▾")])]
     (when (and drops-open? (seq out))
       [:ul.drops-list
         (for [[code rs] by-country]
           ^{:key code}
           [:li [country-label code]
            [:span.drop-names
             (for [{:keys [id name stop] :as r} (sort-by :rank rs)]
               ^{:key id}
               [:span.drop {:title (out-text r)} name " " [:span.code (or (stop-short stop) "no result")]])]])])]))

(defn- short-country
  "[flag] CODE linking to the team page, the full name on hover: for lists
  where a whole name per country is too long."
  [code]
  [:a.cshort {:href (str "#team/" code) :title (str (or (country-name code) code) ": team page")}
   [flag code] code])

(defn- overtakes-card
  "One line under the drops: how many passes are coming if nobody drops, the
  soonest yard's first. \"Show\" lists them all by yard; a ★ country's
  passes are marked."
  []
  (let [{:keys [overtakes favourites overtakes-open?]} @db
        by-yard (sort-by key (group-by :lap overtakes))
        [y0 first-ones] (first by-yard)
        fav? (fn [{:keys [passer passed]}] (or (contains? favourites passer) (contains? favourites passed)))
        pass (fn [{:keys [passer passed gap gain] :as o}]
               [:span.pass {:class (when (fav? o) "fav")
                            :title (str (country-name passer) " is " gap (if (= 1 gap) " yard" " yards")
                                        " behind " (country-name passed) " and gains " gain " a yard")}
                [short-country passer] " passes " [short-country passed]])]
    [:div.drops.overtakes.wide
     [:div.drops-line
      [:b "Coming overtakes:"]
      (if (seq overtakes)
        [:<>
         [:span (str "after yard " y0 " ")]
         (for [o (take 2 first-ones)] ^{:key (str (:passer o) (:passed o))} [pass o])
         (when (> (count overtakes) 2)
           [:button.link {:aria-expanded (boolean overtakes-open?) :on-click #(swap! db update :overtakes-open? not)}
            (if overtakes-open? "Hide ▴" (str "All " (count overtakes) " ▾"))])]
        [:span "none in the next yards"])
      [:span.muted "if nobody drops"]]
     (when (and overtakes-open? (seq overtakes))
       [:ul.drops-list
        (for [[y os] by-yard]
          ^{:key y}
          [:li [:span.yard (str "After yard " y)]
           [:span.drop-names
            (for [o (sort-by (comp not fav?) os)] ^{:key (str (:passer o) (:passed o))} [pass o])]])])]))

(defn- surname [nm] (last (str/split (str/trim (str nm)) #"\s+")))

(defn- slot-state
  "A runner's cell in the Runners grid: :win, :out, :dns, :done (already
  finished the yard on course now), :running (on course), or :awaiting
  (still in, but no result yet for the last completed yard)."
  [{:keys [status stop laps]} current-lap]
  (cond (= "finished" status) :win
        (= "dns" stop) :dns
        (= "out" status) :out
        (>= laps current-lap) :done
        (< laps (dec current-lap)) :awaiting
        :else :running))

(declare margin-of)

(defn- margin-level
  "How much pressure a runner still in is under, by their margin (see
  `margin-trend`), 0-5 in 2-minute steps of rest: 10:00 or more, 8-10,
  6-8, 4-6, 2-4, under 2:00; notime without timed yards. Not by the
  trend: late in the race most runners are over a minute slower than their
  average (103 of 153 at yard 51 in 2026), so a ▼ alone says little."
  [{:keys [margin]}]
  (cond (nil? margin) "notime"
        (>= margin 600) 0 (>= margin 480) 1 (>= margin 360) 2
        (>= margin 240) 3 (>= margin 120) 4 :else 5))

(defn- slowdown
  "How much slower a runner's last 2 timed yards were than the 6 before
  them, in seconds; nil with fewer than 8 timed yards. 3 minutes or more is
  `slowing?`: tested on 19 Oct (yards 10-51), it came before 47% of the
  next-yard drops, a margin under 4:00 before 18%, either before 55%; 1 in
  6 flagged runners dropped next yard (the base rate is 1 in 28). It's against the runner's own pace, so it
  also catches a fast runner who slows to a margin that still looks fine."
  [times]
  (let [s (mapv second (take-last 8 times))]
    (when (= 8 (count s))
      (js/Math.round (- (/ (+ (s 6) (s 7)) 2) (/ (reduce + (subvec s 0 6)) 6))))))

(defn- slowing? [slow] (and slow (>= slow 180)))

(defn- race-runners-grid
  "The standings with every runner: per country its 15 seeds as cells of
  yards and surname, coloured by state; or, with :slot-metric :margin, of
  the margin of the runners still in, smallest first and coloured by
  `margin-level` (then those without times, then the ones who stopped,
  greyed out as in Yards). Hover for the full story."
  [shown]
  (let [{:keys [runners race slot-metric lap-times]} @db
        margin? (= :margin slot-metric)
        current (:current-lap race 0)
        by-country (group-by :country runners)
        slots (reduce max 0 (map :runners shown))]
    [:div.table-wrap.full
     [:table.race-grid
      [:colgroup [:col.rk] [:col.country] [:col.n] [:col.in] [:col.time] [:col]]
      [:thead [:tr [:th.num "#"] [:th "Country"] [:th.num "Yards"] [:th.num "In"]
               [:th.num {:title "Average yard time of all the team's timed yards"} "Avg yard"]
               [:th (if margin? "Runners by margin, smallest first" "Runners by seed")]]]
      [:tbody
       (for [{:keys [code rank active runners total-laps eliminated-after]} shown
             :let [rs (cond->> (sort-by :rank (by-country code))
                        ;; still in by margin, then still in without times, then stopped, each by seed
                        margin? (sort-by (fn [r]
                                           (let [m (:margin (margin-of r (get lap-times (:id r))))]
                                             (cond m [0 m] (running? r) [1 0] :else [2 0])))))
                   timed (keep (fn [{:keys [total-seconds avg-seconds]}]
                                 (when (and total-seconds avg-seconds (pos? avg-seconds))
                                   [total-seconds (js/Math.round (/ total-seconds avg-seconds))]))
                               rs)
                   n-timed (reduce + 0 (map second timed))
                   avg (when (pos? n-timed) (js/Math.round (/ (reduce + 0 (map first timed)) n-timed)))
                   ;; down to 2: a teammate's drop ends the other's race too
                   last-two? (and margin? (= 2 active) (not eliminated-after))]]
         ^{:key code}
         [:tr.clickable {:class (when eliminated-after "out")
                         :on-click #(set! (.-hash js/location) (str "team/" code))}
          [:td.num rank]
          [:td.name [country-label code]]
          [:td.num total-laps]
          [:td.num {:class (when last-two? "danger")
                    :title (str active " of " runners " still in"
                                (when last-two? "; a teammate's drop ends the other's race"))}
           (if last-two? "⚠ 2" active)]
          [:td.num (or (mmss avg) "–")]
          [:td [:div.slots {:style {:grid-template-columns (str "repeat(" (max slots 1) ", minmax(0, 1fr))")}}
                (for [{:keys [id name laps] :as r} rs
                      :let [st (slot-state r current)
                            m (when margin? (margin-of r (get lap-times id)))
                            level (when (and margin? (running? r)) (margin-level m))
                            slow (when level (slowdown (get lap-times id)))]]
                  ^{:key id}
                  [:span.slot {:class (cond (not margin?) (cljs.core/name st)
                                            level [(str "m-" level) (when (slowing? slow) "slowing")]
                                            :else "gone")
                               :title (str "Seed " (:rank r) " · " name " · " laps " yards · "
                                           (when (:margin m)
                                             (str "margin " (mmss (:margin m)) " (rest per yard over the last 3 yards) · "))
                                           (when (slowing? slow)
                                             (str "slowing: the last 2 yards " (mmss slow) " slower than the 6 before · "))
                                           (case st
                                             :win "country winner"
                                             :dns "did not start"
                                             :out (str "out · " (out-text r))
                                             :awaiting (str "result for yard " (dec current) " not in yet")
                                             :done (str "finished yard " current)
                                             :running (str "on yard " current)))}
                   [:b (cond (= st :dns) "–"
                             (not margin?) laps
                             (= st :win) "★"
                             (not level) "out"
                             (:margin m) [:<> (when (slowing? slow) "⇣ ") (mmss (:margin m))]
                             :else "no time")]
                   [:span (surname name)]])]]])]]
     (if margin?
       [:ul.legend.slot-legend
        (for [[cls label] [["m-0" "10:00 or more"] ["m-1" "8–10"] ["m-2" "6–8"] ["m-3" "4–6"] ["m-4" "2–4"]
                           ["m-5" "under 2:00"] ["m-notime" "no time"] ["gone" "stopped"]
                           ["m-0 slowing" "⇣ slowing: the last 2 yards 3+ min slower than the 6 before"]]]
          ^{:key cls} [:li [:span.slot {:class cls}] label])
        [:li "Margin: rest per yard over the last 3 yards. ⚠ 2: down to 2 runners"]]
       [:ul.legend.slot-legend
        (for [[st label] [[:running "on course"] [:done "finished this yard"] [:awaiting "result not in yet"] [:out "out"]
                          [:win "country winner"] [:dns "did not start"]]]
          ^{:key st} [:li [:span.slot {:class (cljs.core/name st)}] label])])]))

(defn- countries-table []
  (let [{:keys [countries selected favourites race-view standings-filter]} @db
        lead (reduce max 0 (map :total-laps countries))
        slots (reduce max 0 (map :runners countries))
        running-only? (= :running standings-filter)
        ;; all: eliminated ones greyed, their score still counts
        shown (cond->> countries running-only? (remove :eliminated-after))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Standings"]
       [:p.sub "Ranked by team score. Star (☆) countries to keep them at hand in the pickers. Click a row for the team page."]]
      [:div.head-actions
       [:div.segmented {:role "group" :aria-label "Show"}
        (for [[v label] [[:teams "Teams"] [:runners "Runners"]]]
          ^{:key v}
          [:button {:aria-pressed (= v (or race-view :teams))
                    :on-click #(do (swap! db assoc :race-view v) (save-prefs!) (fetch-runner-times!))} label])]
       (when (= :runners race-view)
         [:div.segmented {:role "group" :aria-label "Runner cells show"}
          (for [[v label] [[:yards "Yards"] [:margin "Margin"]]]
            ^{:key v}
            [:button {:aria-pressed (= v (or (:slot-metric @db) :yards))
                      :on-click #(do (swap! db assoc :slot-metric v) (save-prefs!) (fetch-runner-times!))} label])])
       [:div.segmented {:role "group" :aria-label "Countries"}
        (for [[v label] [[:all "All"] [:running "Still running"]]]
          ^{:key v}
          [:button {:aria-pressed (= v (or standings-filter :all))
                    :on-click #(do (swap! db assoc :standings-filter v) (save-prefs!))} label])]]]
     (cond
       (empty? shown)
       [:div.empty (if running-only? "No country is still running." "No countries to show")]
       (= :runners race-view) [race-runners-grid shown]
       :else
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
            [:tr.clickable {:class [(when eliminated-after "out")]
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
                (str " · seed " (:rank runner) (when (:bib runner) (str " · bib " (:bib runner))) " ")
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
  (let [{:keys [countries runner-filter query]} @db]
    [:div.filters
     [country-select {:on-pick toggle-filter! :placeholder "+ Country" :exclude runner-filter
                      :label "Filter by country"}]
     (for [code (sort runner-filter)]
       ^{:key code}
       [:span.chip [country-label code]
        [:button.remove {:aria-label (str "Remove " code) :on-click #(toggle-filter! code)} "✕"]])
     [:input.search {:type "search" :placeholder "Search runner" :value query :aria-label "Search runner"
                     :on-change #(swap! db assoc :query (.. % -target -value))}]
     (when (and (pos? n-out) (not (finished?)))
       [:label.toggle
        [:input {:type "checkbox" :checked show-out?
                 :on-change #(do (swap! db update :show-out-runners? not) (save-prefs!))}]
        ;; out and country winners: everyone who has stopped
        (str "Show all (+" n-out " stopped)")])]))

(declare margin-trend)

(defn- margin-of
  "A runner still in's margin and trend (see `margin-trend`), nil otherwise."
  [r times]
  (when (running? r) (margin-trend times)))

(defn- margin-cell
  "Margin as m:ss with its trend: ▼ (critical) when the runner is over a
  minute slower than their average, ▲ when faster, – when steady."
  [{:keys [margin trend]}]
  [:span.margin {:class (when (= :down trend) "danger")
          :title (case trend
                   :down "Rest per yard over the last 3 yards; slower than their average: the margin shrinks"
                   :up "Rest per yard over the last 3 yards; faster than their average"
                   :flat "Rest per yard over the last 3 yards; steady"
                   nil)}
   (if margin [:<> (mmss margin) " " (case trend :down "▼" :up "▲" "–")] "–")])

(defn- runner-row
  "One leaderboard row. A component of its own, so a row whose runner, star
  and yard times are unchanged isn't redrawn: opening or closing the runner
  dialog (or any other change of state) would otherwise redraw ~1000 rows
  with their sparklines."
  [{:keys [id name country laps pos] :as runner} times]
  ;; a grid row, not a <tr>: the browser can skip laying out rows off screen
  ;; (content-visibility), which it can't for table rows
  [:div.lb-row.clickable {:role "row" :class (when (= "out" (:status runner)) "out")
                          :on-click (fn []
                                      (fetch-runner-detail! id)
                                      (swap! db assoc :open id))}
   [:span.num {:role "cell"} pos]
   [:span.name {:role "cell"} name]
   [:span {:role "cell"} [country-label country]]
   [:span.num {:role "cell"} (:rank runner)]
   [:span.num {:role "cell"} laps]
   [:span {:role "cell"} [status-badge runner]]
   [:span.num {:role "cell"} (mmss (:last-seconds runner))]
   [:span.num {:role "cell"} (mmss (:avg-seconds runner))]
   [:span.num {:role "cell"} (mmss (:fastest-seconds runner))]
   [:span.num {:role "cell"} (hmmss (:total-seconds runner))]
   [:span.num {:role "cell"} [margin-cell (margin-of runner times)]]
   [:span.num {:role "cell"} (or (:best-yards runner) "–")]
   [:span {:role "cell"} (when times [chart/sparkline times])]])

(declare lap-shade lap-text lap-grid-legend)

(defn- margins-by-lap
  "{lap margin} for each timed yard: the rest per yard over the last 3 timed
  yards up to it, as `margin-trend` has it for the latest."
  [times]
  (into {} (map-indexed (fn [i [l _]]
                          (let [l3 (map second (subvec times (max 0 (- i 2)) (inc i)))]
                            [l (js/Math.round (- 3600 (/ (reduce + l3) (count l3))))])))
        times))

(defn- slowdowns-by-lap
  "{lap slowdown} for each timed yard from the 8th: `slowdown` as it stood
  after that yard."
  [times]
  (into {} (for [i (range 7 (count times))]
             [(first (times i)) (slowdown (subvec times 0 (inc i)))])))

(defn- yard-cells
  "A runner's yards 1..n as heatmap cells, shaded by time, as the team page's
  grid, built from the yard times and the runner's status; with `margin?`,
  shaded by the margin after each yard instead (see `margin-level`), with a
  bar under the yards after which the runner was `slowing?`. A stop
  is a cell of its own: the yard an INC or OVER failed, solid; the yard an
  RTC didn't start, hollow. Hovering a cell puts its story in `tip`."
  [{:keys [name laps status stop] :as r} times n current-lap tip & [margin?]]
  (let [by-lap (into {} times)
        margins (when margin? (margins-by-lap (vec times)))
        slowdowns (when margin? (slowdowns-by-lap (vec times)))
        failed (when (= "out" status) (out-at r))   ; the yard it stopped at
        ;; an RTC finished `failed` and didn't start the next one
        not-started (when (and failed (= "rtc" stop)) (inc laps))]
    (if (= "dns" stop)
      [:span.lg-dns {:style {:grid-column (str "span " n)}} "did not start"]
      (for [l (range 1 (inc n))
            :let [s (by-lap l)
                  lap (cond s {:seconds s}
                            (= l not-started) {:status "dnf" :stop stop}
                            (= l failed) {:status "dnf" :stop stop}
                            (<= l laps) {:status "finished"})]]
        ^{:key l}
        [:span {:class (cond (= l not-started) "lg rtc"
                             (and s margin?) (str "lg mg" (margin-level {:margin (margins l)})
                                                  (when (slowing? (slowdowns l)) " slowing"))
                             s (str "lg s" (lap-shade s))
                             ;; by margin, the darkest step is the critical red, so the stop is the text colour
                             (= l failed) (cond (not stop) "lg none" margin? "lg end" :else "lg dnf")
                             (<= l laps) "lg notime"
                             (and (= "running" status) (= l current-lap)) "lg running"
                             :else "lg none")
                :on-mouse-enter (fn [e]
                                  (let [rect (.getBoundingClientRect (.-currentTarget e))]
                                    (reset! tip {:x (+ (.-left rect) (/ (.-width rect) 2)) :y (.-top rect)
                                                 :name name :lap l
                                                 :text (str (lap-text lap r l current-lap)
                                                            (when-let [m (and margin? (margins l))]
                                                              (str " · margin " (mmss m)))
                                                            (when (and margin? (slowing? (slowdowns l)))
                                                              (str " · slowing: the last 2 yards " (mmss (slowdowns l))
                                                                   " slower than the 6 before")))})))}]))))

(defn- heat-row
  "One runner in the leaderboard's heatmap: position, name, country, then a
  cell per yard shaded by its time, as the team page's grid. Built from the
  yard times and the runner's status, so it needs no history. A component of
  its own, so unchanged rows aren't redrawn."
  [{:keys [id name country pos] :as r} times n current-lap tip cols margin?]
  (let [open! #(do (fetch-runner-detail! id) (swap! db assoc :open id))]
    ;; one element per row (its own grid): sorting moves ~400 rows, not ~15,000
    ;; cells, and an off-screen row can skip layout (content-visibility)
    [:div.hm-row {:style {:grid-template-columns cols}}
     [:span.hm-pos pos]
     [:button.hm-name {:on-click open! :title "Figures and yard times"} name]
     [:span.hm-country [:a.cshort {:href (str "#team/" country) :title (country-name country)} [flag country] country]]
     (yard-cells r times n current-lap tip margin?)]))

(defn- margin-grid-legend []
  [:ul.legend.lg-legend
   (for [[i t] (map-indexed vector ["margin 10:00 or more" "8–10" "6–8" "4–6" "2–4" "under 2:00"])]
     ^{:key i} [:li [:span {:class (str "lg mg" i)}] t])
   [:li [:span.lg.mg2.slowing] "slowing: the last 2 yards 3+ min slower than the 6 before"]
   [:li [:span.lg.end] "stopped in the yard"]
   [:li [:span.lg.rtc] "didn't start the yard (RTC)"]
   [:li [:span.lg.notime] "no time"]
   [:li [:span.lg.running] "running now"]
   [:li "Margin: rest per yard over the last 3 yards, after each yard"]])

(defn- runner-heatmap
  "The leaderboard as a heatmap: the same runners in the same order, a row of
  yard cells each. Hover a cell for its time; click a name for the dialog."
  [_ _ _]
  (let [tip (r/atom nil)]
    (fn [shown lap-times current-lap]
      (let [n (max 1 current-lap)
            margin? (not= :time (:yard-shade @db))]   ; by margin unless Yard time was picked
        [:<>
         [:div.lap-grid-wrap {:on-mouse-leave #(reset! tip nil) :on-scroll #(reset! tip nil)}
          (let [cols (str "44px minmax(140px, 220px) 76px repeat(" n ", minmax(5px, 1fr))")]
            [:div.hm
             (for [{:keys [id] :as r} shown]
               ^{:key id} [heat-row r (get lap-times id) n current-lap tip cols margin?])])]
         (when-let [{:keys [x y name lap text]} @tip]
           [:div.lg-tip {:style {:left x :top y}} [:b name] (str " · yard " lap " · " text)])
         (if margin? [margin-grid-legend] [lap-grid-legend])]))))

;; the leaderboard's sorts: what each sorts on, from the row and its yard
;; times. Ascending (▲) is quickest first, and for Margin and Trend the most
;; at risk first: the smallest margin, the margin shrinking most
(def ^:private runner-sorts
  {:last (fn [r _] (:last-seconds r)) :avg (fn [r _] (:avg-seconds r))
   :fastest (fn [r _] (:fastest-seconds r)) :total (fn [r _] (:total-seconds r))
   :margin (fn [r times] (:margin (margin-of r times)))
   :trend (fn [r times] (:change (margin-of r times)))})

(defn- sort-runners
  "The leaderboard in the chosen order: position (default), or a column
  ascending (`desc?` descending), runners without a value last either way."
  [rs {:keys [by desc?]} lap-times]
  (if-let [f (runner-sorts by)]
    (let [k #(f % (get lap-times (:id %)))
          {timed true untimed false} (group-by #(some? (k %)) rs)]
      ;; a stable sort of rows in position order: ties stay in position order
      (concat (sort-by k (if desc? #(compare %2 %1) compare) timed) untimed))
    rs))

(defn- pick-sort!
  "Sorts the leaderboard by `by` (the Sort buttons): first quickest first, again on the same
  column slowest first; :pos goes back to the position order."
  [by]
  (let [{cur :by desc? :desc?} (:runner-sort @db)
        on? (= by (or cur :pos))]
    (swap! db assoc :runner-sort (cond (= by :pos) nil
                                       (and on? (not desc?)) {:by by :desc? true}
                                       :else {:by by :desc? false}))
    (save-prefs!)))

(defn- sort-arrow [by]
  (let [{cur :by desc? :desc?} (:runner-sort @db)]
    (cond (not= by (or cur :pos)) "" (and desc? (not= by :pos)) " ▼" :else " ▲")))

(defn- sort-buttons
  "The leaderboard's sort, in both views: the active one with ▲ (quickest,
  or most at risk, first) or ▼; clicking it again flips."
  []
  (let [cur (or (:by (:runner-sort @db)) :pos)]
    [:div.segmented.sort-buttons {:role "group" :aria-label "Sort runners by"}
     (for [[by label title] [[:pos "Pos"] [:last "Last yard"] [:avg "Avg"] [:fastest "Fastest"] [:total "Total"]
                             [:margin "Margin" "Rest per yard over the last 3 yards: smallest first"]
                             [:trend "Trend" "How the margin changes against the runner's average: shrinking most first"]]]
       ^{:key by}
       [:button {:aria-pressed (= by cur) :title title :on-click #(pick-sort! by)}
        label [:span.arrow (sort-arrow by)]])]))

(defn- runners-table []
  (let [{:keys [runners open lap-times show-out-runners? runner-filter query]} @db
        q (str/lower-case (str/trim query))
        candidates (cond->> (ranked runners)
                     (seq runner-filter) (filter #(contains? runner-filter (:country %)))
                     (seq q) (filter #(str/includes? (str/lower-case (:name %)) q)))
        ;; out, or a country winner: either way no longer running
        stopped? #(not= "running" (:status %))
        show-out? (or show-out-runners? (finished?))
        n-out (count (filter stopped? candidates))
        shown (-> (filter #(or show-out? (not (stopped? %))) candidates)
                  (sort-runners (:runner-sort @db) lap-times))
        open-runner (when open (some #(when (= open (:id %)) %) (ranked runners)))]
    [:section.card.wide
     (when open-runner ^{:key open} [runner-dialog open-runner (count runners)])
     [:div.card-head
      [:div [:h2 "Runner leaderboard"]
       [:p.sub "All runners of all countries. Click a row for the runner's figures and yard times."]]
      [:div.segmented {:role "group" :aria-label "Show runners as"}
       (for [[v label] [[:table "Table"] [:heatmap "Every yard"]]]
         ^{:key v}
         [:button {:aria-pressed (= v (:runners-view @db :table))
                   :on-click #(do (swap! db assoc :runners-view v) (save-prefs!))} label])]
      (when (= :heatmap (:runners-view @db))
        [:div.segmented {:role "group" :aria-label "Shade the yards by"}
         (for [[v label] [[:margin "Margin"] [:time "Yard time"]]]
           ^{:key v}
           [:button {:aria-pressed (= v (:yard-shade @db :margin))
                     :on-click #(do (swap! db assoc :yard-shade v) (save-prefs!))} label])])]
     [:div.filters-bar [runner-filters n-out show-out?] [sort-buttons]]
     (if (empty? shown)
       [:div.empty (cond
                     (seq candidates) "Everyone here has stopped. Tick \"Show all\" to see them."
                     :else "No runners match")]
       (if (= :heatmap (:runners-view @db))
        [runner-heatmap shown lap-times (get-in @db [:race :current-lap] 1)]
       [:div.table-wrap.full
        ;; fixed columns, so filtering never moves them
        ;; a grid with table roles: ~400 rows lay out much faster than a
        ;; <table>, whose rows the browser must all lay out on every sort.
        ;; The columns it shares with the team page have the same widths
        [:div.lb {:role "table" :aria-label "Runner leaderboard"}
         [:div.lb-row.lb-head {:role "row"}
          (for [[cls label title] [[:num "Pos"] [nil "Runner"] [nil "Country"] [:num "Seed"] [:num "Yards"]
                                   [nil "Status"] [:num "Last yard"] [:num "Avg" "Average of the timed yards"]
                                   [:num "Fastest"] [:num "Total" "Sum of the timed yards"]
                                   [:num "Margin" "Rest per yard over the last 3 yards, and its trend"]
                                   [:num "Best" "Best backyard result (yards) in the 2026 qualifying period"]
                                   [nil "Yard times"]]]
            ^{:key label} [:span {:role "columnheader" :class cls :title title} label])]
         (for [{:keys [id] :as runner} shown]
           ^{:key id} [runner-row runner (get lap-times id)])]]))]))

;; --- seed ranking ---
;; Every runner has a seed (1-15, their roster rank in the team, as on the
;; official site: by best qualifying yards). The view
;; compares the runners in the same seed across countries. It's our own
;; comparison, not an official competition: no invite comes with it (the
;; invites to the individual championship go to the national champions).

(def seeds (range 1 16))

(defn- seed-ranking
  "Runners of seed n, best first, with :pos (see `ranked`)."
  [runners n]
  (rank-runners (filter #(= n (:rank %)) runners)))

(defn- seed-summary [runners n]
  (let [ranked (seed-ranking runners n)
        in (filter running? ranked)
        leaders (filter #(= 1 (:pos %)) ranked)]
    {:seed n
     :leaders leaders
     :total (count ranked)
     :running (count in)
     :state (cond
              (>= (count in) 2) :open
              (= 1 (count in)) :last-in
              (= 1 (count leaders)) :winner
              :else :tie)}))

(defn- seed-state-pill [{:keys [state running leaders]}]
  (case state
    :open [:span.pill.in [:span.dot.in] (str "open · " running " still in")]
    :last-in [:span.pill.in [:span.dot.in] "last one in"]
    :winner [:span.pill.win "★ winner"]
    :tie [:span.pill.out (str "tie · " (count leaders))]))

(defn- seeds-overview [runners]
  [:table
   [:thead [:tr [:th.num "Seed"] [:th "Leader"] [:th "Country"] [:th.num "Yards"] [:th "State"]]]
   [:tbody
    (for [n seeds
          :let [{:keys [leaders] :as sum} (seed-summary runners n)
                lead (first leaders)]]
      ^{:key n}
      [:tr.clickable {:on-click #(swap! db assoc :seed n)}
       [:td.num [:b n]]
       (if (> (count leaders) 1)
         [:<> [:td.muted (str (count leaders) " tied")] [:td]]
         [:<> [:td.name (:name lead "–")]
          [:td (when lead [country-label (:country lead)])]])
       ;; the yards the leaders are ranked on (a runner who already finished the
       ;; current yard counts up to the last bell, as the others)
       [:td.num (some-> lead ranked-laps)]
       [:td [seed-state-pill sum]]])]])

(defn- margin-trend
  "A runner's rest per yard over their last 3 timed yards, and its trend
  against their average: :down when they've slowed by over a minute (the
  margin shrinks), :up when they've sped up, else :flat. nil without times."
  [times]
  (let [secs (map second times)]
    (when (seq secs)
      (let [l3 (take-last 3 secs)
            recent (/ (reduce + l3) (count l3))
            avg (/ (reduce + secs) (count secs))
            d (- recent avg)]
        {:margin (js/Math.round (- 3600 recent))
         :change (js/Math.round (- d))   ; the margin's change against the average
         :trend (cond (> d 60) :down (< d -60) :up :else :flat)}))))

(defn- seed-row
  "One runner of the chosen seed: position, name, country, their team's
  runners still in, margin and trend, yards, then their yard cells. A row of
  its own grid, so unchanged rows aren't redrawn or laid out off screen."
  [{:keys [id name country pos] :as r} times team-in n current-lap tip cols]
  (let [in? (running? r)
        open! #(do (fetch-runner-detail! id) (swap! db assoc :open id))]
    [:div.hm-row.seed-row {:style {:grid-template-columns cols} :class (when-not in? "out")}
     [:span.hm-pos pos]
     [:button.hm-name {:on-click open! :title "Figures and yard times"} name]
     [:span.hm-country [:a.cshort {:href (str "#team/" country) :title (country-name country)} [flag country] country]]
     [:span.num {:class (when (and in? (= 2 team-in)) "danger")
                 :title (when (and in? (= 2 team-in)) "If a teammate drops, this runner finishes the yard alone and their race ends")}
      (cond (not in?) "–" (= 2 team-in) "⚠ 2" :else team-in)]
     [:span.num [margin-cell (margin-of r times)]]
     [:span.num.yards (ranked-laps r)]
     (yard-cells r times n current-lap tip)]))

(defn- seed-detail
  "The chosen seed's runners, as yard-cell rows with their team's runners in
  and their margin: still in first (tied ones most comfortable first), the
  stopped ones folded under \"Show\"."
  [_ _]
  (let [tip (r/atom nil)]
    (fn [runners n]
      (let [{:keys [countries lap-times race seed-stopped?]} @db
            current (:current-lap race 1)
            active (into {} (map (juxt :code :active)) countries)
            ranked (seed-ranking runners n)
            margin #(or (:margin (margin-trend (get lap-times (:id %)))) -1)
            [in out] ((juxt filter remove) running? ranked)
            in (sort-by (juxt :pos (comp - margin)) in)
            cols (str "44px minmax(140px, 220px) 76px 70px 96px 56px repeat(" (max 1 current) ", minmax(4px, 1fr))")
            row (fn [r] ^{:key (:id r)} [seed-row r (get lap-times (:id r)) (active (:country r)) current current tip cols])]
        [:<>
         [:p.seed-sum (str (count in) " of " (count ranked) " still in"
                           (when-let [y (some-> (first in) ranked-laps)]
                             (if (every? #(= y (ranked-laps %)) in) (str " · tied on " y " yards") ""))
                           ". Team in: when a team is down to 2, a teammate's drop ends this runner's race.")]
         [:div.lap-grid-wrap {:on-mouse-leave #(reset! tip nil) :on-scroll #(reset! tip nil)}
          [:div.hm.seed-grid
           [:div.hm-row.seed-row.seed-head {:style {:grid-template-columns cols}}
            [:span.num "Pos"] [:span "Runner"] [:span "Country"]
            [:span.num {:title "The country's runners still in"} "Team in"]
            [:span.num {:title "Rest per yard over the last 3 yards, and its trend"} "Margin"]
            [:span.num.yards "Yards"]
            [:span.yards-label {:style {:grid-column (str "span " (max 1 current))}} (str "1 … " current)]]
           (map row in)
           (when (seq out)
             [:div.seed-fold
              [:span (str (count out) " stopped")]
              [:button.link {:aria-expanded (boolean seed-stopped?) :on-click #(swap! db update :seed-stopped? not)}
               (if seed-stopped? "Hide ▴" "Show ▾")]])
           (when seed-stopped? (map row out))]]
         (when-let [{:keys [x y name lap text]} @tip]
           [:div.lg-tip {:style {:left x :top y}} [:b name] (str " · yard " lap " · " text)])
         [lap-grid-legend]]))))

(defn- seeds-card []
  (let [{:keys [runners seed]} @db]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Seed ranking"]
       [:p.sub "Every runner has a seed (1–15), their place in their team by best qualifying yards. Runners in the same seed, compared across all countries. Click a seed for its runners."]]
      [:label.toggle "Seed "
       [:select.seed {:value (or seed "")
                      :on-change #(swap! db assoc :seed (some-> (.. % -target -value) not-empty parse-long))}
        [:option {:value ""} "All seeds"]
        (for [n seeds] ^{:key n} [:option {:value n} n])]]]
     (if (empty? runners)
       [:div.empty "No runners yet"]
       [:div.table-wrap.full
        (if seed [seed-detail runners seed] [seeds-overview runners])])]))

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


(defn- go-team! [code] (set! (.. js/window -location -hash) (str "team/" code)))

(defn- team-switcher
  "Picks another team. The page's header already names the current one, so
  the picker doesn't repeat it."
  [placeholder]
  [:div.team-switch
   [country-select {:value nil :on-pick go-team! :placeholder placeholder :label "Switch team"}]])

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
       ;; the picker right next to the name it switches away from
       [:div.team-name-row
        [:h2 name " " [:span.code code]]
        [team-switcher "Switch team"]]
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
  [[:catching-you "Threats from behind" "danger"]
   [:catching "Targets ahead" "chance"]
   [:level "Staying level" nil]
   [:cant-catch "Stays behind" nil]
   [:out-of-reach "Stays ahead" nil]])

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

(defn- one-drop-line
  "What one runner less now would cost this team, if nobody else drops: who
  would pass it within 6 yards (or sooner than they will anyway), in the
  chase's words: \"in N yards\" from the last bell."
  [{:keys [ends? passes]} current-lap]
  (let [in-yards #(- % (dec current-lap))]
    [:div.nb-line.one-drop
     [:span.label {:title "If this team loses one runner this yard and nobody else drops: the countries that would pass it within 6 yards, or sooner than they will anyway"}
      "If one drops now"]
     (cond
       (and ends? (empty? passes)) [:span.muted "its race ends, and nobody passes it in the next 6 yards"]
       (empty? passes) [:span.muted "no place lost in the next 6 yards"]
       :else [:<>
              (when ends? [:span "its race ends;"])
              (for [{:keys [code lap]} passes]
                ^{:key code}
                [:span.drop-yard
                 [rival-chip {:code code :name (country-name code)} false]
                 [:span.muted (in-laps (in-yards lap))]])])]))

(defn- chase-card [{:keys [country chase one-drop current-lap]}]
  (let [{:keys [ahead level behind field-active]} chase
        ;; level countries go above or below this one by the tie-break
        [level-above level-below] ((juxt filter remove) #(ahead-of? % country) level)
        ;; once out, nothing ahead can change: only being overtaken can
        out? (:eliminated-after country)
        ahead (if out? [] ahead)
        ;; a country that has stopped and stays behind changes nothing: leave it out
        stopped-behind? #(and (:eliminated-after %) (= :cant-catch (chase-bucket % country)))
        behind (remove stopped-behind? behind)
        level-below (remove stopped-behind? level-below)
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
        ;; nothing to chase yet (early on); a country that is out just has its final score
        early? (and (not out?) (empty? ahead) (empty? behind))
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
                      :catching-you (str "⚠ threat · " (in-laps laps-to-pass))
                      :catching (str "✓ target · " (in-laps laps-to-pass))
                      :level "staying level"
                      :cant-catch "stays behind"
                      :out-of-reach "stays ahead"))]]))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "The chase"]
       [:p.sub (if (:eliminated-after country)
                 (str "Who can still pass " nm ", if nobody else drops.")
                 (str "Threats from behind and targets ahead for " nm ", if nobody else drops."))]]]
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
     (when one-drop [:div.buckets [one-drop-line one-drop current-lap]])
     (when (and early? field-active)
       [:p.chase-note (str nm " has " (:active country) " in; countries still going average " field-active ".")])
     (when (and (not early?) (seq rivals))
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
                  [:th.num {:title "What this team gains on them each yard: its runners in minus theirs (a country that's out or on its last runner adds nothing)"} "This team gains / yard"]
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
     (when (and (not early?) (seq rivals) (#{:history :runners} view))
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
         "Each line is a country's team score minus " (:name country) "'s. Above the dashed line it's ahead, below behind; "
         "crossing it is an overtake. Lines end at the last yard both countries reported."
         rivals-note])])]))

(defn- team-runners [{:keys [runners]}]
  (let [sort-by-key (:team-sort @db :seed)
        ;; seed order; most yards first; fastest average first (untimed last)
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
       [:p.sub "Their seed (place in the team by best qualifying yards), and their place among all runners in that seed across countries. Click a runner for their yard times."]]
      [:div.segmented {:role "group" :aria-label "Sort runners by"}
       (for [[v label] [[:seed "Seed"] [:yards "Yards"] [:avg "Avg"]]]
         ^{:key v}
         [:button {:aria-pressed (= v sort-by-key)
                   :on-click #(do (swap! db assoc :team-sort v) (save-prefs!))} label])]]
     [:div.table-wrap.full
      ;; same order and widths as the runner leaderboard for the columns they share
      [:table.runners.team-runners
       [:colgroup [:col] [:col.n] [:col.n] [:col.status] [:col.time] [:col.time] [:col.time] [:col.seed-race] [:col.spark]]
       [:thead [:tr [:th "Runner"] [:th.num "Seed"] [:th.num "Yards"] [:th "Status"]
                [:th.num "Last yard"]
                [:th.num {:title "Average of the timed yards"} "Avg"]
                [:th.num {:title "Rest per yard, average of the last 3 timed yards"} "Margin"]
                [:th {:title "Place among all runners in this seed"} "Seed race"]
                [:th "Yard times"]]]
       [:tbody
        (for [{:keys [id name rank laps last-seconds avg-seconds seed-place seed-runners history] :as r} runners]
          ^{:key id}
          [:tr.clickable {:class (when (= "out" (:status r)) "out")
                          :on-click #(do (fetch-runner-detail! id) (swap! db assoc :open id))}
           [:td.name name]
           [:td.num rank]
           [:td.num laps]
           [:td [status-badge r]]
           [:td.num (or (mmss last-seconds) "–")]
           [:td.num (or (mmss avg-seconds) "–")]
           ;; with its trend, as on the Runners and Seeds views
           [:td.num [margin-cell (margin-of r (vec (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])) history)))]]
           [:td (when seed-place
                  [:a {:href "#seeds" :on-click (fn [e] (.stopPropagation e) (swap! db assoc :seed rank))}
                   (str (ordinal seed-place) " of " seed-runners)])]
           [:td [chart/sparkline (vec (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])) history))]]])]]]]))

(defn- lap-shade
  "0-5 by lap time: under 40 min, 40-46, 46-49, 49-53, 53-57, 57 or more.
  The fastest yards go well under 30 min; about 2% are under 40."
  [s]
  (cond (< s 2400) 0 (< s 2760) 1 (< s 2940) 2 (< s 3180) 3 (< s 3420) 4 :else 5))

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

(defn- lap-grid-legend []
  [:ul.legend.lg-legend
   (for [[i t] (map-indexed vector ["under 40:00" "40–46" "46–49" "49–53" "53–57" "57:00 or more"])]
     ^{:key i} [:li [:span {:class (str "lg s" i)}] t])
   [:li [:span.lg.dnf] "stopped in the yard"]
   [:li [:span.lg.rtc] "didn't start the yard (RTC)"]
   [:li [:span.lg.notime] "no time"]
   [:li [:span.lg.running] "running now"]])

(defn- lap-grid
  "Runners × laps. Hovering (or tapping) a cell shows its lap at once, in a
  tooltip fixed to the viewport, so the grid's scrolling box can't clip it.
  \"Yard time | Margin\" shades the yards by time, or as the Runners view's
  Every yard does by margin, with its slowing bars."
  [_]
  (let [tip (r/atom nil)]  ; {:x :y :name :lap :text} of the cell under the pointer
    (fn [{:keys [runners settled-lap current-lap]}]
      (let [n (max 1 (if (pos? settled-lap) current-lap 1))
            margin? (not= :time (:team-yard-shade @db))   ; by margin unless Yard time was picked
            show! (fn [e r l lap extra]
                    (let [rect (.getBoundingClientRect (.-currentTarget e))]
                      (reset! tip {:x (+ (.-left rect) (/ (.-width rect) 2)) :y (.-top rect)
                                   :name (:name r) :lap l :text (str (lap-text lap r l current-lap) extra)})))]
        [:section.card.wide
         [:div.card-head
          [:div [:h2 "Every yard, every runner"]
           [:p.sub "One cell per yard. Hover or tap a cell for its yard time, or why the runner stopped."]]
          [:div.segmented {:role "group" :aria-label "Shade the yards by"}
           (for [[v label] [[:margin "Margin"] [:time "Yard time"]]]
             ^{:key v}
             [:button {:aria-pressed (= v (:team-yard-shade @db :margin))
                       :on-click #(do (swap! db assoc :team-yard-shade v) (save-prefs!))} label])]]
         [:div.lap-grid-wrap {:on-mouse-leave #(reset! tip nil) :on-scroll #(reset! tip nil)}
          [:div.lap-grid {:style {:grid-template-columns (str "minmax(110px, 160px) repeat(" n ", minmax(5px, 1fr))")}}
           (for [{:keys [id name history stop] :as r} runners
                 :let [by-lap (into {} (map (juxt :lap identity)) history)
                       times (when margin? (vec (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])) history)))
                       margins (when margin? (margins-by-lap times))
                       slowdowns (when margin? (slowdowns-by-lap times))]]
             ^{:key id}
             [:<>
              [:span.lg-name name]
              (if (= "dns" stop)
               [:span.lg-dns {:style {:grid-column (str "span " n)}} "did not start"]
               (for [l (range 1 (inc n))
                    :let [{:keys [status seconds] :as lap} (by-lap l)
                          slowing (when (slowing? (get slowdowns l)) (slowdowns l))
                          extra (str (when-let [m (get margins l)] (str " · margin " (mmss m)))
                                     (when slowing (str " · slowing: the last 2 yards " (mmss slowing) " slower than the 6 before")))]]
                ^{:key l}
                [:span {:class [(cond
                                  (= "dnf" status) (cond (= "rtc" (:stop lap)) "lg rtc" margin? "lg end" :else "lg dnf")
                                  (and seconds margin?) (str "lg mg" (margin-level {:margin (margins l)}) (when slowing " slowing"))
                                  seconds (str "lg s" (lap-shade seconds))
                                  (= "finished" status) "lg notime"
                                  (and (= "running" (:status r)) (= l current-lap)) "lg running"
                                  :else "lg none")
                                (when (and (= name (:name @tip)) (= l (:lap @tip))) "hover")]
                        :aria-label (str name ", yard " l ": " (lap-text lap r l current-lap) extra)
                        :on-mouse-enter #(show! % r l lap extra)
                        :on-click #(show! % r l lap extra)}]))])]]
         (when-let [{:keys [x y name lap text]} @tip]
           [:div.lg-tip {:style {:left x :top y}} [:b name] (str " · yard " lap " · " text)])
         (if margin? [margin-grid-legend] [lap-grid-legend])]))))

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
     (cond
       (nil? team) [:div.grid [:section.card.wide [:div.empty [team-switcher "Pick a country"]
                                                   [:p "Pick a country to see its team page."]]]]
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
   {:id :charts :label "Charts"}
   {:id :team :label "Team"}
   {:id :runners :label "Runners"}
   {:id :seeds :label "Seeds"}])

(defn- parse-hash
  "{:view v} from the URL hash, plus :team for #team/CODE."
  []
  (let [h (subs (.. js/window -location -hash) 1)
        [v code] (str/split h #"/" 2)]
    (cond
      (= v "team") (cond-> {:view :team} (seq code) (assoc :team (str/upper-case code)))
      ;; #seats was the Seeds view's old name
      (= v "seats") {:view :seeds}
      ;; #countries was the Race view's old name: old links still land there
      :else {:view (or (some #(when (= v (name (:id %))) (:id %)) views) :race)})))

(defonce ^:private tracked (atom nil))

(defn- track-view!
  "Tells Vercel Web Analytics which view is shown, as a path (/race,
  /team/BEL), grouped by route (/team/[code]). Once per change of view."
  [view team]
  (let [path (str "/" (name view) (when (= :team view) (str "/" team)))]
    (when (and js/window.va (not= path @tracked))
      (reset! tracked path)
      (js/window.va "pageview" #js {:path path
                                    :route (if (= :team view) "/team/[code]" path)}))))

(defn- go-to-hash! []
  (let [{:keys [view team]} (parse-hash)
        changed? (and team (not= team (:team @db)))]
    (swap! db #(cond-> (assoc % :view view)
                 team (assoc :team team)
                 changed? (assoc :team-data nil)))
    (when team (save-prefs!))
    (track-view! view (:team @db))
    (when (= view :charts) (fetch-panels!))
    (fetch-team!)
    (fetch-overtakes!)
    (fetch-runner-times!)))

(defn- apply-theme!
  "Light or dark on <html data-theme>; system leaves it to the OS setting."
  [theme]
  (let [el js/document.documentElement]
    (if (#{:light :dark} theme)
      (set! (.. el -dataset -theme) (name theme))
      (js-delete (.-dataset el) "theme"))))

(defn- theme-toggle []
  (let [theme (:theme @db :system)]
    [:div.segmented.theme {:role "group" :aria-label "Theme"}
     (for [[v label icon] [[:light "Light" "☀"] [:dark "Dark" "☾"] [:system "System" "◐"]]]
       ^{:key v}
       [:button {:aria-pressed (= v theme) :title label :aria-label label
                 :on-click #(do (swap! db assoc :theme v) (apply-theme! v) (save-prefs!))}
        icon])]))

(defn- nav []
  (let [view (:view @db)]
    [:nav.menu {:aria-label "Views"}
     [:div.brand "WTC 2026" [:span (if (get-in @db [:race :simulation]) "Sim" "Live")]]
     [:ul
      (for [{:keys [id label]} views]
        ^{:key id}
        [:li [:a {:href (if (= id :team) (str "#team/" (:team @db)) (str "#" (name id)))
                  :aria-current (when (= id view) "page")}
              label]])]
     [theme-toggle]]))

(defn- charts-view
  "The race over time, in two sections: a group of countries (the group and
  All yards | Last 12 right above their charts) and the whole field (no
  controls)."
  []
  (let [{compare true overall false} (group-by (comp boolean :per-country?) panels/panels)]
    [:<>
     [:section.chart-section
      [:div.section-head
       [:h2 "Compare countries"]
       [:p.sub "Pick a group of countries. Hover a line to see which country it is."]]
      [:div.charts-bar
       [:div.segmented {:role "group" :aria-label "Countries"}
        (for [[g label] chart-groups]
          ^{:key g}
          [:button {:aria-pressed (= g (:chart-group @db))
                    :title (str (count (group-codes g)) " countries")
                    :on-click #(pick-group! g)} label])]
       [:div.segmented {:role "group" :aria-label "Yards shown"}
        (for [[v label] [[:all "All yards"] [:last-12 "Last 12"]]]
          ^{:key v}
          [:button {:aria-pressed (= v (:chart-range @db :all))
                    :on-click #(do (swap! db assoc :chart-range v) (save-prefs!))} label])]]
      [:div.grid
       (for [p compare] ^{:key (:id p)} [panel p])]]
     [:section.chart-section
      [:div.section-head
       [:h2 "The whole field"]
       [:p.sub (str "All " (count (:countries @db)) " countries, no selection needed.")]]
      [:div.grid
       (for [p overall] ^{:key (:id p)} [panel p])]]]))

(defn- view-body [view]
  (case view
    :team [team-view]
    :runners [:div.grid [runners-table]]
    :seeds [:div.grid [seeds-card]]
    :charts [charts-view]
    ;; race: who went out at the last bell, the passes coming, then the standings
    [:div.grid [drops-card] [overtakes-card] [countries-table]]))

(defn- site-footer
  "Who made it, where the data and the code come from, and that it's
  unofficial."
  []
  (let [ext (fn [href text] [:a {:href href :target "_blank" :rel "noopener"} text])]
    [:footer.site-footer
     [:p "Made by Endymion Kasanardjo · "
      (ext "https://github.com/zetafish/byu-wtc-2026" "Source on GitHub")]
     [:p "Data: the " (ext "https://wtc2026.bigsbackyardultra.com/" "official WTC 2026 results")
      ", entered hourly by each country's race director. Ours may lag them by a few minutes."]
     [:p "An unofficial fan site, not affiliated with Big's Backyard Ultra."]]))

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
    [site-footer]]])

(defonce root (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load render []
  (rdc/render @root [app]))

(defn init []
  (when-let [{:keys [favourites show-out-runners runner-filter variant sim-poll-ms team chase-view chase-range team-sort runner-sort runners-view yard-shade team-yard-shade race-view slot-metric theme standings-filter chart-range chart-group]} (load-prefs)]
    (swap! db assoc
           :team team
           :chase-view (case chase-view "history" :history "runners" :runners :numbers)
           :chase-range (if (= "last-12" chase-range) :last-12 :all)
           :team-sort (case team-sort "yards" :yards "avg" :avg :seed)
           :runners-view (if (= "heatmap" runners-view) :heatmap :table)
           :yard-shade (if (= "time" yard-shade) :time :margin)
           :team-yard-shade (if (= "time" team-yard-shade) :time :margin)
           :runner-sort (when-let [by (#{:last :avg :fastest :total} (keyword (:by runner-sort)))]
                          {:by by :desc? (true? (:desc? runner-sort))})
           :race-view (if (= "runners" race-view) :runners :teams)
           :slot-metric (if (= "margin" slot-metric) :margin :yards)
           :theme (case theme "light" :light "dark" :dark :system)
           :standings-filter (if (= "running" standings-filter) :running :all)
           :chart-range (if (= "last-12" chart-range) :last-12 :all)
           :chart-group (or (#{"TOP" "SUPER" "AAA" "AA" "A" "ALL"} chart-group) "ALL")
           :sim-poll-ms (or sim-poll-ms default-sim-poll-ms)
           :variant (or variant {})
           :favourites (set favourites)
           :runner-filter (set runner-filter)
           :show-out-runners? (true? show-out-runners)))
  (apply-theme! (:theme @db))
  ;; a shared time-travel link (?at=n); the server answers live if n isn't a finished yard
  (when-let [n (some-> (.get (js/URLSearchParams. (.-search js/location)) "at") parse-long)]
    (swap! db assoc :at n :travel-open? true))
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
