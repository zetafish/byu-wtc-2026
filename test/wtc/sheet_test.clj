(ns wtc.sheet-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [wtc.model :as model]
            [wtc.race :as race]
            [wtc.source.sheet :as sheet])
  (:import (java.time Instant)))

(def start (Instant/parse "2026-10-17T12:00:00Z"))

;; The published Lite2 tab at yard 3 (17 Oct, ~14:50Z). Its 2281 yards
;; matched the official site's feed exactly.
(def snap (sheet/parse (sheet/load-roster "roster/wtc-2026.edn")
                       (slurp (io/resource "wtc/fixtures/sheet-lite2.csv"))))

(defn- laps-of [id] (count (filter #(= id (:runner-id %)) (:laps snap))))

(deftest parse-lite2
  (testing "validates against the model"
    (is (zero? (:rejected (model/sanitize snap)))))
  (testing "every yard becomes a finished lap without a time"
    (is (= 2281 (count (:laps snap))))
    (is (every? #(and (= :finished (:status %)) (nil? (:seconds %))) (:laps snap))))
  (testing "ids, names and country names come from the roster"
    (is (= {:id "NED-01" :name "Kanter Eefting" :country "NED" :bib "1"}
           (first (filter #(= "NED-01" (:id %)) (:runners snap)))))
    (is (= "Netherlands" (:name (first (filter #(= "NED" (:code %)) (:countries snap)))))))
  (testing "yards per seat"
    (is (= [2 2 2 2 3 3 3 3 2 3 3 3 3 2 3] (map #(laps-of (format "NED-%02d" %)) (range 1 16))))
    (is (= 2 (laps-of "BEL-13"))))
  (testing "withdrawn countries (# is 0) and seats without a runner are skipped"
    (is (not-any? #{"MRI" "SGP"} (map :code (:countries snap))))
    (is (not-any? #{"ECU-14" "ECU-15"} (map :id (:runners snap))))
    (is (some #{"ECU-13"} (map :id (:runners snap))))))

(deftest derive-from-sheet
  (let [now (.plusSeconds start (+ (* 3 3600) 1800))
        d (race/derive-state (model/sanitize snap) {:start start :now now :grace-s 900})
        status #(get-in d [:runners % :status])]
    (is (= :running (status "NED-05")))
    (testing "a runner behind on a settled lap is out"
      (is (= :out (status "NED-01")))
      (is (= :out (status "ECU-13"))))))

(deftest rejects-unknown-layout
  (is (thrown? clojure.lang.ExceptionInfo
               (sheet/parse {} ",TM,Team\n1,NED,Netherlands\n"))))

(deftest rejects-stale-copies
  (let [accept! #'sheet/accept!
        best (atom 0)
        snap-of (fn [n] {:laps (repeat n {})})]
    (is (= 3 (count (:laps (accept! best (snap-of 3))))))
    (testing "an older copy, with fewer yards, is rejected"
      (is (thrown-with-msg? clojure.lang.ExceptionInfo #"stale copy \(2 yards, had 3\)"
                            (accept! best (snap-of 2)))))
    (testing "the same or a newer copy is accepted"
      (is (accept! best (snap-of 3)))
      (is (= 5 (count (:laps (accept! best (snap-of 5)))))))))
