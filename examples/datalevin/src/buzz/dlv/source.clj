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
    (doseq [[cache {:keys [q attrs runs notify]}] @subs
            :when (seq (set/intersection wrote attrs))]
      (swap! runs inc)
      ;; Serialize query evaluation and cache updates for each handle.
      (when (locking cache
              (let [v (d/q q db)]
                (when (not= v @cache)
                  (reset! cache v)
                  true)))
        (notify)))))

(defrecord DatalevinSource [conn subs listener]
  source/Source
  ;; Each subscription has an independent handle.
  (-subscribe [_ q notify]
    (let [cache (atom ::unread)]
      ;; Coordinate listener registration and removal with subscription changes.
      (locking listener
        (swap! subs assoc cache {:q q
                                 :attrs (query-attrs conn q)
                                 :runs (atom 0)
                                 :notify notify})
        (d/listen! conn ::source #(refresh! conn subs %)))
      ;; Preserve any value already delivered by the listener.
      (let [v (d/q q (d/db conn))]
        (locking cache
          (compare-and-set! cache ::unread v)))
      cache))
  (-unsubscribe [_ _ handle]
    (locking listener
      (when (empty? (swap! subs dissoc handle))
        (d/unlisten! conn ::source)))))

(defn datalevin-source [conn]
  (->DatalevinSource conn (atom {}) (Object.)))

(defn runs
  "Returns a map from queries to total re-run counts across active handles."
  [source]
  (reduce (fn [m [_ sub]] (update m (:q sub) (fnil + 0) @(:runs sub)))
          {}
          @(:subs source)))
