(ns wtc.dev
  "shadow-cljs dev server hooks (JVM side, not part of the app build)."
  (:require [clojure.string :as str]))

(defn proxy-api?
  "Only /api/* goes to the backend; everything else (incl. /) is served
  from public/."
  [req _config]
  (let [path (if (map? req) (:uri req) (str (.getRequestPath req)))]
    (str/starts-with? path "/api/")))
