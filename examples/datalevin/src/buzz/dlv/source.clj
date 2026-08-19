(ns buzz.dlv.source
  "A Buzz source over a Datalevin connection, keyed by a datalog query.

  Subscribing runs the query once and keeps the result. One listener on the
  connection turns every transaction into notifications: the attributes the
  transaction wrote are intersected with the attributes each subscribed query
  reads, and only the queries that overlap run again.

  So the topics are derived from the write, not declared. A transaction on
  `:query/text` leaves a query over `:artist/name` alone, and the connections
  reading that query are never rendered.

  This sees transactions made through this connection in this process. A
  writer in another process is invisible."
  (:require [buzz.source :as source]
            [clojure.set :as set]
            [datalevin.core :as d]))

(defn- query-attrs
  "The schema attributes `q` reads."
  [conn q]
  (let [known (set (keys (d/schema conn)))]
    (into #{} (filter known) (tree-seq coll? seq q))))

(defn- refresh!
  "Runs the subscribed queries the transaction can have changed."
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
  ;; Subscribe before reading: the listener is in place before the first
  ;; result is cached, so a transaction landing in between is not lost.
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
  "How often each subscribed query has run again since it was subscribed."
  [source]
  (update-vals @(:subs source) #(deref (:runs %))))
