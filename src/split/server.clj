(ns split.server
  "A Ring handler for a page built from components. Knows nothing about any
  particular application, and runs no server of its own:

    (def ui
      (handler {:index \"public/index.html\"
                :watch [app/db]                              ; patch everyone on change
                :mounts [{:el \"app\"
                          :state (fn [] {:query (atom \"\")}) ; per connection, optional
                          :component (fn [state] (app/todo-app (:query state)))}]}))

    (defn app [req] (or (ui req) (my-static-files req)))

  Every atom in a mount's `:state` map is watched for that connection alone.
  Atoms in the top level `:watch` are watched for all of them.

  Requests the page does not own return nil, so the application composes."
  (:require [babashka.fs :as fs]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [reagami.ssr :as ssr]
            [split.core :as core]
            [squint.compiler :as squint]))

(defonce ^:private page (atom nil))

;; Two endpoints. GET /events is one long-lived SSE stream per browser, POST
;; /rpc is a plain request. Nothing is bidirectional, so there is no upgrade to
;; negotiate and no socket to nurse: EventSource reconnects on its own.
;;
;; session -> {:ch channel :mounted [{:el :state :instance :spec}]}. A connection
;; needs its own instances because a handler closes over the arguments its
;; component was called with, and it needs a session id because the RPC arrives
;; on a separate request that has to find them again.
(defonce conns (atom {}))

(defn- event!
  "One SSE frame. JSON escapes newlines inside strings, so a value can never
  break out of its own `data:` line."
  [ch msg]
  (http/send! ch (str "data: " (json/generate-string msg) "\n\n") false))

;; A watched atom says something changed somewhere, not that this mount cares.
;; Rather than have each mount declare what it reads, which can drift from what
;; its slots actually do, run the slots and send nothing when the values are the
;; same as last time. Unchanged slots are usually the identical objects, so the
;; comparison stops at the first identity check.
(defn- patch! [ch {:keys [instance sent]}]
  (let [vals ((:slots instance))]
    (when (not= vals @sent)
      (reset! sent vals)
      (event! ch ["patch" (:id instance) vals]))))

(defn- build [{:keys [el state component] :as spec}]
  (let [st (if state (state) {})]
    {:el el :state st :spec spec :sent (atom ::none) :instance (component st)}))

(defn- watch-session! [ch session mount]
  (doseq [a (vals (:state mount))]
    (add-watch a [::render session (:id (:instance mount))]
               (fn [_ _ _ _] (patch! ch mount)))))

(defn- unwatch-session! [session mounted]
  (doseq [m mounted, a (vals (:state m))]
    (remove-watch a [::render session (:id (:instance m))])))

(defn- open-stream [session ch]
  (http/send! ch {:status 200
                  :headers {"Content-Type" "text/event-stream"
                            "Cache-Control" "no-cache"
                            "X-Accel-Buffering" "no"}}
              false)
  (event! ch ["session" session])
  (let [mounted (mapv build (:mounts @page))]
    (swap! conns assoc session {:ch ch :mounted mounted})
    (doseq [{:keys [el instance sent] :as m} mounted]
      (watch-session! ch session m)
      (let [vals ((:slots instance))]
        (reset! sent vals)
        (event! ch ["mount" (:id instance) el vals])))))

(defn- events [req]
  (let [session (str (random-uuid))]
    (http/as-channel req
                     {:on-open  (fn [ch] (open-stream session ch))
                      :on-close (fn [_ _]
                                  (unwatch-session! session (:mounted (get @conns session)))
                                  (swap! conns dissoc session))})))

(defn- rpc [req]
  (let [[session handler-id args] (json/parse-string (slurp (:body req)))]
    (if-let [f (some #(get (:handlers (:instance %)) handler-id)
                     (:mounted (get @conns session)))]
      (do (apply f args)
          {:status 204})
      {:status 404 :body "no such handler"})))

;; The reply to an RPC is not the response. It is whatever :patch the write
;; happens to produce, on every stream watching that data.
(defn- broadcast-patch! [_ _ _ _]
  (doseq [{:keys [ch mounted]} (vals @conns)
          m mounted]
    (patch! ch m)))

;; Re-evaluating a defui in a REPL bumps the revision. Rebuild each
;; connection's instances so their handler ids match the new code, then tell the
;; browser to import the components again under a fresh URL. Per-connection
;; state is kept, so a reload does not clear what someone had typed.
(defn- reload-all! [_ _ _ rev]
  (doseq [[session {:keys [ch mounted]}] @conns]
    (let [rebuilt (mapv (fn [m] (assoc m :instance ((:component (:spec m)) (:state m)))) mounted)]
      (swap! conns assoc-in [session :mounted] rebuilt)
      ;; the slots may have changed shape, so this one always goes out
      (doseq [{:keys [instance sent]} rebuilt]
        (let [vals ((:slots instance))]
          (reset! sent vals)
          (event! ch ["reload" rev (:id instance) vals]))))))

(add-watch core/revision ::reload reload-all!)

;; Idle streams get dropped by proxies. A comment frame is ignored by
;; EventSource and keeps the connection accounted for.
(defn- heartbeat! []
  (future
    (loop []
      (Thread/sleep 25000)
      (doseq [{:keys [ch]} (vals @conns)]
        (http/send! ch ": ping\n\n" false))
      (recur))))

;; The runtime is written in Clojure and compiled here. Nothing interprets
;; Clojure in the browser, so the page loads no interpreter at all. These two
;; ship with the library rather than with the application.
(def ^:private js-headers
  {"Content-Type" "text/javascript"
   ;; compiled per request, so never let a stale copy survive an edit
   "Cache-Control" "no-store"})

(defn- runtime-module [n]
  {:status 200
   :headers js-headers
   :body (squint/compile-string (slurp (io/resource (str "split/" n))))})

;; The components are an ordinary module too. `defui` already compiled each
;; one to a JavaScript expression, so this only has to give them their imports
;; and a name. The browser imports the result and never evaluates a string.
(defn- components-module []
  ;; Only :js is read, and that does not depend on the arguments, so these
  ;; instances are thrown away.
  (let [insts (map (comp :instance build) (:mounts @page))]
    {:status 200
     :headers js-headers
     :body (str "import * as SQ from \"squint-cljs/core.js\";\n"
                "import { rpc_BANG_ } from \"/rpc.mjs\";\n"
                "export const registry = {\n"
                (str/join ",\n" (map #(str "  " (pr-str (:id %)) ": " (:js %)) insts))
                "\n};\n")}))

;; Nothing here evaluates code the browser was handed, so the page can say so
;; and let the browser hold it to that. Without 'unsafe-eval' a stray `eval` or
;; `new Function` fails loudly instead of quietly working. esm.sh appears in
;; connect-src as well as script-src because devtools fetches source maps
;; through connect-src, which grants nothing new.
(defn- csp [nonce]
  (str "default-src 'none'; "
       "script-src 'self' https://esm.sh 'nonce-" nonce "'; "
       "style-src 'nonce-" nonce "'; "
       "connect-src 'self' https://esm.sh; "
       "media-src 'self'; "
       "img-src 'self' data:; "
       "base-uri 'none'"))

;; First paint. Each component renders here from the same converted form, with
;; handlers blanked, which changes no output because Reagami's ssr drops every
;; `on*` attribute by name anyway. Reagami adopts these nodes in the browser
;; instead of rebuilding them. There is no connection yet, so a mount's `:state`
;; starts empty for this render.
(defn- index []
  (let [nonce (str (random-uuid))
        html  (reduce (fn [page-html {:keys [el] :as spec}]
                        (let [inst (:instance (build spec))]
                          (str/replace page-html
                                       (str "<!--" el "-->")
                                       (ssr/render (into [(:ssr inst)] ((:slots inst)))))))
                      (slurp (fs/file (:index @page)))
                      (:mounts @page))]
    {:status 200
     :headers {"Content-Type" "text/html"
               "Content-Security-Policy" (csp nonce)}
     :body (str/replace html "NONCE" nonce)}))

(defn handler
  "Returns a Ring handler for the page described by `spec`. Requests it does not
  own get nil, so an application can compose it with whatever else it serves and
  run whichever server it likes.

  Installs watches and starts the heartbeat as a side effect of being called."
  [{:keys [watch] :as spec}]
  (reset! page (merge {:index "public/index.html"} spec))
  (doseq [a watch]
    (add-watch a ::render broadcast-patch!))
  (heartbeat!)
  (fn [req]
    (case (:uri req)
      "/"               (index)
      "/client.mjs"     (runtime-module "client.cljs")
      "/rpc.mjs"        (runtime-module "rpc.cljs")
      "/components.mjs" (components-module)
      "/events"         (events req)
      "/rpc"            (rpc req)
      nil)))
