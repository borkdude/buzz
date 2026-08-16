(ns buzz.capra
  "Capra adapter for `buzz.stream`. A bounded queue prevents stream writes
  from blocking watch threads."
  (:require [buzz.stream :as stream]
            [ring.core.protocols :as ring-protocols])
  (:import [java.util.concurrent LinkedBlockingQueue TimeUnit]))

(def ^:private goodbye
  (Object.))

(defn- die!
  "Closes the connection once. The daemon thread prevents a blocking close
  from blocking the caller."
  [open out on-close done]
  (when (compare-and-set! open true false)
    (doto (Thread. #(try (.close out) (catch Exception _)))
      (.setDaemon true)
      (.start))
    (on-close)
    (deliver done true)))

(defn- pump!
  "Writes queued frames until the connection closes."
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
      (boolean
       (when @open
         ;; Close a connection that stops draining its queue.
         (or (.offer queue s 1 TimeUnit/SECONDS)
             (do (die! open out on-close done) false)))))
    (close! [_]
      (die! open out on-close done)
      (.offer queue goodbye))))

(defn adapter
  "Returns a Capra response that streams frames through a bounded queue."
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
                  ;; Keep the response open until the channel closes.
                  @done
                  (.offer queue goodbye)
                  (.join pump 1000))))})
