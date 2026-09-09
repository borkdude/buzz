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
            [reagami.ssr :as ssr])
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

(defn- element-id [id]
  (str "bz-" (str/replace (subs (str id) (if (keyword? id) 1 0)) #"[^A-Za-z0-9_-]" "-")))

(defn- html [hiccup] (ssr/render hiccup))

(defn- track
  "Renders `f` for fragment `id`, recording the topics it reads."
  [{:keys [session index frags]} id f]
  (let [reads (atom #{})
        out   (binding [hub/*tracking* {:reads reads :index index :session session}]
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
      (do (swap! (:frags r) assoc-in [id :f] f)
          (swap! (:frags r) assoc-in [id :el] el)
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
    (binding [*render* r]
      (stream/send! ch (patch-elements (html [:div {:id el} (track r id f)]))))))

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
        r     {:session session :index index :frags frags :ch ch :path path}
        lane  (page/new-lane)]
    (swap! registry assoc session {:ch ch :owner token :req req :lane lane :frags frags})
    (swap! (:jobs lane) conj
           (fn []
             (stream/send! ch (patch-signals {:buzzSession session}))
             (binding [*render* r *path* path]
               (html (render req)))
             (doseq [id (keys @frags)]
               (send-fragment! r id))))
    (page/start-lane! session lane interval
                      (fn []
                        (swap! registry dissoc session)
                        (hub/drop-session! index session)
                        (when on-close (on-close req)))
                      (fn [topics]
                        (when (get @registry session)
                          (doseq [[id {:keys [reads]}] @frags
                                  :when (some topics reads)]
                            (try (send-fragment! r id)
                                 (catch Throwable e
                                   (println "buzz: render failed for" session "-"
                                            (ex-message e))))))))))

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
              "<script type=\"module\" src=\"" datastar "\"></script>\n"
              (or head "")
              "</head>\n<body data-init=\"@get('" path "/events')\">\n"
              (binding [*path* path] (html (render req)))
              "\n</body>\n</html>\n")})

(defn handler
  "Returns a Ring handler for one page. `:render` is `(fn [req] hiccup)`.
  `:path` prefixes the page routes. `:adapter` provides the event stream and
  defaults to http-kit. Unknown routes return nil."
  [{:keys [render path adapter on-close] :as spec}]
  @page/heartbeat
  (let [adapter  (or adapter @(requiring-resolve 'buzz.httpkit/adapter))
        registry (atom {})
        index    (atom {:by-topic {} :by-session {}})
        interval (or (:render-interval-ms spec) 20)
        base     {:registry registry :index index :spec spec}
        entry    (hub/register-handler!
                  (assoc base :mark! (fn [topics] (page/mark! base interval topics))))
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
