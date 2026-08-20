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

;; ---------------------------------------------------------------------------
;; Sources

(defrecord SourceTopic [source k])

(defn source-topic?
  "True for the topics `observe` produces."
  [t]
  (instance? SourceTopic t))

(defn- path-of [k]
  (if (sequential? k) (vec k) [k]))

;; Identity, not equality. A path nobody wrote keeps the same object through a
;; swap, so `identical?` answers "did this key change" without walking a large
;; value. A write that lands on an equal but fresh value notifies once too
;; often, which costs a render and no frame, since the values compare equal
;; where they are sent.
(defrecord AtomSource [a]
  Source
  ;; The watch goes on before the first value is read, and the first value is
  ;; stored with a compare-and-set. Reading and storing are two steps, so a
  ;; write between them fires the watch with the newer value and a plain
  ;; `reset!` would put the older one back on top of it. The version would then
  ;; say current while the handle was stale, which no later check can repair.
  ;;
  ;; The watch is keyed by the handle rather than by `k`. Two subscriptions to
  ;; one key can overlap while an old one is being released, and a watch keyed
  ;; by `k` would let either one remove the other's callback.
  ;; Watches run on the writing threads, so two writes that land in order can
  ;; have their callbacks finish in the opposite order. A callback that stores
  ;; the snapshot it was handed would then put the older value on top of the
  ;; newer one. Each callback takes the handle and reads the atom itself, so
  ;; whichever finishes last stores what is current. `notify` is called outside
  ;; the lock, since it renders and must not hold a writing thread's lock.
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

;; Every acquisition raises the entry's generation. A release is scheduled for
;; the generation that was current when the last holder let go, so an
;; acquisition in the meantime makes the release a no-op. Without it a render
;; can take the handle a moment before a delayed release closes it, and end up
;; holding a topic whose source is gone.
(defn- acquire [m t]
  (if-let [e (get m t)]
    (assoc m t (update e :gen inc))
    (assoc m t {:gen 0 :sub (delay (new-sub t))})))

;; Nothing serializes creating against closing. Rule four of the contract is
;; what makes that safe: `-unsubscribe` closes only the handle it is given, so
;; a close that overlaps a new subscription for the same key cannot touch it. A
;; lock here would serialize every first subscription in the process behind the
;; slowest one, which is the wrong price for defending against a source that
;; breaks a rule the suite already tests.
(defn sub-for
  "The shared subscription for `t`, subscribing on first use."
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
      (-unsubscribe (:source t) (:k t) (:handle @(:sub entry))))))

(defn- maybe-release! [topics]
  (doseq [t topics :when (source-topic? t)]
    (when-let [gen (generation t)]
      (schedule! @release-grace-ms #(release! t gen)))))

(defn release-unheld!
  "Schedules a release for topics nothing is holding. `observe` uses it for a
  read outside a render, which subscribes like any other read but leaves no
  connection behind to let go."
  [topics]
  (maybe-release! topics))

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
    (if *reads*
      (swap! *reads* assoc t @version)
      ;; A read from a router, an rpc handler or the first paint subscribes
      ;; like any other, and no connection will ever drop it. Schedule the
      ;; release here, or every distinct key ever read leaks a subscription.
      (release-unheld! [t]))
    @handle))

(defn stale?
  "Whether any of `reads` has changed since it was read. A subscription that is
  gone counts as stale: the read cannot be trusted and the topic has to be
  taken again."
  [reads]
  (boolean (some (fn [[t v]]
                   (if-let [e (get @open-subs t)]
                     (not= v @(:version @(:sub e)))
                     true))
                 reads)))

(defmacro with-reads
  "Runs `body` with read tracking on. Returns [result reads], where reads maps
  each topic to the version it was read at."
  [& body]
  `(let [reads# (atom {})]
     (binding [*reads* reads#]
       [(do ~@body) @reads#])))
