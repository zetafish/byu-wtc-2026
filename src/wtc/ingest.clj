(ns wtc.ingest
  "Polls every source on a fixed delay. Each source is isolated: a timeout,
  an HTTP error or bad data only means its last good dataset stays in use."
  (:require [clojure.tools.logging :as log]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.roster :as roster]
            [wtc.source :as source]
            [wtc.store :as store])
  (:import (java.time Instant)
           (java.util.concurrent ExecutionException Executors ScheduledExecutorService TimeUnit)))

(defn- with-timeout [ms f]
  (let [fut (future (f))
        r (try (deref fut ms ::timeout)
               (catch ExecutionException e (throw (or (.getCause e) e))))]
    (when (= r ::timeout)
      (future-cancel fut)
      (throw (ex-info (str "timed out after " ms " ms") {:timeout-ms ms})))
    r))

(defn- fetch-with-retry [src ctx {:keys [timeout-ms attempts backoff-ms]}]
  (loop [attempt 1]
    (let [r (try {:ok (with-timeout timeout-ms #(source/fetch (:impl src) ctx))}
                 (catch Exception e
                   (if (< attempt attempts)
                     (do (log/warnf "%s attempt %d failed: %s" (:id src) attempt (ex-message e))
                         (Thread/sleep (long (* backoff-ms (bit-shift-left 1 (dec attempt)))))
                         ::retry)
                     (throw e))))]
      (if (= r ::retry) (recur (inc attempt)) (:ok r)))))

(defn ingest-source!
  "Fetch one source and update the live dataset. Persisting is retried on
  every tick until the stored copy matches the live one."
  [{:keys [state store clock config]} src]
  (let [started (clock)
        t0 (System/nanoTime)
        entry (try
                (let [dataset (model/sanitize
                               (fetch-with-retry src {:start (:race-start config) :now started}
                                                 (:fetch config)))]
                  (swap! state assoc-in [:datasets (:id src)] (dissoc dataset :rejected))
                  {:ok true :laps (count (:laps dataset)) :rejected (:rejected dataset)})
                (catch Exception e
                  (log/errorf "%s fetch failed: %s" (:id src) (ex-message e))
                  {:ok false :error (ex-message e)}))
        entry (assoc entry
                     :source (:id src)
                     :started-at started
                     :duration-ms (quot (- (System/nanoTime) t0) 1000000))]
    (swap! state assoc-in [:fetches (:id src)] entry)
    (try
      (let [live (get-in @state [:datasets (:id src)])]
        (when (and live (not= live (get-in @state [:persisted (:id src)])))
          (store/save-dataset! store (:id src) live)
          (swap! state assoc-in [:persisted (:id src)] live)))
      (store/record-fetch! store entry)
      (catch Exception e
        (log/errorf e "%s store write failed, retrying next tick" (:id src))))
    entry))

(defn refresh!
  "Recompute the derived race state. Sources merge in :priority order."
  [{:keys [state sources clock config roster-info]}]
  (let [datasets (keep #(get-in @state [:datasets (:id %)]) (sort-by :priority sources))
        derived (race/derive-state (roster/enrich (model/merge-datasets datasets) roster-info)
                                   {:start (:race-start config) :now (clock)
                                    :grace-s (:grace-s config)})]
    (swap! state assoc :derived derived)
    derived))

(defn tick! [sys]
  (doseq [src (:sources sys)] (ingest-source! sys src))
  (refresh! sys))

(defn start-scheduler ^ScheduledExecutorService [sys]
  (doto (Executors/newSingleThreadScheduledExecutor)
    (.scheduleWithFixedDelay
     #(try (tick! sys) (catch Throwable t (log/error t "tick failed")))
     0 (long (get-in sys [:config :poll-seconds])) TimeUnit/SECONDS)))

(defn lap-instant
  "A moment in lap n with lap n-1 already settled; lap 1 is the race start."
  ^Instant [^Instant race-start grace-s n]
  (.plusSeconds race-start (if (<= n 1) 0 (+ (* (dec n) model/lap-seconds) grace-s 60))))

(defn control-sim
  "Move a demo race, which stands still at (:at sim) until it's told to:
  {:action \"jump\" :lap n} to lap n, {:action \"step\" :by k} k laps on
  (or back). Clamped to lap 1 .. (:end-lap sim)."
  [{:keys [at end-lap] :as sim} {:keys [action lap by]} {:keys [race-start grace-s]}]
  (let [target (case action
                 "jump" lap
                 "step" (+ (race/current-lap race-start at) by))]
    (assoc sim :at (lap-instant race-start grace-s (min (or end-lap 500) (max 1 target))))))

(defn sim-end-lap
  "The lap a simulated race ends on: the sources fetched as if a month had
  passed. nil if that fails."
  [{:keys [sources config roster-info]}]
  (try
    (let [start (:race-start config)
          now (.plusSeconds ^Instant start (* 30 24 3600))
          datasets (map #(model/sanitize (source/fetch (:impl %) {:start start :now now}))
                        (sort-by :priority sources))]
      (:final-lap (race/derive-state (roster/enrich (model/merge-datasets datasets) roster-info)
                                     {:start start :now now :grace-s (:grace-s config)})))
    (catch Exception e
      (log/warnf "can't work out the simulated race's last lap: %s" (ex-message e)))))

(defn make-clock
  "Real time, or the fixed moment of a demo race, kept in state under :sim."
  [state]
  (fn [] (or (get-in @state [:sim :at]) (Instant/now))))
