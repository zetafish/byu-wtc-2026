(ns build
  (:require [clojure.tools.build.api :as b]))

(def class-dir "target/classes")
(def uber-file "target/wtc.jar")

(defn uber [_]
  (let [basis (b/create-basis {:project "deps.edn"})]
    (b/delete {:path "target"})
    (b/copy-dir {:src-dirs ["src" "resources"] :target-dir class-dir})
    (b/compile-clj {:basis basis :ns-compile '[wtc.main] :class-dir class-dir})
    (b/uber {:class-dir class-dir :uber-file uber-file :basis basis :main 'wtc.main})))
