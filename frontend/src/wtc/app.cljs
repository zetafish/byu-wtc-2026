(ns wtc.app
  (:require [clojure.string :as str]
            [reagent.core :as r]
            [reagent.dom.client :as rdc]
            [wtc.chart :as chart]
            [wtc.panels :as panels]))

(def max-selected 8)
(def poll-ms 60000)

(defonce db (r/atom {:selected {}     ; country code -> color slot 1..8
                     :starred #{}
                     :show-out? false ; list eliminated countries too
                     :show-out-runners? false ; list eliminated runners too
                     :seat nil        ; seat ranking: nil = all seats overview
                     :results {}      ; panel id -> query result
                     :open nil}))      ; runner id with the detail chart open

;; --- persistence of viewer preferences (best effort) ---

(defn- load-prefs []
  (try (some-> (js/localStorage.getItem "wtc-prefs") js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- save-prefs! []
  (try (js/localStorage.setItem "wtc-prefs"
                                (js/JSON.stringify (clj->js {:selected (:selected @db)
                                                             :starred (vec (:starred @db))
                                                             :show-out (:show-out? @db)
                                                             :show-out-runners (:show-out-runners? @db)})))
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

(defn- fetch-panels! []
  (doseq [{:keys [id metric per-country?]} panels/panels]
    (-> (get-json "/api/query" (cond-> {:metric metric}
                                 per-country? (assoc :by "country" :countries (selected-codes))))
        (.then #(swap! db assoc-in [:results id] (get-in % [:data :result])))
        (.catch #(js/console.warn %))))
  (let [ids (str/join "," (:starred @db))
        codes (selected-codes)]
    (-> (js/Promise.all
         (clj->js [(if (seq codes) (get-json "/api/query" {:metric "lap_time_seconds" :countries codes}) #js {})
                   (if (seq ids) (get-json "/api/query" {:metric "lap_time_seconds" :runners ids}) #js {})]))
        (.then (fn [rs]
                 (let [series (mapcat #(get-in (js->clj % :keywordize-keys true) [:data :result]) rs)]
                   (swap! db assoc :lap-times
                          (into {} (map (fn [s] [(get-in s [:metric :runner]) (:values s)])) series)))))
        (.catch #(js/console.warn %)))))

(defn- default-selection [countries]
  (let [codes (map :code (sort-by :rank countries))
        picks (take 4 (distinct (cons "NED" (filter #(some #{%} codes) codes))))]
    (into {} (map-indexed (fn [i c] [c (inc i)])) (filter (set codes) picks))))

(defn refresh! []
  (-> (js/Promise.all (clj->js [(get-json "/api/race" {})
                                (get-json "/api/countries" {})
                                (get-json "/api/runners" {})]))
      (.then (fn [[race countries runners]]
               (swap! db (fn [s]
                           (cond-> (assoc s :race race :countries countries :runners runners
                                          :error nil :updated (js/Date.))
                             (empty? (:selected s)) (assoc :selected (default-selection countries)))))
               (fetch-panels!)))
      (.catch #(swap! db assoc :error (.-message %)))))

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

(defn- toggle-star! [id]
  (swap! db update :starred #(if (contains? % id) (disj % id) (conj % id)))
  (save-prefs!)
  (fetch-panels!))

;; --- view helpers ---

(defn- color [slot] (str "var(--series-" slot ")"))

(defn- mmss [s]
  (when s (str (quot s 60) ":" (.padStart (str (mod s 60)) 2 "0"))))

(def ^:private reason-text
  {"dnf" "did not finish" "missing" "no result" "team" "team rule"})

(defn- status-badge [{:keys [status reason]}]
  (if (= status "running")
    [:span.pill.in [:span.dot.in] "in race"]
    [:span.pill.out [:span.icon "✕"] (str "out · " (reason-text reason reason))]))

(defn- country-name [code] (some #(when (= code (:code %)) (:name %)) (:countries @db)))

(defn- to-series [panel result]
  (let [{:keys [per-country? series-label series-order series-names]} panel
        selected (:selected @db)]
    (cond
      per-country?
      (for [{:keys [metric values]} (sort-by #(get selected (get-in % [:metric :country])) result)
            :let [code (:country metric)]]
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
       [:div.summary
        [:span.chip [:span.dot.in] (str (:running race) " in race")]
        [:span.chip [:span.dot.out] (str (- (:runners race) (:running race)) " out")]
        [:span.chip (str (count (filter :eliminated-after countries)) " of " (count countries) " countries eliminated")]])
     (when (= "pre" (:phase race))
       [:p.notice [:span.icon.info "◷"]
        (str "The race starts "
             (.toLocaleString (js/Date. (:start race)) js/undefined
                              #js {:weekday "long" :day "numeric" :month "long"
                                   :hour "2-digit" :minute "2-digit"})
             " (your local time). Charts fill in after the first lap.")])
     (when (or error (seq failing))
       [:p.notice [:span.icon "⚠"]
        (if error
          (str "Can't reach the server (" error "). Showing the last data we had.")
          (str "Data source problem (" (str/join ", " (map :error failing)) "). Showing the last good data."))])]))


(defn- panel [_]
  (let [table? (r/atom false)]
    (fn [{:keys [id title subtitle kind] :as p}]
      (let [series (to-series p (get-in @db [:results id]))
            opts {:series series :x-label (:x-label p) :y-label (:y-label p)}]
        [:section.card
         [:div.card-head
          [:div [:h2 title] (when subtitle [:p.sub subtitle])]
          [:button.ghost {:on-click #(swap! table? not)} (if @table? "Chart" "Table")]]
         (cond
           @table? [chart/data-table opts]
           (= kind :line) [chart/line-chart opts]
           :else [chart/bar-chart opts])]))))

(defn- out? [{:keys [eliminated-after]}] (some? eliminated-after))

(defn- countries-table []
  (let [{:keys [countries selected show-out?]} @db
        full? (>= (count selected) max-selected)
        n-out (count (filter out? countries))
        ;; selected countries stay visible so they can still be deselected
        shown (filter #(or show-out? (not (out? %)) (contains? selected (:code %))) countries)]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Countries"]
       [:p.sub (str "Select up to " max-selected " to compare in the charts and the runner list")]]
      (when (pos? n-out)
        [:label.toggle
         [:input {:type "checkbox" :checked show-out?
                  :on-change #(do (swap! db update :show-out? not) (save-prefs!))}]
         (str "Show eliminated (" n-out ")")])]
     [:div.table-wrap.full
      [:table
       [:thead [:tr [:th "#"] [:th "Country"] [:th.num "In race"] [:th.num "Team score"] [:th "Status"]]]
       [:tbody
        (for [{:keys [code name rank active runners total-laps eliminated-after]} shown
              :let [slot (get selected code)]]
          ^{:key code}
          [:tr {:class [(when slot "selected") (when eliminated-after "out")]}
           [:td.num rank]
           [:td [:label.pick
                 [:input {:type "checkbox" :checked (boolean slot) :disabled (and full? (not slot))
                          :on-change #(toggle-country! code)}]
                 [:span.key-box {:style {:background (if slot (color slot) "transparent")}}]
                 name " " [:span.muted code]]]
           [:td.num active " / " runners]
           [:td.num total-laps]
           [:td (if eliminated-after
                  [:span.pill.out [:span.icon "✕"] (str "out after lap " eliminated-after)]
                  [:span.pill.in [:span.dot.in] "in race"])]])]]]]))

(defn- runner-detail [{:keys [id name]}]
  (let [values (get-in @db [:lap-times id])]
    [:tr.detail
     [:td {:col-span 8}
      [chart/line-chart {:series [{:key id :label name :color "var(--series-1)" :values values}]
                         :y-zero? false :fmt-y mmss :x-label "lap" :y-label "lap time"}]]]))

(defn- runners-table []
  (let [{:keys [runners selected starred open lap-times show-out-runners?]} @db
        starred? #(contains? starred (:id %))
        candidates (filter #(or (contains? selected (:country %)) (starred? %)) runners)
        out-runner? #(not= "running" (:status %))
        n-out (count (filter out-runner? candidates))
        ;; starred runners stay visible so they can still be unstarred
        shown (->> candidates
                   (filter #(or show-out-runners? (not (out-runner? %)) (starred? %)))
                   (sort-by (juxt #(if (starred? %) 0 1)
                                  #(if (out-runner? %) 1 0)
                                  (comp - :laps))))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Runners"]
       [:p.sub "Starred runners and the selected countries. Click a row for the lap times."]]
      (when (pos? n-out)
        [:label.toggle
         [:input {:type "checkbox" :checked show-out-runners?
                  :on-change #(do (swap! db update :show-out-runners? not) (save-prefs!))}]
         (str "Show eliminated (" n-out ")")])]
     (if (empty? shown)
       [:div.empty (if (seq candidates)
                     "Everyone here is out. Tick \"Show eliminated\" to see them."
                     "Select a country or star a runner")]
       [:div.table-wrap.full
        [:table
         [:thead [:tr [:th] [:th "Runner"] [:th "Country"] [:th.num "Laps"]
                  [:th.num {:title "Best backyard result (laps) in the 2026 qualifying period"} "Best"]
                  [:th "Status"]
                  [:th.num "Last lap"] [:th "Lap times"]]]
         [:tbody
          (for [{:keys [id name country laps] :as runner} shown
                :let [times (get lap-times id)]]
            ^{:key id}
            [:<>
             [:tr.clickable {:class (when (not= "running" (:status runner)) "out")
                             :on-click #(swap! db update :open (fn [o] (when-not (= o id) id)))}
              [:td [:button.star {:aria-label (if (contains? starred id) "Unstar" "Star")
                                  :aria-pressed (contains? starred id)
                                  :on-click (fn [e] (.stopPropagation e) (toggle-star! id))}
                    (if (contains? starred id) "★" "☆")]]
              [:td name]
              [:td [:span.key-box {:style {:background (some-> (get selected country) color)}}] country]
              [:td.num laps]
              [:td.num (or (:best-yards runner) "–")]
              [:td [status-badge runner]]
              [:td.num (mmss (second (last times)))]
              [:td [chart/sparkline times]]]
             (when (= open id) [runner-detail runner])])]]])]))

;; --- seat ranking ---
;; Every runner has a seat (1-15, their roster rank in the team). All runners
;; with the same seat compete across countries; each seat's winner is invited
;; to the Individual Big's Backyard Ultra.

(def seats (range 1 16))

(defn- running? [r] (= "running" (:status r)))

(defn- seat-key [r] [(:laps r) (if (running? r) 1 0)])

(defn- seat-ranking
  "Runners of seat n, best first, with :pos. Equal laps share a position,
  except that a runner still going is ahead of one who is out."
  [runners n]
  (let [rs (filter #(= n (:rank %)) runners)
        better? (fn [a b] (pos? (compare (seat-key a) (seat-key b))))]
    (->> rs
         (map (fn [r] (assoc r :pos (inc (count (filter #(better? % r) rs))))))
         (sort-by (juxt :pos :name)))))

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

(defn- app []
  [:main
   [header]
   [:div.grid
    (for [p panels/panels] ^{:key (:id p)} [panel p])
    [countries-table]
    [seats-card]
    [runners-table]]
   [:footer "Unofficial. Data may lag the official results by a few minutes."]])

(defonce root (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load render []
  (rdc/render @root [app]))

(defn init []
  (when-let [{:keys [selected starred show-out show-out-runners]} (load-prefs)]
    (swap! db assoc
           :selected (into {} (map (fn [[k v]] [(clojure.core/name k) v])) selected)
           :starred (set starred)
           :show-out? (true? show-out)
           :show-out-runners? (true? show-out-runners)))
  (render)
  (refresh!)
  (js/setInterval refresh! poll-ms))
