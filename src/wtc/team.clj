(ns wtc.team
  "Everything the team page shows for one country, from the derived race
  state (see `wtc.race/derive-state`). Pure.

  The chase: each lap, a country gains exactly as many yards as it has
  runners still in. So the gap between two countries changes every lap by
  the difference in their runners still in, and the laps until one passes
  the other follow from that, as long as nobody drops."
  (:require [wtc.model :as model]))

(defn- totals-at
  "[team score, farthest runner] of runners `rs` after lap n."
  [rs n]
  (let [ls (map #(min (:laps %) n) rs)]
    [(reduce + 0 ls) (reduce max 0 ls)]))

(defn- places-at
  "Official place of every country after lap n: by team score, then the
  farthest runner, shared when both are equal. {code place}"
  [by-country n]
  (let [ks (update-vals by-country #(totals-at % n))
        better (fn [k] (count (filter #(pos? (compare % k)) (vals ks))))]
    (update-vals ks #(inc (better %)))))

(defn- seat-places
  "Every runner's place in their seat across all countries, as on the Seats
  view: by laps, a runner still in ahead of one who is out, else shared.
  {id [place runners-in-seat]}"
  [runners]
  (into {}
        (for [[_ rs] (group-by :rank (filter :rank runners))
              :let [k (fn [r] [(:laps r) (if (= :running (:status r)) 1 0)])
                    ks (map k rs)]
              r rs]
          [(:id r) [(inc (count (filter #(pos? (compare % (k r))) ks))) (count rs)]])))

(defn- laps-to-pass
  "Laps until a country `gap` yards behind, gaining `gain` per lap, is
  strictly ahead; nil if it never gets there at that rate."
  [gap gain]
  (when (pos? gain) (inc (quot gap gain))))

(defn- rival
  "One country seen from ours. :gap is theirs minus ours (positive: they're
  ahead); :gain is how much we gain on them per lap (negative: they gain)."
  [ours theirs series-of]
  (let [gap (- (:total-laps theirs) (:total-laps ours))
        gain (- (:active ours) (:active theirs))]
    (merge (select-keys theirs [:code :name :rank :tied :total-laps :active :eliminated-after])
           {:gap gap
            :gain gain
            :laps-to-pass (cond
                            (pos? gap) (laps-to-pass gap gain)          ; we pass them
                            (neg? gap) (laps-to-pass (- gap) (- gain))  ; they pass us
                            :else nil)
            :series (series-of (:code theirs))})))

(defn- rest-margin
  "Average rest per lap over the last 3 timed laps: what's left of the hour."
  [history]
  (let [l3 (take-last 3 (keep :seconds history))]
    (when (seq l3)
      (Math/round (double (/ (reduce + (map #(- model/lap-seconds %) l3)) (count l3)))))))

(defn team
  "The team page's data for country `code`, or nil if there's no such
  country. `n-rivals` countries ahead and behind are in the chase."
  ([derived code] (team derived code 3))
  ([{:keys [countries runners settled-lap current-lap]} code n-rivals]
   (when-let [ours (get countries code)]
     (let [all (vals runners)
           by-country (group-by :country all)
           rs (by-country code)
           laps (range 1 (inc settled-lap))
           totals (fn [c] (mapv #(totals-at (by-country c) %) laps))
           our-totals (totals code)
           series-of (fn [c] (mapv (fn [n [t _] [o _]] [n (- t o)]) laps (totals c) our-totals))
           others (remove #(= code (:code %)) (sort-by :position (vals countries)))
           seats (seat-places all)
           still-going (remove :eliminated-after (vals countries))]
       {:country ours
        ;; sharing the place (equal team score and farthest runner), from the same snapshot
        :tied-with (->> others (filter #(= (:rank %) (:rank ours))) (mapv #(select-keys % [:code :name])))
        :settled-lap settled-lap
        :current-lap current-lap
        :runners (->> rs
                      (sort-by (juxt (comp - :laps) :rank :id))
                      (mapv (fn [r]
                              (let [timed (keep :seconds (:history r))
                                    [place of] (seats (:id r))]
                                (-> (select-keys r [:id :name :rank :laps :status :reason :started :history])
                                    (assoc :last-seconds (last timed)
                                           :avg-seconds (when (seq timed)
                                                          (Math/round (double (/ (reduce + timed) (count timed)))))
                                           :margin-seconds (rest-margin (:history r))
                                           :seat-place place
                                           :seat-runners of))))))
        :over-time {:runners-in (mapv (fn [n] [n (count (filter #(>= (:started %) n) rs))])
                                      (range 1 (inc current-lap)))
                    :team-score (mapv (fn [n [t _]] [n t]) laps our-totals)
                    :place (mapv (fn [n] [n ((places-at by-country n) code)]) laps)}
        :chase {:ahead (->> others (filter #(> (:total-laps %) (:total-laps ours)))
                            reverse (take n-rivals) (mapv #(rival ours % series-of)))
                :level (->> others (filter #(= (:total-laps %) (:total-laps ours))) (mapv :code))
                :behind (->> others (filter #(< (:total-laps %) (:total-laps ours)))
                             (take n-rivals) (mapv #(rival ours % series-of)))
                :field-active (when (seq still-going)
                                (/ (Math/round (* 10.0 (/ (reduce + (map :active still-going)) (count still-going))))
                                   10.0))}}))))
