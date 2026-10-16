(ns wtc.roster
  (:require [cheshire.core :as json]
            [clojure.java.io :as io]
            [org.httpkit.client :as h]))

(defn fetch-selection [country]
  (-> (h/request {:url  "https://bigsbackyardultra.com/wp-json/byu-wtt/v1/selection"
                  :query-params {:cycle "2024_2026"
                                 :country country}})
      deref
      :body
      (json/decode true)))

(defn get-through-cache
  ([country] (get-through-cache country country))
  ([key country]
   (let [f (io/file "roster" (str key ".json"))]
     (when-not (.exists f)
       (spit f (json/encode (fetch-selection country)
                            {:pretty true})))
     (json/decode (slurp f) true))))

(defn init []
  (.mkdir (io/file "roster"))
  (->> (:countries (get-through-cache "_" ""))
       (map #(do (println "Fetching" %)
                 (get-through-cache %)))
       (doall)))

;; (init)
