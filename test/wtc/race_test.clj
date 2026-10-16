(ns wtc.race-test
  (:require [clojure.test :refer [deftest is testing]]
            [wtc.model :as model]
            [wtc.race :as race])
  (:import (java.time Instant)))

(def start (Instant/parse "2026-10-17T12:00:00Z"))

(defn at-hours [h] (.plusSeconds start (long (* h 3600))))

(defn dataset [runners laps]
  (model/sanitize
   {:runners (map (fn [[id c]] {:id id :name id :country c}) runners)
    :laps (for [[id ls] laps, [l status secs] ls]
            {:runner-id id :lap l :status status :seconds secs})}))

(defn finished [n] (map (fn [l] [l :finished 3000]) (range 1 (inc n))))

(deftest lap-clock
  (is (= 0 (race/current-lap start (at-hours -1))))
  (is (= 1 (race/current-lap start start)))
  (is (= 3 (race/current-lap start (at-hours 2.5))))
  (testing "a lap settles only after the grace period"
    (is (= 1 (race/settled-lap start (at-hours 2.2) 900)))
    (is (= 2 (race/settled-lap start (at-hours 2.3) 900)))))

(deftest runner-status
  (let [d (race/derive-state
           (dataset [["a" "NED"] ["b" "NED"] ["c" "NED"] ["d" "NED"]]
                    {"a" (finished 3)
                     "b" (concat (finished 2) [[3 :dnf nil]])
                     "c" (finished 1)
                     "d" [[1 :finished 3000] [3 :finished 3100]]})
           {:start start :now (at-hours 3.5)})
        r #(get-in d [:runners %])]
    (is (= 3 (:settled-lap d)))
    (is (= [:running 3 4] ((juxt :status :laps :started) (r "a"))))
    (is (= [:out :dnf 2 3] ((juxt :status :reason :laps :started) (r "b"))))
    (testing "no result for a settled lap means out"
      (is (= [:out :missing 1] ((juxt :status :reason :laps) (r "c")))))
    (testing "a missed registration is inferred from a later lap"
      (is (= 3 (:laps (r "d"))))
      (is (:inferred (second (:history (r "d"))))))))

(deftest team-rule
  (let [d (race/derive-state
           (dataset [["a" "BEL"] ["b" "BEL"] ["c" "BEL"] ["x" "USA"] ["y" "USA"]]
                    {"a" (finished 3) "b" (finished 2) "c" (finished 1)
                     "x" (finished 3) "y" (finished 3)})
           {:start start :now (at-hours 3.5)})]
    (testing "b fails lap 3, so a alone finishes lap 3: BEL is done and a is its winner"
      (is (= 3 (get-in d [:countries "BEL" :eliminated-after])))
      (is (= {:id "a" :name "a" :laps 3} (get-in d [:countries "BEL" :winner])))
      (is (= [:finished :winner 3 3] ((juxt :status :reason :laps :started) (get-in d [:runners "a"])))))
    (testing "the winner stays the winner when later laps have no result"
      (let [later (race/derive-state
                   (dataset [["a" "BEL"] ["b" "BEL"]] {"a" (finished 3) "b" (concat (finished 2) [[3 :dnf nil]])})
                   {:start start :now (at-hours 6.5)})]
        (is (= :winner (get-in later [:runners "a" :reason])))))
    (is (nil? (get-in d [:countries "USA" :eliminated-after])))
    (is (= 2 (get-in d [:countries "USA" :active])))
    (is (= {"USA" 1 "BEL" 2} (update-vals (:countries d) :rank)))))

(deftest suspect-lap-time
  (let [d (race/derive-state (dataset [["a" "NED"]] {"a" [[1 :finished 9000]]})
                             {:start start :now (at-hours 0.9)})]
    (is (= {:lap 1 :status :finished :seconds nil :suspect true}
           (first (get-in d [:runners "a" :history]))))))

(deftest too-fast-lap-time
  (let [d (race/derive-state (dataset [["a" "NED"]] {"a" [[1 :finished 1500] [2 :finished 3000]]})
                             {:start start :now (at-hours 2.5)})]
    (testing "a lap under 30 min is flagged but still counts, with its time"
      (is (= {:lap 1 :status :finished :seconds 1500 :too-fast true}
             (first (get-in d [:runners "a" :history]))))
      (is (= 2 (get-in d [:runners "a" :laps]))))))

(defn- derive-at [runners laps hours]
  (race/derive-state (dataset runners laps) {:start start :now (at-hours hours)}))

(deftest country-endings
  (testing "both finish: the country continues"
    (let [d (derive-at [["a" "NED"] ["b" "NED"]] {"a" (finished 3) "b" (finished 3)} 3.5)]
      (is (nil? (get-in d [:countries "NED" :eliminated-after])))
      (is (= :live (:phase d)))))
  (testing "both start and neither finishes: no winner, both DNF, the country is out after the last lap it completed"
    (let [d (derive-at [["a" "NED"] ["b" "NED"]]
                       {"a" (concat (finished 2) [[3 :dnf nil]]) "b" (concat (finished 2) [[3 :dnf nil]])} 3.5)]
      (is (= 2 (get-in d [:countries "NED" :eliminated-after])))
      (is (nil? (get-in d [:countries "NED" :winner])))
      (is (= #{:dnf} (set (map :reason (vals (:runners d))))))))
  (testing "A finishes, B doesn't start (no result at all): A wins once the lap settles"
    (let [laps {"a" (finished 3) "b" (finished 2)}]
      (is (= :running (get-in (derive-at [["a" "NED"] ["b" "NED"]] laps 2.9) [:runners "a" :status]))
          "while lap 3 is in progress A has finished, but nobody knows yet that B won't")
      (is (= :winner (get-in (derive-at [["a" "NED"] ["b" "NED"]] laps 3.5) [:runners "a" :reason])))))
  (testing "A starts but doesn't finish, B doesn't start: no winner"
    (let [d (derive-at [["a" "NED"] ["b" "NED"]] {"a" (concat (finished 2) [[3 :dnf nil]]) "b" (finished 2)} 3.5)]
      (is (nil? (get-in d [:countries "NED" :winner])))))
  (testing "more than two left and all but one fail the same lap: that one wins"
    (let [d (derive-at [["a" "NED"] ["b" "NED"] ["c" "NED"] ["d" "NED"]]
                       {"a" (finished 3) "b" (finished 2) "c" (finished 2) "d" (finished 2)} 3.5)]
      (is (= "a" (get-in d [:countries "NED" :winner :id])))
      (is (= 3 (get-in d [:countries "NED" :eliminated-after])))))
  (testing "a winner ends the race: it lasted as long as the winner's last lap"
    (let [d (derive-at [["a" "NED"] ["b" "NED"]] {"a" (finished 3) "b" (concat (finished 2) [[3 :dnf nil]])} 4.5)]
      (is (= :finished (:phase d)))
      (is (= [3 3] ((juxt :final-lap :race-laps) d))))))

(deftest last-country-standing
  (let [runners [["a" "BEL"] ["b" "BEL"] ["x" "USA"] ["y" "USA"]]
        ;; USA is done after lap 5; BEL's two keep going and both DNF on lap 101
        laps {"x" (concat (finished 4) [[5 :dnf nil]]) "y" (concat (finished 4) [[5 :dnf nil]])
              "a" (concat (finished 100) [[101 :dnf nil]]) "b" (concat (finished 100) [[101 :dnf nil]])}
        mid (derive-at runners (assoc laps "a" (finished 50) "b" (finished 50)) 50.5)
        end (derive-at runners laps 150)]
    (testing "one country can continue alone while every other country is out"
      (is (= :live (:phase mid)))
      (is (= 2 (get-in mid [:countries "BEL" :active]))))
    (testing "both DNF on 101: the race is over, BEL has no individual winner but ranks first"
      (is (= :finished (:phase end)))
      (is (= 100 (get-in end [:countries "BEL" :eliminated-after])))
      (is (= 4 (get-in end [:countries "USA" :eliminated-after])))
      (is (nil? (get-in end [:countries "BEL" :winner])))
      (is (= 1 (get-in end [:countries "BEL" :rank]))))
    (testing "both DNF on 101: the race lasted 100 laps"
      (is (= 100 (:race-laps end))))
    (testing "after the end the lap counter stops at the last lap anyone started"
      (is (= 101 (:final-lap end)))
      (is (= 101 (:current-lap end)))
      (is (<= (:settled-lap end) 101)))))
