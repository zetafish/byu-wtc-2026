(ns wtc.http
  "JSON API. Served from the in-memory derived state, never from the
  database. Responses carry CDN cache headers because the data changes at
  most a few times per hour."
  (:require [clojure.string :as str]
            [jsonista.core :as j]
            [reitit.ring :as ring]
            [ring.middleware.params :refer [wrap-params]]
            [wtc.metrics :as metrics])
  (:import (java.time Instant)))

(def ^:private mapper
  (j/object-mapper {:encoders {Instant (fn [i ^com.fasterxml.jackson.core.JsonGenerator gen]
                                         (.writeString gen (str i)))}}))

(def ^:private cached "public, max-age=15, s-maxage=60, stale-while-revalidate=300")

(defn- json
  ([body] (json 200 body cached))
  ([status body cache-control]
   {:status status
    :headers {"Content-Type" "application/json"
              "Cache-Control" cache-control
              "Access-Control-Allow-Origin" "*"}
    :body (j/write-value-as-string body mapper)}))

(defn- csv-set [s] (when-not (str/blank? s) (set (str/split s #","))))

(defn- runner-row [r] (dissoc r :history))

(defn- handlers [state]
  (let [derived #(:derived @state)]
    {:health (fn [_] (json 200 {:ok (some? (derived)) :fetches (:fetches @state)} "no-store"))
     :race (fn [_]
             (let [d (derived)]
               (json (-> (select-keys d [:start :now :current-lap :settled-lap :phase])
                         (assoc :runners (count (:runners d))
                                :running (count (filter #(= :running (:status %)) (vals (:runners d))))
                                :fetches (:fetches @state))))))
     :countries (fn [_] (json (sort-by :rank (vals (:countries (derived))))))
     :runners (fn [{{:strs [country history]} :query-params}]
                (json (->> (vals (:runners (derived)))
                           (filter #(or (nil? country) (= country (:country %))))
                           (sort-by (juxt (comp - :laps) :id))
                           (map (if (= history "true") identity runner-row)))))
     :runner (fn [{{:keys [id]} :path-params}]
               (if-let [r (get-in (derived) [:runners id])]
                 (json r)
                 (json 404 {:error "not found"} "no-store")))
     :metrics (fn [_] (json (update-vals metrics/registry #(dissoc % :fn))))
     :query (fn [{{:strs [metric by countries runners lap]} :query-params}]
              (let [r (metrics/query (derived) metric
                                     {:by by
                                      :countries (csv-set countries)
                                      :runners (csv-set runners)
                                      :lap (some-> lap parse-long)})]
                (if (= "error" (:status r)) (json 400 r "no-store") (json r))))}))

(defn app [state]
  (let [h (handlers state)]
    (ring/ring-handler
     (ring/router
      [["/api/health" {:get (:health h)}]
       ["/api/race" {:get (:race h)}]
       ["/api/countries" {:get (:countries h)}]
       ["/api/runners" {:get (:runners h)}]
       ["/api/runners/:id" {:get (:runner h)}]
       ["/api/metrics" {:get (:metrics h)}]
       ["/api/query" {:get (:query h)}]]
      {:data {:middleware [wrap-params]}})
     (ring/create-default-handler))))
