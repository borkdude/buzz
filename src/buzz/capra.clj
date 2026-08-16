(ns buzz.capra
  "The Capra half of a stream, and proof the seam is a seam. Capra streams
  through `ring.core.protocols/StreamableResponseBody`, and its writes block
  when the connection stops draining. Buzz sends frames from watch threads
  that must never block, so this adapter puts a queue and a pump thread
  between them: `send!` offers, the pump writes, and only the pump feels the
  backpressure. A client that went away is learned about from a failed write,
  or from the queue filling against a wedged one. The heartbeat writes every
  25 seconds, so that is how stale a dead connection can get.

  Loaded only when a handler is given this adapter. Runs on the JVM and on
  babashka alike."
  (:require [buzz.stream :as stream]
            [ring.core.protocols :as ring-protocols])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private goodbye
  "The queue entry that tells the pump the stream is over."
  (Object.))

(defn- die!
  "One death, whoever notices it first. Closing `out` breaks a pump that is
  parked in a write that will never finish, but the close itself can park in
  the same backpressure, so it happens on a thread nobody waits for."
  [open out on-close done]
  (when (compare-and-set! open true false)
    (doto (Thread. #(try (.close out) (catch Exception _)))
      (.setDaemon true)
      (.start))
    (on-close)
    (deliver done true)))

(defn- pump!
  "Writes what the queue holds until the stream dies under it."
  [^LinkedBlockingQueue queue out open on-close done]
  (loop []
    (let [x (.take queue)]
      (when-not (identical? x goodbye)
        (when @open
          (try
            (.write out (.getBytes ^String x "UTF-8"))
            (.flush out)
            (catch Exception _
              (die! open out on-close done)))
          (recur))))))

(defn- channel [^LinkedBlockingQueue queue out open on-close done]
  (reify stream/Channel
    (send! [_ s]
      (when @open
        ;; a queue that stays full means nobody is draining it, which is a
        ;; dead connection with extra steps
        (when-not (.offer queue s 1 TimeUnit/SECONDS)
          (die! open out on-close done))))
    (close! [_]
      (die! open out on-close done)
      (.offer queue goodbye))))

(defn adapter
  "Answers `req` with a body that holds the response open and writes frames as
  Buzz sends them."
  [_req {:keys [status headers on-open on-close]}]
  {:status  status
   :headers headers
   :body    (reify ring-protocols/StreamableResponseBody
              (write-body-to-stream [_ _ out]
                (let [queue (LinkedBlockingQueue. 256)
                      open  (atom true)
                      done  (promise)
                      pump  (doto (Thread. #(pump! queue out open on-close done))
                              (.setDaemon true)
                              (.start))]
                  (on-open (channel queue out open on-close done))
                  ;; returning would let the server close the stream, so stay
                  ;; here until the channel says it is over
                  @done
                  (.offer queue goodbye)
                  (.join pump 1000))))})
