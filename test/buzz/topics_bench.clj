(ns buzz.topics-bench
  "Reproduces the connections vs us/rpc table in
  doc/ai/adr/0001-render-scheduling.md and puts the topic mechanism beside it.
  Reading the whole atom reruns every connection's slots on a write. Reading
  one user's key reruns only that user's connection.

  Two tables, same scenarios and connection counts, different slot cost. The
  first slot is a map lookup, cheap enough that fan out barely shows on the
  clock. The second does real work standing in for a database query, which is
  where 0001's point shows up: the wide key grows with the connection count
  and the narrow one stays flat. Slot runs, not the clock, are what proves the
  fan out either way.

  Every scenario runs with :render-interval-ms 0, which makes a write render
  synchronously on the writing thread. That is what makes the write itself
  timeable, and it is how 0001 measured."
  (:require [buzz.core :as buzz :refer [defui request server]]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(defn- user-of [req] (get-in req [:headers "x-user"]))

(defonce ^:private slot-runs (atom 0))
(defonce ^:private state (atom {}))
(defonce ^:private state-source (buzz/atom-source state))

;; ---------------------------------------------------------------------------
;; Slot cost: a map lookup

(defui wide-lookup []
  [:p (server (do (swap! slot-runs inc)
                  (get (buzz/observe state-source []) (user-of (request)))))])

(defui narrow-lookup []
  [:p (server (do (swap! slot-runs inc)
                  (buzz/observe state-source [(user-of (request))])))])

(defn- wide-lookup-spec []
  {:mounts [{:el "app" :ui #'wide-lookup}]
   :render-interval-ms 0})

(defn- narrow-lookup-spec []
  {:mounts [{:el "app" :ui #'narrow-lookup}]
   :render-interval-ms 0})

;; ---------------------------------------------------------------------------
;; Slot cost: a query stand-in

;; Deterministic CPU work with no allocation or IO, so its cost is repeatable
;; run to run. `work-n` is tuned by calibrate-work! so one call costs about
;; the target microseconds on this machine.
(defonce ^:private work-n (atom 200))

(defn- churn [seed n]
  (loop [i 0 acc (long seed)]
    (if (< i n)
      (recur (inc i) (unchecked-add acc (unchecked-multiply acc 2654435761)))
      acc)))

(defui wide-query []
  [:p (server (do (swap! slot-runs inc)
                  (churn (hash (user-of (request))) @work-n)
                  (get (buzz/observe state-source []) (user-of (request)))))])

(defui narrow-query []
  [:p (server (do (swap! slot-runs inc)
                  (churn (hash (user-of (request))) @work-n)
                  (buzz/observe state-source [(user-of (request))])))])

(defn- wide-query-spec []
  {:mounts [{:el "app" :ui #'wide-query}]
   :render-interval-ms 0})

(defn- narrow-query-spec []
  {:mounts [{:el "app" :ui #'narrow-query}]
   :render-interval-ms 0})

;; ---------------------------------------------------------------------------
;; SSE plumbing, same protocol as open-events in handler_test.clj

(defn- next-event
  "The next SSE frame. Headings, chunk sizes and heartbeats are not frames."
  [rdr]
  (loop []
    (when-let [line (.readLine rdr)]
      (if (str/starts-with? line "data: ")
        (json/parse-string (subs line 6))
        (recur)))))

(defn- open-events
  "One SSE connection as `user`. Reads past the headers to the blank line,
  then the first data: frame is the session id."
  [port user]
  (let [sock (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes (str "GET /events HTTP/1.1\r\nHost: localhost\r\n"
                              "X-User: " user "\r\n\r\n")))
      (.flush))
    (let [rdr (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))]
      (loop []
        (let [line (.readLine rdr)]
          (when-not (or (nil? line) (str/blank? line)) (recur))))
      (next-event rdr)
      {:sock sock :rdr rdr})))

(defn- drain!
  "Reads and discards from `rdr` on its own thread, so a full socket buffer
  never distorts the timing of a write."
  [rdr]
  (future
    (try
      (while (.readLine rdr))
      (catch Exception _ nil))))

;; ---------------------------------------------------------------------------
;; Timing

(defn- median [xs]
  (let [s   (vec (sort xs))
        n   (count s)
        mid (quot n 2)]
    (if (odd? n)
      (nth s mid)
      (/ (+ (nth s (dec mid)) (nth s mid)) 2.0))))

(def ^:private warmup-writes 30)
(def ^:private sample-writes 201)

(defn- run-scenario
  "Serves `spec` behind `n` connections, one per user, and repeatedly writes
  user-0's data. Returns [median-us-per-write slot-runs-per-write]."
  [spec n]
  (reset! state (into {} (for [i (range n)] [(str "user-" i) []])))
  (let [ui    (buzz/handler spec)
        stop  (http/run-server (fn [req] (or (ui req) {:status 404 :body "no"}))
                               {:port 0})
        port  (:local-port (meta stop))
        conns (mapv #(open-events port (str "user-" %)) (range n))]
    (try
      (run! #(drain! (:rdr %)) conns)
      ;; lets every mount, and the slot run it costs, land before measuring
      (Thread/sleep (max 100 (* 2 n)))
      (dotimes [_ warmup-writes] (swap! state update "user-0" conj "x"))
      (Thread/sleep 20)
      (reset! slot-runs 0)
      (let [samples (mapv (fn [_]
                            (let [t0 (System/nanoTime)]
                              (swap! state update "user-0" conj "x")
                              (- (System/nanoTime) t0)))
                          (range sample-writes))]
        [(/ (median samples) 1000.0)
         (/ (double @slot-runs) sample-writes)])
      (finally
        (run! #(.close ^java.net.Socket (:sock %)) conns)
        (stop)))))

(defn- measure-churn
  "Median cost, in us, of one (churn 1 n) call over `samples` runs."
  [n samples]
  (median (mapv (fn [_]
                  (let [t0 (System/nanoTime)]
                    (churn 1 n)
                    (/ (- (System/nanoTime) t0) 1000.0)))
                (range samples))))

(defn- calibrate-work!
  "Sets work-n so one churn call costs about target-us on this machine.
  Doubles n until it reaches the target, then scales once to refine it.
  Returns the measured cost of the tuned call, in us."
  [target-us]
  (loop [n 64]
    (let [us (measure-churn n 30)]
      (if (or (>= us target-us) (>= n 100000000))
        (let [n' (max 1 (long (* n (/ target-us us))))]
          (reset! work-n n')
          (measure-churn n' 50))
        (recur (* n 2))))))

;; ---------------------------------------------------------------------------
;; Reporting

(def ^:private sizes [1 10 25 50 100])

(defn- row [& cols]
  (apply str (map #(format "%-20s" (str %)) cols)))

(defn- print-table [label wide-spec-fn narrow-spec-fn]
  (println label)
  (println (row "connections" "wide key us/write" "narrow key us/write"
                "wide slot runs" "narrow slot runs"))
  (doseq [n sizes]
    (let [[wide-us wide-runs] (run-scenario (wide-spec-fn) n)
          [narrow-us narrow-runs] (run-scenario (narrow-spec-fn) n)]
      (println (row n
                    (format "%.1f" wide-us)
                    (format "%.1f" narrow-us)
                    (format "%.1f" wide-runs)
                    (format "%.1f" narrow-runs)))))
  (println))

(defn -main [& _]
  (println "runtime:" (if-let [v (System/getProperty "babashka.version")]
                        (str "babashka " v)
                        "jvm"))
  (println "render-interval-ms 0: a write renders synchronously on the writing thread")
  (println)
  (print-table "slot: a map lookup" wide-lookup-spec narrow-lookup-spec)
  (let [query-us (calibrate-work! 60)]
    (print-table (format "slot: about %.1fus of work, standing in for a query" query-us)
                 wide-query-spec narrow-query-spec))
  (System/exit 0))
