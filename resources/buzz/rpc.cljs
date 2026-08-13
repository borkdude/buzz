;; Its own module so that both the runtime and the generated components can
;; import it without a cycle.

(ns rpc)

(def state #js {:session nil})

(defn set-session! [s]
  (set! (.-session state) s))

(defn rpc!
  "Stands in for a `(server ...)` form the server kept. Fire and forget: the
  answer comes back on the event stream as a patch, not in this response."
  [id args]
  (js/fetch "/rpc" #js {:method "POST"
                        :body (js/JSON.stringify #js [(.-session state) id args])})
  nil)
