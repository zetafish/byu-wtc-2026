(ns wtc.store
  "Durable storage of each source's dataset. The live state lives in memory.
  The store exists so a restart doesn't lose history, and so the hourly
  data can be queried for statistics later.")

(defprotocol Store
  (load-datasets [this] "-> {source-id dataset}")
  (save-dataset! [this source-id dataset] "Upsert the full dataset of a source.")
  (record-fetch! [this entry] "Append a fetch log entry.")
  (close! [this]))

(defrecord MemoryStore [db]
  Store
  (load-datasets [_] (:datasets @db))
  (save-dataset! [_ source-id dataset]
    (swap! db assoc-in [:datasets source-id] (dissoc dataset :rejected)))
  (record-fetch! [_ entry] (swap! db update :fetches (fnil conj []) entry))
  (close! [_]))

(defn memory-store [] (->MemoryStore (atom {})))
