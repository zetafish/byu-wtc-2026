(ns wtc.race
  "Pure derivation of race state from a merged dataset and the current time.
  Everything about who is in or out is computed here, on read, so late or
  corrected data fixes itself on the next derive."
  (:require [wtc.model :as model])
  (:import (java.time Instant)))

(defn- secs-since ^long [^Instant start ^Instant now]
  (- (.getEpochSecond now) (.getEpochSecond start)))

(defn current-lap
  "Lap in progress at `now`; 0 before the start."
  [start now]
  (let [s (secs-since start now)]
    (if (neg? s) 0 (inc (quot s model/lap-seconds)))))

(defn settled-lap
  "Last lap whose deadline is at least `grace-s` in the past. A runner
  without a result for a settled lap is treated as out."
  [start now grace-s]
  (max 0 (Math/floorDiv (- (secs-since start now) (long grace-s))
                        (long model/lap-seconds))))

(defn- country-settled
  "The settled lap for one country: up to what it says it has fully
  reported, when its source says so, else the lap the grace period settled."
  [{:keys [reported-through]} settled current]
  (if reported-through
    (max 0 (min reported-through (dec current)))
    settled))

(def too-fast-seconds
  "A finished lap under 30 min is implausible: flagged, but still credited."
  1800)

(defn- clean [{:keys [seconds status] :as lap}]
  (cond
    (and seconds (> seconds model/lap-seconds)) (assoc lap :seconds nil :suspect true)
    (and seconds (= :finished status) (< seconds too-fast-seconds)) (assoc lap :too-fast true)
    :else lap))

(defn- runner-state
  "Laps are credited up to the last finished lap. Gaps below it are missed
  registrations: they count, marked :inferred."
  [runner laps settled]
  (let [finished (keep (fn [[l o]] (when (= :finished (:status o)) l)) laps)
        credited (if (seq finished) (apply max finished) 0)
        dnf (->> laps
                 (keep (fn [[l o]] (when (and (= :dnf (:status o)) (> l credited)) l)))
                 sort first)
        history (cond-> (mapv (fn [l]
                                (let [o (get laps l)]
                                  (if (= :finished (:status o))
                                    (clean (dissoc o :runner-id))
                                    {:lap l :status :finished :inferred true})))
                              (range 1 (inc credited)))
                  dnf (conj (dissoc (get laps dnf) :runner-id)))]
    (merge runner
           {:laps credited :history history}
           (cond
             dnf {:status :out :reason :dnf}
             (< credited settled) {:status :out :reason :missing}
             :else {:status :running}))))

(defn- team-result
  "A country continues while at least 2 of its runners finish each lap. The
  first settled lap with fewer finishers ends it: a single finisher is the
  country's winner, with none everyone left is a DNF and there is no winner.
  -> {:after last-lap-completed :winner id-or-nil}, or nil while the country
  is still going. :after is the deciding lap when someone won it, and the lap
  before it when nobody finished it."
  [runners settled]
  (some (fn [lap]
          (let [finishers (filter #(>= (:laps %) lap) runners)]
            (case (count finishers)
              0 {:after (dec lap) :winner nil}
              1 {:after lap :winner (:id (first finishers))}
              nil)))
        (range 1 (inc settled))))

(defn- rank-countries
  "Official ranking: team score, then the farthest single runner (\"the race
  went farther\"), then runners still in, then code. :position is the
  order; :rank is the place, shared (:tied) only when team score and
  farthest runner are both equal."
  [rows]
  (let [place-key (juxt :total-laps :farthest)
        sorted (sort-by (juxt (comp - :total-laps) (comp - :farthest) (comp - :active) :code) rows)
        first-pos (reduce (fn [m [i c]] (update m (place-key c) #(or % (inc i))))
                          {} (map-indexed vector sorted))
        counts (frequencies (map place-key sorted))]
    (map-indexed (fn [i c]
                   (assoc c
                          :position (inc i)
                          :rank (first-pos (place-key c))
                          :tied (> (counts (place-key c)) 1)))
                 sorted)))

(defn- started [{:keys [status laps]} current]
  (case status
    :running (max laps current)
    :finished laps
    (inc laps)))

(defn derive-state
  [{:keys [countries runners laps]} {:keys [start now grace-s] :or {grace-s 900}}]
  (let [current (current-lap start now)
        settled (min (settled-lap start now grace-s) (max 0 (dec current)))
        laps-by-runner (reduce-kv (fn [m [id l] o] (assoc-in m [id l] o)) {} laps)
        settled-of (fn [code] (country-settled (countries code) settled current))
        base (update-vals runners #(runner-state % (get laps-by-runner (:id %) {}) (settled-of (:country %))))
        result (into {} (map (fn [[code rs]] [code (team-result rs (settled-of code))]))
                     (group-by :country (vals base)))
        runners (update-vals base
                             (fn [r]
                               (let [r (if (= (:id r) (:winner (result (:country r))))
                                         (assoc r :status :finished :reason :winner)
                                         r)]
                                 (assoc r :started (started r current)))))
        by-country (group-by :country (vals runners))
        country-rows (->> countries
                          vals
                          (map (fn [{:keys [code] :as c}]
                                 (let [rs (by-country code)]
                                   (assoc c
                                          :runners (count rs)
                                          :active (count (filter #(= :running (:status %)) rs))
                                          :total-laps (reduce + 0 (map :laps rs))
                                          :farthest (reduce max 0 (map :laps rs))
                                          :eliminated-after (:after (result code))
                                          :winner (when-let [id (:winner (result code))]
                                                    (select-keys (runners id) [:id :name :laps]))))))
                          rank-countries)
        any-running? (some #(= :running (:status %)) (vals runners))
        phase (cond (zero? current) :pre
                    (and (pos? settled) (not any-running?)) :finished
                    :else :live)
        ;; once it's over the clock keeps ticking, but the race doesn't
        final (when (= phase :finished) (reduce max 1 (map :started (vals runners))))
        ;; the race's length: the last lap anyone completed. With a winner
        ;; that's the final lap; when the last runners all DNF it's one less.
        race-laps (when final (reduce max 0 (map :laps (vals runners))))]
    {:start start
     :now now
     :current-lap (if final (min current final) current)
     :settled-lap (if final (min settled final) settled)
     :final-lap final
     :race-laps race-laps
     :phase phase
     :runners runners
     :countries (into {} (map (juxt :code identity)) country-rows)}))
