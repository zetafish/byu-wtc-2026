(ns wtc.source.mock
  "Deterministic simulated race. The full race is generated from :seed up
  front; a fetch reveals only what has happened by ctx :now.

  Options (all optional):
    :seed 42, :countries 20, :team-size 15
    :missing-rate  0.01  finished laps never registered (a later lap shows up)
    :silent-rate   0.3   DNFs never reported (runner just stops appearing)
    :failure-rate  0.0   fraction of fetches that throw, like an HTTP 503
    :latency-ms    0     added to every fetch
    :delay-minutes 0     how long after the fact results become visible"
  (:require [wtc.model :as model]
            [wtc.source :as source])
  (:import (java.time Instant)
           (java.util Random)))

(def ^:private countries
  [["NED" "Netherlands"] ["BEL" "Belgium"] ["USA" "United States"]
   ["GBR" "Great Britain"] ["GER" "Germany"] ["FRA" "France"]
   ["ESP" "Spain"] ["ITA" "Italy"] ["SWE" "Sweden"] ["NOR" "Norway"]
   ["DEN" "Denmark"] ["FIN" "Finland"] ["POL" "Poland"] ["CZE" "Czechia"]
   ["AUS" "Australia"] ["NZL" "New Zealand"] ["JPN" "Japan"]
   ["CAN" "Canada"] ["MEX" "Mexico"] ["RSA" "South Africa"]])

(def ^:private first-names
  ["Anna" "Bram" "Chris" "Dana" "Eli" "Femke" "Gus" "Hana" "Ivo" "Jade"
   "Koen" "Lena" "Milo" "Noor" "Otto" "Pia" "Quin" "Rosa" "Sam" "Tess"])

(def ^:private last-names
  ["Berg" "Costa" "Dijk" "Evans" "Fox" "Gray" "Hall" "Ito" "Jansen" "Klein"
   "Lund" "Moreau" "Nagy" "Olsen" "Park" "Rossi" "Smit" "Tan" "Vos" "Weber"])

(defn- rng ^Random [& xs] (Random. (long (hash (vec xs)))))

(defn- gaussian [^Random r mean sd] (+ mean (* sd (.nextGaussian r))))

(defn- rand-nth* [^Random r coll] (nth coll (.nextInt r (count coll))))

(defn- capacity
  "How many laps this runner could do on their own."
  [r strength]
  (-> (Math/exp (gaussian r (Math/log 35) 0.5))
      (* strength (if (< (.nextDouble r) 0.05) 1.8 1.0))
      double Math/round (max 1) (min 150)))

(defn- generate-roster [{:keys [seed] :as opts}]
  (vec (for [[ci [code cname]] (map-indexed vector (take (:countries opts) countries))
             :let [strength (+ 0.6 (* 0.8 (.nextDouble (rng seed code))))]
             i (range 1 (inc (:team-size opts)))
             :let [r (rng seed code i)
                   id (format "%s-%02d" code i)]]
         {:id id
          :name (str (rand-nth* r first-names) " " (rand-nth* r last-names))
          :country code
          :country-name cname
          :country-index ci
          :bib (str i)
          :capacity (capacity r strength)
          :pace (gaussian r 3000 150)
          :fatigue (gaussian r 6 2)})))

(defn- apply-team-rule
  "Lap-by-lap: a country continues only while at least 2 of its runners
  finish the lap. Returns runner id -> {:laps n :stop :dnf|:team}."
  [roster]
  (into {}
        (mapcat
         (fn [[_ rs]]
           (loop [lap 1, alive rs, out {}]
             (let [finishers (filter #(>= (:capacity %) lap) alive)
                   failed (remove #(>= (:capacity %) lap) alive)
                   out (into out (map (fn [r] [(:id r) {:laps (dec lap) :stop :dnf}])) failed)]
               (cond
                 (empty? finishers) out
                 (< (count finishers) 2)
                 (into out (map (fn [r] [(:id r) {:laps lap :stop :team}])) finishers)
                 :else (recur (inc lap) finishers out))))))
        (group-by :country roster)))

(defn- lap-time [{:keys [id pace fatigue]} seed lap]
  (let [r (rng seed id lap)]
    (-> (+ pace (* fatigue lap) (gaussian r 0 120))
        (max 2400.0) (min 3540.0) double Math/round)))

(defn- events
  "Every lap result of the whole race with the instant it becomes known."
  [roster outcome {:keys [seed missing-rate silent-rate]} ^Instant start]
  (let [at (fn [lap secs] (.plusSeconds start (+ (* (dec lap) model/lap-seconds) secs)))]
    (vec
     (for [{:keys [id] :as runner} roster
           :let [{:keys [laps stop]} (outcome id)]
           lap (range 1 (+ laps (if (= stop :dnf) 2 1)))
           :let [r (rng seed id lap :edge)]
           e (cond
               (<= lap laps)
               (let [secs (lap-time runner seed lap)]
                 (when-not (and (< lap laps) (< (.nextDouble r) missing-rate))
                   [{:at (at lap secs)
                     :lap {:runner-id id :lap lap :status :finished :seconds secs}}]))

               (>= (.nextDouble r) silent-rate)
               [{:at (at lap model/lap-seconds)
                 :lap {:runner-id id :lap lap :status :dnf}}])]
       e))))

(defrecord MockSource [opts roster outcome]
  source/Source
  (fetch [_ {:keys [^Instant start ^Instant now]}]
    (when (pos? (:latency-ms opts)) (Thread/sleep (long (:latency-ms opts))))
    (when (< (rand) (:failure-rate opts))
      (throw (ex-info "mock: 503 Service Unavailable" {:status 503})))
    (let [cutoff (.minusSeconds now (* 60 (:delay-minutes opts)))]
      {:countries (distinct (map #(hash-map :code (:country %) :name (:country-name %)) roster))
       :runners (map #(select-keys % [:id :name :country :bib]) roster)
       :laps (into [] (comp (filter #(not (.isAfter ^Instant (:at %) cutoff))) (map :lap))
                   (events roster outcome opts start))})))

(def defaults
  {:seed 42 :countries 20 :team-size 15
   :missing-rate 0.01 :silent-rate 0.3
   :failure-rate 0.0 :latency-ms 0 :delay-minutes 0})

(defmethod source/create :mock [cfg]
  (let [opts (merge defaults cfg)
        roster (generate-roster opts)]
    (->MockSource opts roster (apply-team-rule roster))))
