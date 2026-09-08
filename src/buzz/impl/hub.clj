(ns buzz.impl.hub
  "Topics, sources and the shared scheduler. The public API is exposed through
  `buzz.core`."
  (:require [buzz.source :refer [Source -subscribe -unsubscribe]]
            [clojure.set :as set]))

;; The daemon scheduler handles render delays and subscription releases.
(defonce scheduler
  (delay (java.util.concurrent.Executors/newSingleThreadScheduledExecutor
          (reify java.util.concurrent.ThreadFactory
            (newThread [_ r]
              (doto (Thread. ^Runnable r "buzz-render")
                (.setDaemon true)))))))

(defn schedule!
  "Runs `f` after `ms` on the shared scheduler."
  [^long ms f]
  (.schedule ^java.util.concurrent.ScheduledExecutorService @scheduler
             ^Runnable f ms java.util.concurrent.TimeUnit/MILLISECONDS))

;; ---------------------------------------------------------------------------
;; Sources

(defrecord SourceTopic [source k])

(defn source-topic?
  "True for the topics `observe` produces."
  [t]
  (instance? SourceTopic t))

(defn- path-of [k]
  (if (sequential? k) (vec k) [k]))

;; Identity checks avoid traversing unchanged values.
(defrecord AtomSource [a]
  Source
  ;; Register before reading and serialize cache updates to preserve the latest value.
  (-subscribe [_ k notify]
    (let [path  (path-of k)
          cache (atom ::unread)]
      (add-watch a cache
                 (fn [_ _ _ _]
                   (when (locking cache
                           (let [v (get-in @a path)]
                             (when-not (identical? v @cache)
                               (reset! cache v)
                               true)))
                     (notify))))
      (locking cache
        (compare-and-set! cache ::unread (get-in @a path)))
      cache))
  (-unsubscribe [_ _ handle]
    (remove-watch a handle)))

(defn atom-source
  "A source over `a`, keyed by a path into it. `(observe src [:todos \"alice\"])`
  reads `(get-in @a [:todos \"alice\"])` and only notifies when that path
  changes."
  [a]
  (->AtomSource a))

;; ---------------------------------------------------------------------------
;; Handlers and the topic index

;; Each handler registers {:index :mark!}. `mark!` takes a set of topics.
(defonce ^:private handlers (atom #{}))

(defn register-handler! [entry]
  (swap! handlers conj entry)
  entry)

(defn entries
  "Returns the set of registered handlers."
  []
  @handlers)

(defn sessions-for
  "Returns the sessions subscribed to any of `topics`."
  [index topics]
  (let [by-topic (:by-topic @index)]
    (into #{} (mapcat by-topic) topics)))

(defn- holds-any? [index topics]
  (let [by-topic (:by-topic @index)]
    (boolean (some #(seq (get by-topic %)) topics))))

(defn invalidate!
  "Marks `topics` changed. Only the connections holding one of them render.
  Invalidating a topic nobody holds does nothing."
  [& topics]
  (let [topics (set topics)]
    (doseq [{:keys [index mark!]} @handlers]
      (when (holds-any? index topics)
        (mark! topics))))
  nil)

(defn- reindex [m session topics]
  (let [old (get (:by-session m) session #{})
        add (set/difference topics old)
        del (set/difference old topics)]
    (-> m
        (assoc-in [:by-session session] topics)
        (update :by-topic
                (fn [by-topic]
                  (as-> by-topic $
                    (reduce (fn [bt t] (update bt t (fnil conj #{}) session)) $ add)
                    (reduce (fn [bt t]
                              (let [remaining (disj (get bt t) session)]
                                (if (seq remaining)
                                  (assoc bt t remaining)
                                  (dissoc bt t))))
                            $ del)))))))

;; ---------------------------------------------------------------------------
;; Source subscriptions
;;
;; Delay release to reuse subscriptions across changes in observed keys.

(defonce ^:private open-subs (atom {}))

(def release-grace-ms
  "Subscription release delay in milliseconds, stored in an atom."
  (atom 10000))

(defn subscriptions
  "Returns the set of subscribed source topics."
  []
  (set (keys @open-subs)))


;; A new acquisition cancels pending releases by advancing the generation.
(defn- acquire [m t]
  (if-let [e (get m t)]
    (assoc m t (update e :gen inc))
    (assoc m t {:gen 0 :sub (delay (-subscribe (:source t) (:k t)
                                               #(invalidate! t)))})))

;; Concurrent subscriptions to one key must have independent handles.
(defn sub-for
  "Returns the shared handle for `t`, subscribing on first use."
  [t]
  @(:sub (get (swap! open-subs acquire t) t)))

(defn- generation [t]
  (:gen (get @open-subs t)))

(defn- held-anywhere? [t]
  (boolean (some #(seq (get (:by-topic @(:index %)) t)) @handlers)))

(defn- release! [t gen]
  (let [[old _] (swap-vals! open-subs
                            (fn [m]
                              (if (and (= gen (:gen (get m t)))
                                       (not (held-anywhere? t)))
                                (dissoc m t)
                                m)))
        entry (get old t)]
    ;; only ever close the handle this release was scheduled for
    (when (and entry (= gen (:gen entry)))
      (-unsubscribe (:source t) (:k t) @(:sub entry)))))

(defn- maybe-release! [topics]
  (doseq [t topics :when (source-topic? t)]
    (when-let [gen (generation t)]
      (schedule! @release-grace-ms #(release! t gen)))))

(defn release-unheld!
  "Schedules subscription release for `topics` after the grace period.
  Topics still observed by a connection remain subscribed."
  [topics]
  (maybe-release! topics))

(defn set-topics!
  "Replaces the topics observed by `session`. Schedules release of source
  subscriptions that are no longer observed."
  [index session topics]
  (let [[old _] (swap-vals! index reindex session topics)]
    (maybe-release! (set/difference (get (:by-session old) session #{}) topics))))

(defn drop-session!
  "Removes `session` and schedules release of its unshared subscriptions."
  [index session]
  (set-topics! index session #{})
  (swap! index update :by-session dissoc session))

;; ---------------------------------------------------------------------------
;; Read tracking

(def ^:dynamic *tracking*
  "Render context containing `:reads`, `:index` and `:session`."
  nil)

(defn add-topic!
  "Adds one topic to what `session` holds."
  [index session t]
  (swap! index (fn [m]
                 (-> m
                     (update-in [:by-session session] (fnil conj #{}) t)
                     (update-in [:by-topic t] (fnil conj #{}) session)))))

(defn observe
  "Reads `k` from `source` and subscribes the current connection to it. Outside
  a slot it is a plain read.

    (server (observe todos [:todos (whoami (request))]))"
  [source k]
  (let [t (->SourceTopic source k)
        handle (sub-for t)]
    (if-let [{:keys [reads index session]} *tracking*]
      ;; Register before reading so concurrent changes trigger another render.
      (when-not (contains? @reads t)
        (swap! reads conj t)
        (add-topic! index session t))
      ;; Reads outside a render need a scheduled release.
      (release-unheld! [t]))
    @handle))
