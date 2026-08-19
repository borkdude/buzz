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


;; Every subscription carries a version that goes up before each notification.
;; A reader records the version it read at, so it can find out afterwards
;; whether the value moved under it.
(defn- new-sub [t]
  (let [version (atom 0)]
    {:version version
     :handle  (-subscribe (:source t) (:k t)
                          (fn [] (swap! version inc) (invalidate! t)))}))

(defn sub-for
  "The shared subscription for `t`, subscribing on first use."
  [t]
  (let [pending (delay (new-sub t))]
    @(get (swap! open-subs update t #(or % pending)) t)))

(defn- held-anywhere? [t]
  (boolean (some #(seq (get (:by-topic @(:index %)) t)) @handlers)))

(defn- release! [t]
  (when-not (held-anywhere? t)
    (let [[old _] (swap-vals! open-subs dissoc t)]
      (when-let [sub (get old t)]
        (-unsubscribe (:source t) (:k t) (:handle @sub))))))

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
  topic it read and the version it read it at. The keys become what that
  connection holds, and the versions say whether the read is still current."
  nil)

(defn observe
  "Reads `k` from `source` and subscribes the current connection to it. Outside
  a slot it is a plain read.

    (server (observe todos [:todos (whoami (request))]))"
  [source k]
  (let [t (->SourceTopic source k)
        {:keys [handle version]} (sub-for t)]
    ;; the version first: a change between the two reads then reports a stale
    ;; read, which costs one render, rather than a current one, which loses it
    (when *reads* (swap! *reads* assoc t @version))
    @handle))

(defn stale?
  "Whether any of `reads` has changed since it was read."
  [reads]
  (boolean (some (fn [[t v]]
                   (when-let [sub (get @open-subs t)]
                     (not= v @(:version @sub))))
                 reads)))

(defmacro with-reads
  "Runs `body` with read tracking on. Returns [result reads], where reads maps
  each topic to the version it was read at."
  [& body]
  `(let [reads# (atom {})]
     (binding [*reads* reads#]
       [(do ~@body) @reads#])))

;; ---------------------------------------------------------------------------
;; The development check
;;
;; A connection holds what its slots read through `observe`. A slot that reads
;; mutable state some other way holds nothing for it, so nothing marks that
;; connection and the browser keeps a value that is no longer true. Nothing in
;; the mechanism can notice this, because the mechanism only ever looks at
;; connections that hold a marked topic.
;;
;; So the check works from the outside. It renders every connection on a timer,
;; whatever the topics say, and reports the ones whose values had changed. It
;; also sends the patch, so a development session behaves as if every write
;; reached every connection.

(defonce ^:private check-task (atom nil))

(defn checking?
  "Whether the development check is running."
  []
  (some? @check-task))

(defn- sweep-all! []
  (doseq [{:keys [dirty? sweep!]} @handlers]
    (try
      ;; a render already on its way is not a missed one
      (when-not (and dirty? (dirty?))
        (when sweep! (sweep!)))
      (catch Throwable e
        (println "buzz: check failed -" (ex-message e))))))

(defn check-topics!
  "Starts or stops the development check. While it runs, every connection is
  rendered on a timer and any whose value had changed without a source saying
  so is reported. Off by default, and never for production."
  ([on?] (check-topics! on? 1000))
  ([on? ^long ms]
   (swap! check-task
          (fn [task]
            (when task (.cancel ^java.util.concurrent.ScheduledFuture task false))
            (when on?
              (.scheduleWithFixedDelay
               ^java.util.concurrent.ScheduledExecutorService @scheduler
               ^Runnable sweep-all!
               ms ms java.util.concurrent.TimeUnit/MILLISECONDS))))
   on?))

(defn observed-keys
  "The source keys `session` holds, for a report."
  [index session]
  (into [] (comp (filter source-topic?) (map :k))
        (get (:by-session @index) session)))
