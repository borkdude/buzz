(ns buzz.impl.page
  "The page half of Buzz: the Ring handler for what the compiler produced.
  Its own file for its own reading, not its own API: everything an
  application touches is forwarded from `buzz.core`."
  (:require [babashka.fs :as fs]
            [buzz.impl.parts :as parts]
            [buzz.stream :as stream]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [reagami.ssr :as ssr]
            [squint.compiler :as squint]))

(defonce ^:private registries (atom #{}))

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

(defn connection
  "The id of the connection `req` belongs to: the same value in a slot and in
  every rpc that connection sends. A tab holds one at a time, and a reconnect
  starts a new one, with `:on-close` for the old. Nil during the first paint,
  which belongs to no connection yet."
  [req]
  (:buzz.core/connection req))

(defn token
  "The browser token in `req`: one value per browser, minted by Buzz. Stable
  across tabs and reconnects, so it keys state a browser owns."
  [req]
  (browser-token req))

(defn- event!
  "One SSE frame. JSON escapes newlines inside strings, so a value can never
  break out of its own `data:` line."
  [ch msg]
  (stream/send! ch (str "data: " (json/generate-string msg) "\n\n")))

;; A watched atom says something changed somewhere, not that this mount cares.
;; Rather than have each mount declare what it reads, which can drift from what
;; its slots actually do, run the slots and send nothing when the values are the
;; same as last time. Unchanged slots are usually the identical objects, so the
;; comparison stops at the first identity check.
(defn- slot-vals
  "The current slot values of one mount. The instance says whether its slots
  read the request, so a page that never asks pays nothing."
  [{:keys [instance req]}]
  (if (:request instance) ((:slots instance) req) ((:slots instance))))

(defn- patch! [ch {:keys [instance sent] :as mount}]
  (let [vals (slot-vals mount)]
    (when (not= vals @sent)
      (reset! sent vals)
      (event! ch ["patch" (:id instance) vals]))))

;; A mount names its component by var (or thunk), so nothing closes over a
;; connection and one instance serves them all. Cached per revision: a reload
;; builds the next one, and every connection sees it.
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
  ;; The session id is what an RPC arrives with, so it goes out only once there
  ;; is something here to find under it. Building the mounts renders every
  ;; component, which is long enough for a browser to have answered.
  (let [mounted (mapv #(build % req) mounts)]
    (swap! registry assoc session {:ch ch :mounted mounted :owner token})
    (event! ch ["session" session])
    (doseq [{:keys [el instance sent] :as m} mounted]
      (let [vals (slot-vals m)]
        (reset! sent vals)
        (event! ch ["mount" (:id instance) el vals])))))

;; The stream is the one response a plain Ring handler cannot make, so it is
;; the one place an adapter is asked to help: answer the request, keep it
;; open, and say when the client went away.
(defn- events [registry adapter req mounts on-close]
  (let [session (str (random-uuid))
        held    (browser-token req)
        token   (or held (str (random-uuid)))
        ;; enriched once: the same request the slots read, connection id and
        ;; all, so a key derived at open can be derived again at close
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
                          ;; the app hears the request its keys derived from,
                          ;; so it can derive them again and let go
                          (when on-close (on-close req)))})))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; A handler that threw used to escape into http-kit, so the browser saw nothing
;; and the log said nothing about which handler it was. Now it answers and the
;; browser's promise rejects. The detail stays here: an exception message can
;; carry more than a browser should be told.
;;
;; Two things have to agree before a handler runs. The header, because a
;; request that carries one is not a form a page elsewhere can post: it needs a
;; preflight, and Buzz answers none. Same site is not the same as same origin,
;; so a neighbouring subdomain would otherwise be allowed to try. The token,
;; because a session id on its own says nothing about who is asking. The page
;; needs no check of its own any more: each handler looks in its own registry,
;; so another page's session id finds nothing here.
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

;; The reply to an RPC is not the response. It is whatever :patch the write
;; happens to produce, on every stream of this handler watching that data. The
;; walk is per registry, so a write watched by one page runs no other page's
;; slots.
(defn- broadcast-patch! [registry]
  (fn [_ _ _ _]
    (doseq [{:keys [ch mounted]} (vals @registry)
            m mounted]
      (patch! ch m))))

;; Re-evaluating a defui in a REPL bumps the revision. Rebuild each
;; connection's instances so their handler ids match the new code, then tell the
;; browser to import the components again under a fresh URL. Browser state is
;; the browser's, so a reload does not clear what someone had typed.
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

;; Idle streams get dropped by proxies. A comment frame is ignored by
;; EventSource and keeps the connection accounted for.
;; One for the whole process rather than one per page, since it walks every
;; registry there is.
(defonce ^:private heartbeat
  (delay
    (future
      (loop []
        (Thread/sleep 25000)
        (doseq [registry @registries
                {:keys [ch]} (vals @registry)]
          (stream/send! ch ": ping\n\n"))
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
;; instead of rebuilding them. There is no connection yet, so `(request)` in a
;; slot is the page request and its connection id is nil.
(defn- first-paint [spec req]
  (let [mount (build spec req)
        inst  (:instance mount)
        ;; a browser slot has no value yet, so the first paint renders whatever
        ;; the component makes of an empty one
        locals (repeatedly (:locals inst 0) #(atom nil))]
    (ssr/render (into [(:ssr inst)] (concat (slot-vals mount) locals)))))

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

  The stream is served through `buzz.stream`: give `:adapter` a fn of a
  request and the stream's callbacks to run on another server. Without one
  the bundled http-kit adapter is used.

  Installs watches and starts the heartbeat as a side effect of being called."
  [{:keys [watch mounts path] :as spec}]
  (doseq [m mounts]
    (when (or (:state m) (:component m))
      (throw (ex-info (str "a mount is {:el ... :ui #'component}. :state and :component are gone: "
                           "derive per connection facts from (request) and key your own atoms")
                      {:mount (select-keys m [:el])})))
    (when-not (:ui m)
      (throw (ex-info "a mount needs :ui, a component var or thunk" {:mount m}))))
  @heartbeat
  (let [adapter  (or (:adapter spec)
                     ;; the bundled http-kit adapter, loaded only when asked
                     ;; for, so nothing here names http-kit
                     @(requiring-resolve 'buzz.httpkit/adapter))
        registry (atom {})
        _      (swap! registries conj registry)
        _      (doseq [a watch]
                 ;; keyed by this handler's registry, so two handlers watching
                 ;; one atom each broadcast to their own connections
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
    ;; the registry rides on the handler for whoever holds the fn, which is
    ;; how the tests look inside without a global to reach for
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
