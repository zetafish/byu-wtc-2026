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
            [wtc.source.sheet]
            [wtc.store :as store]
            [wtc.store.postgres :as pg])
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
   :database-url (env "DATABASE_URL")
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
  (let [store (if-let [url (:database-url cfg)]
                (pg/postgres-store url)
                (do (log/warn "DATABASE_URL not set, using in-memory store")
                    (store/memory-store)))
        sources (mapv #(assoc % :impl (source/create %)) (:sources cfg))
        ;; a source's own saved snapshot is newer than the store's copy
        saved (into {} (for [{:keys [id impl]} sources
                             :let [snap (source/saved impl)]
                             :when snap]
                         (do (log/infof "%s: starting from its saved snapshot" id)
                             [id (dissoc (model/sanitize snap) :rejected)])))
        persisted (store/load-datasets store)
        datasets (merge persisted saved)
        state (atom (cond-> {:datasets datasets :persisted persisted
                             :mock-data (boolean (some #(= :mock (:type %)) (:sources cfg)))}
                      (get-in cfg [:clock :start]) (assoc :sim {:at (get-in cfg [:clock :start])})))
        sys {:config cfg
             :store store
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
        stop-http (http-kit/run-server (http/app state {:control-sim! control-sim!}) {:port (:port cfg)})]
    (log/infof "listening on %d, sources %s" (:port cfg) (mapv :id (:sources sys)))
    (assoc sys :scheduler scheduler :stop-http stop-http)))

(defn stop [{:keys [^ExecutorService scheduler stop-http store]}]
  (when stop-http (stop-http :timeout 1000))
  (when scheduler (.shutdownNow scheduler))
  (when store (store/close! store)))

(defn -main [& _]
  (let [sys (start (config))]
    (.addShutdownHook (Runtime/getRuntime) (Thread. #(stop sys)))
    @(promise)))
