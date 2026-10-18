(ns wtc.model
  "The data model every source adapter produces. Adapters return a snapshot:
  the full current state of what that source knows, not a delta.

    {:countries [{:code \"NED\" :name \"Netherlands\" :reported-through 7}]
     :runners   [{:id \"NED-01\" :name \"…\" :country \"NED\" :bib \"1\"}]
     :laps      [{:runner-id \"NED-01\" :lap 1 :status :finished :seconds 2950}
                 {:runner-id \"NED-01\" :lap 7 :status :dnf}]}

  Lap n runs from start + (n-1)h to start + n h. `:seconds` is the lap time
  and may be missing. A lap that's absent hasn't been reported yet.
  `:reported-through` (optional) is the last lap the country has fully
  reported: a runner without a result up to there is out, and no runner is
  out for a missing result after it, however late.
  Runner ids must be stable and identical across sources, because sources
  are merged per [runner-id lap]."
  (:require [malli.core :as m]))

(def lap-seconds 3600)

(def Country
  [:map
   [:code [:string {:min 1}]]
   [:name :string]
   [:reported-through {:optional true} [:maybe [:int {:min 0}]]]])

(def Runner
  [:map
   [:id [:string {:min 1}]]
   [:name :string]
   [:country [:string {:min 1}]]
   [:bib {:optional true} [:maybe :string]]])

(def Lap
  [:map
   [:runner-id [:string {:min 1}]]
   [:lap [:int {:min 1 :max 1000}]]
   [:status [:enum :finished :dnf]]
   [:seconds {:optional true} [:maybe [:int {:min 0}]]]])

(defn- index [schema ks key-fn rows]
  (let [valid? (m/validator schema)
        ok (filter valid? rows)]
    [(into {} (map (fn [r] [(key-fn r) (select-keys r ks)])) ok)
     (- (count rows) (count ok))]))

(defn sanitize
  "Snapshot -> dataset {:countries {code c} :runners {id r} :laps {[id lap] l}}.
  Invalid rows, and laps of unknown runners, are dropped (counted in
  :rejected) rather than failing the whole snapshot."
  [{:keys [countries runners laps]}]
  (let [[runners bad-r] (index Runner [:id :name :country :bib] :id runners)
        [countries bad-c] (index Country [:code :name :reported-through] :code countries)
        [laps bad-l] (index Lap [:runner-id :lap :status :seconds]
                            (juxt :runner-id :lap) laps)
        known (into {} (filter (fn [[[id] _]] (contains? runners id))) laps)
        implied (into {} (map (fn [c] [c {:code c :name c}]))
                      (map :country (vals runners)))]
    {:countries (merge implied countries)
     :runners runners
     :laps known
     :rejected (+ bad-r bad-c bad-l (- (count laps) (count known)))}))

(defn merge-datasets
  "Merge datasets ordered from lowest to highest priority. Higher priority
  wins per runner field and per [runner-id lap]."
  [datasets]
  {:countries (apply merge-with merge (map :countries datasets))
   :runners (apply merge-with merge (map :runners datasets))
   :laps (apply merge (map :laps datasets))})
