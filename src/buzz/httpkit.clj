(ns buzz.httpkit
  "The http-kit half of a stream. The one namespace that knows http-kit:
  `buzz.core` loads it when a handler is given no `:adapter` of its own."
  (:require [buzz.stream :as stream]
            [org.httpkit.server :as http]))

(defn- wrap [ch]
  (reify stream/Channel
    (send! [_ s] (http/send! ch s false))
    (close! [_] (http/close ch))))

(defn adapter
  "Answers `req` with a long lived response and hands the open channel on."
  [req {:keys [status headers on-open on-close]}]
  (http/as-channel req
                   {:on-open  (fn [ch]
                                (http/send! ch {:status status :headers headers} false)
                                (on-open (wrap ch)))
                    :on-close (fn [_ _] (on-close))}))
