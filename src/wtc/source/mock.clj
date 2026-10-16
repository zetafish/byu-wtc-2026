(ns wtc.source.mock
  "Deterministic simulated race of the real roster. Every team member
  (rank 1-15) of `resources/roster/wtc-2026.edn` starts; how far they get is
  drawn around their best backyard result. The full race is generated from
  :seed up front; a fetch reveals only what has happened by ctx :now.

  Options (all optional):
    :seed 42
    :roster        \"roster/wtc-2026.edn\"  classpath resource
    :countries     nil   only these country codes, e.g. [\"NED\" \"BEL\"]
    :missing-rate  0.01  finished laps never registered (a later lap shows up)
    :silent-rate   0.3   DNFs never reported (runner just stops appearing)
    :failure-rate  0.0   fraction of fetches that throw, like an HTTP 503
    :latency-ms    0     added to every fetch
    :delay-minutes 0     how long after the fact results become visible"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [wtc.model :as model]
            [wtc.source :as source])
  (:import (java.time Instant)
           (java.util Random)))

(defn- rng ^Random [& xs] (Random. (long (hash (vec xs)))))

(defn- gaussian [^Random r mean sd] (+ mean (* sd (.nextGaussian r))))

(defn- capacity
  "How many laps this runner can do on their own: their best result give or
  take ~25%, so some set a PB and some have a bad day."
  [^Random r best-yards]
  (let [base (if (and best-yards (pos? best-yards)) best-yards 20)]
    (-> (* base (Math/exp (gaussian r 0 0.25)))
        double Math/round (max 1) (min 200))))

(defn- load-roster [{:keys [seed roster countries]}]
  (let [res (or (io/resource roster)
                (throw (ex-info (str "mock: roster resource not found: " roster) {:roster roster})))
        wanted (some-> countries set)]
    (vec (for [{:keys [code name runners]} (:countries (edn/read-string (slurp res)))
               :when (or (nil? wanted) (wanted code))
               {:keys [id rank role best-yards] :as runner} runners
               :when (= :team role)
               :let [r (rng seed id)]]
           {:id id
            :name (:name runner)
            :country code
            :country-name name
            :bib (str rank)
            :capacity (capacity r best-yards)
            :pace (gaussian r 3000 150)
            :fatigue (gaussian r 6 2)}))))

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

(defrecord MockSource [opts roster outcome cache]
  source/Source
  (fetch [_ {:keys [^Instant start ^Instant now]}]
    (when (pos? (:latency-ms opts)) (Thread/sleep (long (:latency-ms opts))))
    (when (< (rand) (:failure-rate opts))
      (throw (ex-info "mock: 503 Service Unavailable" {:status 503})))
    (let [cutoff (.minusSeconds now (* 60 (:delay-minutes opts)))]
      {:countries (distinct (map #(hash-map :code (:country %) :name (:country-name %)) roster))
       :runners (map #(select-keys % [:id :name :country :bib]) roster)
       :laps (into [] (comp (filter #(not (.isAfter ^Instant (:at %) cutoff))) (map :lap))
                   (let [c @cache]
                     (if (= start (:start c))
                       (:events c)
                       (:events (reset! cache {:start start
                                               :events (events roster outcome opts start)})))))})))

(def defaults
  {:seed 42 :roster "roster/wtc-2026.edn" :countries nil
   :missing-rate 0.01 :silent-rate 0.3
   :failure-rate 0.0 :latency-ms 0 :delay-minutes 0})

(defmethod source/create :mock [cfg]
  (let [opts (merge defaults cfg)
        roster (load-roster opts)]
    (->MockSource opts roster (apply-team-rule roster) (atom nil))))
