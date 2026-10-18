(ns wtc.source
  "Pluggable data sources. To add one, create `wtc.source.<name>` with a
  `create` method for its :type and require it in `wtc.main`.

  Source config: {:id :mock :type :mock :priority 0 ...type-specific}.
  Higher :priority wins when sources disagree on a lap.")

(defprotocol Source
  (fetch [this ctx]
    "Return a snapshot (see `wtc.model`). ctx is {:start Instant :now Instant}.
    Throw on failure; the ingester retries, times out and keeps the last good
    data."))

(defprotocol Saved
  (saved [this]
    "The snapshot this source saved on its last good fetch, from before a
    restart, or nil. It stands in until the first fetch succeeds."))

(extend-protocol Saved
  Object
  (saved [_] nil))

(defmulti create :type)

(defmethod create :default [cfg]
  (throw (ex-info (str "Unknown source type " (:type cfg)) {:config cfg})))
