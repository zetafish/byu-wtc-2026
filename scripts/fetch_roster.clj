#!/usr/bin/env bb
;; Builds the official WTC 2026 roster (teams + reserves) as EDN from the JSON
;; endpoint behind https://bigsbackyardultra.com/world-team-championship-2026/
;; Raw responses are cached in roster/ (`_.json` = country list, `<Country>.json`);
;; only countries without a cached file are fetched.
;;
;;   bb scripts/fetch_roster.clj [out-file]            build, fetching missing countries
;;   bb scripts/fetch_roster.clj --refresh [out-file]  re-fetch everything (slow, ~2 min)
(ns fetch-roster
  (:require [babashka.fs :as fs]
            [babashka.http-client :as http]
            [cheshire.core :as json]
            [clojure.pprint :as pp]
            [clojure.string :as str])
  (:import (java.text Normalizer Normalizer$Form)
           (java.time Instant)
           (java.util.concurrent Executors)))

(def endpoint "https://bigsbackyardultra.com/wp-json/byu-wtt/v1/selection")
(def roster-cycle "2024_2026")
(def team-size 15)
(def raw-dir "roster")

(defn- raw-file [country] (str (fs/path raw-dir (str (if (= "" country) "_" country) ".json"))))

(defn- save-raw! [country resp]
  (fs/create-dirs raw-dir)
  (spit (raw-file country) (json/generate-string resp {:pretty true}))
  resp)

(defn- read-raw [country]
  (let [f (raw-file country)]
    (when-not (fs/exists? f) (throw (ex-info (str "missing " f) {})))
    (json/parse-string (slurp f) true)))

(def codes
  {"Algeria" "ALG" "Argentina" "ARG" "Australia" "AUS" "Austria" "AUT"
   "Belgium" "BEL" "Bermuda" "BER" "Bolivia" "BOL" "Brazil" "BRA"
   "Brunei" "BRU" "Bulgaria" "BUL" "Canada" "CAN" "Chile" "CHI"
   "Colombia" "COL" "Costa Rica" "CRC" "Cuba" "CUB" "Czechia" "CZE"
   "Côte d’Ivoire" "CIV" "Denmark" "DEN" "Dominican Republic" "DOM"
   "Ecuador" "ECU" "Estonia" "EST" "Finland" "FIN" "France" "FRA"
   "Germany" "GER" "Greece" "GRE" "Guatemala" "GUA" "Hungary" "HUN"
   "Iceland" "ISL" "India" "IND" "Ireland" "IRL" "Israel" "ISR"
   "Italy" "ITA" "Japan" "JPN" "Kenya" "KEN" "Latvia" "LAT"
   "Lithuania" "LTU" "Malaysia" "MAS" "Malta" "MLT" "Mauritius" "MRI"
   "Mexico" "MEX" "Morocco" "MAR" "Namibia" "NAM" "Nepal" "NEP"
   "Netherlands" "NED" "New Zealand" "NZL" "Norway" "NOR" "Pakistan" "PAK"
   "Paraguay" "PAR" "Philippines" "PHI" "Poland" "POL" "Portugal" "POR"
   "Romania" "ROU" "Singapore" "SGP" "Slovakia" "SVK" "Slovenia" "SLO"
   "South Africa" "RSA" "Spain" "ESP" "Sweden" "SWE" "Switzerland" "SUI"
   "Taipei" "TPE" "Tajikistan" "TJK" "Turkey" "TUR" "Ukraine" "UKR"
   "United Kingdom" "GBR" "United States of America" "USA" "Uruguay" "URU"
   "Venezuela" "VEN" "Zambia" "ZAM" "AIN" "AIN"})

(defn- fetch [country]
  (loop [attempt 1]
    (let [r (try (http/get endpoint {:query-params {"cycle" roster-cycle "country" country}
                                     :headers {"User-Agent" "wtc-live roster import"}
                                     :timeout 30000})
                 (catch Exception e e))]
      (cond
        (and (map? r) (= 200 (:status r))) (json/parse-string (:body r) true)
        (< attempt 3) (do (Thread/sleep 3000) (recur (inc attempt)))
        :else (throw (ex-info (str "fetch failed for " country) {:result r}))))))

(defn- slug [s]
  (-> (Normalizer/normalize s Normalizer$Form/NFKD)
      (str/replace #"\p{M}" "")
      str/lower-case
      (str/replace #"[^\p{L}\p{N}]+" "-")
      (str/replace #"^-|-$" "")))

(defn- int-or-nil [v] (when (int? v) v))

(defn- iso-date [d] ; dd/mm/yyyy
  (when-let [[_ dd mm yyyy] (re-matches #"(\d{2})/(\d{2})/(\d{4})" (str d))]
    (str yyyy "-" mm "-" dd)))

(def ^:private statuses
  {"At Large List" :at-large "Silver Ticket" :silver-ticket "Reserve" :reserve})

(defn- runner [code {:keys [rank status wa name best_yards yards race date]}]
  (cond-> {:id (str code "-" (slug name))
           :name (str/trim name)
           :rank rank
           :role (if (<= rank team-size) :team :reserve)
           :status (statuses status status)
           :best-yards (int-or-nil best_yards)}
    (seq wa) (assoc :wa wa)
    (seq race) (assoc :qualifying (cond-> {:race race :yards (int-or-nil yards)}
                                    (iso-date date) (assoc :date (iso-date date))))))

(defn- get-through-cache
  "The cached response for `country` (\"\" = the country list), fetching and
  caching it when missing or when `refresh?`."
  [refresh? country]
  (if (and (not refresh?) (fs/exists? (raw-file country)))
    (read-raw country)
    (let [resp (save-raw! country (fetch country))]
      (binding [*out* *err*] (println "fetched" (if (= "" country) "country list" country)))
      resp)))

(def parallelism 6) ; the endpoint takes ~3-4 s per country

(defn- load-all
  "country -> rows, fetching uncached countries in parallel."
  [refresh? countries]
  (let [pool (Executors/newFixedThreadPool parallelism)
        futs (mapv (fn [c] [c (.submit pool ^Callable #(:rows (get-through-cache refresh? c)))])
                   countries)]
    (try
      (into {} (map (fn [[c f]] [c (.get ^java.util.concurrent.Future f)])) futs)
      (finally (.shutdownNow pool)))))

(defn -main [& args]
  (let [refresh? (boolean (some #{"--refresh"} args))
        out (first (remove #{"--refresh"} args))
        countries (:countries (get-through-cache refresh? ""))
        unknown (remove codes countries)
        _ (when (seq unknown) (throw (ex-info (str "no code for " (vec unknown)) {})))
        rows-by-country (load-all refresh? countries)
        fetched (vec (for [c countries]
                       (let [rows (rows-by-country c)
                             code (codes c)
                             runners (->> rows
                                          (filter #(seq (str/trim (:name %))))
                                          (sort-by :rank)
                                          (mapv #(runner code %)))
                             ;; The roster sometimes lists one person twice (e.g.
                             ;; with and without an accent); keep the best rank.
                             {kept false dupes true} (group-by (let [seen (volatile! #{})]
                                                                 (fn [{:keys [id]}]
                                                                   (let [dup? (contains? @seen id)]
                                                                     (vswap! seen conj id)
                                                                     dup?)))
                                                               runners)]
                         {:country {:code code :name c :runners (vec kept)}
                          :dupes (vec dupes)})))
        data (mapv :country fetched)
        doc {:event "Big's Backyard Ultra World Team Championship 2026"
             :start "2026-10-17T12:00:00Z"
             :source "https://bigsbackyardultra.com/world-team-championship-2026/"
             :endpoint (str endpoint "?cycle=" roster-cycle)
             ;; newest cached response
             :fetched-at (->> countries (cons "") (map #(fs/last-modified-time (raw-file %)))
                              (map #(.toInstant ^java.nio.file.attribute.FileTime %))
                              (reduce #(if (.isAfter ^Instant %2 %1) %2 %1))
                              str)
             :notes ["Ranks 1-15 are the team (:role :team), 16+ are reserves."
                     "Empty slots are dropped, so some countries have fewer runners."
                     ":wa is copied verbatim from the roster's W/A column; its meaning isn't documented there."
                     ":id is <country code>-<slugged name>; real-time adapters should produce the same ids."
                     "Runners listed twice in one country keep their best rank; the others are in :dropped-duplicates."]
             :dropped-duplicates (into [] (comp (mapcat :dupes)
                                                (map #(select-keys % [:id :name :rank])))
                                       fetched)
             :countries data}]
    (if out
      (spit out (with-out-str (pp/pprint doc)))
      (pp/pprint doc))))

(apply -main *command-line-args*)
