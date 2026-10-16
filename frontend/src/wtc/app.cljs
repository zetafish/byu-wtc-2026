(ns wtc.app
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [wtc.chart :as chart]
            [wtc.panels :as panels]))

(def max-selected 8)
(def poll-ms 60000)
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
                     :seat nil        ; seat ranking: nil = all seats overview
                     :results {}      ; [panel id, metric] -> query result
                     :variant {}      ; panel id -> index into the panel's :variants
                     :zoom nil        ; panel id shown big in the zoom dialog
                     :open nil}))      ; runner id with the detail chart open

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
                                                             :sim-poll-ms (:sim-poll-ms @db)})))
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
  "Lap times for the sparklines: the leaderboard's country filter plus the
  starred runners. Unfiltered, all ~1,000 runners would be too much per poll."
  [latest?]
  (let [ids (str/join "," (:starred @db))
        codes (str/join "," (sort (:runner-filter @db)))]
    (-> (js/Promise.all
         (clj->js [(if (seq codes) (get-json "/api/query" {:metric "lap_time_seconds" :countries codes}) #js {})
                   (if (seq ids) (get-json "/api/query" {:metric "lap_time_seconds" :runners ids}) #js {})]))
        (.then (fn [rs]
                 (let [series (mapcat #(get-in (js->clj % :keywordize-keys true) [:data :result]) rs)]
                   (when (latest?)
                     (swap! db update :lap-times merge
                            (into {} (map (fn [s] [(get-in s [:metric :runner]) (:values s)])) series))))))
        (.catch #(js/console.warn %)))))

(defn- fetch-lap-times! [id]
  (-> (get-json (str "/api/runners/" (js/encodeURIComponent id)) {})
      (.then (fn [r] (swap! db assoc-in [:lap-times id]
                            (into [] (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])))
                                  (:history r)))))
      (.catch #(js/console.warn %))))

(defn refresh! []
  ;; charts load alongside the rest instead of after it, so they don't pop in late
  (fetch-panels!)
  (-> (js/Promise.all (clj->js [(get-json "/api/race" {})
                                (get-json "/api/countries" {})
                                (get-json "/api/runners" {})]))
      (.then (fn [[race countries runners]]
               (swap! db assoc :race race :countries countries :runners runners
                      :error nil :updated (js/Date.))))
      (.catch #(swap! db assoc :error (.-message %)))))

(defonce ^:private poll-timer (atom nil))

(declare control-sim!)

(defn- schedule-poll!
  "(Re)starts the poll timer. The real race reloads every `poll-ms`. A demo
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
      (reset! poll-timer (js/setTimeout #(do (refresh!) (schedule-poll!)) poll-ms))
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

(defn- ranked-codes [pred] (map :code (filter pred (sort-by :rank (:countries @db)))))

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

(def ^:private reason-text
  {"dnf" "did not finish" "missing" "no result"})

(defn- status-badge [{:keys [status reason]}]
  (case status
    "running" [:span.pill.in [:span.dot.in] "in race"]
    "finished" [:span.pill.win "★ country winner"]
    [:span.pill.out [:span.icon "✕"] (str "out · " (reason-text reason reason))]))

(defn- finished? [] (= "finished" (get-in @db [:race :phase])))

(defn- country-name [code] (some #(when (= code (:code %)) (:name %)) (:countries @db)))

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
        {:key code :label (or (country-name code) code) :color (color (get selected code 1)) :values values})

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
       (let [podium (take 3 (sort-by :rank countries))]
         [:div.finished
          [:h2 (str "Race finished after " (:race-laps race) " laps")]
          [:ol.podium
           (for [{:keys [code name rank total-laps]} podium]
             ^{:key code}
             [:li [:span.pos (str rank ".")] [:b name] [:span (str total-laps " laps")]])]]))
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
          (for [{:keys [code name rank active runners total-laps eliminated-after winner] :as c} shown
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
              [:td.num rank]
              [:td.name name " " [:span.muted code]]
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

(defn- runner-detail [{:keys [id name]}]
  (let [values (get-in @db [:lap-times id])]
    [:tr.detail
     [:td {:col-span 10}
      [chart/line-chart {:series [{:key id :label name :color "var(--series-1)" :values values}]
                         :y-zero? false :fmt-y mmss :x-label "lap" :y-label "lap time"}]]]))

(defn- runner-filters [n-out show-out?]
  (let [{:keys [countries runner-filter starred-only? query]} @db]
    [:div.filters
     [:select.seat {:value "" :aria-label "Filter by country"
                    :on-change #(some-> (.. % -target -value) not-empty toggle-filter!)}
      [:option {:value ""} "+ Country"]
      (for [{:keys [code name]} (sort-by :name (remove #(contains? runner-filter (:code %)) countries))]
        ^{:key code} [:option {:value code} name])]
     (for [code (sort runner-filter)]
       ^{:key code}
       [:span.chip (or (country-name code) code)
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
        ;; sparklines only for a filtered list, see `fetch-runner-times!`
        sparks? (or (seq runner-filter) starred-only?)]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Runner leaderboard"]
       [:p.sub "All runners of all countries. Star (☆) runners to keep them at hand with \"★ only\". Filter by country or ★ to see lap-time sparklines; click a row for the lap-time chart."]]]
     [runner-filters n-out show-out?]
     (if (empty? shown)
       [:div.empty (cond
                     (seq candidates) "Everyone here is out. Tick \"Show eliminated\" to see them."
                     starred-only? "No starred runners here yet. Star (☆) a runner to add them."
                     :else "No runners match")]
       [:div.table-wrap.full
        [:table
         [:thead [:tr [:th.num "Pos"] [:th] [:th "Runner"] [:th "Country"] [:th.num "Seat"] [:th.num "Laps"]
                  [:th.num {:title "Best backyard result (laps) in the 2026 qualifying period"} "Best"]
                  [:th "Status"]
                  [:th.num "Last lap"] (when sparks? [:th "Lap times"])]]
         [:tbody
          (for [{:keys [id name country laps pos] :as runner} shown
                :let [times (get lap-times id)]]
            ^{:key id}
            [:<>
             [:tr.clickable {:class (when (out-runner? runner) "out")
                             :on-click (fn []
                                         (when-not (contains? lap-times id) (fetch-lap-times! id))
                                         (swap! db update :open (fn [o] (when-not (= o id) id))))}
              [:td.num pos]
              [:td [:button.star {:aria-label (if (starred? runner) "Unstar" "Star")
                                  :aria-pressed (starred? runner)
                                  :on-click (fn [e] (.stopPropagation e) (toggle-star! id))}
                    (if (starred? runner) "★" "☆")]]
              [:td.name name]
              [:td country]
              [:td.num (:rank runner)]
              [:td.num laps]
              [:td.num (or (:best-yards runner) "–")]
              [:td [status-badge runner]]
              [:td.num (mmss (:last-seconds runner))]
              (when sparks? [:td (when times [chart/sparkline times])])]
             (when (= open id) [runner-detail runner])])]]])]))

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
          [:td (when lead [:<> [country-key (:country lead)] (:country lead)])]])
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
       [:td [country-key country] country]
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

;; --- views ---
;; The left menu switches between views. The view lives in the URL hash so
;; it survives a reload, can be linked and works with the back button.

(def views
  [{:id :countries :label "Countries"}
   {:id :runners :label "Runners"}
   {:id :seats :label "Seats"}])

(defn- hash-view []
  (let [h (subs (.. js/window -location -hash) 1)]
    (or (some #(when (= h (name (:id %))) (:id %)) views) :countries)))

(defn- nav-count [id]
  (let [{:keys [countries runners]} @db]
    (case id
      :countries (count (remove out? countries))
      :runners (count (filter running? runners))
      :seats (count seats))))

(defn- nav-title [id]
  (case id
    :countries "countries still in the race"
    :runners "runners still in the race"
    :seats "seats"))

(defn- nav []
  (let [view (:view @db)]
    [:nav.menu {:aria-label "Views"}
     [:div.brand "WTC 2026" [:span (if (get-in @db [:race :simulation]) "Sim" "Live")]]
     [:ul
      (for [{:keys [id label]} views]
        ^{:key id}
        [:li [:a {:href (str "#" (name id))
                  :aria-current (when (= id view) "page")}
              label [:span.count {:title (nav-title id)} (nav-count id)]]])]]))

(defn- country-picker
  "Search box with suggestions; picking a suggestion, or Enter, adds the country."
  []
  (let [text (r/atom "")]
    (fn []
      (let [{:keys [countries selected favourites]} @db
            full? (>= (count selected) max-selected)
            options (->> countries
                         (remove #(contains? selected (:code %)))
                         (sort-by (juxt #(not (contains? favourites (:code %))) :name)))
            add! (fn [{:keys [code]}] (reset! text "") (toggle-country! code))
            find-by (fn [q f] (let [q (str/lower-case (str/trim q))]
                                (when (seq q) (some #(when (f q (str/lower-case (:name %)) (str/lower-case (:code %))) %)
                                                    options))))]
        [:<>
         [:input.search {:type "search" :list "country-options" :value @text :disabled full?
                         :placeholder (if full? (str max-selected " compared, the most") "Add a country")
                         :aria-label "Add a country to compare"
                         :on-change (fn [e]
                                      (let [v (.. e -target -value)]
                                        ;; a picked suggestion fills in the full name
                                        (if-let [c (find-by v (fn [q nm _] (= q nm)))] (add! c) (reset! text v))))
                         :on-key-down (fn [e]
                                        (when (= "Enter" (.-key e))
                                          (some-> (or (find-by @text (fn [q _ cd] (= q cd)))
                                                      (find-by @text (fn [q nm _] (str/starts-with? nm q)))
                                                      (find-by @text (fn [q nm _] (str/includes? nm q))))
                                                  add!)))}]
         [:datalist {:id "country-options"}
          (for [{:keys [code name]} options]
            ^{:key code} [:option {:value name} (str (when (contains? favourites code) "★ ") code)])]]))))

(defn- comparing []
  (let [{:keys [selected favourites]} @db]
    [:div.comparing
     [:span.label "Compare"]
     [country-picker]
     [:button.ghost {:on-click #(select-countries! (ranked-codes any?))} (str "Top " max-selected)]
     (when (seq favourites)
       [:button.ghost {:title (str "Your favourite countries, the best-ranked " max-selected " if more")
                       :on-click #(select-countries! (ranked-codes (comp favourites :code)))} "★ Favourites"])
     [:button.ghost {:on-click #(select-countries! []) :disabled (empty? selected)} "Clear"]
     (if (empty? selected)
       [:span.muted "no countries compared yet"]
       (for [[code slot] (sort-by val selected)]
         ^{:key code}
         [:span.chip [:span.key-box {:style {:background (color slot)}}] (or (country-name code) code)
          [:button.remove {:aria-label (str "Stop comparing " code) :on-click #(toggle-country! code)} "✕"]]))]))

(defn- view-body [view]
  (case view
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
  (when-let [{:keys [selected starred favourites show-out show-out-runners runner-filter starred-only favourites-only variant sim-poll-ms]} (load-prefs)]
    (swap! db assoc
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
  (swap! db assoc :view (hash-view))
  (.addEventListener js/window "hashchange"
                     #(do (swap! db assoc :view (hash-view)) (js/window.scrollTo 0 0)))
  (render)
  ;; the first poll waits for the first load, which says if it's a simulation
  (-> (refresh!) (.then schedule-poll!)))
