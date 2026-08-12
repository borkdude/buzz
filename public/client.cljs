;; The runtime. Loaded last on purpose: the namespace this file ends in is the
;; one the server's component gets evaluated in, which is how it sees `rpc!`
;; without a require.

(ns user
  (:require [clojure.edn :as edn]
            [reagami.core :as reagami]))

(defonce instances (atom {}))    ; id -> atom of the current slot values
(defonce session (atom nil))     ; names our stream when we POST to /rpc

(defn rpc!
  "Stands in for a `(server ...)` form the server kept. Fire and forget: the
  answer comes back on the event stream as a :patch, not in this response."
  [id args]
  (js/fetch "/rpc" #js {:method "POST" :body (pr-str [@session id args])})
  nil)

;; What the last render did. On the first one these should be adopted nodes
;; rather than created ones: the server already rendered this tree with
;; reagami.ssr, and Reagami takes the existing DOM over instead of rebuilding.
(defonce last-render (atom nil))

(defn- mount! [id el src vals]
  (let [f    (js/scittle.core.eval_string src)
        a    (atom vals)
        node (js/document.getElementById el)
        draw #(reset! last-render (reagami/render node (into [f] @a)))]
    (add-watch a ::draw (fn [_ _ _ _] (draw)))
    (swap! instances assoc id a)
    (draw)))

(defn- handle [[op a b c d]]
  (case op
    :session (reset! session a)
    :mount   (mount! a b c d)
    :patch   (reset! (@instances a) b)))

;; No reconnect loop: EventSource does that itself. A reconnect gets a fresh
;; session and :mount, so the page rebuilds without a reload.
(defonce stream
  (doto (js/EventSource. "/events")
    (.addEventListener "message" #(handle (edn/read-string (.-data %))))))
