(ns buzz.handler-test
  (:require [buzz.core :refer [defui server]]
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
        sock (java.net.Socket. "127.0.0.1" (int (:local-port (meta stop))))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"))
      (.flush))
    (let [rdr (java.io.BufferedReader.
               (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))]
      (try
        (let [[_ session] (next-event rdr)]
          (f {:sock sock :rdr rdr :session session}))
        (finally
          (.close sock)
          (stop))))))

(defn- connection-state
  "The atoms one connection owns, which is what a REPL reaches for too."
  [session]
  (:state (first (:mounted (get @handler/conns session)))))

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
