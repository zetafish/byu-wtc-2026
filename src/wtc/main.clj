(ns wtc.main
  (:gen-class)
  (:require [clojure.edn :as edn]
            [clojure.tools.logging :as log]
            [org.httpkit.server :as http-kit]
            [wtc.http :as http]
            [wtc.roster :as roster]
            [wtc.ingest :as ingest]
            [wtc.model :as model]
            [wtc.source :as source]
            [wtc.source.mock]
            [wtc.source.official]
            [wtc.source.sheet])
  (:import (java.time Instant)
           (java.util.concurrent ExecutorService)))

(defn- env
  ([k] (env k nil))
  ([k default] (let [v (System/getenv k)] (if (seq v) v default))))

(defn config
  "Everything comes from env vars. SOURCES is an EDN vector of source
  configs, see `wtc.source`."
  []
  {:port (parse-long (env "PORT" "8080"))
   :race-start (Instant/parse (env "RACE_START" "2026-10-17T12:00:00Z"))
   :grace-s (parse-long (env "GRACE_SECONDS" "900"))
   :poll-seconds (parse-long (env "POLL_SECONDS" "60"))
   :fetch {:timeout-ms (parse-long (env "FETCH_TIMEOUT_MS" "20000"))
           :attempts 3
           :backoff-ms 2000}
   ;; set: a demo race that stands at this moment until the site moves it
   :clock {:start (some-> (env "CLOCK_START") Instant/parse)}
   :sources (edn/read-string (env "SOURCES" "[{:id :mock :type :mock :priority 0}]"))})

(defn start [cfg]
  (let [sources (mapv #(assoc % :impl (source/create %)) (:sources cfg))
        ;; the state lives in memory only: start from what the sources saved
        datasets (into {} (for [{:keys [id impl]} sources
                                :let [snap (source/saved impl)]
                                :when snap]
                            (do (log/infof "%s: starting from its saved snapshot" id)
                                [id (dissoc (model/sanitize snap) :rejected)])))
        state (atom (cond-> {:datasets datasets
                             :mock-data (boolean (some #(= :mock (:type %)) (:sources cfg)))}
                      (get-in cfg [:clock :start]) (assoc :sim {:at (get-in cfg [:clock :start])})))
        sys {:config cfg
             :clock (ingest/make-clock state)
             :roster-info (roster/runner-info)
             :sources sources
             :state state}
        _ (when (:sim @state) (swap! state assoc-in [:sim :end-lap] (ingest/sim-end-lap sys)))
        control-sim! (fn [cmd]
                       (swap! state update :sim ingest/control-sim cmd cfg)
                       ;; synchronous, so the response already shows the new lap
                       (ingest/tick! sys))
        _ (ingest/refresh! sys)
        scheduler (ingest/start-scheduler sys)
        stop-http (http-kit/run-server (http/app state {:control-sim! control-sim!
                                                         :derived-at #(ingest/derived-at sys %)}) {:port (:port cfg)})]
    (log/infof "listening on %d, sources %s" (:port cfg) (mapv :id (:sources sys)))
    (assoc sys :scheduler scheduler :stop-http stop-http)))

(defn stop [{:keys [^ExecutorService scheduler stop-http]}]
  (when stop-http (stop-http :timeout 1000))
  (when scheduler (.shutdownNow scheduler)))

(defn -main [& _]
  (let [sys (start (config))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop sys)))
    @(promise)))
