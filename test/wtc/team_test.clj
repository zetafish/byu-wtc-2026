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
           (:series ((by-code (get-in (team/team derived "BBB") [:chase :ahead])) "AAA")))))
  (testing "runners who finished each lap, for this team and its rivals"
    (let [{:keys [finishers ahead]} (:chase (team/team derived "BBB"))]
      (is (= [[1 3] [2 3] [3 2] [4 2] [5 2] [6 2]] finishers))
      (is (= [[1 3] [2 3] [3 3] [4 3] [5 3] [6 3]] (:finishers ((by-code ahead) "AAA"))))))
  (testing "a gap line stops at the last lap both countries have reported"
    (let [d (assoc-in derived [:countries "AAA" :reported-through] 4)]
      (is (= [[1 0] [2 0] [3 1] [4 2]]
             (:series ((by-code (get-in (team/team d "BBB") [:chase :ahead])) "AAA"))))
      (is (= [[1 0] [2 0] [3 -1] [4 -2]]
             (:series ((by-code (get-in (team/team d "AAA") [:chase :behind])) "BBB")))))))

(deftest out-or-last-runner
  (testing "a country down to its last runner adds no more laps: it can't catch anyone"
    (let [d (assoc-in derived [:countries "DDD" :active] 1)
          bbb ((by-code (get-in (team/team d "DDD") [:chase :ahead])) "BBB")]
      (is (= {:gap 2 :gain -2 :laps-to-pass nil} (select-keys bbb [:gap :gain :laps-to-pass])))))
  (testing "a country that's out stands still: AAA (3 in) passes CCC, 6 ahead, in 3 laps"
    (let [d (assoc-in derived [:countries "CCC" :eliminated-after] 6)
          ccc ((by-code (get-in (team/team d "AAA") [:chase :ahead])) "CCC")]
      (is (= {:gap 6 :gain 3 :laps-to-pass 3} (select-keys ccc [:gap :gain :laps-to-pass]))))))

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

(deftest fast-mover-further-away
  ;; OUR 6+6+5+5 = 22 with 2 in. Behind, by team score: L1 6+6+5+4 = 21, L2 20, L3 19
  ;; (2 in each, never closer), L4 6+6+6 = 18 with 3 in: 4th behind, but gains 1 a lap
  ;; on a gap of 4, so it passes in 5 laps. L5 6+6+5 = 17 with 2 in never does.
  (let [team-of (fn [code laps] (for [[i l] (map-indexed vector laps)] [(str code i) code l]))
        out (fn [n] (concat (finished n) [[(inc n) :dnf nil]]))
        teams (concat (team-of "OUR" [6 6 5 5]) (team-of "LA" [6 6 5 4]) (team-of "LB" [6 6 5 3])
                      (team-of "LC" [6 6 4 3]) (team-of "LD" [6 6 6]) (team-of "LE" [6 6 5]))
        d (race/derive-state
           (dataset (map (fn [[id c]] [id c]) teams)
                    (into {} (map (fn [[id _ l]] [id (if (= 6 l) (finished 6) (out l))])) teams))
           {:start start :now (at-hours 6.5)})
        behind (get-in (team/team d "OUR") [:chase :behind])]
    (is (= ["LA" "LB" "LC" "LD"] (map :code behind)))
    (is (= {:gap -4 :gain -1 :laps-to-pass 5} (select-keys (last behind) [:gap :gain :laps-to-pass])))
    (is (every? :series behind) "each picked rival has its history")))

(deftest overtakes
  ;; settled lap 6: AAA 18 (3 in), BBB 14 (2), CCC 24 (4), DDD 12 (2), EEE 14 (2)
  (let [os (team/overtakes derived 20)
        pass (into {} (map (juxt (juxt :passer :passed) :lap)) os)]
    (testing "AAA, 3 in, gains 1 a lap on the 2-runner countries behind it: never"
      (is (nil? (pass ["AAA" "CCC"]))))
    (testing "CCC is ahead of everyone already, nobody is behind it to pass"
      (is (not-any? #(= "CCC" (:passed %)) (filter #(#{"BBB" "DDD" "EEE"} (:passer %)) os))))
    (testing "level countries with as many in never pass each other"
      (is (nil? (pass ["BBB" "EEE"])))
      (is (nil? (pass ["EEE" "BBB"]))))
    (testing "soonest first"
      (is (= (sort (map :lap os)) (map :lap os)))))
  (testing "a gaining country passes; once out it passes nobody"
    (let [d (race/derive-state
             (dataset (concat (for [x ["x1" "x2"]] [x "XXX"]) (for [x ["y1" "y2" "y3"]] [x "YYY"]))
                      {"x1" (finished 6) "x2" (finished 6)
                       "y1" (finished 6) "y2" (finished 6) "y3" (concat (finished 2) [[3 :dnf nil]])})
             {:start start :now (at-hours 6.5)})]
      ;; XXX 12 (2 in), YYY 14 (2 in): level pace, nobody passes
      (is (= [] (team/overtakes d 20)))
      (let [d (assoc-in d [:countries "XXX" :active] 3)]
        ;; XXX 2 behind, gaining 1 a lap: strictly ahead after 3 laps
        (is (= [{:passer "XXX" :passed "YYY" :lap 9 :gap 2 :gain 1}] (team/overtakes d 20))))
      (let [d (-> d (assoc-in [:countries "XXX" :active] 3) (assoc-in [:countries "XXX" :eliminated-after] 6))]
        (is (= [] (team/overtakes d 20)))))))

(deftest one-drop
  (let [d (-> (race/derive-state
               (dataset (concat (for [x ["x1" "x2"]] [x "XXX"]) (for [x ["y1" "y2" "y3"]] [x "YYY"]))
                        {"x1" (finished 6) "x2" (finished 6)
                         "y1" (finished 6) "y2" (finished 6) "y3" (concat (finished 2) [[3 :dnf nil]])})
               {:start start :now (at-hours 6.5)})
              ;; XXX 12 with 3 in, YYY 14 with 2 in: XXX passes after lap 9
              (assoc-in [:countries "XXX" :active] 3))]
    (testing "down to 2, one drop ends YYY's race: XXX passes after the next lap instead"
      (is (= {:ends? true :passes [{:code "XXX" :lap 7}]} (team/one-drop d "YYY" 20))))
    (testing "XXX has nobody behind it: a drop lets nobody pass"
      (is (= {:ends? false :passes []} (team/one-drop d "XXX" 20))))
    (testing "nothing to say once a country is out"
      (is (nil? (team/one-drop (assoc-in d [:countries "YYY" :eliminated-after] 6) "YYY" 20))))))
