(ns wtc.source.official
  "The official site's results feed (wtc2026.bigsbackyardultra.com): the JSON
  its own pages poll every 60 s. It's read from the organisers' master sheet,
  checked and consolidated, so it's fresher than the published sheet and has
  what that lacks: full names, lap times and stop reasons.

    {:nations [{:code \"NLD\" :name \"Netherlands\" :reportedThrough 7 …}]
     :runners [{:id \"NLD-kanter-eefting\" :nation \"NLD\" :name \"…\"
                :dns false :laps [2950 3012 nil] :stop nil|\"RTC\"|\"OVER\"|\"INC\"}]
     :config  {:excluded {\"MUS\" \"why\"} :hiddenRunners [id …]}}

  It's an internal feed, not a promised API: the shape may change. Run the
  sheet source next to it at a lower priority as a fallback.

  Mapping to the model:
  - Nations use ISO codes (NLD); ours are the roster's (NED). Matched on the
    country name, else the code.
  - Runners are matched to roster ids by name within their country (the
    same words, or one name's words containing the other's). An unmatched
    runner keeps their feed id under our country code.
  - laps[i] is lap i+1, finished, with that many seconds (nil: no time
    recorded). A stop, or DNS, is a :dnf on the lap after the last.
  - reportedThrough, the last yard a nation has finished entering, is the
    country's :reported-through.
  - Excluded nations and hidden runners are left out.

  Polls send the last ETag, so an unchanged feed costs a 304.

  Options (all optional):
    :url     (default `results-url`)
    :roster  \"roster/wtc-2026.edn\"  classpath resource"
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [jsonista.core :as json]
            [org.httpkit.client :as http]
            [wtc.source :as source])
  (:import (java.text Normalizer Normalizer$Form)))

(def results-url "https://wtc2026.bigsbackyardultra.com/api/results")

(defn- words
  "The words of a name, any script, without accents or case."
  [s]
  (-> (Normalizer/normalize (str s) Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (->> (re-seq #"\p{L}+") set)))

(defn load-roster
  "{:code-by-name {words code} :countries {code name} :runners {code [{:id :words}]}}
  for the team members."
  [resource]
  (let [res (or (io/resource resource)
                (throw (ex-info (str "official: roster resource not found: " resource) {:roster resource})))
        cs (:countries (edn/read-string (slurp res)))]
    {:code-by-name (into {} (map (juxt (comp words :name) :code)) cs)
     :countries (into {} (map (juxt :code :name)) cs)
     :runners (into {} (for [{:keys [code runners]} cs]
                         [code (vec (for [r runners :when (= :team (:role r))]
                                      {:id (:id r) :words (words (:name r))}))]))}))

(defn- country-code [roster {:keys [code name]}]
  (or (get-in roster [:code-by-name (words name)])
      (when (contains? (:countries roster) code) code)
      code))

(defn- runner-id
  "The roster id of the only runner of `code` whose name has the same words,
  else the only one whose words contain the other's (or the other way round)."
  [roster code name]
  (let [ws (words name)
        cands (get-in roster [:runners code])
        only (fn [pred] (let [hits (filter pred cands)] (when (= 1 (count hits)) (:id (first hits)))))]
    (when (seq ws)
      (or (only #(= ws (:words %)))
          (only #(and (seq (:words %))
                      (or (every? (:words %) ws) (every? ws (:words %)))))))))

(defn parse
  "The feed, as parsed JSON (string keys) -> snapshot (see `wtc.model`)."
  [roster feed]
  (let [nations (get feed "nations")
        _ (when-not (and (sequential? nations) (sequential? (get feed "runners")))
            (throw (ex-info "official: unexpected feed shape" {:keys (keys feed)})))
        excluded (set (keys (get-in feed ["config" "excluded"])))
        hidden (set (get-in feed ["config" "hiddenRunners"]))
        ours (into {} (for [n nations]
                        [(get n "code") (country-code roster {:code (get n "code") :name (get n "name")})]))
        runners (for [r (get feed "runners")
                      :let [nation (get r "nation")
                            code (get ours nation nation)]
                      :when (not (or (excluded nation) (hidden (get r "id"))))]
                  {:id (or (runner-id roster code (get r "name"))
                           (str code (subs (get r "id") (min (count nation) (count (get r "id"))))))
                   :name (get r "name")
                   :country code
                   :laps (get r "laps")
                   :out? (boolean (or (get r "stop") (get r "dns")))})]
    {:countries (for [n nations :let [code (get ours (get n "code"))]
                      :when (not (excluded (get n "code")))]
                  (cond-> {:code code :name (get-in roster [:countries code] (get n "name"))}
                    (int? (get n "reportedThrough")) (assoc :reported-through (get n "reportedThrough"))))
     :runners (map #(select-keys % [:id :name :country]) runners)
     :laps (for [{:keys [id laps out?]} runners
                 lap (concat (map-indexed (fn [i s]
                                            {:lap (inc i) :status :finished
                                             :seconds (when (number? s) (Math/round (double s)))})
                                          laps)
                             (when out? [{:lap (inc (count laps)) :status :dnf}]))]
             (assoc lap :runner-id id))}))

(defrecord OfficialSource [opts roster cache]
  source/Source
  (fetch [_ _]
    (let [{:keys [etag snap]} @cache
          {:keys [status body headers error]}
          @(http/get (:url opts) {:timeout 15000 :as :text
                                  :headers (cond-> {"User-Agent" "bigs-live (unofficial WTC 2026 results site)"}
                                             etag (assoc "If-None-Match" etag))})]
      (cond
        error (throw (ex-info (str "official: " (ex-message error)) {} error))
        (and (= 304 status) snap) snap
        (not= 200 status) (throw (ex-info (str "official: HTTP " status) {:status status}))
        :else (let [snap (parse roster (json/read-value body))]
                (reset! cache {:etag (:etag headers) :snap snap})
                snap)))))

(def defaults {:url results-url :roster "roster/wtc-2026.edn"})

(defmethod source/create :official [cfg]
  (let [opts (merge defaults cfg)]
    (->OfficialSource opts (load-roster (:roster opts)) (atom nil))))
