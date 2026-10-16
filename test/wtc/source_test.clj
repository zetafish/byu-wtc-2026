(ns wtc.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [wtc.ingest :as ingest]
            [wtc.metrics :as metrics]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.source :as source]
            [wtc.source.mock]
            [wtc.store :as store])
  (:import (java.time Instant)))

(def start (Instant/parse "2026-10-17T12:00:00Z"))

(defn- fetch [cfg hours]
  (source/fetch (source/create (merge {:id :mock :type :mock} cfg))
                {:start start :now (.plusSeconds start (* hours 3600))}))

(deftest mock-source
  (testing "deterministic per seed and validates against the model"
    (let [snap (fetch {} 30)]
      (is (= snap (fetch {} 30)))
      (is (zero? (:rejected (model/sanitize snap))))
      (is (= 300 (count (:runners snap))))))
  (testing "reveals more as time passes"
    (is (< (count (:laps (fetch {} 5))) (count (:laps (fetch {} 10))))))
  (testing "with clean data, the derived state matches the mock's own team rule"
    (let [d (race/derive-state (model/sanitize (fetch {:missing-rate 0 :silent-rate 0} 300))
                               {:start start :now (.plusSeconds start (* 300 3600))})]
      (is (= :finished (:phase d)))
      (is (every? #(#{:dnf :team} (:reason %)) (vals (:runners d)))))))

(deftest merge-priority
  (let [low (model/sanitize {:runners [{:id "a" :name "A" :country "NED"}]
                             :laps [{:runner-id "a" :lap 1 :status :finished :seconds 3000}]})
        high (model/sanitize {:runners [{:id "a" :name "A" :country "NED"}]
                              :laps [{:runner-id "a" :lap 1 :status :finished :seconds 2990}]})]
    (is (= 2990 (get-in (model/merge-datasets [low high]) [:laps ["a" 1] :seconds])))))

(deftest sanitize-drops-bad-rows
  (let [d (model/sanitize {:runners [{:id "a" :name "A" :country "NED"} {:id "" :name "?"}]
                           :laps [{:runner-id "a" :lap 1 :status :finished}
                                  {:runner-id "zz" :lap 1 :status :finished}
                                  {:runner-id "a" :lap 0 :status :weird}]})]
    (is (= 3 (:rejected d)))
    (is (= #{["a" 1]} (set (keys (:laps d)))))))

(defn- system [source-cfg]
  {:config {:race-start start :grace-s 900
            :fetch {:timeout-ms 500 :attempts 2 :backoff-ms 1}}
   :store (store/memory-store)
   :clock (constantly (.plusSeconds start (* 10 3600)))
   :sources [(assoc source-cfg :impl (source/create source-cfg))]
   :state (atom {})})

(deftest ingest-keeps-last-good-data
  (let [sys (system {:id :mock :type :mock})
        ok (ingest/tick! sys)
        failing (assoc-in sys [:sources 0 :impl] (reify source/Source (fetch [_ _] (throw (ex-info "boom" {})))))
        after (ingest/tick! failing)]
    (is (= (:runners ok) (:runners after)))
    (is (false? (get-in @(:state sys) [:fetches :mock :ok])))
    (is (= 2 (count (:fetches @(:db (:store sys))))))))

(deftest ingest-times-out
  (let [sys (system {:id :slow :type :mock :latency-ms 2000})]
    (is (= "timed out after 500 ms" (:error (ingest/ingest-source! sys (first (:sources sys))))))))

(deftest query-shape
  (let [sys (system {:id :mock :type :mock})
        d (ingest/tick! sys)]
    (doseq [m (keys metrics/registry)]
      (let [r (metrics/query d m {:by "country"})]
        (is (= "success" (:status r)) m)
        (is (every? #(and (map? (:metric %)) (vector? (:values %))) (get-in r [:data :result])) m)))
    (is (= [1 300] (first (get-in (metrics/query d "runners_started" {}) [:data :result 0 :values]))))
    (is (= "error" (:status (metrics/query d "nope" {}))))))
