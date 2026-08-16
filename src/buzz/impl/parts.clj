(ns buzz.impl.parts
  "Shared metadata and revision state for compiled parts."
  (:require [clojure.string :as str]))

;; Incremented when a defui or defpart is evaluated.
(defonce revision (atom 0))

(defn fn-part?
  "Whether `x` is the value a `defpart` defines."
  [x]
  (boolean (and (fn? x) (::fn-part (meta x)))))

(defn fn-part-meta
  "The metadata `defpart` marks its function with."
  [m]
  (assoc m ::fn-part true))

(defn js-name
  "Returns a JavaScript binding for a qualified part name. The namespace
  prevents collisions and bare reserved-word bindings."
  [sym]
  (str/replace (munge (str sym)) "." "_DOT_"))

(defn parts-closure
  "Returns metadata for every part reachable from `syms`."
  [syms]
  (loop [todo (seq syms) seen {}]
    (if-let [[s & more] todo]
      (if (contains? seen s)
        (recur more seen)
        (let [m (some-> (try (resolve s) (catch Exception _ nil)) deref meta)]
          (if (::fn-part m)
            (recur (concat more (:buzz/parts m)) (assoc seen s m))
            (recur more seen))))
      seen)))
