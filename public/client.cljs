;; The runtime. Squint compiles this on the server and serves the result as an
;; ES module, so nothing here is interpreted in the browser.

(ns client
  (:require ["https://esm.sh/reagami@0.2.40" :as reagami]
            ["squint-cljs/core.js" :as SQ]))

(def instances (js/Map.))    ; id -> {:vals ... :draw ...}
(def state #js {:session nil})

(defn rpc!
  "Stands in for a `(server ...)` form the server kept. Fire and forget: the
  answer comes back on the event stream as a :patch, not in this response."
  [id args]
  (js/fetch "/rpc" #js {:method "POST"
                        :body (js/JSON.stringify #js [(.-session state) id args])})
  nil)

(defn- component
  "The server sends a JavaScript expression with `SQ` and `rpc_BANG_` free.
  Supplying them as arguments keeps both out of the global scope."
  [js]
  ((js/Function. "SQ" "rpc_BANG_" (str "return " js)) SQ rpc!))

(defn- mount! [id el js vals]
  (let [f (component js)
        node (js/document.getElementById el)
        entry #js {:vals vals}
        draw (fn [] (reagami/render node (.concat #js [f] (.-vals entry))))]
    (set! (.-draw entry) draw)
    (.set instances id entry)
    (draw)))

(defn- handle [[op a b c d]]
  (case op
    "session" (set! (.-session state) a)
    "mount"   (mount! a b c d)
    "patch"   (let [entry (.get instances a)]
                (set! (.-vals entry) b)
                ((.-draw entry)))
    nil))

;; No reconnect loop: EventSource does that itself.
(def stream
  (doto (js/EventSource. "/events")
    (.addEventListener "message" (fn [e] (handle (js/JSON.parse (.-data e)))))))
