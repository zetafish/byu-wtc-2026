(ns wtc.ingest
  "Polls every source on a fixed delay. Each source is isolated: a timeout,
  an HTTP error or bad data only means its last good dataset stays in use."
  (:require [clojure.tools.logging :as log]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.source :as source]
            [wtc.store :as store])
  (:import (java.time Duration Instant)
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
  [{:keys [state sources clock config]}]
  (let [datasets (keep #(get-in @state [:datasets (:id %)]) (sort-by :priority sources))
        derived (race/derive-state (model/merge-datasets datasets)
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

(defn make-clock
  "Real time by default. For demos and dev: start the app clock at `start`
  and/or run it `speed` times faster than real time."
  [{:keys [start speed] :or {speed 1}}]
  (let [boot (Instant/now)
        origin (or start boot)]
    (if (and (nil? start) (= 1 speed))
      #(Instant/now)
      #(.plus ^Instant origin
              (Duration/ofMillis (long (* speed (.toMillis (Duration/between boot (Instant/now))))))))))
