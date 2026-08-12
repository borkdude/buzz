(ns split.server
  (:require [babashka.fs :as fs]
            [clojure.edn :as edn]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [reagami.ssr :as ssr]
            [split.app :as app]))

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
  "One SSE frame. `pr-str` escapes newlines inside strings, so a value can never
  break out of its own `data:` line."
  [ch msg]
  (http/send! ch (str "data: " (pr-str msg) "\n\n") false))

(defn- open-stream [session ch]
  (http/send! ch {:status 200
                  :headers {"Content-Type" "text/event-stream"
                            "Cache-Control" "no-cache"
                            "X-Accel-Buffering" "no"}}
              false)
  (event! ch [:session session])
  (let [inst (app/todo-app)]
    (swap! conns assoc session {:ch ch :instances {(:id inst) inst}})
    (event! ch [:mount (:id inst) "app" (:src inst) ((:slots inst))])))

(defn- events [req]
  (let [session (str (random-uuid))]
    (http/as-channel req {:on-open  (fn [ch] (open-stream session ch))
                          :on-close (fn [_ _] (swap! conns dissoc session))})))

(defn- rpc [req]
  (let [[session handler-id args] (edn/read-string (slurp (:body req)))]
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
    (event! ch [:patch (:id inst) ((:slots inst))])))

(add-watch app/db ::render broadcast-patch!)
(add-watch app/clicks ::render broadcast-patch!)

;; Idle streams get dropped by proxies. A comment frame is ignored by
;; EventSource and keeps the connection accounted for.
(defn- heartbeat! []
  (future
    (loop []
      (Thread/sleep 25000)
      (doseq [{:keys [ch]} (vals @conns)]
        (http/send! ch ": ping\n\n" false))
      (recur))))

(def ^:private content-types
  {"html" "text/html" "cljs" "text/plain; charset=utf-8" "css" "text/css"})

;; First paint. The same component renders here, from the same converted form,
;; with handlers blanked — which changes no output, because Reagami's ssr drops
;; every `on*` attribute by name anyway. Reagami adopts these nodes on the
;; browser side instead of rebuilding them, so nothing on that side knows this
;; happened.
(defn- index []
  (let [inst (app/todo-app)
        html (ssr/render (into [(:ssr inst)] ((:slots inst))))]
    {:status 200
     :headers {"Content-Type" "text/html"}
     :body (str/replace (slurp (fs/file public "index.html"))
                        "<!--ssr-->" html)}))

(defn- serve-file [uri]
  (let [f (fs/canonicalize (fs/path public (subs (if (= "/" uri) "/index.html" uri) 1)))]
    (if (and (fs/starts-with? f public) (fs/regular-file? f))
      {:status 200
       :headers {"Content-Type" (content-types (fs/extension f) "text/plain")}
       :body (fs/read-all-bytes f)}
      {:status 404 :body "not found"})))

(defn handler [req]
  (case (:uri req)
    "/"       (index)
    "/events" (events req)
    "/rpc"    (rpc req)
    (serve-file (:uri req))))

(defn -main [& _]
  (app/seed!)
  (heartbeat!)
  (http/run-server handler {:port 1341})
  (println "http://localhost:1341")
  @(promise))
