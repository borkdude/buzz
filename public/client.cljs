;; The runtime. Squint compiles this on the server and serves the result as an
;; ES module. The components arrive the same way, as a module the browser
;; imports, so the page never evaluates anything it was handed at runtime.

(ns client
  (:require ["https://esm.sh/reagami@0.2.40" :as reagami]
            ["/components.mjs" :as components]
            ["/rpc.mjs" :as rpc]))

(def instances (js/Map.))                            ; id -> {:vals ... :draw ...}
(def registry #js {:v (.-registry components)})      ; replaced on reload

(defn- draw! [id]
  (let [entry (.get instances id)
        node (.-node entry)]
    (reagami/render node (.concat #js [(aget (.-v registry) id)] (.-vals entry)))))

(defn- mount! [id el vals]
  (.set instances id #js {:vals vals :node (js/document.getElementById el)})
  (draw! id))

(defn- patch! [id vals]
  (set! (.-vals (.get instances id)) vals)
  (draw! id))

;; Live reload. The query string is what makes the browser fetch the module
;; again instead of handing back the one it already has.
(defn- reload! [rev id vals]
  (-> (js/import (str "/components.mjs?v=" rev))
      (.then (fn [m]
               (set! (.-v registry) (.-registry m))
               (patch! id vals)))))

(defn- handle [[op a b c]]
  (case op
    "session" (rpc/set-session! a)
    "mount"   (mount! a b c)
    "patch"   (patch! a b)
    "reload"  (reload! a b c)
    nil))

;; No reconnect loop: EventSource does that itself.
(def stream
  (doto (js/EventSource. "/events")
    (.addEventListener "message" (fn [e] (handle (js/JSON.parse (.-data e)))))))
