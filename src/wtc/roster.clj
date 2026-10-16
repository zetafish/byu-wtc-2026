(ns wtc.roster
  "Static reference data from the official roster (resources/roster/,
  built by scripts/fetch_roster.clj). Merged into runners by id, so any
  source that uses the roster's ids gets it for free."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def default-resource "roster/wtc-2026.edn")

(defn runner-info
  "runner id -> {:best-yards n :rank n}, or {} when the resource is missing."
  ([] (runner-info default-resource))
  ([resource]
   (if-let [res (io/resource resource)]
     (into {}
           (for [{:keys [runners]} (:countries (edn/read-string (slurp res)))
                 {:keys [id best-yards rank]} runners]
             [id {:best-yards best-yards :rank rank}]))
     {})))

(defn enrich
  "Add roster info to the runners of a merged dataset. Source fields win."
  [dataset info]
  (update dataset :runners
          (fn [runners] (update-vals runners #(merge (get info (:id %)) %)))))
