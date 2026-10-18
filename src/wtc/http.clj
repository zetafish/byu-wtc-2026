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

(defn- runner-row
  "A runner without history, plus the times of their last and fastest timed laps,
  and the sum and rounded mean of all their timed laps."
  [r]
  (let [timed (keep :seconds (:history r))]
    (-> r
        (assoc :last-seconds (last timed)
               :fastest-seconds (when (seq timed) (apply min timed))
               :avg-seconds (when (seq timed) (Math/round (double (/ (reduce + timed) (count timed)))))
               :total-seconds (when (seq timed) (reduce + timed)))
        (dissoc :history))))

(defn- sim-command
  "Validated demo command from a request body, or nil."
  [body]
  (let [{:keys [action lap by] :as cmd} (try (j/read-value body j/keyword-keys-object-mapper)
                                             (catch Exception _ nil))]
    (when (case action
            "jump" (and (int? lap) (<= 1 lap 500))
            "step" (and (int? by) (<= -24 by 24))
            false)
      cmd)))

(defn- handlers [state {:keys [control-sim!]}]
  (let [derived #(:derived @state)]
    {:health (fn [_] (json 200 {:ok (some? (derived)) :fetches (:fetches @state)} "no-store"))
     :race (fn [_]
             (let [d (derived)]
               (json (-> (select-keys d [:start :now :current-lap :settled-lap :final-lap :race-laps :phase])
                         (assoc :runners (count (:runners d))
                                :running (count (filter #(= :running (:status %)) (vals (:runners d))))
                                :fetches (:fetches @state))
                         (cond-> (or (:sim @state) (:mock-data @state))
                           (assoc :simulation {:mock-data (:mock-data @state)
                                               :controls (some? (:sim @state))
                                               :end-lap (get-in @state [:sim :end-lap])}))))))
     :sim (fn [{:keys [body]}]
            (if-not (and control-sim! (:sim @state))
              (json 404 {:error "not a demo race (CLOCK_START unset)"} "no-store")
              (if-let [cmd (sim-command body)]
                (do (control-sim! cmd)
                    (json 200 {:ok true :now (:now (derived))} "no-store"))
                (json 400 {:error "expected {action: jump, lap: 1-500} or {action: step, by: -24..24}"} "no-store"))))
     :countries (fn [_] (json (sort-by :position (vals (:countries (derived))))))
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

(defn app
  ([state] (app state {}))
  ([state opts]
   (let [h (handlers state opts)]
     (ring/ring-handler
      (ring/router
       [["/api/health" {:get (:health h)}]
        ["/api/race" {:get (:race h)}]
        ["/api/sim" {:post (:sim h)}]
        ["/api/countries" {:get (:countries h)}]
        ["/api/runners" {:get (:runners h)}]
        ["/api/runners/:id" {:get (:runner h)}]
        ["/api/metrics" {:get (:metrics h)}]
        ["/api/query" {:get (:query h)}]]
       {:data {:middleware [wrap-params]}})
      (ring/create-default-handler)
      ;; a demo race moves whenever someone steps it, so no caching at all
      {:middleware [(fn [handler]
                      (fn [req]
                        (cond-> (handler req)
                          (:sim @state) (assoc-in [:headers "Cache-Control"] "no-store"))))]}))))
