;; Its own module so that both the runtime and the generated components can
;; import it without a cycle.

(ns rpc)

(def state #js {:session nil})

(defn set-session! [s]
  (set! (.-session state) s))

(defn ^:async send! [id args]
  (let [res (await (js/fetch "/rpc"
                            #js {:method "POST"
                                 :body (js/JSON.stringify #js [(.-session state) id args])}))]
    (when-not (.-ok res)
      (let [body (await (.catch (.json res) (fn [_] nil)))]
        (throw (js/Error. (or (and body (.-error body))
                              (str "server answered " (.-status res)))))))
    ;; 204 for a plain effect, a body when the handler used `reply`
    (when-not (= 204 (.-status res))
      (await (.json res)))))

(defn rpc!
  "What a `(server! ...)` becomes. Returns a promise: empty for an effect, the
  value for a `reply`, rejected when the server could not do it.

  Resolving means the server accepted the call, not that the page shows it. Any
  change to what is rendered arrives separately, as a patch.

  The failure is reported even when nobody is listening, so a dropped write is
  never silent. Attaching that to a derived promise leaves the original free for
  the caller to catch."
  [id args]
  (let [p (send! id args)]
    (.catch p (fn [e] (js/console.error "buzz:" id "failed -" (.-message e))))
    p))
