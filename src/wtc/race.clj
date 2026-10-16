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
        base (update-vals runners #(runner-state % (get laps-by-runner (:id %) {}) settled))
        result (update-vals (group-by :country (vals base)) #(team-result % settled))
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
                                          :eliminated-after (:after (result code))
                                          :winner (when-let [id (:winner (result code))]
                                                    (select-keys (runners id) [:id :name :laps]))))))
                          (sort-by (juxt (comp - :total-laps) (comp - :active) :code))
                          (map-indexed (fn [i c] (assoc c :rank (inc i)))))
        any-running? (some #(= :running (:status %)) (vals runners))
        phase (cond (zero? current) :pre
                    (and (pos? settled) (not any-running?)) :finished
                    :else :live)
        ;; once it's over the clock keeps ticking, but the race doesn't
        final (when (= phase :finished) (reduce max 1 (map :started (vals runners))))]
    {:start start
     :now now
     :current-lap (if final (min current final) current)
     :settled-lap (if final (min settled final) settled)
     :final-lap final
     :phase phase
     :runners runners
     :countries (into {} (map (juxt :code identity)) country-rows)}))
