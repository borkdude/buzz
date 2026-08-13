(ns buzz.handler-test
  (:require [buzz.core :refer [defui reply server server!]]
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

(defn- with-connection
  "Serves `spec` on a free port and opens one raw /events connection. Reading
  SSE by hand keeps the assertions on what the browser is actually sent."
  [spec f]
  (let [stop (http/run-server (fn [req] (or ((handler/handler spec) req)
                                             {:status 404 :body "no"}))
                              {:port 0})
        ;; asking for port 0 and reading back what was bound leaves no window
        ;; for another process to take the port first
        port (:local-port (meta stop))
        sock (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"))
      (.flush))
    (let [rdr (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))]
      (try
        (let [[_ session] (next-event rdr)]
          (f {:sock sock :rdr rdr :session session :port port}))
        (finally
          (.close sock)
          (stop))))))

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
             :state (fn [] {:q (atom 0)})
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
             :state (fn [] {:board (atom {:shown 0 :hidden 0})})
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
