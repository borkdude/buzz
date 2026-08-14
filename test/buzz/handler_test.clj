(ns buzz.handler-test
  (:require [babashka.fs :as fs]
            [buzz.core :refer [defpart defui local-state reply server server!]]
            [buzz.handler :as handler]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]))

(defn- next-event
  "The next SSE frame. Headings, chunk sizes and heartbeats are not frames."
  [rdr]
  (loop []
    (when-let [line (.readLine rdr)]
      (if (str/starts-with? line "data: ")
        (json/parse-string (subs line 6))
        (recur)))))

(defn- silent?
  "Whether nothing arrives within `ms`. A frame that is never sent cannot be
  waited for, so this is a timeout on purpose."
  [sock rdr ms]
  (.setSoTimeout sock (int ms))
  (try
    (nil? (next-event rdr))
    (catch java.net.SocketTimeoutException _ true)
    (finally (.setSoTimeout sock 5000))))

(defn- open-events
  "One browser on the page. Reading SSE by hand keeps the assertions on what the
  browser is actually sent."
  ([port] (open-events port {}))
  ([port headers]
   (let [sock (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes (str "GET /events HTTP/1.1\r\nHost: localhost\r\n"
                              (apply str (for [[k v] headers] (str k ": " v "\r\n")))
                              "\r\n")))
      (.flush))
    (let [rdr (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
          [_ session] (next-event rdr)]
      {:sock sock :rdr rdr :session session :port port}))))

(defn- with-connection
  "Serves `spec` on a free port and opens one connection to it, as user alice."
  [spec f]
  (let [stop (http/run-server (fn [req] (or ((handler/handler spec) req)
                                             {:status 404 :body "no"}))
                              {:port 0})
        ;; asking for port 0 and reading back what was bound leaves no window
        ;; for another process to take the port first
        port (:local-port (meta stop))
        {:keys [sock] :as conn} (open-events port {"X-User" "alice"})]
    (try
      (f conn)
      (finally
        (.close sock)
        (stop)))))

(defn- connection-state
  "The atoms one connection owns, which is what a REPL reaches for too."
  [session]
  (:state (first (:mounted (get @handler/conns session)))))

(defn- rpc
  "One POST to /rpc, the way the browser makes it. Returns [status body]."
  [port session handler-id args]
  (let [payload (.getBytes (json/generate-string [session handler-id args]) "UTF-8")]
    (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
      (.setSoTimeout sock 5000)
      (doto (.getOutputStream sock)
        ;; asking to close lets the body be read to the end of the stream,
        ;; without a length to parse first
        (.write (.getBytes (str "POST /rpc HTTP/1.1\r\n"
                                "Host: localhost\r\n"
                                "Content-Type: application/json\r\n"
                                "Content-Length: " (alength payload) "\r\n"
                                "Connection: close\r\n\r\n")
                           "UTF-8"))
        (.write payload)
        (.flush))
      (let [[head body] (str/split (slurp (.getInputStream sock)) #"\r\n\r\n" 2)
            status (second (str/split (first (str/split-lines head)) #" "))]
        [(Integer/parseInt status) (or body "")]))))

;; Two slots over an atom the connection owns. Redefining this is the reload.
(defui panel [q]
  [:p (server @q) (server (inc @q))])

(def ^:private two-slots '(defui panel [q] [:p (server @q) (server (inc @q))]))
(def ^:private one-slot '(defui panel [q] [:p (server @q)]))

(defn- redefine!
  "Re-evaluates a defui here, which is what a REPL does. The runner is in
  another namespace, so this says which one."
  [form]
  (binding [*ns* (the-ns 'buzz.handler-test)]
    (eval form)))

(def ^:private panel-spec
  {:title "panel"
   :mounts [{:el "app"
             :state (fn [_req] {:q (atom 0)})
             :component (fn [state] (panel (:q state)))}]})

;; Re-evaluating a defui rebuilds every open connection. The instance a
;; connection holds and the watches feeding it have to be replaced together, or
;; a later patch carries the slots of code the browser has stopped running.
(deftest a-reload-replaces-what-a-connection-watches
  (with-connection panel-spec
    (fn [{:keys [rdr session]}]
      (let [q (:q (connection-state session))]
        (try
          (testing "a connection is mounted with the slots of the current code"
            (is (= ["mount" "panel" "app" [0 1]] (next-event rdr))))

          (testing "changing state the connection owns patches that connection"
            (swap! q inc)
            (is (= ["patch" "panel" [1 2]] (next-event rdr))))

          (testing "redefining the component sends the new shape"
            (redefine! one-slot)
            (let [[kind _rev id vals] (next-event rdr)]
              (is (= "reload" kind))
              (is (= "panel" id))
              (is (= [1] vals))))

          (testing "and a later change patches with the new shape, not the old"
            (swap! q inc)
            (is (= ["patch" "panel" [2]] (next-event rdr))))

          (finally (redefine! two-slots)))))))

;; The slot reads one key. The other is there to be written without the browser
;; hearing about it.
(defui board [st]
  [:p (server (:shown @st))])

(def ^:private board-spec
  {:title "board"
   :mounts [{:el "app"
             :state (fn [_req] {:board (atom {:shown 0 :hidden 0})})
             :component (fn [state] (board (:board state)))}]})

;; A watched atom says something was written, not that this mount has anything
;; to say. Rather than declare what a component reads, the slots are run and the
;; frame is sent only when a value differs.
(deftest a-write-that-changes-no-slot-sends-nothing
  (with-connection board-spec
    (fn [{:keys [sock rdr session]}]
      (let [st (:board (connection-state session))]

        (testing "the connection is mounted with the value the slot reads"
          (is (= ["mount" "board" "app" [0]] (next-event rdr))))

        (testing "writing state no slot reads sends nothing"
          (swap! st update :hidden inc)
          (is (silent? sock rdr 300)))

        (testing "writing the same value again sends nothing"
          (swap! st update :shown identity)
          (is (silent? sock rdr 300)))

        (testing "a value that differs is sent"
          (swap! st update :shown inc)
          (is (= ["patch" "board" [1]] (next-event rdr))))))))

;; Three handlers. The first answers nothing, the second replies with a value,
;; and the third fails.
(def ^:private log (atom []))

(defui desk []
  [:p
   [:button {:on-click (fn [_] (server! (swap! log conj :quiet)))} "quiet"]
   [:button {:on-click (fn [_] (server! (reply (count @log))))} "ask"]
   [:button {:on-click (fn [_] (server! (throw (ex-info "the database password" {}))))} "boom"]])

(def ^:private desk-spec
  {:title "desk"
   :mounts [{:el "app" :component (fn [_] (desk))}]})

(deftest a-handler-answers-over-rpc
  (reset! log [])
  (with-connection desk-spec
    (fn [{:keys [port session]}]

      (testing "a handler with no reply says so rather than sending a body"
        (is (= [204 ""] (rpc port session "desk/0" [])))
        (is (= [:quiet] @log)))

      (testing "a reply carries the value back"
        (is (= [200 "1"] (rpc port session "desk/1" []))))

      (testing "a handler that throws fails the browser's promise"
        (let [[status body] (rpc port session "desk/2" [])]
          (is (= 500 status))
          (is (= {"error" "handler failed"} (json/parse-string body)))
          (testing "and says nothing about why"
            (is (not (str/includes? body "database password"))))))

      (testing "an unknown handler is not found"
        (is (= [404 "{\"error\":\"no such handler\"}"]
               (rpc port session "desk/9" []))))

      (testing "a handler cannot be reached without a session"
        (is (= 404 (first (rpc port "made-up" "desk/0" []))))))))

;; No slots and no handlers. This one is only here to be rendered into a page.
(defui greeting []
  [:p "hello"])

(def ^:private page-spec
  {:title "a title"
   :head "<link rel=\"stylesheet\" href=\"/style.css\">"
   :mounts [{:el "app" :component (fn [_] (greeting))}]})

(defn- nonce-of [csp]
  (second (re-find #"nonce-([^']+)'" csp)))

;; The page is handed out with a policy it has to satisfy itself. Nothing in the
;; browser evaluates Clojure or JavaScript from a string, so the policy says so
;; and the browser holds the page to it.
(deftest the-page-carries-the-nonce-its-policy-names
  (let [ui (handler/handler page-spec)
        {:keys [status headers body]} (ui {:uri "/"})
        csp (get headers "Content-Security-Policy")
        nonce (nonce-of csp)]

    (testing "the page is written from the spec"
      (is (= 200 status))
      (is (str/includes? body "<title>a title</title>"))
      (is (str/includes? body "/style.css"))
      (testing "with a div per mount holding its first render"
        (is (str/includes? body "<div id=\"app\"><p>hello</p></div>"))))

    (testing "every script the page carries is named by the policy"
      (is (some? nonce))
      (is (pos? (count (re-seq #"nonce=" body))))
      (is (= (count (re-seq #"nonce=" body))
             (count (re-seq (re-pattern (str "nonce=\"" nonce "\"")) body)))))

    (testing "no script may be built from a string"
      (is (not (str/includes? csp "unsafe-eval")))
      (is (not (str/includes? csp "unsafe-inline"))))

    (testing "a second request is named by a nonce of its own"
      (is (not= nonce (nonce-of (get-in (ui {:uri "/"}) [:headers "Content-Security-Policy"])))))

    (testing "a request the page does not own is declined rather than answered"
      (is (nil? (ui {:uri "/feed.xml"}))))))

(defn- index-spec
  "A spec whose page is the given file rather than one Buzz writes."
  [html]
  (let [f (fs/create-temp-file {:suffix ".html"})]
    (fs/delete-on-exit f)
    (spit (fs/file f) html)
    {:index (str f)
     :mounts [{:el "app" :component (fn [_] (greeting))}]}))

;; With an :index the page is yours. Buzz fills in the mount comments and the
;; nonce, and touches nothing else.
(deftest an-index-file-is-filled-in-rather-than-written
  (let [ui (handler/handler
            (index-spec (str "<!DOCTYPE html>\n<html>\n<body>\n"
                             "<h1>mine</h1>\n"
                             "<div id=\"app\"><!--app--></div>\n"
                             "<script type=\"importmap\" nonce=\"NONCE\">{}</script>\n"
                             "</body>\n</html>\n")))
        {:keys [status headers body]} (ui {:uri "/"})
        nonce (nonce-of (get headers "Content-Security-Policy"))]

    (testing "the comment becomes the first render of that mount"
      (is (= 200 status))
      (is (str/includes? body "<div id=\"app\"><p>hello</p></div>"))
      (is (not (str/includes? body "<!--app-->"))))

    (testing "NONCE becomes the nonce the policy names"
      (is (str/includes? body (str "nonce=\"" nonce "\"")))
      (is (not (str/includes? body "NONCE"))))

    (testing "the rest of the file is left alone"
      (is (str/includes? body "<h1>mine</h1>")))))

(deftest an-index-without-a-comment-is-still-served
  (let [ui (handler/handler (index-spec "<html><body><div id=\"app\"></div></body></html>"))
        {:keys [status body]} (ui {:uri "/"})]
    (testing "the page arrives empty and the browser fills it in"
      (is (= 200 status))
      (is (str/includes? body "<div id=\"app\"></div>"))
      (is (not (str/includes? body "hello"))))))

(defn- mount-state
  "The atoms one mount of one connection owns."
  [session el]
  (:state (first (filter #(= el (:el %)) (:mounted (get @handler/conns session))))))

;; A page can hold more than one mount. Each gets its own instance, its own
;; state and its own slots, so nothing one does reaches the other.
(defui left-tally [n]
  [:p (server @n) [:button {:on-click (fn [_] (server! (swap! n inc)))} "+"]])

(defui right-tally [n]
  [:p (server @n) [:button {:on-click (fn [_] (server! (swap! n dec)))} "-"]])

(def ^:private two-mounts-spec
  {:title "two"
   :mounts [{:el "left"
             :state (fn [_req] {:n (atom 0)})
             :component (fn [st] (left-tally (:n st)))}
            {:el "right"
             :state (fn [_req] {:n (atom 100)})
             :component (fn [st] (right-tally (:n st)))}]})

(deftest each-mount-is-its-own
  (with-connection two-mounts-spec
    (fn [{:keys [sock rdr session port]}]

      (testing "every mount arrives with the value it was built with"
        (is (= ["mount" "left-tally" "left" [0]] (next-event rdr)))
        (is (= ["mount" "right-tally" "right" [100]] (next-event rdr))))

      (testing "writing one mount's state patches that mount alone"
        (swap! (:n (mount-state session "left")) inc)
        (is (= ["patch" "left-tally" [1]] (next-event rdr)))
        (is (silent? sock rdr 300)))

      (testing "a handler belongs to the mount it came from"
        (is (= 204 (first (rpc port session "right-tally/0" []))))
        (is (= 1 @(:n (mount-state session "left"))))
        (is (= 99 @(:n (mount-state session "right"))))
        (is (= ["patch" "right-tally" [99]] (next-event rdr)))))))

;; The headline the readme makes: state the server owns is the same for every
;; browser, and state a browser owns is its own.
(def ^:private shared (atom 0))

(defui ticker [seen]
  [:p (server @shared) (server @seen)])

(def ^:private ticker-spec
  {:title "ticker"
   :watch [shared]
   :mounts [{:el "app"
             :state (fn [_req] {:seen (atom 0)})
             :component (fn [st] (ticker (:seen st)))}]})

(deftest a-watched-atom-reaches-every-connection
  (reset! shared 0)
  (with-connection ticker-spec
    (fn [one]
      (let [two (open-events (:port one))]
        (try
          (testing "each browser mounts with its own instance"
            (is (not= (:session one) (:session two)))
            (is (= ["mount" "ticker" "app" [0 0]] (next-event (:rdr one))))
            (is (= ["mount" "ticker" "app" [0 0]] (next-event (:rdr two)))))

          (testing "a write to watched state reaches both"
            (swap! shared inc)
            (is (= ["patch" "ticker" [1 0]] (next-event (:rdr one))))
            (is (= ["patch" "ticker" [1 0]] (next-event (:rdr two)))))

          (testing "a write to one browser's own state reaches only that browser"
            (swap! (:seen (connection-state (:session two))) inc)
            (is (= ["patch" "ticker" [1 1]] (next-event (:rdr two))))
            (is (silent? (:sock one) (:rdr one) 300)))

          (testing "so the two browsers hold different values"
            (is (= 0 @(:seen (connection-state (:session one)))))
            (is (= 1 @(:seen (connection-state (:session two))))))

          (finally (.close (:sock two))))))))

(defn- until
  "Polls until `f` answers, or the deadline passes. A close is noticed on
  another thread, so this waits for it rather than assuming it has happened."
  [ms f]
  (let [deadline (+ (System/currentTimeMillis) ms)]
    (loop []
      (or (f)
          (when (< (System/currentTimeMillis) deadline)
            (Thread/sleep 10)
            (recur))))))

(defui leaky [q]
  [:p (server @q)])

(def ^:private leaky-spec
  {:title "leaky"
   :mounts [{:el "app"
             :state (fn [_req] {:q (atom 0)})
             :component (fn [st] (leaky (:q st)))}]})

;; A browser that goes away takes its instances and its watches with it.
;; Otherwise every reload of a page leaves another watch on the atoms behind it.
(deftest a-closed-connection-is-forgotten
  (with-connection leaky-spec
    (fn [{:keys [sock rdr session]}]
      (let [q (:q (connection-state session))]

        (testing "while it is open the connection is watching its own state"
          (is (= ["mount" "leaky" "app" [0]] (next-event rdr)))
          (is (seq (.getWatches q))))

        (.close sock)

        (testing "closing it drops the connection"
          (is (until 3000 #(not (contains? @handler/conns session)))))

        (testing "and the watches on its state go with it"
          (is (until 3000 #(empty? (.getWatches q)))))))))

;; A part leaves no trace at runtime: it is spliced in when the component that
;; uses it expands. Editing one has to expand those components again, or they
;; keep running the JavaScript the old part produced.
(defpart badge [^:server q]
  [:em (server @q)])

(defui card [q]
  [:p (badge q)])

(def ^:private wider-badge
  '(defpart badge [^:server q] [:em (server @q) (server (inc @q))]))

(def ^:private narrow-badge
  '(defpart badge [^:server q] [:em (server @q)]))

(def ^:private card-spec
  {:title "card"
   :mounts [{:el "app"
             :state (fn [_req] {:q (atom 0)})
             :component (fn [st] (card (:q st)))}]})

(deftest editing-a-part-reloads-the-components-that-use-it
  (with-connection card-spec
    (fn [{:keys [rdr session]}]
      (let [q (:q (connection-state session))]
        (try
          (testing "the part contributes its slot to the component"
            (is (= ["mount" "card" "app" [0]] (next-event rdr))))

          (testing "editing the part reloads the component, which was not touched"
            (redefine! wider-badge)
            (let [[kind _rev id vals] (next-event rdr)]
              (is (= "reload" kind))
              (is (= "card" id))
              (is (= [0 1] vals))))

          (testing "and the component now runs what the new part produced"
            (swap! q inc)
            (is (= ["patch" "card" [1 2]] (next-event rdr))))

          (finally (redefine! narrow-badge)))))))

;; What the browser imports. The registry entries are read by client.cljs as
;; `.-f`, `.-init` and `.-nlocals`, so the names here are a contract between two
;; files that nothing else holds together.
(defui gauge [q]
  (let [seen (local-state 0)]
    [:p (server @q) @seen]))

(def ^:private gauge-spec
  {:title "gauge"
   :mounts [{:el "app"
             :state (fn [_req] {:q (atom 0)})
             :component (fn [st] (gauge (:q st)))}]})

(deftest the-browser-is-served-the-modules-it-imports
  (let [ui (handler/handler gauge-spec)]

    (testing "the runtime is compiled here and served as a module"
      (doseq [uri ["/client.mjs" "/rpc.mjs" "/components.mjs"]]
        (let [{:keys [status headers body]} (ui {:uri uri})]
          (is (= 200 status) uri)
          (is (= "text/javascript" (get headers "Content-Type")) uri)
          (testing "and never cached, so an edit is never served stale"
            (is (= "no-store" (get headers "Cache-Control")) uri))
          (is (pos? (count body)) uri))))

    (testing "the components module imports what it needs"
      (let [body (:body (ui {:uri "/components.mjs"}))]
        (is (str/includes? body "import * as SQ from \"squint-cljs/core.js\""))
        (is (str/includes? body "from \"/rpc.mjs\""))

        (testing "and holds one entry per component, named as the client reads it"
          (is (str/includes? body "\"gauge\": {f: "))
          (is (str/includes? body ", init: "))
          (is (str/includes? body ", nlocals: 1}")))))

    (testing "a module the page does not serve is declined"
      (is (nil? (ui {:uri "/nope.mjs"}))))))

;; A handler is a closure over the state its component was built with, and never
;; sees a request. So the request that opened the connection is the one chance to
;; put an identity somewhere the component and its handlers can reach.
(defui greeter [who]
  [:p (server @who)
   [:button {:on-click (fn [_] (server! (reply @who)))} "who am i"]])

(def ^:private greeter-spec
  {:title "greeter"
   :mounts [{:el "app"
             :state (fn [req] {:who (atom (get-in req [:headers "x-user"] "nobody"))})
             :component (fn [st] (greeter (:who st)))}]})

(deftest a-mount-builds-its-state-from-the-request
  (with-connection greeter-spec
    (fn [{:keys [rdr port] :as alice}]
      (let [bob (open-events port {"X-User" "bob"})]
        (try
          (testing "the state fn reads whoever opened the connection"
            (is (= ["mount" "greeter" "app" ["alice"]] (next-event rdr))))

          (testing "and another browser gets its own"
            (is (= ["mount" "greeter" "app" ["bob"]] (next-event (:rdr bob)))))

          (testing "a handler acts as the identity its connection was built with"
            (is (= [200 "\"alice\""] (rpc port (:session alice) "greeter/0" [])))
            (is (= [200 "\"bob\""] (rpc port (:session bob) "greeter/0" []))))

          (finally (.close (:sock bob))))))))

(deftest the-first-paint-is-rendered-for-the-request-that-asked
  (let [ui (handler/handler greeter-spec)
        body (:body (ui {:uri "/" :headers {"x-user" "carol"}}))]
    (testing "so a page arrives already showing who is looking at it"
      (is (str/includes? body "<div id=\"app\"><p>carol"))
      (is (not (str/includes? body "nobody"))))))
