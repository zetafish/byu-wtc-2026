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
                     :results {}      ; panel id -> query result
                     :open nil}))      ; runner id with the detail chart open

;; --- persistence of viewer preferences (best effort) ---

(defn- load-prefs []
  (try (some-> (js/localStorage.getItem "wtc-prefs") js/JSON.parse (js->clj :keywordize-keys true))
       (catch :default _ nil)))

(defn- save-prefs! []
  (try (js/localStorage.setItem "wtc-prefs"
                                (js/JSON.stringify (clj->js {:selected (:selected @db)
                                                             :starred (vec (:starred @db))})))
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
    [:span.badge.good [:span.icon "●"] "in race"]
    [:span.badge.out [:span.icon "✕"] (str "out · " (reason-text reason reason))]))

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

(defn- stat [label value]
  [:div.stat [:div.stat-value value] [:div.stat-label label]])

(defn- header []
  (let [{:keys [race countries error updated]} @db
        failing (remove :ok (vals (:fetches race)))]
    [:header
     [:div.title
      [:h1 "World Team Championship 2026"]
      [:p.sub "Big's Backyard Ultra · unofficial live stats"
       (when updated [:span " · updated " (.toLocaleTimeString updated)])]]
     [:div.stats
      [stat "current lap" (:current-lap race "–")]
      [stat "runners in race" (str (:running race "–") " / " (:runners race "–"))]
      [stat "countries in race" (count (filter #(pos? (:active %)) countries))]]
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

(defn- countries-table []
  (let [{:keys [countries selected]} @db
        full? (>= (count selected) max-selected)]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Countries"]
       [:p.sub (str "Select up to " max-selected " to compare in the charts and the runner list")]]]
     [:div.table-wrap
      [:table
       [:thead [:tr [:th "#"] [:th "Country"] [:th.num "In race"] [:th.num "Team score"] [:th "Status"]]]
       [:tbody
        (for [{:keys [code name rank active runners total-laps eliminated-after]} countries
              :let [slot (get selected code)]]
          ^{:key code}
          [:tr {:class (when slot "selected")}
           [:td.num rank]
           [:td [:label.pick
                 [:input {:type "checkbox" :checked (boolean slot) :disabled (and full? (not slot))
                          :on-change #(toggle-country! code)}]
                 [:span.key-box {:style {:background (if slot (color slot) "transparent")}}]
                 name " " [:span.muted code]]]
           [:td.num active " / " runners]
           [:td.num total-laps]
           [:td (if eliminated-after
                  [:span.badge.out [:span.icon "✕"] (str "out after lap " eliminated-after)]
                  [:span.badge.good [:span.icon "●"] "in race"])]])]]]]))

(defn- runner-detail [{:keys [id name]}]
  (let [values (get-in @db [:lap-times id])]
    [:tr.detail
     [:td {:col-span 7}
      [chart/line-chart {:series [{:key id :label name :color "var(--series-1)" :values values}]
                         :y-zero? false :fmt-y mmss :x-label "lap" :y-label "lap time"}]]]))

(defn- runners-table []
  (let [{:keys [runners selected starred open lap-times]} @db
        shown (->> runners
                   (filter #(or (contains? selected (:country %)) (contains? starred (:id %))))
                   (sort-by (juxt #(if (contains? starred (:id %)) 0 1)
                                  #(if (= "running" (:status %)) 0 1)
                                  (comp - :laps))))]
    [:section.card.wide
     [:div.card-head
      [:div [:h2 "Runners"]
       [:p.sub "Starred runners and the selected countries. Click a row for the lap times."]]]
     (if (empty? shown)
       [:div.empty "Select a country or star a runner"]
       [:div.table-wrap
        [:table
         [:thead [:tr [:th] [:th "Runner"] [:th "Country"] [:th.num "Laps"] [:th "Status"]
                  [:th.num "Last lap"] [:th "Lap times"]]]
         [:tbody
          (for [{:keys [id name country laps] :as runner} shown
                :let [times (get lap-times id)]]
            ^{:key id}
            [:<>
             [:tr.clickable {:on-click #(swap! db update :open (fn [o] (when-not (= o id) id)))}
              [:td [:button.star {:aria-label (if (contains? starred id) "Unstar" "Star")
                                  :aria-pressed (contains? starred id)
                                  :on-click (fn [e] (.stopPropagation e) (toggle-star! id))}
                    (if (contains? starred id) "★" "☆")]]
              [:td name]
              [:td [:span.key-box {:style {:background (some-> (get selected country) color)}}] country]
              [:td.num laps]
              [:td [status-badge runner]]
              [:td.num (mmss (second (last times)))]
              [:td [chart/sparkline times]]]
             (when (= open id) [runner-detail runner])])]]])]))

(defn- app []
  [:main
   [header]
   [:div.grid
    (for [p panels/panels] ^{:key (:id p)} [panel p])
    [countries-table]
    [runners-table]]
   [:footer "Unofficial. Data may lag the official results by a few minutes."]])

(defonce root (delay (rdc/create-root (js/document.getElementById "app"))))

(defn ^:dev/after-load render []
  (rdc/render @root [app]))

(defn init []
  (when-let [{:keys [selected starred]} (load-prefs)]
    (swap! db assoc
           :selected (into {} (map (fn [[k v]] [(clojure.core/name k) v])) selected)
           :starred (set starred)))
  (render)
  (refresh!)
  (js/setInterval refresh! poll-ms))
