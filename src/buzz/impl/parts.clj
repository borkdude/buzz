(ns buzz.impl.parts
  "What the compiler publishes about compiled parts, shared by `buzz.core`
  and `buzz.impl.page`: the revision that says code changed, which parts a
  component can reach, and the names they carry in the module."
  (:require [clojure.string :as str]))

;; Bumped every time a defui or a defpart is evaluated, so re-evaluating one
;; in a REPL is enough to tell the browsers something changed.
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
  "The name a part has in the compiled module: its qualified name, munged.
  The dot is munged here as well, since a flat const has no namespace
  objects, so two parts collide only when their qualified names do. The
  namespace also keeps the name from being a bare JavaScript reserved word,
  which `munge` does not rename and the compiler reads as an operator."
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
