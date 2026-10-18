(ns wtc.team-test
  (:require [clojure.test :refer [deftest is testing]]
            [wtc.race :as race]
            [wtc.race-test :refer [at-hours dataset finished start]]
            [wtc.team :as team]))

;; At lap 6.5, settled lap 6:
;;   AAA 6+6+6 = 18, 3 still in      BBB 6+6+2 = 14, 2 still in
;;   CCC 6+6+6+6 = 24, 4 still in    DDD 6+6+0 = 12, 2 still in
;;   EEE 6+6+2 = 14, 2 still in (level with BBB)
(def derived
  (race/derive-state
   (dataset (concat (for [x ["a1" "a2" "a3"]] [x "AAA"])
                    (for [x ["b1" "b2" "b3"]] [x "BBB"])
                    (for [x ["c1" "c2" "c3" "c4"]] [x "CCC"])
                    (for [x ["d1" "d2" "d3"]] [x "DDD"])
                    (for [x ["e1" "e2" "e3"]] [x "EEE"]))
            {"a1" (finished 6) "a2" (finished 6) "a3" (finished 6)
             "b1" (finished 6) "b2" (finished 6) "b3" (concat (finished 2) [[3 :dnf nil]])
             "c1" (finished 6) "c2" (finished 6) "c3" (finished 6) "c4" (finished 6)
             "d1" (finished 6) "d2" (finished 6) "d3" [[1 :dnf nil]]
             "e1" (finished 6) "e2" (finished 6) "e3" (concat (finished 2) [[3 :dnf nil]])})
   {:start start :now (at-hours 6.5)}))

(defn- by-code [rivals] (into {} (map (juxt :code identity)) rivals))

(deftest chase
  (let [{:keys [ahead level behind]} (:chase (team/team derived "BBB"))]
    (testing "nearest first, ahead and behind"
      (is (= ["AAA" "CCC"] (map :code ahead)))
      (is (= ["DDD"] (map :code behind)))
      (is (= ["EEE"] (map :code level)))
      (is (= {:gap 0 :gain 0 :laps-to-pass nil} (select-keys (first level) [:gap :gain :laps-to-pass]))))
    (testing "AAA is 4 ahead with 3 in against our 2: they gain 1 a lap, we never pass"
      (is (= {:gap 4 :gain -1 :laps-to-pass nil}
             (select-keys ((by-code ahead) "AAA") [:gap :gain :laps-to-pass]))))
    (testing "DDD is 2 behind with as many in: nobody gains"
      (is (= {:gap -2 :gain 0 :laps-to-pass nil}
             (select-keys ((by-code behind) "DDD") [:gap :gain :laps-to-pass])))))
  (let [{:keys [ahead behind]} (:chase (team/team derived "DDD"))]
    (testing "with as many in as BBB, DDD never catches it"
      (is (nil? (:laps-to-pass ((by-code ahead) "BBB"))))))
  (let [{:keys [behind]} (:chase (team/team derived "AAA"))]
    (testing "AAA gains 1 a lap on BBB, which is 4 behind"
      (is (= {:gap -4 :gain 1 :laps-to-pass nil}
             (select-keys ((by-code behind) "BBB") [:gap :gain :laps-to-pass])))))
  (let [{:keys [ahead]} (:chase (team/team derived "AAA"))]
    (testing "CCC is 6 ahead with 4 in against AAA's 3: it pulls away"
      (is (= {:gap 6 :gain -1} (select-keys ((by-code ahead) "CCC") [:gap :gain])))))
  (testing "the gap over the laps, as theirs minus ours"
    (is (= [[1 0] [2 0] [3 1] [4 2] [5 3] [6 4]]
           (:series ((by-code (get-in (team/team derived "BBB") [:chase :ahead])) "AAA"))))))

(deftest catching-up
  ;; JJJ 6+6+4+4+4 = 24 with 2 still in; KKK 6+6+6 = 18 with 3 still in:
  ;; KKK gains 1 a lap on a gap of 6, so it's strictly ahead after 7 laps
  (let [d (race/derive-state
           (dataset (concat (for [x ["j1" "j2" "j3" "j4" "j5"]] [x "JJJ"])
                            (for [x ["k1" "k2" "k3"]] [x "KKK"]))
                    (merge {"j1" (finished 6) "j2" (finished 6)
                            "k1" (finished 6) "k2" (finished 6) "k3" (finished 6)}
                           (into {} (for [x ["j3" "j4" "j5"]] [x (concat (finished 4) [[5 :dnf nil]])]))))
           {:start start :now (at-hours 6.5)})]
    (is (= {:code "JJJ" :gap 6 :gain 1 :laps-to-pass 7}
           (select-keys (get-in (team/team d "KKK") [:chase :ahead 0]) [:code :gap :gain :laps-to-pass])))
    (is (= {:code "KKK" :gap -6 :gain -1 :laps-to-pass 7}
           (select-keys (get-in (team/team d "JJJ") [:chase :behind 0]) [:code :gap :gain :laps-to-pass])))))

(deftest team-page
  ;; seats come from the roster; here the number in the id is the seat
  (let [seated (update derived :runners update-vals #(assoc % :rank (parse-long (subs (:id %) 1))))
        t (team/team seated "BBB")]
    (testing "the countries sharing its place"
      (is (= [{:code "EEE" :name "EEE"}] (:tied-with t))))
    (testing "unknown country"
      (is (nil? (team/team derived "ZZZ"))))
    (testing "runners best first, with their place in their seat"
      (is (= ["b1" "b2" "b3"] (map :id (:runners t))))
      (testing "b1 has 6 laps, level in seat 1 with a1, c1, d1 and e1; b3 is out on 2 laps, 3rd of 5 in seat 3, level with e3"
        (is (= {"b1" [1 5] "b2" [1 5] "b3" [3 5]}
               (into {} (map (juxt :id (juxt :seat-place :seat-runners))) (:runners t))))))
    (testing "the team over time"
      (is (= [[1 3] [2 3] [3 3] [4 2] [5 2] [6 2] [7 2]] (get-in t [:over-time :runners-in])))
      (is (= [[1 3] [2 6] [3 8] [4 10] [5 12] [6 14]] (get-in t [:over-time :team-score])))
      (is (= 3 (get-in t [:over-time :place 5 1])) "after lap 6: behind CCC and AAA, sharing 3rd with EEE"))))
