(ns wtc.source.sheet
  "The official leaderboard: the published Google Sheet behind the official
  site's \"Leaderboard\" link, read as CSV from its `Lite2` tab.

  One row per country: Rank, TM, Team, Flag, # (runners), Yards, Avg, then
  the yards of seats 1-15. The seats are the roster's ranks, so a runner's id
  is <code>-<seat> as in the roster, and the names come from the roster.

  Lite2, not the Leaderboard tab: on race day the Leaderboard tab lagged
  behind, its seat-4 column was always `00:00:00`, and its surname lookup
  showed `#N/A` for runners who were running. Lite2 matched the official
  site's feed to the yard (2281 yards at yard 3).

  The sheet only has completed yards per runner: no lap times (Big's never
  required them) and no DNFs (those are cell colours, which the CSV drops).
  So a runner with n yards gets finished laps 1..n without :seconds, and
  `wtc.race` treats a runner without a result for a settled lap as out.

  A withdrawn country has 0 runners (#) and is skipped, as is a seat without
  a roster runner and without yards.

  Google serves different cached copies of a published tab on different
  requests, so a fetch can return an older copy than the one before (on race
  day: 1774 yards on most requests, 2281 on some). A copy with fewer yards in
  total than the last accepted one is rejected as stale; the ingester retries
  and keeps the last good data. The cost: a correction that lowers the total
  only shows once the total has grown past it again.

  Options (all optional):
    :url     the Lite2 tab as CSV (default `lite2-csv`)
    :roster  \"roster/wtc-2026.edn\"  classpath resource"
  (:require [clojure.data.csv :as csv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.client :as http]
            [wtc.source :as source]))

(def lite2-csv
  (str "https://docs.google.com/spreadsheets/d/e/"
       "2PACX-1vR-vmYpj04JD3879yCoJkPoXz8YqjJw9ykVU70nb830fFkmdeEQE75d8fwI5gVbljKe8a0MeK2Grx7L"
       "/pub?single=true&output=csv&gid=1199062225"))

(def ^:private seats 15)

(defn load-roster
  "[code seat] -> {:id :name :country-name} for the team members."
  [resource]
  (let [res (or (io/resource resource)
                (throw (ex-info (str "sheet: roster resource not found: " resource) {:roster resource})))]
    (into {}
          (for [{:keys [code name runners]} (:countries (edn/read-string (slurp res)))
                {:keys [id rank role] :as runner} runners
                :when (= :team role)]
            [[code rank] {:id id :name (:name runner) :country-name name}]))))

(defn- int-cell [s]
  (let [s (str/trim (or s ""))]
    (when (re-matches #"\d+" s) (parse-long s))))

(defn parse
  "CSV text of the Lite2 tab -> snapshot (see `wtc.model`)."
  [roster text]
  (let [[header & body] (drop-while #(not= "TM" (second %)) (csv/read-csv text))
        col (fn [label] (.indexOf ^java.util.List (vec header) label))
        [code-at team-at count-at seat-at] (map col ["TM" "Team" "#" "1"])
        _ (when (or (some neg? [code-at team-at count-at seat-at])
                    (not= (str seats) (nth header (+ seat-at seats -1) nil)))
            (throw (ex-info "sheet: unexpected Lite2 layout" {:header header})))
        entries (for [row body
                      :when (int-cell (first row))
                      :let [code (str/trim (nth row code-at ""))]
                      :when (pos? (or (int-cell (nth row count-at "")) 0))
                      seat (range 1 (inc seats))
                      :let [r (get roster [code seat])
                            yards (or (int-cell (nth row (+ seat-at seat -1) "")) 0)]
                      :when (or r (pos? yards))]
                  {:country {:code code :name (or (:country-name r) (str/trim (nth row team-at "")))}
                   :runner {:id (or (:id r) (format "%s-%02d" code seat))
                            :name (or (:name r) (str code " seat " seat))
                            :country code
                            :bib (str seat)}
                   :yards yards})]
    {:countries (distinct (map :country entries))
     :runners (map :runner entries)
     :laps (for [{:keys [runner yards]} entries
                 lap (range 1 (inc yards))]
             {:runner-id (:id runner) :lap lap :status :finished})}))

(defn- accept!
  "The snapshot, unless it has fewer laps than the last accepted one."
  [best snap]
  (let [n (count (:laps snap))]
    (when (< n @best)
      (throw (ex-info (format "sheet: stale copy (%d yards, had %d)" n @best) {:yards n :best @best})))
    (reset! best n)
    snap))

(defrecord SheetSource [opts roster best]
  source/Source
  (fetch [_ _]
    (let [{:keys [status body error]}
          @(http/get (:url opts) {:timeout 15000 :as :text
                                  :headers {"User-Agent" "bigs-live (unofficial WTC 2026 results site)"}})]
      (cond
        error (throw (ex-info (str "sheet: " (ex-message error)) {} error))
        (not= 200 status) (throw (ex-info (str "sheet: HTTP " status) {:status status}))
        :else (accept! best (parse roster body))))))

(def defaults {:url lite2-csv :roster "roster/wtc-2026.edn"})

(defmethod source/create :sheet [cfg]
  (let [opts (merge defaults cfg)]
    (->SheetSource opts (load-roster (:roster opts)) (atom 0))))
