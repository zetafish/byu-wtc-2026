(ns wtc.store.postgres
  (:require [clojure.string :as str]
            [next.jdbc :as jdbc]
            [next.jdbc.connection :as connection]
            [next.jdbc.result-set :as rs]
            [wtc.store :as store])
  (:import (com.zaxxer.hikari HikariDataSource)
           (java.net URI)
           (java.sql Timestamp)))

(def ^:private schema
  [;; The plural tables of earlier versions. The data ones only hold a copy
   ;; of what the sources serve, so the next fetch refills them; the fetch
   ;; log is kept.
   "drop table if exists laps, runners, countries"
   "do $$ begin
      if to_regclass('fetches') is not null and to_regclass('fetch_log') is null then
        alter table fetches rename to fetch_log;
      end if;
    end $$"
   "create table if not exists country (
      source text not null, code text not null, name text not null,
      primary key (source, code))"
   "create table if not exists runner (
      source text not null, id text not null, name text not null,
      country text not null, bib text,
      primary key (source, id),
      foreign key (source, country) references country (source, code))"
   "create table if not exists lap (
      source text not null, runner_id text not null, lap int not null,
      status text not null, seconds int,
      updated_at timestamptz not null default now(),
      primary key (source, runner_id, lap),
      foreign key (source, runner_id) references runner (source, id))"
   "create table if not exists fetch_log (
      id bigserial primary key, source text not null,
      started_at timestamptz not null, duration_ms int not null,
      ok boolean not null, error text, laps int, rejected int)"])

(defn jdbc-url
  "Accepts a jdbc: URL or a postgres:// URL as Neon hands them out."
  [url]
  (if (str/starts-with? url "jdbc:")
    url
    (let [u (URI. url)
          [user pass] (some-> (.getRawUserInfo u) (str/split #":" 2))
          params (cond-> []
                   user (conj (str "user=" user))
                   pass (conj (str "password=" pass))
                   (.getRawQuery u) (conj (.getRawQuery u)))]
      (str "jdbc:postgresql://" (.getHost u)
           (when (pos? (.getPort u)) (str ":" (.getPort u)))
           (.getRawPath u)
           (when (seq params) (str "?" (str/join "&" params)))))))

(def ^:private opts {:builder-fn rs/as-unqualified-lower-maps})

(defn- batch! [tx sql rows]
  (when (seq rows) (jdbc/execute-batch! tx sql rows {})))

(defrecord PostgresStore [^HikariDataSource ds]
  store/Store
  (load-datasets [_]
    (let [q #(group-by :source (jdbc/execute! ds [%] opts))
          countries (q "select * from country")
          runners (q "select * from runner")
          laps (q "select * from lap")]
      (into {}
            (for [src (distinct (concat (keys countries) (keys runners)))]
              [(keyword src)
               {:countries (into {} (map (fn [c] [(:code c) (select-keys c [:code :name])]))
                                 (countries src))
                :runners (into {} (map (fn [r] [(:id r) (select-keys r [:id :name :country :bib])]))
                               (runners src))
                :laps (into {} (map (fn [l]
                                      [[(:runner_id l) (:lap l)]
                                       {:runner-id (:runner_id l) :lap (:lap l)
                                        :status (keyword (:status l)) :seconds (:seconds l)}]))
                            (laps src))}]))))

  (save-dataset! [_ source-id {:keys [countries runners laps]}]
    (let [src (name source-id)]
      (jdbc/with-transaction [tx ds]
        (batch! tx "insert into country (source, code, name) values (?, ?, ?)
                    on conflict (source, code) do update set name = excluded.name"
                (map (fn [c] [src (:code c) (:name c)]) (vals countries)))
        (batch! tx "insert into runner (source, id, name, country, bib) values (?, ?, ?, ?, ?)
                    on conflict (source, id) do update set name = excluded.name,
                    country = excluded.country, bib = excluded.bib"
                (map (fn [r] [src (:id r) (:name r) (:country r) (:bib r)]) (vals runners)))
        (batch! tx "insert into lap (source, runner_id, lap, status, seconds) values (?, ?, ?, ?, ?)
                    on conflict (source, runner_id, lap) do update set status = excluded.status,
                    seconds = excluded.seconds, updated_at = now()
                    where (lap.status, lap.seconds) is distinct from (excluded.status, excluded.seconds)"
                (map (fn [l] [src (:runner-id l) (:lap l) (name (:status l)) (:seconds l)]) (vals laps))))))

  (record-fetch! [_ {:keys [source started-at duration-ms ok error laps rejected]}]
    (jdbc/execute! ds ["insert into fetch_log (source, started_at, duration_ms, ok, error, laps, rejected)
                        values (?, ?, ?, ?, ?, ?, ?)"
                       (name source) (Timestamp/from started-at) duration-ms ok error laps rejected]))

  (close! [_] (.close ds)))

(defn postgres-store [database-url]
  (let [ds (connection/->pool HikariDataSource {:jdbcUrl (jdbc-url database-url)
                                                :maximumPoolSize 4})]
    (doseq [ddl schema] (jdbc/execute! ds [ddl]))
    (->PostgresStore ds)))
