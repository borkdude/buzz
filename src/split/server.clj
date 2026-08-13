(ns split.server
  (:require [babashka.fs :as fs]
            [babashka.nrepl.server :as nrepl]
            [cheshire.core :as json]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [reagami.ssr :as ssr]
            [split.app :as app]
            [split.core :as core]
            [squint.compiler :as squint]))

(def ^:private public (fs/canonicalize "public"))

;; Two endpoints. GET /events is one long-lived SSE stream per browser, POST
;; /rpc is a plain request. Nothing is bidirectional, so there is no upgrade to
;; negotiate and no socket to nurse: EventSource reconnects on its own.
;;
;; session -> {:ch channel :instances {id instance}}. A connection needs its own
;; instances because a handler closes over the arguments its component was
;; called with, and it needs a session id because the RPC arrives on a separate
;; request that has to find them again.
(defonce conns (atom {}))

(defn- event!
  "One SSE frame. JSON escapes newlines inside strings, so a value can never
  break out of its own `data:` line."
  [ch msg]
  (http/send! ch (str "data: " (json/generate-string msg) "\n\n") false))

(defn- open-stream [session ch]
  (http/send! ch {:status 200
                  :headers {"Content-Type" "text/event-stream"
                            "Cache-Control" "no-cache"
                            "X-Accel-Buffering" "no"}}
              false)
  (event! ch ["session" session])
  (let [inst (app/todo-app)]
    (swap! conns assoc session {:ch ch :instances {(:id inst) inst}})
    (event! ch ["mount" (:id inst) "app" ((:slots inst))])))

(defn- events [req]
  (let [session (str (random-uuid))]
    (http/as-channel req {:on-open  (fn [ch] (open-stream session ch))
                          :on-close (fn [_ _] (swap! conns dissoc session))})))

(defn- rpc [req]
  (let [[session handler-id args] (json/parse-string (slurp (:body req)))]
    (if-let [f (some #(get (:handlers %) handler-id)
                     (vals (:instances (get @conns session))))]
      (do (apply f args)
          {:status 204})
      {:status 404 :body "no such handler"})))

;; The reply to an RPC is not the response. It is whatever :patch the write
;; happens to produce, on every stream watching that data.
(defn- broadcast-patch! [_ _ _ _]
  (doseq [{:keys [ch instances]} (vals @conns)
          inst (vals instances)]
    (event! ch ["patch" (:id inst) ((:slots inst))])))

(add-watch app/db ::render broadcast-patch!)
(add-watch app/clicks ::render broadcast-patch!)

;; Re-evaluating a defsplit in a REPL bumps the revision. Rebuild each
;; connection's instance so its handler ids match the new code, then tell the
;; browser to import the components again under a fresh URL.
(defn- reload-all! [_ _ _ rev]
  (doseq [[session {:keys [ch]}] @conns]
    (let [inst (app/todo-app)]
      (swap! conns assoc-in [session :instances] {(:id inst) inst})
      (event! ch ["reload" rev (:id inst) ((:slots inst))]))))

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
;; Clojure in the browser, so the page loads no interpreter at all.
(def ^:private js-headers
  {"Content-Type" "text/javascript"
   ;; compiled per request, so never let a stale copy survive an edit
   "Cache-Control" "no-store"})

(defn- squint-module [f]
  {:status 200
   :headers js-headers
   :body (squint/compile-string (slurp (fs/file public f)))})

;; The components are an ordinary module too. `defsplit` already compiled each
;; one to a JavaScript expression, so this only has to give them their imports
;; and a name. The browser imports the result and never evaluates a string.
(defn- components-module []
  (let [insts [(app/todo-app)]]
    {:status 200
     :headers {"Content-Type" "text/javascript"}
     :body (str "import * as SQ from \"squint-cljs/core.js\";\n"
                "import { rpc_BANG_ } from \"/rpc.mjs\";\n"
                "export const registry = {\n"
                (str/join ",\n" (map #(str "  " (pr-str (:id %)) ": " (:js %)) insts))
                "\n};\n")}))

(def ^:private content-types
  {"html" "text/html" "cljs" "text/plain; charset=utf-8" "css" "text/css"})

;; First paint. The same component renders here, from the same converted form,
;; with handlers blanked — which changes no output, because Reagami's ssr drops
;; every `on*` attribute by name anyway. Reagami adopts these nodes on the
;; browser side instead of rebuilding them, so nothing on that side knows this
;; happened.
;; Nothing here evaluates code the browser was handed, so the page can say so
;; and let the browser hold it to that. Without 'unsafe-eval' a stray `eval` or
;; `new Function` fails loudly instead of quietly working.
;; esm.sh appears in connect-src as well as script-src because devtools fetches
;; source maps through connect-src. It grants nothing new: that origin is
;; already allowed to run code here.
(defn- csp [nonce]
  (str "default-src 'none'; "
       "script-src 'self' https://esm.sh 'nonce-" nonce "'; "
       "style-src 'nonce-" nonce "'; "
       "connect-src 'self' https://esm.sh; "
       "base-uri 'none'"))

(defn- index []
  (let [inst  (app/todo-app)
        html  (ssr/render (into [(:ssr inst)] ((:slots inst))))
        nonce (str (random-uuid))]
    {:status 200
     :headers {"Content-Type" "text/html"
               "Content-Security-Policy" (csp nonce)}
     :body (-> (slurp (fs/file public "index.html"))
               (str/replace "<!--ssr-->" html)
               (str/replace "NONCE" nonce))}))

(defn- serve-file [uri]
  (let [f (fs/canonicalize (fs/path public (subs (if (= "/" uri) "/index.html" uri) 1)))]
    (if (and (fs/starts-with? f public) (fs/regular-file? f))
      {:status 200
       :headers {"Content-Type" (content-types (fs/extension f) "text/plain")}
       :body (fs/read-all-bytes f)}
      {:status 404 :body "not found"})))

(defn handler [req]
  (case (:uri req)
    "/"               (index)
    "/client.mjs"     (squint-module "client.cljs")
    "/rpc.mjs"        (squint-module "rpc.cljs")
    "/components.mjs" (components-module)
    "/events"         (events req)
    "/rpc"            (rpc req)
    (serve-file (:uri req))))

(defn -main [& args]
  (app/seed!)
  (heartbeat!)
  (http/run-server handler {:port 1341})
  (println "http://localhost:1341")
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1667})
    (println "nrepl://localhost:1667"))
  @(promise))
