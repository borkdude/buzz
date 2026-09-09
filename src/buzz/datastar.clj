(ns buzz.datastar
  "Server-rendered pages over the Buzz engine, with Datastar in the browser.

  A page is a function of the request that returns Hiccup. Wrap the parts
  that read server state in `fragment`. When an observed topic changes, the
  fragments that read it are rendered again, for the connections that read
  it, and sent as Datastar element patches. Browser state is Datastar
  signals. Handlers are functions on vars, reached through `action`."
  (:require [buzz.impl.hub :as hub]
            [buzz.impl.page :as page]
            [buzz.stream :as stream]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [reagami.ssr :as ssr]
            [squint.compiler :as squint])
  (:import (java.net URLEncoder)))

(def datastar
  "The Datastar bundle the page loads."
  "https://cdn.jsdelivr.net/gh/starfederation/datastar@v1.0.2/bundles/datastar.js")

;; ---------------------------------------------------------------------------
;; Rendering

(def ^:private ^:dynamic *render*
  "Set while a page renders for a connection: {:session :index :frags :path}."
  nil)

(def ^:private ^:dynamic *path* "")

(def ^:private ^:dynamic *parent*
  "The fragment being rendered, so a nested one knows its ancestor."
  nil)

(def ^:private ^:dynamic *signals*
  "Signals used while a fragment renders, declared on its element."
  nil)

(defn- element-id [id]
  (str "bz-" (str/replace (subs (str id) (if (keyword? id) 1 0)) #"[^A-Za-z0-9_-]" "-")))

(defn- html [hiccup] (ssr/render hiccup))

(defn- descendant?
  "Whether fragment `id` sits inside fragment `of`, by the parent chain."
  [frags id of]
  (loop [p (get-in frags [id :parent])]
    (cond (nil? p) false
          (= p of) true
          :else (recur (get-in frags [p :parent])))))

(defn- track
  "Renders `f` for fragment `id`, recording the topics it reads. Fragments
  nested in it are dropped first, so only the ones rendered again remain."
  [{:keys [session index frags]} id f]
  (swap! frags (fn [m] (into {} (remove (fn [[k _]] (descendant? m k id))) m)))
  (let [reads (atom #{})
        ;; Hiccup built with `for` is lazy. Realize it here, so reads and
        ;; signals inside are seen while this fragment is the context.
        out   (binding [hub/*tracking* {:reads reads :index index :session session}
                        *parent* id]
                (walk/prewalk identity (f)))]
    (swap! frags assoc-in [id :reads] @reads)
    (hub/set-topics! index session (reduce into #{} (map :reads (vals @frags))))
    out))

(defn- fragment-div
  "The element of a fragment: its body, and the signals used inside declared
  on it."
  [r id f el]
  (let [used (atom {})
        body (binding [*signals* used]
               (if r
                 (track r id f)
                 (walk/prewalk identity (f))))]
    [:div (cond-> {:id el}
            (seq @used) (assoc :data-signals__ifmissing (json/generate-string @used)))
     body]))

(defn fragment
  "Renders `(f)` inside a `div` with an id derived from `id`, and makes it the
  unit of re-rendering: a change to a topic read in `f` sends this element
  again. The signals used inside are declared on the element. `id` must be
  unique within the page."
  [id f]
  (let [el (element-id id)
        r  *render*]
    (when r (swap! (:frags r) update id assoc :f f :el el :parent *parent*))
    (fragment-div r id f el)))

(defn- patch-elements [h]
  (str "event: datastar-patch-elements\n"
       (str/join (map #(str "data: elements " % "\n") (str/split-lines h)))
       "\n"))

(defn- patch-signals [m]
  (str "event: datastar-patch-signals\ndata: signals " (json/generate-string m) "\n\n"))

(defn- send-fragment! [{:keys [ch] :as r} id]
  (let [{:keys [f el]} (get @(:frags r) id)]
    (binding [*render* r *path* (:path r)]
      (stream/send! ch (patch-elements (html (fragment-div r id f el)))))))

(defn- send-fragments!
  "Sends the fragments in `ids`, skipping one whose ancestor is sent too."
  [r ids]
  (let [frags @(:frags r)
        ids   (set ids)]
    (doseq [id ids
            :when (not (some #(and (not= % id) (descendant? frags id %)) ids))]
      (send-fragment! r id))))

(defn- render-page!
  "Renders the page for a connection from scratch and sends every fragment."
  [{:keys [frags req render path] :as r}]
  (reset! frags {})
  (binding [*render* r *path* path]
    (html (fragment ::page #(render req))))
  (send-fragments! r (keys @frags)))

;; ---------------------------------------------------------------------------
;; Signals and actions

(defrecord Signal [name init])

(defn signal
  "Browser state: a Datastar signal named `k` with initial value `init`. Bind
  it with `def` or `let`, read and write it inside `expr` like an atom, and
  pass it as an `action` argument to read it when the action fires."
  ([k] (->Signal (name k) nil))
  ([k init] (->Signal (name k) init)))

(defn signal? [x] (instance? Signal x))

(def evt
  "Datastar's event inside an `expr`, for interop forms such as `(.-key evt)`."
  ::evt)

(def el
  "Datastar's element inside an `expr`."
  ::el)

(defn- use! [sig]
  (when *signals* (swap! *signals* assoc (:name sig) (:init sig)))
  sig)

(defn ref-js
  "The Datastar reference for a signal. Public because `expr` expands into
  calls to it."
  [sig]
  (if (signal? sig)
    (str "$" (:name (use! sig)))
    (throw (ex-info "reset!, swap! and deref in an expression take a signal"
                    {:value sig}))))

(defn bind
  "The name of `sig`, for a `data-bind` attribute."
  [sig]
  (:name (use! sig)))

(defn signals
  "Attributes declaring `sigs` with their initial values. Fragments declare
  the signals they use on their own, so this is for a signal only ever read
  by Datastar itself."
  [& sigs]
  {:data-signals (json/generate-string (into {} (map (juxt :name :init)) sigs))})

(defonce ^:private actions (atom {}))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn action
  "A Datastar expression that posts to `v`, a var holding `(fn [req args])`.
  Values in `args` cross as data. A signal is read from the browser when the
  action fires."
  ([v] (action v {}))
  ([v args]
   (let [id     (str (symbol v))
         server (into {} (remove (fn [[_ x]] (signal? x))) args)
         sigs   (into {} (keep (fn [[k x]] (when (signal? x) [(name k) (:name (use! x))]))) args)]
     (swap! actions assoc id v)
     (str "@post('" *path* "/action?id=" (encode id)
          "&a=" (encode (json/generate-string server))
          "&s=" (encode (json/generate-string sigs)) "')"))))

(def connection
  "Returns the connection ID in `req`."
  page/connection)

(defn- query-params [req]
  (into {} (for [kv (str/split (or (:query-string req) "") #"&")
                 :let [[k v] (str/split kv #"=" 2)]
                 :when (seq k)]
             [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- run-action [{:keys [registry]} req]
  (let [q       (query-params req)
        signals (json/parse-string (slurp (:body req)))
        session (get signals "buzzSession")
        conn    (get @registry session)
        v       (get @actions (get q "id"))]
    (if (and conn v (= (:owner conn) (page/browser-token req)))
      (try
        (let [args (merge (json/parse-string (get q "a") true)
                          (into {} (map (fn [[k s]] [(keyword k) (get signals s)]))
                                (json/parse-string (get q "s"))))]
          (v (assoc req :buzz.core/connection session) args)
          {:status 204})
        (catch Exception e
          (println "buzz:" (get q "id") "failed -" (ex-message e))
          {:status 500 :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:error "action failed"})}))
      {:status 404 :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error "no such action"})})))

;; ---------------------------------------------------------------------------
;; Expressions

(defn- signal-var
  "The signal a symbol names through a var, at expansion time."
  [sym]
  (when (symbol? sym)
    (when-let [v (try (resolve sym) (catch Exception _ nil))]
      (when (and (var? v) (bound? v) (signal? @v))
        @v))))

(defn- action-form? [x]
  (and (seq? x) (symbol? (first x))
       (= #'action (try (resolve (first x)) (catch Exception _ nil)))))

(defn- lift
  "Rewrites `form` for Squint. Signals read and write like atoms: a signal
  held by a var becomes its reference now, one held by a local at render
  time. Locals and keyword calls splice as literals, actions as raw
  JavaScript. Returns the form and the placeholder pairs to splice."
  [form locals]
  (let [found (atom [])
        place (fn [x kind]
                (let [p (symbol (str "buzz_" kind "_" (count @found) "_"))]
                  (swap! found conj [p x kind])
                  p))
        sig   (fn [x]
                (if (or (signal-var x)
                        (and (simple-symbol? x) (contains? locals x)))
                  (place x "signal")
                  (throw (ex-info (str x " is not a signal in this expression")
                                  {:symbol x}))))
        path  (fn [ref k] (symbol (str ref "." (name k))))
        walk  (fn walk [x]
                (cond
                  (action-form? x) (place x "action")

                  (and (seq? x) (symbol? (first x)))
                  (let [[h & args] x]
                    (case h
                      (deref clojure.core/deref) (sig (first args))
                      (reset! clojure.core/reset!) (list 'set! (sig (first args)) (walk (second args)))
                      (swap! clojure.core/swap!)
                      (let [[s f & more] args
                            ref (sig s)]
                        (if (= 'assoc f)
                          (cons 'expr/do (mapv (fn [[k v]] (list 'set! (path ref k) (walk v)))
                                               (partition 2 more)))
                          (list 'set! ref (list* f ref (mapv walk more)))))
                      (apply list (map walk x))))

                  (and (seq? x) (keyword? (first x)) (= 2 (count x)))
                  (let [inner (walk (second x))]
                    (if (and (symbol? inner) (str/starts-with? (str inner) "buzz_signal_"))
                      (path inner (first x))
                      (place x "value")))

                  (seq? x) (apply list (map walk x))
                  (vector? x) (mapv walk x)
                  (map? x) (into {} (map (fn [[k v]] [(walk k) (walk v)])) x)
                  (and (simple-symbol? x) (contains? locals x)) (place x "value")
                  :else x))]
    [(walk form) @found]))

;; Datastar evaluates an expression with `Function`, so control flow and
;; comparison compile to bare operators rather than calls into Squint's core.
(defn- template [op n]
  (str/join op (repeat n "(~{})")))

(defn- js-op [js & args]
  (with-meta (list* 'js* js args) {:tag 'boolean}))

(def ^:private expr-macros
  {'expr {'and      (fn [_ _ & xs] (case (count xs) 0 true 1 (first xs)
                                     (apply js-op (template " && " (count xs)) xs)))
          'or       (fn [_ _ & xs] (case (count xs) 0 nil 1 (first xs)
                                     (apply js-op (template " || " (count xs)) xs)))
          'not      (fn [_ _ x] (js-op "(!(~{}))" x))
          '=        (fn [_ _ x y] (js-op "(~{}) === (~{})" x y))
          'not=     (fn [_ _ x y] (js-op "(~{}) !== (~{})" x y))
          'str      (fn [_ _ & xs] (list* 'js* (str/join " + " (cons "''" (repeat (count xs) "(~{})"))) xs))
          'do       (fn [_ _ & xs] (case (count xs) 0 nil 1 (first xs)
                                     (list* 'js* (template ", " (count xs)) xs)))
          'if       (fn [_ _ test then & [else]] (list 'if (js-op "(~{})" test) then else))
          'when     (fn [_ _ test & body] (list 'if (js-op "(~{})" test) (cons 'expr/do body)))
          'when-not (fn [_ _ test & body] (list 'if (js-op "(!(~{}))" test) (cons 'expr/do body)))}})

(def ^:private expr-heads
  '{and expr/and, or expr/or, not expr/not, = expr/=, not= expr/not=, str expr/str,
    do expr/do, if expr/if, when expr/when, when-not expr/when-not})

(defn- to-js [form]
  (let [form (walk/prewalk (fn [x]
                             (if (and (seq? x) (contains? expr-heads (first x)))
                               (cons (expr-heads (first x)) (rest x))
                               x))
                           form)]
    ;; Not top level: Squint drops a leading string literal there as a directive.
    (-> (:body (squint/compile* [form] {:context :expr :core-alias "SQ" :elide-imports true
                                        :top-level false :macros expr-macros}))
        (str/replace #"\n" " ")
        ;; Datastar splits a value expression on `;` to add its return, so a
        ;; function body ends without one.
        (str/replace #";\s*\}" " }")
        str/trim)))

(defmacro expr
  "Compiles `body` to a Datastar expression with Squint. A signal reads and
  writes like an atom, `(:k @sig)` and `(swap! sig assoc :k v)` reach into a
  map signal, `evt` and `el` are Datastar's, locals splice as literals, and
  an `action` fires when the expression reaches it.

    {:data-on:click (expr (swap! open not))}
    {:data-show (expr @open)}
    {:data-on:keydown (expr (when (= evt.key \"Enter\") (action #'save! {:q q})))}"
  [& body]
  (let [locals       (set (keys &env))
        [form pairs] (lift (if (= 1 (count body)) (first body) (cons 'do body)) locals)
        js           (to-js form)]
    (if (seq pairs)
      `(-> ~js ~@(map (fn [[p x kind]]
                        `(str/replace ~(str p) ~(case kind
                                                  "action" x
                                                  "signal" `(ref-js ~x)
                                                  "value" `(json/generate-string ~x))))
                      pairs))
      js)))

;; ---------------------------------------------------------------------------
;; The stream

(defn- open-stream [{:keys [registry index]} session ch req render token interval
                    on-close path]
  (let [frags (atom {})
        lane  (page/new-lane)
        r     {:session session :index index :frags frags :ch ch :path path
               :req req :render render :lane lane}]
    (swap! registry assoc session (assoc r :owner token))
    (swap! (:jobs lane) conj
           (fn []
             (stream/send! ch (patch-signals {:buzzSession session}))
             (render-page! r)))
    (page/start-lane! session lane interval
                      (fn []
                        (swap! registry dissoc session)
                        (hub/drop-session! index session)
                        (when on-close (on-close req)))
                      (fn [topics]
                        (when (get @registry session)
                          (try (send-fragments! r (for [[id {:keys [reads]}] @frags
                                                        :when (some topics reads)]
                                                    id))
                               (catch Throwable e
                                 (println "buzz: render failed for" session "-"
                                          (ex-message e)))))))))

;; ---------------------------------------------------------------------------
;; The REPL

(defonce ^:private entries (atom #{}))

(defn refresh!
  "Renders every open page again and sends it. Called when a var in the
  render function's namespace is re-evaluated."
  []
  (doseq [{:keys [registry]} @entries
          [_ conn] @registry
          :let [lane (:lane conn)]]
    (swap! (:jobs lane) conj #(render-page! conn))
    (page/signal! lane))
  nil)

(defonce ^:private refresh-pending (atom false))

(defn- schedule-refresh!
  "One refresh for a burst of re-evaluations, such as loading a file."
  []
  (when (compare-and-set! refresh-pending false true)
    (hub/schedule! 50 (fn [] (reset! refresh-pending false) (refresh!)))))

(defn- watch-namespace!
  "Refreshes open pages when any var in the namespace of `v` changes."
  [v]
  (doseq [var (vals (ns-interns (:ns (meta v))))]
    (add-watch var ::refresh (fn [_ _ _ _] (schedule-refresh!)))))

(defn- events [{:keys [registry] :as entry} adapter req render on-close interval path]
  (let [session (str (random-uuid))
        held    (page/browser-token req)
        token   (or held (str (random-uuid)))
        req     (assoc req :buzz.core/connection session)]
    (adapter req
             {:status 200
              :headers (cond-> {"Content-Type" "text/event-stream"
                                "Cache-Control" "no-cache"
                                "X-Accel-Buffering" "no"}
                         (nil? held) (merge (page/token-headers token)))
              :on-open  (fn [ch]
                          (open-stream entry session ch req render token interval on-close path))
              :on-close (fn []
                          (if-let [lane (:lane (get @registry session))]
                            (page/close-lane! lane)
                            (when on-close (on-close req))))})))

(defn- escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

(defn- page-response [req {:keys [render title head]} path]
  {:status 200
   :headers (cond-> {"Content-Type" "text/html"}
              (nil? (page/browser-token req))
              (merge (page/token-headers (str (random-uuid)))))
   :body (str "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
              "<title>" (escape (or title "buzz")) "</title>\n"
              "<script type=\"importmap\">{\"imports\": {\"squint-cljs/core.js\": \""
              page/squint-core "\"}}</script>\n"
              "<script type=\"module\">import * as SQ from \"squint-cljs/core.js\"; globalThis.SQ = SQ;</script>\n"
              "<script type=\"module\" src=\"" datastar "\"></script>\n"
              (or head "")
              "</head>\n<body data-init=\"@get('" path "/events')\">\n"
              (binding [*path* path] (html (fragment ::page #(render req))))
              "\n</body>\n</html>\n")})

(defn handler
  "Returns a Ring handler for one page. `:render` is `(fn [req] hiccup)`.
  Pass it as a var to refresh open pages when a var in its namespace is
  re-evaluated. `:path` prefixes the page routes. `:adapter` provides the
  event stream and defaults to http-kit. Unknown routes return nil."
  [{:keys [render path adapter on-close] :as spec}]
  @page/heartbeat
  (let [adapter  (or adapter @(requiring-resolve 'buzz.httpkit/adapter))
        registry (atom {})
        index    (atom {:by-topic {} :by-session {}})
        interval (or (:render-interval-ms spec) 20)
        base     {:registry registry :index index :spec spec}
        entry    (hub/register-handler!
                  (assoc base :mark! (fn [topics] (page/mark! base interval topics))))
        _        (swap! entries conj entry)
        _        (when (var? render) (watch-namespace! render))
        path     (or path "")
        routes   (cond-> {(str path "/")       :page
                          (str path "/events") :events
                          (str path "/action") :action}
                   (seq path) (assoc path :page))]
    (fn [req]
      (case (routes (:uri req))
        :page   (page-response req spec path)
        :events (events entry adapter req render on-close interval path)
        :action (run-action entry req)
        nil))))
