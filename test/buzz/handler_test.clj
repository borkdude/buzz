(ns buzz.handler-test
  (:require [babashka.fs :as fs]
            [buzz.core :refer [defpart defui local-state reply request server server!]]
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
  ([port headers] (open-events port headers "/events"))
  ([port headers uri]
   (let [sock (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes (str "GET " uri " HTTP/1.1\r\nHost: localhost\r\n"
                              (apply str (for [[k v] headers] (str k ": " v "\r\n")))
                              "\r\n")))
      (.flush))
    (let [rdr (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))
          ;; the token Buzz hands out, which an rpc has to come back with
          token (loop [tok nil]
                  (let [line (.readLine rdr)]
                    (cond
                      (or (nil? line) (str/blank? line)) tok
                      (str/starts-with? (str/lower-case line) "set-cookie:")
                      (recur (second (re-find #"buzz-browser=([^;]+)" line)))
                      :else (recur tok))))
          [_ session] (next-event rdr)]
      {:sock sock :rdr rdr :session session :token token :port port}))))

(defn- with-connection
  "Serves `spec` on a free port and opens one connection to it, as user alice."
  [spec f]
  (let [ui   (handler/handler spec)
        stop (http/run-server (fn [req] (or (ui req) {:status 404 :body "no"}))
                              {:port 0})
        ;; asking for port 0 and reading back what was bound leaves no window
        ;; for another process to take the port first
        port (:local-port (meta stop))
        {:keys [sock] :as conn} (open-events port {"X-User" "alice"})]
    (try
      (f (assoc conn :ui ui))
      (finally
        (.close sock)
        (stop)))))

(defn- registry-of
  "The handler's own connections, riding on its meta for tests to look at."
  [ui]
  @(::handler/registry (meta ui)))

(defn- rpc
  "One POST to /rpc, the way the browser makes it, carrying the token the stream
  handed out. Returns [status body]."
  [{:keys [port session token uri no-header] :or {uri "/rpc"}} handler-id args]
  (let [payload (.getBytes (json/generate-string [session handler-id args]) "UTF-8")]
    (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
      (.setSoTimeout sock 5000)
      (doto (.getOutputStream sock)
        ;; asking to close lets the body be read to the end of the stream,
        ;; without a length to parse first
        (.write (.getBytes (str "POST " uri " HTTP/1.1\r\n"
                                "Host: localhost\r\n"
                                "Content-Type: application/json\r\n"
                                (when-not no-header "X-Buzz-RPC: 1\r\n")
                                (when token (str "Cookie: buzz-browser=" token "\r\n"))
                                "Content-Length: " (alength payload) "\r\n"
                                "Connection: close\r\n\r\n")
                           "UTF-8"))
        (.write payload)
        (.flush))
      (let [[head body] (str/split (slurp (.getInputStream sock)) #"\r\n\r\n" 2)
            status (second (str/split (first (str/split-lines head)) #" "))]
        [(Integer/parseInt status) (or body "")]))))

;; Two slots over a watched atom. Redefining this is the reload.
(def ^:private panel-q (atom 0))

(defui panel []
  [:p (server @panel-q) (server (inc @panel-q))])

(def ^:private two-slots '(defui panel [] [:p (server @panel-q) (server (inc @panel-q))]))
(def ^:private one-slot '(defui panel [] [:p (server @panel-q)]))

(defn- redefine!
  "Re-evaluates a defui here, which is what a REPL does. The runner is in
  another namespace, so this says which one."
  [form]
  (binding [*ns* (the-ns 'buzz.handler-test)]
    (eval form)))

(def ^:private panel-spec
  {:title "panel"
   :watch [panel-q]
   :mounts [{:el "app" :ui #'panel}]})

;; Re-evaluating a defui rebuilds every open connection. The instance a
;; connection holds and the watches feeding it have to be replaced together, or
;; a later patch carries the slots of code the browser has stopped running.
(deftest a-reload-replaces-what-a-connection-watches
  (reset! panel-q 0)
  (with-connection panel-spec
    (fn [{:keys [rdr]}]
      (try
        (testing "a connection is mounted with the slots of the current code"
          (is (= ["mount" "panel" "app" [0 1]] (next-event rdr))))

        (testing "changing watched state patches the connection"
          (swap! panel-q inc)
          (is (= ["patch" "panel" [1 2]] (next-event rdr))))

        (testing "redefining the component sends the new shape"
          (redefine! one-slot)
          (let [[kind _rev id vals] (next-event rdr)]
            (is (= "reload" kind))
            (is (= "panel" id))
            (is (= [1] vals))))

        (testing "and a later change patches with the new shape, not the old"
          (swap! panel-q inc)
          (is (= ["patch" "panel" [2]] (next-event rdr))))

        (finally (redefine! two-slots))))))

;; The slot reads one key. The other is there to be written without the browser
;; hearing about it.
(def ^:private board-st (atom {:shown 0 :hidden 0}))

(defui board []
  [:p (server (:shown @board-st))])

(def ^:private board-spec
  {:title "board"
   :watch [board-st]
   :mounts [{:el "app" :ui #'board}]})

;; A watched atom says something was written, not that this mount has anything
;; to say. Rather than declare what a component reads, the slots are run and the
;; frame is sent only when a value differs.
(deftest a-write-that-changes-no-slot-sends-nothing
  (reset! board-st {:shown 0 :hidden 0})
  (with-connection board-spec
    (fn [{:keys [sock rdr]}]

      (testing "the connection is mounted with the value the slot reads"
        (is (= ["mount" "board" "app" [0]] (next-event rdr))))

      (testing "writing state no slot reads sends nothing"
        (swap! board-st update :hidden inc)
        (is (silent? sock rdr 300)))

      (testing "writing the same value again sends nothing"
        (swap! board-st update :shown identity)
        (is (silent? sock rdr 300)))

      (testing "a value that differs is sent"
        (swap! board-st update :shown inc)
        (is (= ["patch" "board" [1]] (next-event rdr)))))))

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
   :mounts [{:el "app" :ui #'desk}]})

(deftest a-handler-answers-over-rpc
  (reset! log [])
  (with-connection desk-spec
    (fn [conn]

      (testing "a handler with no reply says so rather than sending a body"
        (is (= [204 ""] (rpc conn "desk/0" [])))
        (is (= [:quiet] @log)))

      (testing "a reply carries the value back"
        (is (= [200 "1"] (rpc conn "desk/1" []))))

      (testing "a handler that throws fails the browser's promise"
        (let [[status body] (rpc conn "desk/2" [])]
          (is (= 500 status))
          (is (= {"error" "handler failed"} (json/parse-string body)))
          (testing "and says nothing about why"
            (is (not (str/includes? body "database password"))))))

      (testing "an unknown handler is not found"
        (is (= [404 "{\"error\":\"no such handler\"}"]
               (rpc conn "desk/9" []))))

      (testing "a handler cannot be reached without a session"
        (is (= 404 (first (rpc (assoc conn :session "made-up") "desk/0" []))))))))

;; No slots and no handlers. This one is only here to be rendered into a page.
(defui greeting []
  [:p "hello"])

(def ^:private page-spec
  {:title "a title"
   :head "<link rel=\"stylesheet\" href=\"/style.css\">"
   :mounts [{:el "app" :ui #'greeting}]})

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
     :mounts [{:el "app" :ui #'greeting}]}))

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

;; A page can hold more than one mount. Each gets its own instance and its
;; own slots, so nothing one does reaches the other.
(def ^:private left-n (atom 0))
(def ^:private right-n (atom 100))

(defui left-tally []
  [:p (server @left-n) [:button {:on-click (fn [_] (server! (swap! left-n inc)))} "+"]])

(defui right-tally []
  [:p (server @right-n) [:button {:on-click (fn [_] (server! (swap! right-n dec)))} "-"]])

(def ^:private two-mounts-spec
  {:title "two"
   :watch [left-n right-n]
   :mounts [{:el "left" :ui #'left-tally}
            {:el "right" :ui #'right-tally}]})

(deftest each-mount-is-its-own
  (reset! left-n 0)
  (reset! right-n 100)
  (with-connection two-mounts-spec
    (fn [{:keys [sock rdr] :as conn}]

      (testing "every mount arrives with the value it was built with"
        (is (= ["mount" "left-tally" "left" [0]] (next-event rdr)))
        (is (= ["mount" "right-tally" "right" [100]] (next-event rdr))))

      (testing "writing what one mount reads patches that mount alone"
        (swap! left-n inc)
        (is (= ["patch" "left-tally" [1]] (next-event rdr)))
        (is (silent? sock rdr 300)))

      (testing "a handler belongs to the mount it came from"
        (is (= 204 (first (rpc conn "right-tally/0" []))))
        (is (= 1 @left-n))
        (is (= 99 @right-n))
        (is (= ["patch" "right-tally" [99]] (next-event rdr)))))))

;; The headline the readme makes: state the server owns is the same for every
;; browser, and state a browser owns is its own.
(def ^:private shared (atom 0))

(def ^:private seen (atom {}))

(defui ticker []
  [:p (server @shared) (server (get @seen (handler/connection (request)) 0))])

(def ^:private ticker-spec
  {:title "ticker"
   :watch [shared seen]
   :mounts [{:el "app" :ui #'ticker}]})

(deftest a-watched-atom-reaches-every-connection
  (reset! shared 0)
  (reset! seen {})
  (with-connection ticker-spec
    (fn [one]
      (let [two (open-events (:port one))]
        (try
          (testing "each browser mounts with the values its request selects"
            (is (not= (:session one) (:session two)))
            (is (= ["mount" "ticker" "app" [0 0]] (next-event (:rdr one))))
            (is (= ["mount" "ticker" "app" [0 0]] (next-event (:rdr two)))))

          (testing "a write to watched state reaches both"
            (swap! shared inc)
            (is (= ["patch" "ticker" [1 0]] (next-event (:rdr one))))
            (is (= ["patch" "ticker" [1 0]] (next-event (:rdr two)))))

          (testing "a write under one connection's key patches only that one:
                    the others' slots run and their values did not change"
            (swap! seen assoc (:session two) 1)
            (is (= ["patch" "ticker" [1 1]] (next-event (:rdr two))))
            (is (silent? (:sock one) (:rdr one) 300)))

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

(defui leaky []
  [:p (server 0)])

(def ^:private left-behind (atom nil))

(def ^:private leaky-spec
  {:title "leaky"
   :mounts [{:el "app" :ui #'leaky}]
   :on-close (fn [conn] (reset! left-behind conn))})

;; A browser that goes away is dropped from its handler's registry, and the
;; application hears which connection it was, so state it keyed goes with it.
(deftest a-closed-connection-is-forgotten
  (reset! left-behind nil)
  (with-connection leaky-spec
    (fn [{:keys [sock rdr session ui]}]

      (testing "while it is open the registry holds it"
        (is (= ["mount" "leaky" "app" [0]] (next-event rdr)))
        (is (contains? (registry-of ui) session)))

      (.close sock)

      (testing "closing it drops the connection"
        (is (until 3000 #(not (contains? (registry-of ui) session)))))

      (testing "and the app is told which one ended"
        (is (until 3000 #(= session @left-behind)))))))

(defpart badge [n]
  [:em n])

(def ^:private card-q (atom 0))

(defui card []
  [:p (badge (server @card-q))])

(def ^:private louder-badge '(defpart badge [n] [:em n "!"]))
(def ^:private plain-badge  '(defpart badge [n] [:em n]))

(def ^:private card-spec
  {:title "card"
   :watch [card-q]
   :mounts [{:el "app" :ui #'card}]})

(deftest editing-a-part-reloads-the-pages-that-show-it
  (reset! card-q 0)
  (with-connection card-spec
    (fn [{:keys [rdr]}]
      (try
        (testing "the component passes its slot through the part"
          (is (= ["mount" "card" "app" [0]] (next-event rdr))))

        (testing "editing the part reloads the page"
          (redefine! louder-badge)
          (let [[kind _rev id vals] (next-event rdr)]
            (is (= "reload" kind))
            (is (= "card" id))
            (is (= [0] vals))))

        (testing "the module contains the edited part"
          (is (str/includes? (:body ((handler/handler card-spec) {:uri "/components.mjs"}))
                             "\"!\"")))

        (finally (redefine! plain-badge))))))

(def ^:private steps (atom 0))

(defpart step-button [label]
  [:button {:on-click (fn [_] (server! (swap! steps inc)))} label])

(defui stepped-panel []
  [:div (server @steps) (step-button "go")])

(def ^:private stepped-spec
  {:title "stepped"
   :watch [steps]
   :mounts [{:el "app" :ui #'stepped-panel}]})

(deftest a-function-part-serves-and-answers-through-its-component
  (reset! steps 0)
  (with-connection stepped-spec
    (fn [{:keys [rdr] :as conn}]
      (testing "the mount carries the component's slot"
        (is (= ["mount" "stepped-panel" "app" [0]] (next-event rdr))))

      (testing "the module defines the part the component calls"
        (let [body (:body ((handler/handler stepped-spec) {:uri "/components.mjs"}))]
          (is (str/includes? body "const buzz_DOT_handler_test_SLASH_step_button = "))
          (is (str/includes? body "buzz_DOT_handler_test_SLASH_step_button("))))

      (testing "the mount dispatches to the part handler"
        (is (= 204 (first (rpc conn "buzz.handler-test/step-button/0" []))))
        (is (= 1 @steps))
        (is (= ["patch" "stepped-panel" [1]] (next-event rdr)))))))

;; The request mark, end to end. A slot reads the request that opened the
;; stream, a handler reads the rpc that carried the call, and the connection
;; accessor names the same connection in both, so state keyed by it agrees.
(defonce ^:private seen-conn (atom nil))
(defonce ^:private closed (atom nil))

(defui req-panel []
  [:div
   [:p (server (str (get-in (request) [:headers "x-user"])
                    "/" (some? (handler/connection (request)))))]
   [:button {:on-click (fn [_] (server! (reply (do (reset! seen-conn (handler/connection (request)))
                                                   (get-in (request) [:headers "x-user"] "nobody")))))}
    "who"]])

(def ^:private req-spec
  {:title "req"
   :mounts [{:el "app" :ui #'req-panel}]
   :on-close (fn [session] (reset! closed session))})

(deftest the-request-reaches-slots-and-handlers
  (reset! seen-conn nil)
  (reset! closed nil)
  (with-connection req-spec
    (fn [{:keys [sock rdr session] :as conn}]

      (testing "a slot reads the request that opened the stream"
        (is (= ["mount" "req-panel" "app" ["alice/true"]] (next-event rdr))))

      (testing "a handler reads the rpc, which carries no x-user header"
        (is (= [200 "\"nobody\""] (rpc conn "req-panel/0" []))))

      (testing "slot and handler name the same connection"
        (is (= session @seen-conn)))

      (testing "closing the stream tells the app which connection ended"
        (.close sock)
        (is (until 3000 #(= session @closed)))))))

;; A `:ui` mount names its component by var. Nothing closes over a
;; connection, so one instance serves them all, and the request keeps their
;; renders apart.
(defui shared-ui []
  [:p (server (get-in (request) [:headers "x-user"] "nobody"))])

(def ^:private shared-spec
  {:title "shared"
   :mounts [{:el "app" :ui #'shared-ui}]})

(def ^:private louder-shared
  '(defui shared-ui [] [:p (server (get-in (request) [:headers "x-user"] "nobody")) "!"]))

(def ^:private plain-shared
  '(defui shared-ui [] [:p (server (get-in (request) [:headers "x-user"] "nobody"))]))

(deftest a-ui-var-names-one-instance-for-every-connection
  (with-connection shared-spec
    (fn [{:keys [rdr session port ui]}]
      (is (= ["mount" "shared-ui" "app" ["alice"]] (next-event rdr)))
      (let [bob (open-events port {"X-User" "bob"})]
        (try
          (testing "each connection renders from its own request"
            (is (= ["mount" "shared-ui" "app" ["bob"]] (next-event (:rdr bob)))))

          (testing "through one shared instance"
            (is (identical? (:instance (first (:mounted (get (registry-of ui) session))))
                            (:instance (first (:mounted (get (registry-of ui) (:session bob))))))))

          (testing "a reload builds the next instance, once, for both"
            (try
              (redefine! louder-shared)
              (is (= "reload" (first (next-event rdr))))
              (is (= "reload" (first (next-event (:rdr bob)))))
              (is (identical? (:instance (first (:mounted (get (registry-of ui) session))))
                              (:instance (first (:mounted (get (registry-of ui) (:session bob)))))))
              (finally (redefine! plain-shared))))

          (finally (.close (:sock bob))))))))

;; What the browser imports. The registry entries are read by client.cljs as
;; `.-f`, `.-init` and `.-nlocals`, so the names here are a contract between two
;; files that nothing else holds together.
(def ^:private gauge-q (atom 0))

(defui gauge []
  (let [seen (local-state 0)]
    [:p (server @gauge-q) @seen]))

(def ^:private gauge-spec
  {:title "gauge"
   :watch [gauge-q]
   :mounts [{:el "app" :ui #'gauge}]})

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

;; A slot reads the request that opened the stream, so the page mounts already
;; knowing who is looking at it.
(defui greeter []
  [:p (server (get-in (request) [:headers "x-user"] "nobody"))
   [:button {:on-click (fn [_] (server! (reply (get-in (request) [:headers "x-user"] "nobody"))))}
    "who am i"]])

(def ^:private greeter-spec
  {:title "greeter"
   :mounts [{:el "app" :ui #'greeter}]})

;; A session id arrives in the rpc body, so on its own it would be a second way
;; to be someone: learn one, send it, and the connection answers. The token
;; Buzz hands a browser is what ties the two together.
(deftest a-connection-answers-only-the-browser-that-opened-it
  (with-connection greeter-spec
    (fn [{:keys [port] :as alice}]
      (let [bob (open-events port {"X-User" "bob"})]
        (try
          (testing "another browser cannot drive a connection by its session id"
            (is (= 404 (first (rpc (assoc bob :token (:token alice))
                                   "greeter/0" [])))))

          (testing "and neither can a browser carrying no token at all"
            (is (= 404 (first (rpc (dissoc bob :token) "greeter/0" [])))))

          (testing "the browser that opened it still gets through, and the
                    handler reads its own rpc, which carries no name"
            (is (= [200 "\"nobody\""] (rpc bob "greeter/0" []))))

          (testing "and only when it asks the way the runtime does"
            (is (= 404 (first (rpc (assoc bob :no-header true) "greeter/0" [])))))

          (finally (.close (:sock bob))))))))

;; Each handler holds its own registry, so a session id from one page finds
;; nothing at another one's endpoint. Two handlers on one server is the
;; ordinary arrangement: a sign in page and the page behind it.
(deftest a-connection-answers-only-through-the-page-that-built-it
  (let [main  (handler/handler greeter-spec)
        other (handler/handler (assoc greeter-spec :path "/other"))
        stop  (http/run-server (fn [req] (or (other req) (main req) {:status 404 :body "no"}))
                               {:port 0})
        port  (:local-port (meta stop))]
    (try
      (let [conn (open-events port {"X-User" "alice"} "/other/events")]
        (try
          (testing "the page that built the connection answers it"
            (is (= [200 "\"nobody\""]
                   (rpc (assoc conn :uri "/other/rpc") "greeter/0" []))))

          (testing "another page's rpc endpoint does not, though the id is the same"
            (is (= 404 (first (rpc (assoc conn :uri "/rpc") "greeter/0" [])))))

          (finally (.close (:sock conn)))))
      (finally (stop)))))

(deftest the-first-paint-is-rendered-for-the-request-that-asked
  (let [ui (handler/handler greeter-spec)
        body (:body (ui {:uri "/" :headers {"x-user" "carol"}}))]
    (testing "so a page arrives already showing who is looking at it"
      (is (str/includes? body "<div id=\"app\"><p>carol"))
      (is (not (str/includes? body "nobody"))))))

;; The page belongs to the handler, not to the library. Two of them in one
;; process used to share one global, so the second silently replaced the first.
(defui doorway [] [:p "the doorway"])
(defui parlour [] [:p "the parlour"])

(def ^:private doorway-spec
  {:title "doorway" :mounts [{:el "door" :ui #'doorway}]})

(def ^:private parlour-spec
  {:title "parlour" :mounts [{:el "room" :ui #'parlour}]})

(deftest two-handlers-serve-two-pages
  (let [door  (handler/handler doorway-spec)
        room  (handler/handler parlour-spec)
        page  (fn [ui] (:body (ui {:uri "/"})))]

    (testing "each one keeps the title it was given"
      (is (str/includes? (page door) "<title>doorway</title>"))
      (is (str/includes? (page room) "<title>parlour</title>")))

    (testing "and its own mount, rendered into its own element"
      (is (str/includes? (page door) "<div id=\"door\"><p>the doorway</p></div>"))
      (is (str/includes? (page room) "<div id=\"room\"><p>the parlour</p></div>")))

    (testing "so does the module each browser imports"
      (is (str/includes? (:body (door {:uri "/components.mjs"})) "\"doorway\": {f: "))
      (is (not (str/includes? (:body (door {:uri "/components.mjs"})) "parlour"))))

    (testing "the one built first is not the one that changed"
      (is (str/includes? (page door) "the doorway")))))

;; A handler under a `:path` has to answer for its own stream and its own
;; modules. The browser asks for whatever URLs the page it was served names, so
;; the path goes into the compiled runtime rather than into a global.
(def ^:private door-at-path
  {:title "doorway" :path "/signin"
   :mounts [{:el "door" :ui #'doorway}]})

(deftest a-handler-answers-under-its-own-path
  (let [door (handler/handler door-at-path)
        room (handler/handler parlour-spec)]

    (testing "the page is served at the path, with or without a slash"
      (is (= 200 (:status (door {:uri "/signin"}))))
      (is (= 200 (:status (door {:uri "/signin/"})))))

    (testing "and not at the root, which belongs to the other one"
      (is (nil? (door {:uri "/"})))
      (is (= 200 (:status (room {:uri "/"})))))

    (testing "the page names its own runtime"
      (is (str/includes? (:body (door {:uri "/signin"})) "src=\"/signin/client.mjs\"")))

    (testing "which asks for its own stream and its own rpc"
      (let [client (:body (door {:uri "/signin/client.mjs"}))
            rpc-js (:body (door {:uri "/signin/rpc.mjs"}))]
        (is (str/includes? client "EventSource(\"/signin/events\")"))
        (is (str/includes? rpc-js "fetch(\"/signin/rpc\""))))

    (testing "and its own components, importing its own rpc module"
      (let [body (:body (door {:uri "/signin/components.mjs"}))]
        (is (str/includes? body "\"doorway\": {f: "))
        (is (str/includes? body "from \"/signin/rpc.mjs\""))
        (is (not (str/includes? body "parlour")))))

    (testing "a handler with no path is unchanged"
      (is (str/includes? (:body (room {:uri "/"})) "src=\"/client.mjs\""))
      (is (str/includes? (:body (room {:uri "/client.mjs"})) "EventSource(\"/events\")")))))

;; `(reply v resp)` answers with a value and adds to the http response it
;; arrives in, which is how a handler sets a cookie.
(defui gate []
  [:div
   [:button {:on-click (fn [_] (server! (reply :in {:headers {"Set-Cookie" "who=alice; HttpOnly"}})))} "in"]
   [:button {:on-click (fn [_] (server! (reply :plain)))} "plain"]
   [:button {:on-click (fn [_] (server! (reply :made {:status 201})))} "made"]])

(def ^:private gate-spec
  {:title "gate" :mounts [{:el "app" :ui #'gate}]})

(defn- rpc-raw
  "The whole response, headers and all."
  [{:keys [port session token]} handler-id]
  (let [payload (.getBytes (json/generate-string [session handler-id []]) "UTF-8")]
    (with-open [sock (java.net.Socket. "127.0.0.1" (int port))]
      (.setSoTimeout sock 5000)
      (doto (.getOutputStream sock)
        (.write (.getBytes (str "POST /rpc HTTP/1.1\r\nHost: localhost\r\n"
                                "X-Buzz-RPC: 1\r\n"
                                (when token (str "Cookie: buzz-browser=" token "\r\n"))
                                "Content-Length: " (alength payload) "\r\n"
                                "Connection: close\r\n\r\n")))
        (.write payload)
        (.flush))
      (slurp (.getInputStream sock)))))

(deftest a-reply-can-add-to-its-response
  (with-connection gate-spec
    (fn [conn]

      (testing "the value still arrives as the body"
        (let [raw (rpc-raw conn "gate/0")]
          (is (str/includes? raw "\"in\""))

          (testing "and the header is on the wire"
            (is (str/includes? raw "Set-Cookie: who=alice; HttpOnly")))

          (testing "beside the ones the response already had"
            (is (str/includes? raw "Content-Type: application/json")))))

      (testing "a reply with no response is unchanged"
        (let [raw (rpc-raw conn "gate/1")]
          (is (str/includes? raw "\"plain\""))
          (is (not (str/includes? raw "Set-Cookie")))))

      (testing "anything else in the map is part of the response too"
        (is (str/includes? (rpc-raw conn "gate/2") "HTTP/1.1 201"))))))
