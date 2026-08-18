;; The runtime. Squint compiles this on the server and serves the result as an
;; ES module. The components arrive the same way, as a module the browser
;; imports, so the page never evaluates anything it was handed at runtime.

(ns client
  (:require ["https://esm.sh/reagami@0.2.41" :as reagami]
            ["/components.mjs" :as components]
            ["/rpc.mjs" :as rpc]))

(def instances (js/Map.))                            ; id -> {:vals ... :draw ...}
(def registry #js {:v (.-registry components)})      ; replaced on reload

(defn- component [id] (aget (.-v registry) id))

;; Server slots come first, then the browser's own. A patch replaces only the
;; server half, so browser state survives one.
(defn- draw! [id]
  (let [entry (.get instances id)]
    (reagami/render (.-node entry)
                    (.concat #js [(.-f (component id))] (.-vals entry) (.-locals entry)))))

;; `(local-state init)` becomes an atom here. Watching it is what makes setting
;; one redraw without asking the server anything.
;;
;; A reconnect or a live reload mounts again. Browser state belongs to the
;; browser, so it is kept when the component still has the same number of
;; slots, and started over when the shape changed under it.
(defn- locals-for [id prev vals]
  (let [c (component id)]
    (if (and prev (= (count (.-locals prev)) (.-nlocals c)))
      (.-locals prev)
      (mapv atom (.apply (.-init c) nil vals)))))

(defn- watch-locals! [id locals]
  (doseq [a locals]
    (add-watch a ::draw (fn [_ _ _ _] (draw! id)))))

(defn- mount! [id el vals]
  (let [locals (locals-for id (.get instances id) vals)]
    (.set instances id #js {:vals vals :locals locals :node (js/document.getElementById el)})
    (watch-locals! id locals)
    (draw! id)))

;; A patch can arrive for a component whose mount frame is still on its way:
;; renders and stream opening run on different server threads. The mount that
;; follows carries the full current state, so an early patch can be dropped.
(defn- patch! [id vals]
  (when-let [entry (.get instances id)]
    (set! (.-vals entry) vals)
    (draw! id)))

;; Live reload. The query string is what makes the browser fetch the module
;; again instead of handing back the one it already has.
(defn- reload! [rev id vals]
  (-> (js/import (str "/components.mjs?v=" rev))
      (.then (fn [m]
               (set! (.-v registry) (.-registry m))
               (let [entry  (.get instances id)
                     locals (locals-for id entry vals)]
                 (set! (.-locals entry) locals)
                 (watch-locals! id locals)
                 (patch! id vals))))))

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
