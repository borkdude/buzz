(ns buzz.impl.page
  "Ring page implementation. The public API is exposed through `buzz.core`."
  (:require [babashka.fs :as fs]
            [buzz.impl.parts :as parts]
            [buzz.stream :as stream]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reagami.ssr :as ssr]
            [squint.compiler :as squint]))

(defonce ^:private registries (atom #{}))

;; Bind RPC sessions to an HttpOnly browser cookie. SameSite=Lax permits
;; top-level navigation but withholds the cookie from cross-site POSTs.
(def ^:private token-cookie "buzz-browser")

;; Built once. Every rpc reads the cookies, and compiling this per call cost
;; about as much as parsing the request body.
(def ^:private token-re
  (re-pattern (str "(?:^|;\\s*)" token-cookie "=([^;]+)")))

(defn- browser-token [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find token-re)
           second))

(defn- token-headers
  [token]
  {"Set-Cookie" (str token-cookie "=" token "; Path=/; HttpOnly; SameSite=Lax")})

(defn connection
  "Returns the connection ID in `req`, or nil before the stream opens."
  [req]
  (:buzz.core/connection req))

(defn token
  "Returns the Buzz browser token in `req`."
  [req]
  (browser-token req))

(defn- event!
  "Writes one JSON-encoded SSE frame."
  [ch msg]
  (stream/send! ch (str "data: " (json/generate-string msg) "\n\n")))

;; Recompute slots after a watched change and suppress unchanged patches.
(defn- slot-vals
  "Returns the current slot values for one mount."
  [{:keys [instance req]}]
  (if (:request instance) ((:slots instance) req) ((:slots instance))))

(defn- patch! [ch {:keys [instance sent] :as mount}]
  (let [vals (slot-vals mount)]
    (when (not= vals @sent)
      (reset! sent vals)
      (event! ch ["patch" (:id instance) vals]))))

;; Cache one component instance per revision.
(defn- shared-instance [ui]
  (let [cache (atom nil)]
    (fn []
      (let [rev @parts/revision c @cache]
        (if (and c (= rev (:rev c)))
          (:inst c)
          (:inst (reset! cache {:rev rev :inst (if (var? ui) ((deref ui)) (ui))})))))))

(defn- build [{:keys [el] :as spec} req]
  {:el el :spec spec :sent (atom ::none) :req req
   :instance ((::instance spec))})

(defn- open-stream [registry session ch req mounts token]
  ;; Register the session before sending its ID.
  (let [mounted (mapv #(build % req) mounts)]
    (swap! registry assoc session {:ch ch :mounted mounted :owner token})
    (event! ch ["session" session])
    (doseq [{:keys [el instance sent] :as m} mounted]
      (let [vals (slot-vals m)]
        (reset! sent vals)
        (event! ch ["mount" (:id instance) el vals])))))

(defn- events [registry adapter req mounts on-close]
  (let [session (str (random-uuid))
        held    (browser-token req)
        token   (or held (str (random-uuid)))
        ;; Reuse the opening request for slots and :on-close.
        req     (assoc req :buzz.core/connection session)]
    (adapter req
             {:status 200
              :headers (cond-> {"Content-Type" "text/event-stream"
                                "Cache-Control" "no-cache"
                                "X-Accel-Buffering" "no"}
                         (nil? held) (merge (token-headers token)))
              :on-open  (fn [ch] (open-stream registry session ch req mounts token))
              :on-close (fn []
                          (swap! registry dissoc session)
                          (when on-close (on-close req)))})))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; Require the RPC header, a known session, its browser token, and a registered
;; handler.
(defn- rpc [registry req]
  (let [[session handler-id args] (json/parse-string (slurp (:body req)))
        conn (get @registry session)]
    (if-let [h (and (get-in req [:headers "x-buzz-rpc"])
                    conn
                    (= (:owner conn) (browser-token req))
                    (some #(get (:handlers (:instance %)) handler-id)
                          (:mounted conn)))]
      (try
        (let [v (apply (:fn h) (if (:request h)
                                 (cons (assoc req :buzz.core/connection session) args)
                                 args))]
          (case (:reply h)
            ;; `(reply v resp)`, so the handler answered with both
            :response (let [[value resp] v]
                        (-> (json-response 200 value)
                            (update :headers merge (:headers resp))
                            (merge (dissoc resp :headers))))
            true      (json-response 200 v)
            {:status 204}))
        (catch Exception e
          (println "buzz:" handler-id "failed on" (pr-str args) "-" (ex-message e))
          (json-response 500 {:error "handler failed"})))
      (json-response 404 {:error "no such handler"}))))

;; Patch only connections owned by this handler.
(defn- broadcast-patch! [registry]
  (fn [_ _ _ _]
    (doseq [{:keys [ch mounted]} (vals @registry)
            m mounted]
      (patch! ch m))))

;; Rebuild instances and reload open pages after definitions change.
(defn- reload-all! [_ _ _ rev]
  (doseq [registry @registries
          [session {:keys [ch mounted]}] @registry]
    (let [rebuilt (mapv (fn [m] (assoc m :instance ((::instance (:spec m))))) mounted)]
      (swap! registry assoc-in [session :mounted] rebuilt)
      ;; the slots may have changed shape, so this one always goes out
      (doseq [{:keys [instance sent] :as m} rebuilt]
        (let [vals (slot-vals m)]
          (reset! sent vals)
          (event! ch ["reload" rev (:id instance) vals]))))))

;; Keep idle EventSource connections open through proxies.
(defonce ^:private heartbeat
  (delay
    (future
      (loop []
        (Thread/sleep 25000)
        (doseq [registry @registries
                {:keys [ch]} (vals @registry)]
          (stream/send! ch ": ping\n\n"))
        (recur)))))

(def ^:private js-headers
  {"Content-Type" "text/javascript"
   ;; compiled per request, so never let a stale copy survive an edit
   "Cache-Control" "no-store"})

;; Rewrite runtime routes for a handler path. Match longer paths first.
(defn- at-path [src path]
  (if (str/blank? path)
    src
    (reduce (fn [s u] (str/replace s (str \" u) (str \" path u)))
            src
            ["/components.mjs" "/rpc.mjs" "/events" "/rpc"])))

(defn- runtime-module [n path]
  {:status 200
   :headers js-headers
   :body (squint/compile-string (at-path (slurp (io/resource (str "buzz/" n))) path))})

(defn- components-module [mounts path]
  (let [insts (map #((::instance %)) mounts)
        ;; Resolve parts per request so edits do not require recompiling callers.
        parts (parts/parts-closure (mapcat :parts insts))]
    {:status 200
     :headers js-headers
     :body (str "import * as SQ from \"squint-cljs/core.js\";\n"
                "import { rpc_BANG_ } from \"" path "/rpc.mjs\";\n"
                (str/join (for [[sym m] parts]
                            (str "const " (parts/js-name sym) " = " (:buzz/js m) ";\n")))
                "export const registry = {\n"
                (str/join ",\n"
                          (map #(str "  " (pr-str (:id %)) ": {f: " (:js %)
                                     ", init: " (:init %)
                                     ", nlocals: " (:locals % 0) "}")
                               insts))
                "\n};\n")}))

;; Disallow eval. Permit esm.sh scripts and source maps.
(defn- csp [nonce]
  (str "default-src 'none'; "
       "script-src 'self' https://esm.sh 'nonce-" nonce "'; "
       "style-src 'nonce-" nonce "'; "
       "connect-src 'self' https://esm.sh; "
       "media-src 'self'; "
       "img-src 'self' data:; "
       "base-uri 'none'"))

;; Render the initial HTML before a connection ID exists.
(defn- first-paint [spec req]
  (let [mount (build spec req)
        inst  (:instance mount)
        ;; Initialize browser-local atoms with nil during server rendering.
        locals (repeatedly (:locals inst 0) #(atom nil))]
    (ssr/render (into [(:ssr inst)] (concat (slot-vals mount) locals)))))

(def ^:private squint-core "https://esm.sh/squint-cljs@0.14.208/core.js")

(defn- scripts [nonce path]
  (str "<script type=\"importmap\" nonce=\"" nonce "\">\n"
       "{\"imports\": {\"squint-cljs/core.js\": \"" squint-core "\"}}\n"
       "</script>\n"
       "<script type=\"module\" src=\"" path "/client.mjs\"></script>\n"))

(defn- escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- generated-page [nonce req {:keys [title head mounts path]}]
  (str "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
       "<title>" (escape (or title "buzz")) "</title>\n"
       head
       "</head>\n<body>\n"
       (str/join (for [{:keys [el] :as mount} mounts]
                   (str "<div id=\"" (escape el) "\">" (first-paint mount req) "</div>\n")))
       (scripts nonce (or path ""))
       "</body>\n</html>\n"))

(defn- rendered-page [nonce req {:keys [index mounts]}]
  (-> (reduce (fn [html {:keys [el] :as mount}]
                (str/replace html (str "<!--" el "-->") (first-paint mount req)))
              (slurp (fs/file index))
              mounts)
      (str/replace "NONCE" nonce)))

;; Set the browser token before concurrent tabs open their streams.
(defn- index-page [req spec]
  (let [nonce (str (random-uuid))]
    {:status 200
     :headers (cond-> {"Content-Type" "text/html"
                       "Content-Security-Policy" (csp nonce)}
                (nil? (browser-token req))
                (merge (token-headers (str (random-uuid)))))
     :body (if (:index spec) (rendered-page nonce req spec) (generated-page nonce req spec))}))

(defn handler
  "Returns a Ring handler for one page. Unknown routes return nil. `:path`
  prefixes all page routes. `:adapter` provides the event stream and defaults
  to http-kit. Calling this function installs watches and starts the heartbeat."
  [{:keys [watch mounts path] :as spec}]
  (doseq [m mounts]
    (when (or (:state m) (:component m))
      (throw (ex-info (str ":state and :component are no longer supported. "
                           "Use :ui and derive state from (request).")
                      {:mount (select-keys m [:el])})))
    (when-not (:ui m)
      (throw (ex-info "Mount requires :ui with a component var or thunk." {:mount m}))))
  @heartbeat
  (let [adapter  (or (:adapter spec)
                     ;; Load the default adapter only when needed.
                     @(requiring-resolve 'buzz.httpkit/adapter))
        registry (atom {})
        _      (swap! registries conj registry)
        _      (doseq [a watch]
                 (add-watch a [::render registry] (broadcast-patch! registry)))
        mounts (mapv (fn [m] (assoc m ::instance (shared-instance (:ui m)))) mounts)
        spec   (assoc spec :mounts mounts)
        path   (or path "")
        routes (cond-> {(str path "/")               :page
                        (str path "/client.mjs")     :client
                        (str path "/rpc.mjs")        :rpc-module
                        (str path "/components.mjs") :components
                        (str path "/events")         :events
                        (str path "/rpc")            :rpc}
                 ;; /admin and /admin/ are the same page
                 (seq path) (assoc path :page))]
    (with-meta
      (fn [req]
        (case (routes (:uri req))
          :page       (index-page req spec)
          :client     (runtime-module "client.cljs" path)
          :rpc-module (runtime-module "rpc.cljs" path)
          :components (components-module mounts path)
          :events     (events registry adapter req mounts (:on-close spec))
          :rpc        (rpc registry req)
          nil))
      {:buzz.core/registry registry})))

(add-watch parts/revision ::reload reload-all!)
