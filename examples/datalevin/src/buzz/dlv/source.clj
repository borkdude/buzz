(ns buzz.dlv.source
  "Observe Datalog queries through a Datalevin connection.
  Queries re-run when a transaction changes an attribute they read.
  Only transactions through the supplied connection are observed."
  (:require [buzz.source :as source]
            [clojure.set :as set]
            [datalevin.core :as d]))

(defn- query-attrs
  "Returns the schema attributes used in `q`."
  [conn q]
  (let [known (set (keys (d/schema conn)))]
    (into #{} (filter known) (tree-seq coll? seq q))))

(defn- refresh!
  "Refreshes subscribed queries affected by `report` and notifies on changes."
  [conn subs report]
  (let [wrote (into #{} (map :a) (:tx-data report))
        db    (d/db conn)]
    (doseq [[q {:keys [attrs cache runs notify]}] @subs
            :when (seq (set/intersection wrote attrs))]
      (swap! runs inc)
      (let [v (d/q q db)]
        (when (not= v @cache)
          (reset! cache v)
          (notify))))))

(defrecord DatalevinSource [conn subs]
  source/Source
  ;; Register the listener before the initial query.
  (-subscribe [_ q notify]
    (let [cache (atom nil)]
      (swap! subs assoc q {:attrs (query-attrs conn q)
                           :cache cache
                           :runs (atom 0)
                           :notify notify})
      (d/listen! conn ::source #(refresh! conn subs %))
      (reset! cache (d/q q (d/db conn)))
      cache))
  (-unsubscribe [_ q _]
    (swap! subs dissoc q)
    (when (empty? @subs)
      (d/unlisten! conn ::source))))

(defn datalevin-source [conn]
  (->DatalevinSource conn (atom {})))

(defn runs
  "Returns a map from subscribed queries to their re-run counts."
  [source]
  (update-vals @(:subs source) #(deref (:runs %))))
