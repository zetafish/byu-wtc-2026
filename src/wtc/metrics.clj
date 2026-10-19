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

(defn- out-at
  "The lap whose bell a runner went out at, as the official site counts it:
  an RTC finished lap n and didn't start the next, an INC or OVER failed
  lap n itself, a missing result is the lap after the last. nil for DNS."
  [{:keys [laps stop]}]
  (case stop :dns nil :rtc laps (inc laps)))

(defn- attrition
  "Runners out per lap, per reason: the stop code when the source gives one
  (rtc, inc, over), else dnf or missing. DNS isn't a drop."
  [derived params]
  (let [rs (filter #(and (= :out (:status %)) (out-at %)) (select derived params))]
    (for [[reason rrs] (sort-by key (group-by #(name (or (:stop %) (:reason %))) rs))
          s (grouped rrs params "attrition"
                     (fn [xs] (let [freq (frequencies (map out-at xs))]
                                (mapv (fn [n] [n (get freq n 0)])
                                      (range 1 (inc (:current-lap derived)))))))]
      (assoc-in s [:metric :reason] reason))))

(defn- team-rank
  "Each country's place after every settled lap, by the official ranking:
  team score, then the farthest runner, shared when both are equal."
  [derived {:keys [countries] :as params}]
  (let [by-country (group-by :country (vals (:runners derived)))
        laps (range 1 (inc (:settled-lap derived)))
        key-at (fn [rs n] (let [ls (map #(min (:laps %) n) rs)] [(reduce + 0 ls) (reduce max 0 ls)]))
        places (into {} (for [n laps
                              :let [ks (update-vals by-country #(key-at % n))
                                    vs (vals ks)]]
                          [n (update-vals ks (fn [k] (inc (count (filter #(pos? (compare % k)) vs)))))]))]
    (for [c (sort (cond->> (keys by-country) (seq countries) (filter countries)))]
      {:metric {:__name__ "team_rank" :country c}
       :values (mapv (fn [n] [n (get-in places [n c])]) laps)})))

(defn- countries-running
  "How many countries are still in the race after each settled lap."
  [derived _]
  (let [cs (vals (:countries derived))]
    [{:metric {:__name__ "countries_running"}
      :values (mapv (fn [n] [n (count (filter #(let [a (:eliminated-after %)] (or (nil? a) (> a n))) cs))])
                    (range 1 (inc (:settled-lap derived))))}]))

(defn- lap-time
  "Each runner's lap times; with lap=, only from that lap on (a page that
  has the earlier ones fetches just the new), leaving out runners with none."
  [derived {from :lap :as params}]
  (for [r (sort-by :id (select derived params))
        :let [values (into [] (keep (fn [{:keys [lap seconds]}]
                                      (when (and seconds (or (nil? from) (>= lap from))) [lap seconds])))
                           (:history r))]
        :when (or (nil? from) (seq values))]
    {:metric {:__name__ "lap_time_seconds" :runner (:id r) :name (:name r) :country (:country r)}
     :values values}))

(defn- median [xs]
  (let [v (vec (sort xs)) n (count v)]
    (when (pos? n)
      (if (odd? n) (v (quot n 2)) (quot (+ (v (dec (quot n 2))) (v (quot n 2))) 2)))))

(defn- lap-time-median
  "The field's median lap time per lap, over the runners with a timed lap."
  [derived params]
  (let [by-lap (->> (select derived params)
                    (mapcat :history)
                    (keep (fn [{:keys [lap seconds]}] (when seconds [lap seconds])))
                    (group-by first))]
    [{:metric {:__name__ "lap_time_median_seconds"}
      :values (mapv (fn [[lap xs]] [lap (median (map second xs))]) (sort-by key by-lap))}]))

(defn- countries-by-active [derived {:keys [lap] :as params}]
  (let [n (or lap (:current-lap derived))
        active (update-vals (group-by :country (select derived params))
                            (fn [rs] (count (filter #(>= (:started %) n) rs))))
        freq (frequencies (vals active))]
    [{:metric {:__name__ "countries_by_active_runners" :lap n}
      ;; from 1: the countries with nobody left (most, late on) would dwarf the rest
      :values (mapv (fn [k] [k (get freq k 0)])
                    (range 1 (inc (reduce max 0 (keys freq)))))}]))

(def registry
  {"runners_started" {:doc "Runners starting lap n. by=country" :x "lap" :fn runners-started}
   "team_score" {:doc "Cumulative laps per country (team score) after lap n" :x "lap" :fn team-score}
   "team_score_gap" {:doc "Team score minus the leading country's team score after lap n (leader = 0)" :x "lap" :fn team-score-gap}
   "attrition" {:doc "Runners out at the bell of lap n, per reason (rtc, inc, over, dnf, missing). by=country" :x "lap" :fn attrition}
   "team_rank" {:doc "Each country's place after lap n (official ranking); filter with countries=" :x "lap" :fn team-rank}
   "countries_running" {:doc "Countries still in the race after lap n" :x "lap" :fn countries-running}
   "lap_time_seconds" {:doc "Lap times per runner; filter with runners= or countries=, lap= from that lap on" :x "lap" :fn lap-time}
   "lap_time_median_seconds" {:doc "The field's median lap time per lap" :x "lap" :fn lap-time-median}
   "countries_by_active_runners" {:doc "Histogram: number of countries with k >= 1 runners starting lap= (default current)" :x "active_runners" :fn countries-by-active}})

(defn query [derived metric params]
  (if-let [{:keys [x] f :fn} (registry metric)]
    {:status "success"
     :data {:resultType "matrix" :xAxis x :result (vec (f derived params))}}
    {:status "error" :error (str "unknown metric " metric)}))
