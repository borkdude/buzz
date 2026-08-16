(ns buzz.httpkit
  "http-kit adapter for `buzz.stream`."
  (:require [buzz.stream :as stream]
            [org.httpkit.server :as http]))

(defn- wrap [ch]
  (reify stream/Channel
    (send! [_ s] (http/send! ch s false))
    (close! [_] (http/close ch))))

(defn adapter
  "Opens an http-kit streaming response."
  [req {:keys [status headers on-open on-close]}]
  (http/as-channel req
                   {:on-open  (fn [ch]
                                (http/send! ch {:status status :headers headers} false)
                                (on-open (wrap ch)))
                    :on-close (fn [_ _] (on-close))}))
