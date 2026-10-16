(ns wtc.metrics
  "Visualisation queries, answered in a Prometheus-like shape so the
  frontend can render any of them with generic panels:

    {:status \"success\"
     :data {:resultType \"matrix\" :xAxis \"lap\"
            :result [{:metric {:__name__ \"runners_started\" :country \"NED\"}
                      :values [[1 15] [2 15] ...]}]}}

  To add a visualisation, add an entry to `registry`.")

(defn- select [derived {:keys [countries runners]}]
  (cond->> (vals (:runners derived))
    (seq countries) (filter #(contains? countries (:country %)))
    (seq runners) (filter #(contains? runners (:id %)))))

(defn- grouped
  "One series per country with by=country, otherwise one total series."
  [rs {:keys [by]} metric-name f]
  (if (= by "country")
    (for [[c crs] (sort-by key (group-by :country rs))]
      {:metric {:__name__ metric-name :country c} :values (f crs)})
    [{:metric {:__name__ metric-name} :values (f rs)}]))

(defn- runners-started [derived params]
  (grouped (select derived params) params "runners_started"
           (fn [rs] (mapv (fn [n] [n (count (filter #(>= (:started %) n) rs))])
                          (range 1 (inc (:current-lap derived)))))))

(defn- team-score [derived params]
  (grouped (select derived params) (assoc params :by "country") "team_score"
           (fn [rs] (mapv (fn [n] [n (reduce + (map #(min (:laps %) n) rs))])
                          (range 1 (inc (:settled-lap derived)))))))

(defn- team-score-gap
  "Team score minus the leader's team score at each lap, the leader being
  the best of *all* countries (so a country's line doesn't move when others
  are compared). The leader is at 0; it makes small differences visible
  that a cumulative total hides."
  [derived params]
  (let [lead (->> (team-score derived {})
                  (map #(into {} (:values %)))
                  (apply merge-with max {}))]
    (for [s (team-score derived params)]
      (-> s
          (assoc-in [:metric :__name__] "team_score_gap")
          (update :values (fn [vs] (mapv (fn [[n y]] [n (- y (lead n 0))]) vs)))))))

(defn- attrition [derived params]
  (let [rs (filter #(= :out (:status %)) (select derived params))]
    (for [[reason rrs] (sort-by key (group-by :reason rs))
          s (grouped rrs params "attrition"
                     (fn [xs] (let [freq (frequencies (map :started xs))]
                                (mapv (fn [n] [n (get freq n 0)])
                                      (range 1 (inc (:current-lap derived)))))))]
      (assoc-in s [:metric :reason] (name reason)))))

(defn- lap-time [derived params]
  (for [r (sort-by :id (select derived params))]
    {:metric {:__name__ "lap_time_seconds" :runner (:id r) :name (:name r) :country (:country r)}
     :values (into [] (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])))
                   (:history r))}))

(defn- countries-by-active [derived {:keys [lap] :as params}]
  (let [n (or lap (:current-lap derived))
        active (update-vals (group-by :country (select derived params))
                            (fn [rs] (count (filter #(>= (:started %) n) rs))))
        freq (frequencies (vals active))]
    [{:metric {:__name__ "countries_by_active_runners" :lap n}
      :values (mapv (fn [k] [k (get freq k 0)])
                    (range 0 (inc (reduce max 0 (keys freq)))))}]))

(def registry
  {"runners_started" {:doc "Runners starting lap n. by=country" :x "lap" :fn runners-started}
   "team_score" {:doc "Cumulative laps per country (team score) after lap n" :x "lap" :fn team-score}
   "team_score_gap" {:doc "Team score minus the leading country's team score after lap n (leader = 0)" :x "lap" :fn team-score-gap}
   "attrition" {:doc "Runners out during/after lap n, per reason. by=country" :x "lap" :fn attrition}
   "lap_time_seconds" {:doc "Lap times per runner; filter with runners= or countries=" :x "lap" :fn lap-time}
   "countries_by_active_runners" {:doc "Histogram: number of countries with k runners starting lap= (default current)" :x "active_runners" :fn countries-by-active}})

(defn query [derived metric params]
  (if-let [{:keys [x] f :fn} (registry metric)]
    {:status "success"
     :data {:resultType "matrix" :xAxis x :result (vec (f derived params))}}
    {:status "error" :error (str "unknown metric " metric)}))
