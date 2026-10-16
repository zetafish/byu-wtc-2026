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
    (testing "b fails lap 3, so after lap 3 only a is left and BEL is out"
      (is (= 3 (get-in d [:countries "BEL" :eliminated-after])))
      (is (= [:out :team 3 3] ((juxt :status :reason :laps :started) (get-in d [:runners "a"])))))
    (testing "the team rule wins over a missing result for later laps"
      (let [later (race/derive-state
                   (dataset [["a" "BEL"] ["b" "BEL"]] {"a" (finished 3) "b" (concat (finished 2) [[3 :dnf nil]])})
                   {:start start :now (at-hours 6.5)})]
        (is (= :team (get-in later [:runners "a" :reason])))))
    (is (nil? (get-in d [:countries "USA" :eliminated-after])))
    (is (= 2 (get-in d [:countries "USA" :active])))
    (is (= {"USA" 1 "BEL" 2} (update-vals (:countries d) :rank)))))

(deftest suspect-lap-time
  (let [d (race/derive-state (dataset [["a" "NED"]] {"a" [[1 :finished 9000]]})
                             {:start start :now (at-hours 0.9)})]
    (is (= {:lap 1 :status :finished :seconds nil :suspect true}
           (first (get-in d [:runners "a" :history]))))))
