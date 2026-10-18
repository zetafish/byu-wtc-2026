(ns wtc.build
  "shadow-cljs build hooks (JVM side, not part of the app build)."
  (:require [clojure.java.io :as io])
  (:import (java.security MessageDigest)))

(def ^:private assets ["public/index.html" "public/css/style.css" "public/js/main.js"])

(defn write-version
  "After a release build, writes public/version.json with a hash of what the
  page loads. An open page checks it to offer a reload after a deploy."
  {:shadow.build/stage :flush}
  [build-state]
  (when (= :release (:shadow.build/mode build-state))
    (let [md (MessageDigest/getInstance "SHA-256")]
      (doseq [f assets] (.update md (.readAllBytes (io/input-stream f))))
      (spit "public/version.json"
            (str "{\"version\":\"" (subs (apply str (map #(format "%02x" %) (.digest md))) 0 12) "\"}\n"))))
  build-state)
