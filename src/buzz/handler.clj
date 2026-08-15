(ns buzz.handler
  "A Ring handler for a page built from components. Knows nothing about any
  particular application, and runs no server of its own:

    (def ui
      (handler {:title \"todos\"
                :watch [app/db]                              ; patch everyone on change
                :mounts [{:el \"app\"
                          :state (fn [req] {:query (atom \"\")}) ; per connection, optional
                          :component (fn [state] (app/todo-app (:query state)))}]}))

    (defn app [req] (or (ui req) (my-static-files req)))

  Without an `:index` the page is written here, from `:title`, `:head` and one
  div per mount. Give `:index` a file instead to write your own, and mark each
  mount with an `<!--el-->` comment for the first paint and the script tags with
  `nonce=\"NONCE\"`.

  Every atom in a mount's `:state` map is watched for that connection alone.
  Atoms in the top level `:watch` are watched for all of them.

  A mount's `:state` is given the request that opened the connection, which is
  where an identity comes from. Buzz authenticates nobody: what a `:state` fn
  makes of a request, and whether the handler is wrapped in anything, is the
  application's business.

  It is called once for the first paint and again when the connection opens, so
  read the request rather than act on it.

  Requests the page does not own return nil, so the application composes."
  (:require [babashka.fs :as fs]
            [buzz.core :as core]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [reagami.ssr :as ssr]
            [squint.compiler :as squint]))

;; Two endpoints. GET /events is one long-lived SSE stream per browser, POST
;; /rpc is a plain request. Nothing is bidirectional, so there is no upgrade to
;; negotiate and no socket to nurse: EventSource reconnects on its own.
;;
;; session -> {:ch channel :owner browser token :page the handler that built it
;;             :mounted [{:el :state :instance :spec}]}. A connection needs its
;; own instances because a handler closes over the arguments its component was
;; called with, and it needs a session id because the RPC arrives on a separate
;; request that has to find them again.
(defonce conns (atom {}))

;; A session id arrives in the rpc body, so on its own it lets anyone who learns
;; one act as the connection it belongs to, whatever they signed in as. Buzz
;; gives a browser a token of its own and wants it back with every rpc.
;;
;; Its own, rather than whatever the application already sets, because those
;; cookies change for reasons that have nothing to do with a connection and a
;; stream that quietly stopped answering when an unrelated one was written would
;; be very hard to explain.
;;
;; The browser rather than the connection, because a cookie belongs to an origin
;; and two tabs of one page would otherwise take the token away from each other.
;;
;; Lax rather than Strict, because Strict is withheld on a cross site
;; navigation, so arriving from a link elsewhere would look like a browser with
;; no token and mint a second one over the first. Lax is still withheld from a
;; cross site post, which is the half that guards the rpc.
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
  "What gives a browser a token. HttpOnly, so nothing on the page can read it
  and carry it elsewhere."
  [token]
  {"Set-Cookie" (str token-cookie "=" token "; Path=/; HttpOnly; SameSite=Lax")})

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

;; The request is what a connection knows about who opened it. Handing it to
;; `:state` is the only place identity can enter, since a handler is a closure
;; over the state its component was built with and never sees a request itself.
(defn- build [{:keys [el state component] :as spec} req]
  (let [st (if state (state req) {})]
    {:el el :state st :spec spec :sent (atom ::none) :instance (component st)}))

;; What a connection owns is not all atoms. An identity read from the request is
;; a plain value, and there is nothing to watch about it.
(defn- refs [state]
  (filter #(instance? clojure.lang.IRef %) (vals state)))

(defn- watch-session! [ch session mount]
  (doseq [a (refs (:state mount))]
    (add-watch a [::render session (:id (:instance mount))]
               (fn [_ _ _ _] (patch! ch mount)))))

(defn- unwatch-session! [session mounted]
  (doseq [m mounted, a (refs (:state m))]
    (remove-watch a [::render session (:id (:instance m))])))

(defn- open-stream [session ch req mounts page]
  (let [held  (browser-token req)
        token (or held (str (random-uuid)))]
    (http/send! ch {:status 200
                    :headers (cond-> {"Content-Type" "text/event-stream"
                                      "Cache-Control" "no-cache"
                                      "X-Accel-Buffering" "no"}
                               (nil? held) (merge (token-headers token)))}
                false)
    ;; The session id is what an RPC arrives with, so it goes out only once there
    ;; is something here to find under it. Building the mounts renders every
    ;; component, which is long enough for a browser to have answered.
    (let [mounted (mapv #(build % req) mounts)]
      (swap! conns assoc session {:ch ch :mounted mounted :owner token :page page})
      (event! ch ["session" session])
      (doseq [{:keys [el instance sent] :as m} mounted]
        (watch-session! ch session m)
        (let [vals ((:slots instance))]
          (reset! sent vals)
          (event! ch ["mount" (:id instance) el vals]))))))

(defn- events [req mounts page]
  (let [session (str (random-uuid))]
    (http/as-channel req
                     {:on-open  (fn [ch] (open-stream session ch req mounts page))
                      :on-close (fn [_ _]
                                  (unwatch-session! session (:mounted (get @conns session)))
                                  (swap! conns dissoc session))})))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; A handler that threw used to escape into http-kit, so the browser saw nothing
;; and the log said nothing about which handler it was. Now it answers and the
;; browser's promise rejects. The detail stays here: an exception message can
;; carry more than a browser should be told.
;;
;; Three things have to agree before a handler runs. The header, because a
;; request that carries one is not a form a page elsewhere can post: it needs a
;; preflight, and Buzz answers none. Same site is not the same as same origin,
;; so a neighbouring subdomain would otherwise be allowed to try. The page,
;; because `conns` is one map for every handler in the process and an id from
;; one page would otherwise be answered by another one's endpoint. The token,
;; because a session id on its own says nothing about who is asking.
(defn- rpc [req page]
  (let [[session handler-id args] (json/parse-string (slurp (:body req)))
        conn (get @conns session)]
    (if-let [h (and (get-in req [:headers "x-buzz-rpc"])
                    (= (:page conn) page)
                    (= (:owner conn) (browser-token req))
                    (some #(get (:handlers (:instance %)) handler-id)
                          (:mounted conn)))]
      (try
        (let [v (apply (:fn h) args)]
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
      ;; A watch closes over the mount it was installed with, so the old ones
      ;; still hold the old instance and would patch with the slots of code the
      ;; browser has stopped running.
      (unwatch-session! session mounted)
      ;; the slots may have changed shape, so this one always goes out
      (doseq [{:keys [instance sent] :as m} rebuilt]
        (watch-session! ch session m)
        (let [vals ((:slots instance))]
          (reset! sent vals)
          (event! ch ["reload" rev (:id instance) vals]))))))

(add-watch core/revision ::reload reload-all!)

;; Idle streams get dropped by proxies. A comment frame is ignored by
;; EventSource and keeps the connection accounted for.
;; One for the whole process rather than one per page, since it walks every
;; connection there is.
(defonce ^:private heartbeat
  (delay
    (future
      (loop []
        (Thread/sleep 25000)
        (doseq [{:keys [ch]} (vals @conns)]
          (http/send! ch ": ping\n\n" false))
        (recur)))))

;; The runtime is written in Clojure and compiled here. Nothing interprets
;; Clojure in the browser, so the page loads no interpreter at all. These two
;; ship with the library rather than with the application.
(def ^:private js-headers
  {"Content-Type" "text/javascript"
   ;; compiled per request, so never let a stale copy survive an edit
   "Cache-Control" "no-store"})

;; A handler can be mounted under a path, and the browser has to ask the same
;; handler for its stream and its modules rather than whichever one owns the
;; root. The runtime is compiled per request anyway, so the path goes into it
;; instead of being smuggled through a global on the page. Longest first, or
;; "/rpc" would eat the start of "/rpc.mjs".
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

;; The components are an ordinary module too. `defui` already compiled each
;; one to a JavaScript expression, so this only has to give them their imports
;; and a name. The browser imports the result and never evaluates a string.
(defn- components-module [mounts path]
  ;; Only :js is read, and that does not depend on the arguments, so the
  ;; components are called with no state at all rather than with a connection's.
  ;; Building one here would run every `:state` fn for a module that ignores it.
  (let [insts (map #((:component %) {}) mounts)]
    {:status 200
     :headers js-headers
     :body (str "import * as SQ from \"squint-cljs/core.js\";\n"
                "import { rpc_BANG_ } from \"" path "/rpc.mjs\";\n"
                "export const registry = {\n"
                (str/join ",\n"
                          (map #(str "  " (pr-str (:id %)) ": {f: " (:js %)
                                     ", init: " (:init %)
                                     ", nlocals: " (:locals % 0) "}")
                               insts))
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
(defn- first-paint [spec req]
  (let [inst (:instance (build spec req))
        ;; a browser slot has no value yet, so the first paint renders whatever
        ;; the component makes of an empty one
        locals (repeatedly (:locals inst 0) #(atom nil))]
    (ssr/render (into [(:ssr inst)] (concat ((:slots inst)) locals)))))

;; The version comes from this library rather than from the page, so an import
;; map cannot drift away from the Squint that compiled the components.
(def ^:private squint-core "https://esm.sh/squint-cljs@0.14.208/core.js")

(defn- scripts [nonce path]
  (str "<script type=\"importmap\" nonce=\"" nonce "\">\n"
       "{\"imports\": {\"squint-cljs/core.js\": \"" squint-core "\"}}\n"
       "</script>\n"
       "<script type=\"module\" src=\"" path "/client.mjs\"></script>\n"))

(defn- escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

;; Without an `:index` the page is boilerplate: a div per mount and the two
;; script tags. Buzz knows all of it, so it writes the page instead.
(defn- generated-page [nonce req {:keys [title head mounts path]}]
  (str "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
       "<title>" (escape (or title "buzz")) "</title>\n"
       head
       "</head>\n<body>\n"
       (str/join (for [{:keys [el] :as mount} mounts]
                   (str "<div id=\"" (escape el) "\">" (first-paint mount req) "</div>\n")))
       (scripts nonce (or path ""))
       "</body>\n</html>\n"))

;; With an `:index` the page is yours. Buzz fills each `<!--el-->` with the
;; first paint and every NONCE with the one in the header.
(defn- rendered-page [nonce req {:keys [index mounts]}]
  (-> (reduce (fn [html {:keys [el] :as mount}]
                (str/replace html (str "<!--" el "-->") (first-paint mount req)))
              (slurp (fs/file index))
              mounts)
      (str/replace "NONCE" nonce)))

;; The token is handed out here as well as at the stream, so that a browser
;; opening two tabs at once already has one before either stream asks.
(defn- index-page [req spec]
  (let [nonce (str (random-uuid))]
    {:status 200
     :headers (cond-> {"Content-Type" "text/html"
                       "Content-Security-Policy" (csp nonce)}
                (nil? (browser-token req))
                (merge (token-headers (str (random-uuid)))))
     :body (if (:index spec) (rendered-page nonce req spec) (generated-page nonce req spec))}))

(defn handler
  "Returns a Ring handler for the page described by `spec`. Requests it does not
  own get nil, so an application can compose it with whatever else it serves and
  run whichever server it likes.

  The page belongs to the handler this returns, so an application can serve more
  than one of them. Give each one a `:path` and it answers under that, stream
  and modules included:

    (handler {:path \"/admin\" :mounts [...]})   ; the page is /admin

  Installs watches and starts the heartbeat as a side effect of being called."
  [{:keys [watch mounts path] :as spec}]
  (doseq [a watch]
    (add-watch a ::render broadcast-patch!))
  @heartbeat
  (let [path   (or path "")
        ;; what tells one handler's connections from another's. The path rather
        ;; than something minted here, so that building the same handler twice
        ;; still owns the connections it opened the first time.
        page   path
        routes (cond-> {(str path "/")               :page
                        (str path "/client.mjs")     :client
                        (str path "/rpc.mjs")        :rpc-module
                        (str path "/components.mjs") :components
                        (str path "/events")         :events
                        (str path "/rpc")            :rpc}
                 ;; /admin and /admin/ are the same page
                 (seq path) (assoc path :page))]
    (fn [req]
      (case (routes (:uri req))
        :page       (index-page req spec)
        :client     (runtime-module "client.cljs" path)
        :rpc-module (runtime-module "rpc.cljs" path)
        :components (components-module mounts path)
        :events     (events req mounts page)
        :rpc        (rpc req page)
        nil))))
