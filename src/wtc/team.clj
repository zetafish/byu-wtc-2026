(ns wtc.team
  "Everything the team page shows for one country, from the derived race
  state (see `wtc.race/derive-state`). Pure.

  The chase: each lap, a country gains exactly as many yards as it has
  runners still in. So the gap between two countries changes every lap by
  the difference in their runners still in, and the laps until one passes
  the other follow from that, as long as nobody drops."
  (:require [wtc.model :as model]
            [wtc.race :as race]))

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

(defn- pace
  "Laps a country will keep adding per lap: its runners still in, but 0 once
  it's out or down to its last runner (that lap is its last, by the team
  rule), so it can't catch anyone over many laps."
  [{:keys [active eliminated-after]}]
  (if (or eliminated-after (< active 2)) 0 active))

(defn- rival
  "One country seen from ours. :gap is theirs minus ours (positive: they're
  ahead); :gain is how much we gain on them per lap (negative: they gain)."
  [ours theirs]
  (let [gap (- (:total-laps theirs) (:total-laps ours))
        gain (- (pace ours) (pace theirs))]
    (merge (select-keys theirs [:code :name :rank :position :tied :total-laps :active :eliminated-after])
           {:gap gap
            :gain gain
            :laps-to-pass (cond
                            (pos? gap) (laps-to-pass gap gain)          ; we pass them
                            (neg? gap) (laps-to-pass (- gap) (- gain))  ; they pass us
                            (not (zero? gain)) 1                        ; level: the first lap decides
                            :else nil)})))

(defn- pick-rivals
  "The `n` nearest of `rivals` (nearest first), plus every one further away
  with an overtake at the current runner counts, however far off, so a fast
  mover isn't missed."
  [rivals n]
  (vec (keep-indexed (fn [i r] (when (or (< i n) (:laps-to-pass r)) r)) rivals)))

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
           ;; each country up to the last lap it reported, not the race's settled
           ;; lap (the slowest country's): else the chart lags the gaps in the table
           reported (fn [c] (or (race/country-settled c settled-lap current-lap) 0))
           upto (reported ours)
           laps (range 1 (inc upto))
           our-totals (mapv #(totals-at rs %) laps)
           series-of (fn [c]
                       (vec (for [n (range 1 (inc (min upto (reported (get countries c)))))
                                  :let [[t _] (totals-at (by-country c) n)
                                        [o _] (our-totals (dec n))]]
                              [n (- t o)])))
           ;; runners who finished lap n, up to the last lap the country reported
           finishers-of (fn [c] (let [crs (by-country c)]
                                  (mapv (fn [n] [n (count (filter #(>= (:laps %) n) crs))])
                                        (range 1 (inc (reported (get countries c)))))))
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
                                (-> (select-keys r [:id :name :rank :laps :status :reason :stop :started :history])
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
        :chase (let [with-series #(mapv (fn [r] (assoc r :series (series-of (:code r))
                                                         :finishers (finishers-of (:code r)))) %)
                     side (fn [pred order]
                            (->> others (filter #(pred (:total-laps %) (:total-laps ours))) order
                                 (mapv #(rival ours %))))]
                 {:ahead (with-series (pick-rivals (side > reverse) n-rivals))
                  :level (with-series (side = identity))   ; all of them
                  :behind (with-series (pick-rivals (side < identity) n-rivals))
                  :finishers (finishers-of code)
                  :field-active (when (seq still-going)
                                  (/ (Math/round (* 10.0 (/ (reduce + (map :active still-going)) (count still-going))))
                                     10.0))})}))))

(defn overtakes
  "Every pass coming within `horizon` laps of the last bell, across the field, if nobody drops:
  [{:passer code :passed code :lap n …}], soonest first. Each pair is
  compared at the last lap both have reported, so a country still entering
  results doesn't look behind (an out country's score is final, so it
  compares at any lap); :lap is the lap after which the passer is
  strictly ahead on team score. A country that is out or down to its last
  runner passes nobody, but can be passed."
  ([derived] (overtakes derived 6))
  ([{:keys [countries runners settled-lap current-lap]} horizon]
   (let [by-country (group-by :country (vals runners))
         ;; an out country's score is frozen: any lap compares with it
         settled (update-vals countries #(if (:eliminated-after %)
                                           Long/MAX_VALUE
                                           (or (race/country-settled % settled-lap current-lap) 0)))
         cs (sort-by :position (vals countries))]
     (->> (for [a cs
                :when (pos? (pace a))
                b cs
                :when (not= a b)
                :let [n (min (settled (:code a)) (settled (:code b)))
                      ka (totals-at (by-country (:code a)) n)
                      kb (totals-at (by-country (:code b)) n)]
                ;; behind on the official order: score, then farthest runner
                :when (neg? (compare ka kb))
                :let [gap (- (first kb) (first ka))
                      gain (- (pace a) (pace b))
                      k (if (zero? gap) (when (pos? gain) 1) (laps-to-pass gap gain))]
                :when (and k (<= (+ n k) (+ (max 0 (dec current-lap)) horizon)))]
            {:passer (:code a) :passed (:code b) :lap (+ n k) :gap gap :gain gain})
          (sort-by (juxt :lap #(get-in countries [(:passer %) :position])))
          vec))))
