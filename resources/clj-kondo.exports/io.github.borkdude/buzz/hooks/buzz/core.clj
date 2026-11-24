(ns hooks.buzz.core
  (:require [clj-kondo.hooks-api :as api]))

(defn- lang-do
  [lang children]
  (vary-meta (api/list-node (list* (api/token-node 'do) children))
             assoc :clj-kondo/lang lang))

(defn component
  [{:keys [node]}]
  (let [[_ nm & more] (:children node)
        [doc more] (if (some-> (first more) api/string-node?)
                     [(first more) (rest more)]
                     [nil more])
        [argv & body] more]
    {:node (api/list-node
            (cond-> [(api/token-node 'defn) nm]
              doc (conj doc)
              true (conj argv (lang-do :cljs body))))}))

(defn server
  [{:keys [node]}]
  {:node (lang-do :clj (rest (:children node)))})

(defn client
  [{:keys [node]}]
  {:node (lang-do :cljs (rest (:children node)))})

(defn host
  [{:keys [node]}]
  {:node (api/vector-node
          (map (fn [[k v]]
                 (case (some-> k api/sexpr)
                   :clj (lang-do :clj [v])
                   :cljs (lang-do :cljs [v])
                   v))
               (partition-all 2 (rest (:children node)))))})
