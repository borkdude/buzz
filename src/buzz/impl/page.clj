(ns buzz.impl.page
  "Ring page implementation. The public API is exposed through `buzz.core`."
  (:require [babashka.fs :as fs]
            [buzz.impl.hub :as hub]
            [buzz.impl.parts :as parts]
            [buzz.stream :as stream]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reagami.ssr :as ssr]
            [squint.compiler :as squint]))

;; Each handler registers {:registry :index :spec :mark!} with the hub.

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

;; Every connection holds the broadcast topic, which is what a `:watch` atom
;; marks. Everything else it holds comes from what its slots read.
(def ^:private base-topics #{hub/all})

;; Run one connection's mounts with read tracking on, then replace the topics
;; it holds with everything `observe` read. A mount that throws is contained to
;; its own frame, and a session that saw a failure keeps the topics it had
;; rather than reconciling against a partial read set.
(defn- render-session! [{:keys [index]} session {:keys [ch mounted]} render!]
  (let [ok (volatile! true)
        [_ reads] (hub/with-reads
                    (doseq [m mounted]
                      (try (render! ch m)
                           (catch Throwable e
                             (vreset! ok false)
                             (println "buzz: render failed for" session "-" (ex-message e))))))]
    (when @ok
      (hub/set-topics! index session (into base-topics reads)))))

(defn- open-stream [{:keys [registry] :as entry} session ch req mounts token]
  ;; Register the session before sending its ID.
  (let [mounted (mapv #(build % req) mounts)
        conn    {:ch ch :mounted mounted :owner token :req req}]
    (swap! registry assoc session conn)
    (event! ch ["session" session])
    (render-session! entry session conn
                     (fn [ch {:keys [el instance sent] :as m}]
                       (let [vals (slot-vals m)]
                         (reset! sent vals)
                         (event! ch ["mount" (:id instance) el vals]))))))

(defn- events [{:keys [registry index] :as entry} adapter req mounts on-close]
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
              :on-open  (fn [ch] (open-stream entry session ch req mounts token))
              :on-close (fn []
                          (swap! registry dissoc session)
                          (hub/drop-session! index session)
                          (when on-close (on-close req)))})))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; Require the RPC header, a known session, its browser token, and a registered
;; handler.
(defn- rpc [{:keys [registry]} req]
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

;; Patch only the connections of this handler that hold one of the invalidated
;; topics. Idle connections are never looked at. A slot can be per-connection,
;; so one connection's render may throw while the others are fine: the failure
;; is contained to that connection's frame. Its `sent` state is untouched, so
;; the next healthy render sends the latest state.
(defn- broadcast-patch! [{:keys [registry index] :as entry}]
  (fn [topics]
    (let [conns @registry]
      (doseq [session (hub/sessions-for index topics)
              :let [conn (get conns session)]
              :when conn]
        (render-session! entry session conn patch!)))))

;; Runs `render` at most once per `interval-ms`. The first invalidation renders
;; immediately, ones landing inside the window join the dirty set and the
;; follow-up renders them, so the last state always goes out and intermediate
;; states collapse. The set is swapped out rather than cleared after rendering,
;; so a topic arriving mid render is carried to the next tick instead of being
;; lost. Rendering happens on the scheduler thread, so a write returns without
;; paying for any connection's render. Idle costs nothing: no writes, no
;; wake-ups.
(defn- coalesced [render ^long interval-ms]
  (let [dirty (atom #{})
        active (atom false)
        tick (fn tick []
               (let [[topics _] (reset-vals! dirty #{})]
                 (try (render topics)
                      (catch Throwable e
                        (println "buzz: render failed -" (ex-message e)))))
               (hub/schedule!
                interval-ms
                (fn follow-up []
                  (if (seq @dirty)
                    (tick)
                    (do (reset! active false)
                        ;; a write can land between the check and the flag
                        ;; flip; re-arm rather than lose it
                        (when (and (seq @dirty)
                                   (compare-and-set! active false true))
                          (tick)))))))]
    (fn [topics]
      (swap! dirty into topics)
      (when (compare-and-set! active false true)
        (.submit ^java.util.concurrent.ExecutorService @hub/scheduler ^Runnable tick)))))

;; Rebuild instances and reload open pages after definitions change.
(defn- reload-all! [_ _ _ rev]
  (doseq [{:keys [registry] :as entry} (hub/entries)
          [session conn] @registry]
    (let [rebuilt (mapv (fn [m] (assoc m :instance ((::instance (:spec m))))) (:mounted conn))]
      (swap! registry assoc-in [session :mounted] rebuilt)
      ;; the slots may have changed shape, so this one always goes out
      (render-session! entry session (assoc conn :mounted rebuilt)
                       (fn [ch {:keys [instance sent] :as m}]
                         (let [vals (slot-vals m)]
                           (reset! sent vals)
                           (event! ch ["reload" rev (:id instance) vals])))))))

;; Keep idle EventSource connections open through proxies.
(defonce ^:private heartbeat
  (delay
    (future
      (loop []
        (Thread/sleep 25000)
        (doseq [{:keys [registry]} (hub/entries)
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
        index    (atom {:by-topic {} :by-session {}})
        ;; Async by default: invalidations within the window collapse into one
        ;; render of the connections holding them. `:render-interval-ms 0`
        ;; renders synchronously on the invalidating thread instead.
        interval (or (:render-interval-ms spec) 20)
        render   (-> (broadcast-patch! {:registry registry :index index :spec spec})
                     (cond-> (pos? interval) (coalesced interval)))
        entry    (hub/register-handler!
                  {:registry registry :index index :spec spec :mark! render})
        _      (doseq [a watch]
                 (add-watch a [::render registry] (fn [_ _ _ _] (render #{hub/all}))))
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
          :events     (events entry adapter req mounts (:on-close spec))
          :rpc        (rpc entry req)
          nil))
      {:buzz.core/registry registry})))

(add-watch parts/revision ::reload reload-all!)
