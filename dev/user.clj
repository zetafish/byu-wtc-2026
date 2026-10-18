(ns user
  (:require [wtc.main :as main])
  (:import (java.time Instant)))

(defonce system nil)

(defn start
  "Dev defaults: config from env vars (mock source unless SOURCES is set),
  a demo race standing at lap 31.
  Pass overrides, e.g. (start {:clock {:start (Instant/parse \"2026-10-19T12:00:00Z\")}})."
  ([] (start {}))
  ([overrides]
   (let [cfg (merge-with #(if (map? %1) (merge %1 %2) %2)
                         (main/config)
                         {:clock {:start (.plusSeconds (Instant/parse "2026-10-17T12:00:00Z") (* 30 3600))}}
                         overrides)]
     (alter-var-root #'system (constantly (main/start cfg))))))

(defn stop []
  (when system (main/stop system))
  (alter-var-root #'system (constantly nil)))

(defn restart [] (stop) (start))

(defn derived [] (:derived @(:state system)))
