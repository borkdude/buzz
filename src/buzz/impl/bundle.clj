(ns buzz.impl.bundle
  "Builds squint core with only the functions a page calls. Needs
  org.babashka/esbuild on the classpath."
  (:require [babashka.esbuild :as esbuild]
            [babashka.fs :as fs]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private core-dir
  (delay
    (let [dir (fs/create-temp-dir {:prefix "buzz-squint-core"})]
      (fs/delete-on-exit dir)
      (fs/delete-on-exit (fs/file dir "core.js"))
      (fs/delete-on-exit (fs/file dir "entry.js"))
      (with-open [in (io/input-stream (io/resource "squint/core.js"))]
        (io/copy in (fs/file dir "core.js")))
      dir)))

(defn core-js
  "Returns minified JavaScript that exports `vars` from squint core. vars is a
  collection of munged squint core names."
  [vars]
  (locking core-dir
    (let [entry (fs/file @core-dir "entry.js")]
      (spit entry (str "export { " (str/join ", " (sort vars)) " } from './core.js';\n"))
      (-> (esbuild/build {:entry-points [(str entry)]
                          :bundle true
                          :format :esm
                          :minify true})
          :outputs first :contents))))
