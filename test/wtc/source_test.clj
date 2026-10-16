(ns wtc.source-test
  (:require [clojure.test :refer [deftest is testing]]
            [wtc.ingest :as ingest]
            [wtc.metrics :as metrics]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.source :as source]
            [wtc.source.mock]
            [wtc.roster :as roster]
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
      (testing "runs the team members of the real roster"
        (is (= 1033 (count (:runners snap))))
        (is (some #(= "NED-kanter-eefting" (:id %)) (:runners snap))))))
  (testing ":countries limits the roster"
    (is (= #{"NED" "BEL"} (set (map :country (:runners (fetch {:countries ["NED" "BEL"]} 1)))))))
  (testing "reveals more as time passes"
    (is (< (count (:laps (fetch {} 5))) (count (:laps (fetch {} 10))))))
  (testing "with clean data, the derived state matches the mock's own team rule"
    (let [d (race/derive-state (model/sanitize (fetch {:missing-rate 0 :silent-rate 0} 300))
                               {:start start :now (.plusSeconds start (* 300 3600))})]
      (is (= :finished (:phase d)))
      (is (every? #(#{:dnf :winner} (:reason %)) (vals (:runners d)))))))

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
    (is (= [1 1033] (first (get-in (metrics/query d "runners_started" {}) [:data :result 0 :values]))))
    (is (= "error" (:status (metrics/query d "nope" {}))))
    (testing "gap to leader: the leader is at 0, everyone else at or below"
      (let [gaps (get-in (metrics/query d "team_score_gap" {}) [:data :result])
            last-ys (map (comp second peek :values) gaps)]
        (is (= 0 (apply max last-ys)))
        (is (every? #(<= % 0) (mapcat #(map second (:values %)) gaps)))))
    (testing "a country's gap doesn't depend on which countries are compared"
      (let [gap #(-> (metrics/query d "team_score_gap" {:countries %}) (get-in [:data :result])
                     (->> (filter (fn [s] (= "NED" (get-in s [:metric :country]))))) first :values)]
        (is (= (gap #{"NED"}) (gap #{"NED" "BEL"})))))))

(deftest roster-enrichment
  (let [info (roster/runner-info)
        sys (assoc (system {:id :mock :type :mock :countries ["NED"]}) :roster-info info)
        d (ingest/tick! sys)]
    (is (= 54 (get-in d [:runners "NED-kanter-eefting" :best-yards])))
    (testing "source fields win over roster info"
      (is (= "x" (get-in (roster/enrich {:runners {"a" {:id "a" :bib "x"}}} {"a" {:bib "y" :best-yards 3}})
                         [:runners "a" :bib]))))))

(deftest demo-race-controls
  (let [cfg {:race-start start :grace-s 900}
        lap #(race/current-lap start (:at %))
        sim {:at (.plusSeconds start (* 30 3600)) :end-lap 60}]
    (is (= 31 (lap sim)))
    (testing "jump lands in the lap with the previous one settled"
      (let [j (ingest/control-sim sim {:action "jump" :lap 49} cfg)]
        (is (= 49 (lap j)))
        (is (= 48 (race/settled-lap start (:at j) 900)))))
    (testing "jumping to lap 1 is the race start"
      (is (= start (:at (ingest/control-sim sim {:action "jump" :lap 1} cfg)))))
    (testing "steps move by laps, within lap 1 .. end-lap"
      (is (= 32 (lap (ingest/control-sim sim {:action "step" :by 1} cfg))))
      (is (= 30 (lap (ingest/control-sim sim {:action "step" :by -1} cfg))))
      (is (= 60 (lap (ingest/control-sim sim {:action "jump" :lap 99} cfg))))
      (is (= 7 (lap (ingest/control-sim sim {:action "step" :by -24} cfg))))
      (is (= 1 (lap (reduce #(ingest/control-sim %1 %2 cfg) sim (repeat 3 {:action "step" :by -24}))))))))

(deftest sim-end-lap
  (testing "the mock race's last lap is known up front"
    (is (pos-int? (ingest/sim-end-lap (assoc (system {:id :mock :type :mock})
                                             :roster-info (roster/runner-info)))))))
