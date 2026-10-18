(ns wtc.metrics-test
  (:require [clojure.test :refer [deftest is testing]]
            [wtc.metrics :as metrics]
            [wtc.team-test :refer [derived]]))

;; `derived` at lap 6.5, settled lap 6 (see wtc.team-test):
;;   AAA 18, CCC 24, BBB 14 and EEE 14 (both farthest 6), DDD 12

(defn- values [metric params]
  (into {} (map (juxt #(get-in % [:metric :country] (get-in % [:metric :reason])) :values))
        (get-in (metrics/query derived metric params) [:data :result])))

(deftest team-rank
  (testing "places per lap by the official ranking; equal score and farthest share"
    (let [v (values "team_rank" {:countries #{"CCC" "AAA" "BBB" "EEE"}})]
      (is (= [6 1] (last (v "CCC"))))
      (is (= [6 2] (last (v "AAA"))))
      (is (= [6 3] (last (v "BBB")) (last (v "EEE"))))
      (testing "on lap 1 everyone has 1 yard per runner: CCC's 4 runners lead"
        (is (= [1 1] (first (v "CCC"))))))))

(deftest countries-running
  (is (= [[1 5] [2 5] [3 5] [4 5] [5 5] [6 5]]
         (-> (metrics/query derived "countries_running" {}) :data :result first :values))))

(deftest attrition
  (testing "an out runner without a stop code counts at the lap after its last"
    (let [v (values "attrition" {})]
      (is (= 2 (second (nth (v "dnf") 2))))     ; b3 and e3 out on lap 3
      (is (= 1 (second (first (v "dnf"))))))))  ; d3 out on lap 1
