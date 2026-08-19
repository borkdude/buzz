(ns buzz.impl.hub
  "Topics, sources and the shared scheduler. The public API is exposed through
  `buzz.core`."
  (:require [buzz.source :refer [Source -subscribe -unsubscribe]]
            [clojure.set :as set]))

;; One scheduler thread for every handler and for releasing source
;; subscriptions. Daemon, so a process that stops its server is not kept alive
;; by an idle scheduler.
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

(def all
  "The topic every connection holds."
  ::all)

;; ---------------------------------------------------------------------------
;; Sources

(defrecord SourceTopic [source k])

(defn source-topic?
  "True for the topics `observe` produces."
  [t]
  (instance? SourceTopic t))

(defn- path-of [k]
  (if (sequential? k) (vec k) [k]))

(defrecord AtomSource [a]
  Source
  (-subscribe [_ k notify]
    (let [path  (path-of k)
          cache (atom (get-in @a path))]
      (add-watch a [::observe path]
                 (fn [_ _ _ new]
                   (let [v (get-in new path)]
                     (when (not= v @cache)
                       (reset! cache v)
                       (notify)))))
      cache))
  (-unsubscribe [_ k _]
    (remove-watch a [::observe (path-of k)])))

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
  "Every registered handler."
  []
  @handlers)

(defn sessions-for
  "The sessions holding any of `topics`."
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
;; One subscription per source topic per process, shared by every connection
;; holding it. A topic that loses its last connection is released after a grace
;; period, so a condition that flips between two observes does not close and
;; reopen the same subscription on every render.

(defonce ^:private open-subs (atom {}))

(def release-grace-ms
  "How long a source subscription outlives its last connection."
  (atom 10000))

(defn subscriptions
  "The source topics currently subscribed."
  []
  (set (keys @open-subs)))

(defn handle-for
  "The shared handle for `t`, subscribing on first use."
  [t]
  (let [pending (delay (-subscribe (:source t) (:k t) #(invalidate! t)))]
    @(get (swap! open-subs update t #(or % pending)) t)))

(defn- held-anywhere? [t]
  (boolean (some #(seq (get (:by-topic @(:index %)) t)) @handlers)))

(defn- release! [t]
  (when-not (held-anywhere? t)
    (let [[old _] (swap-vals! open-subs dissoc t)]
      (when-let [handle (get old t)]
        (-unsubscribe (:source t) (:k t) @handle)))))

(defn- maybe-release! [topics]
  (doseq [t topics :when (source-topic? t)]
    (schedule! @release-grace-ms #(release! t))))

(defn set-topics!
  "Replaces the topics `session` holds. Releases source subscriptions no
  connection is left holding."
  [index session topics]
  (let [[old _] (swap-vals! index reindex session topics)]
    (maybe-release! (set/difference (get (:by-session old) session #{}) topics))))

(defn drop-session!
  "Forgets `session` and releases what it alone was holding."
  [index session]
  (set-topics! index session #{})
  (swap! index update :by-session dissoc session))

;; ---------------------------------------------------------------------------
;; Read tracking

(def ^:dynamic *reads*
  "Bound to an atom while a connection's slots run. Every `observe` records the
  topic it read here, and the union becomes what that connection holds."
  nil)

(defn observe
  "Reads `k` from `source` and subscribes the current connection to it. Outside
  a slot it is a plain read.

    (server (observe todos [:todos (whoami (request))]))"
  [source k]
  (let [t (->SourceTopic source k)
        h (handle-for t)]
    (when *reads* (swap! *reads* conj t))
    @h))

(defmacro with-reads
  "Runs `body` with read tracking on. Returns [result reads]."
  [& body]
  `(let [reads# (atom #{})]
     (binding [*reads* reads#]
       [(do ~@body) @reads#])))
