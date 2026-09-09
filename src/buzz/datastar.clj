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
        out   (binding [hub/*tracking* {:reads reads :index index :session session}
                        *parent* id]
                (f))]
    (swap! frags assoc-in [id :reads] @reads)
    (hub/set-topics! index session (reduce into #{} (map :reads (vals @frags))))
    out))

(defn fragment
  "Renders `(f)` inside a `div` with an id derived from `id`, and makes it the
  unit of re-rendering: a change to a topic read in `f` sends this element
  again. `id` must be unique within the page."
  [id f]
  (let [el (element-id id)]
    (if-let [r *render*]
      (do (swap! (:frags r) update id assoc :f f :el el :parent *parent*)
          [:div {:id el} (track r id f)])
      [:div {:id el} (f)])))

(defn- patch-elements [h]
  (str "event: datastar-patch-elements\n"
       (str/join (map #(str "data: elements " % "\n") (str/split-lines h)))
       "\n"))

(defn- patch-signals [m]
  (str "event: datastar-patch-signals\ndata: signals " (json/generate-string m) "\n\n"))

(defn- send-fragment! [{:keys [ch] :as r} id]
  (let [{:keys [f el]} (get @(:frags r) id)]
    (binding [*render* r *path* (:path r)]
      (stream/send! ch (patch-elements (html [:div {:id el} (track r id f)]))))))

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

(defrecord Signal [name])

(defn signal
  "A reference to the Datastar signal `k`, for an `action` argument."
  [k]
  (->Signal (name k)))

(defn signals
  "Attributes declaring Datastar signals with their initial values."
  [m]
  {:data-signals (json/generate-string m)})

(defonce ^:private actions (atom {}))

(defn- encode [s] (URLEncoder/encode (str s) "UTF-8"))

(defn action
  "A Datastar expression that posts to `v`, a var holding `(fn [req args])`.
  Values in `args` cross as data. A `signal` value is read from the browser
  when the action fires."
  ([v] (action v {}))
  ([v args]
   (let [id     (str (symbol v))
         server (into {} (remove (fn [[_ x]] (instance? Signal x))) args)
         sigs   (into {} (keep (fn [[k x]] (when (instance? Signal x) [(name k) (:name x)]))) args)]
     (swap! actions assoc id v)
     (str "@post('" *path* "/action?id=" (encode id)
          "&a=" (encode (json/generate-string server))
          "&s=" (encode (json/generate-string sigs)) "')"))))

(def connection
  "Returns the connection ID in `req`."
  page/connection)

;; ---------------------------------------------------------------------------
;; Expressions

(defn- signal-sym [s]
  (symbol (str "$" (name s))))

(defn- rewrite-signals
  "Signals read and write like atoms. `@open` is the signal `$open`, `reset!`
  assigns it and `swap!` assigns the result of the function."
  [form]
  (walk/postwalk
   (fn [x]
     (if (and (seq? x) (symbol? (first x)) (simple-symbol? (second x)))
       (let [[h s & more] x]
         (case h
           (deref clojure.core/deref) (signal-sym s)
           (reset! clojure.core/reset!) (list 'set! (signal-sym s) (first more))
           (swap! clojure.core/swap!) (list 'set! (signal-sym s)
                                            (list* (first more) (signal-sym s) (rest more)))
           x))
       x))
   form))

(defn- action-form? [x]
  (and (seq? x) (symbol? (first x))
       (= #'action (try (resolve (first x)) (catch Exception _ nil)))))

(defn- lift
  "Replaces every `(action ...)`, every local of the surrounding scope and
  every keyword call with a placeholder. Returns the form and the pairs of
  placeholder and Clojure expression to splice at render time."
  [form locals]
  (let [found (atom [])
        place (fn [x kind]
                (let [p (symbol (str "buzz_" kind "_" (count @found)))]
                  (swap! found conj [p x kind])
                  p))]
    [(walk/prewalk (fn [x]
                     (cond
                       (action-form? x) (place x "action")
                       (and (seq? x) (keyword? (first x))) (place x "value")
                       (and (simple-symbol? x) (contains? locals x)) (place x "value")
                       :else x))
                   form)
     @found]))

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
    (-> (:body (squint/compile* [form] {:context :expr :core-alias "SQ" :elide-imports true
                                        :macros expr-macros}))
        (str/replace #"\n" " ")
        str/trim)))

(defmacro expr
  "Compiles `body` to a Datastar expression with Squint. Signals read and
  write like atoms, `evt` and `el` are Datastar's, and an `action` inside
  fires when the expression reaches it.

    {:data-on:click (expr (swap! open not))}
    {:data-show (expr @open)}
    {:data-on:keydown (expr (when (= evt.key \"Enter\") (action #'save! {:q (signal :q)})))}"
  [& body]
  (let [locals        (set (keys &env))
        [form pairs]  (lift (rewrite-signals (if (= 1 (count body)) (first body) (cons 'do body)))
                            locals)
        js            (to-js form)]
    (if (seq pairs)
      `(-> ~js ~@(map (fn [[p x kind]]
                        `(str/replace ~(str p) ~(if (= "action" kind)
                                                  x
                                                  `(json/generate-string ~x))))
                      pairs))
      js)))

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
