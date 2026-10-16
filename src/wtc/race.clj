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

(defn- clean [{:keys [seconds] :as lap}]
  (if (and seconds (> seconds model/lap-seconds))
    (assoc lap :seconds nil :suspect true)
    lap))

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

(defn- team-eliminated-after
  "First settled lap after which fewer than 2 of the country's runners were
  still in, or nil."
  [runners settled]
  (first (filter (fn [lap] (< (count (filter #(>= (:laps %) lap) runners)) 2))
                 (range 1 (inc settled)))))

(defn- started [{:keys [status reason laps]} current]
  (cond
    (= status :running) (max laps current)
    (= reason :team) laps
    :else (inc laps)))

(defn derive-state
  [{:keys [countries runners laps]} {:keys [start now grace-s] :or {grace-s 900}}]
  (let [current (current-lap start now)
        settled (min (settled-lap start now grace-s) (max 0 (dec current)))
        laps-by-runner (reduce-kv (fn [m [id l] o] (assoc-in m [id l] o)) {} laps)
        base (update-vals runners #(runner-state % (get laps-by-runner (:id %) {}) settled))
        elim (update-vals (group-by :country (vals base))
                          #(team-eliminated-after % settled))
        runners (update-vals base
                             (fn [r]
                               (let [e (elim (:country r))
                                     r (if (and e (>= (:laps r) e) (not= :dnf (:reason r)))
                                         (assoc r :status :out :reason :team)
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
                                          :eliminated-after (elim code)))))
                          (sort-by (juxt (comp - :total-laps) (comp - :active) :code))
                          (map-indexed (fn [i c] (assoc c :rank (inc i)))))
        any-running? (some #(= :running (:status %)) (vals runners))]
    {:start start
     :now now
     :current-lap current
     :settled-lap settled
     :phase (cond (zero? current) :pre
                  (and (pos? settled) (not any-running?)) :finished
                  :else :live)
     :runners runners
     :countries (into {} (map (juxt :code identity)) country-rows)}))
