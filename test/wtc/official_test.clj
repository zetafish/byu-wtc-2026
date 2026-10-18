(ns wtc.official-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [jsonista.core :as json]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.source.official :as official])
  (:import (java.time Instant)))

(def start (Instant/parse "2026-10-17T12:00:00Z"))

;; The official feed on 17 Oct at yard 3 for DZA, MUS (excluded), PRT and
;; TWN, plus the Netherlands from the site's own replay (/data/replay.json),
;; which has stop codes.
(def snap (official/parse (official/load-roster "roster/wtc-2026.edn")
                          (json/read-value (slurp (io/resource "wtc/fixtures/official-results.json")))))

(defn- laps-of [id] (filter #(= id (:runner-id %)) (:laps snap)))

(deftest parse-feed
  (testing "validates against the model"
    (is (zero? (:rejected (model/sanitize snap)))))
  (testing "nations get the roster's codes and names; excluded nations are left out"
    (is (= #{"ALG" "NED" "POR" "TPE"} (set (map :code (:countries snap)))))
    (is (= "Netherlands" (:name (first (filter #(= "NED" (:code %)) (:countries snap)))))))
  (testing "reportedThrough is the country's :reported-through"
    (is (= {"ALG" 3 "NED" 2 "POR" 2 "TPE" 2}
           (into {} (map (juxt :code :reported-through)) (:countries snap)))))
  (testing "every runner is matched to a roster id, also in other scripts"
    (is (every? #(re-matches #"[A-Z]{3}-\d\d" %) (map :id (:runners snap))))
    (is (= "ALG-01" (:id (first (filter #(= "Madjid Bentalha" (:name %)) (:runners snap)))))))
  (testing "laps are finished with their time, nil when none was recorded"
    (is (= [nil 2943 2856] (map :seconds (laps-of "ALG-01"))))
    (is (every? #(= :finished (:status %)) (laps-of "ALG-01"))))
  (testing "a stop is a DNF on the lap after the last"
    (let [ls (laps-of "NED-01")]
      (is (= 43 (count ls)))
      (is (= {:runner-id "NED-01" :lap 43 :status :dnf} (last ls)))))
  (testing "a runner still in has no DNF"
    (is (not-any? #(= :dnf (:status %)) (laps-of "NED-06"))))
  (testing "DNS is a DNF on lap 1"
    (is (= [{:runner-id "POR-02" :lap 1 :status :dnf}] (laps-of "POR-02")))))

(deftest derive-from-feed
  (let [d (race/derive-state (model/sanitize snap)
                             {:start start :now (.plusSeconds start (* 100 3600)) :grace-s 900})]
    (is (= {:status :out :reason :dnf} (select-keys (get-in d [:runners "NED-01"]) [:status :reason])))
    (is (= 42 (get-in d [:runners "NED-01" :laps])))))

(deftest rejects-unknown-shape
  (is (thrown? clojure.lang.ExceptionInfo (official/parse {} {"teams" []}))))
