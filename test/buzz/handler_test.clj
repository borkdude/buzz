(ns buzz.handler-test
  (:require [buzz.core :refer [defui server]]
            [buzz.handler :as handler]
            [cheshire.core :as json]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [org.httpkit.server :as http]))

;; Two slots over an atom the connection owns. Redefining this is the reload.
(defui panel [q]
  [:p (server @q) (server (inc @q))])

(def ^:private spec
  {:title "panel"
   :mounts [{:el "app"
             :state (fn [] {:q (atom 0)})
             :component (fn [state] (panel (:q state)))}]})

(defn- free-port []
  (with-open [s (java.net.ServerSocket. 0)]
    (.getLocalPort s)))

(defn- open-events
  "A raw connection to /events. Reading SSE by hand keeps the test to what the
  browser is actually sent."
  [port]
  (let [sock (java.net.Socket. "127.0.0.1" (int port))]
    (.setSoTimeout sock 5000)
    (doto (.getOutputStream sock)
      (.write (.getBytes "GET /events HTTP/1.1\r\nHost: localhost\r\n\r\n"))
      (.flush))
    [sock (java.io.BufferedReader.
           (java.io.InputStreamReader. (.getInputStream sock) "UTF-8"))]))

(defn- next-event
  "The next SSE frame. Headings, chunk sizes and heartbeats are not frames."
  [rdr]
  (loop []
    (when-let [line (.readLine rdr)]
      (if (str/starts-with? line "data: ")
        (json/parse-string (subs line 6))
        (recur)))))

(defn- redefine-panel! []
  (binding [*ns* (the-ns 'buzz.handler-test)]
    (eval '(defui panel [q] [:p (server @q)]))))

;; Re-evaluating a defui rebuilds every open connection. The instance a
;; connection holds and the watches feeding it have to be replaced together, or
;; a later patch carries the slots of code the browser has stopped running.
(deftest a-reload-replaces-what-a-connection-watches
  (let [port (free-port)
        stop (http/run-server (fn [req] (or ((handler/handler spec) req)
                                            {:status 404 :body "no"}))
                              {:port port})
        [sock rdr] (open-events port)]
    (try
      (let [[_ session] (next-event rdr)
            q (fn [] (:q (:state (first (:mounted (get @handler/conns session))))))]

        (testing "a connection is mounted with the slots of the current code"
          (is (= ["mount" "panel" "app" [0 1]] (next-event rdr))))

        (testing "changing state the connection owns patches only that connection"
          (swap! (q) inc)
          (is (= ["patch" "panel" [1 2]] (next-event rdr))))

        (testing "redefining the component sends the new shape"
          (redefine-panel!)
          (let [[kind _rev id vals] (next-event rdr)]
            (is (= "reload" kind))
            (is (= "panel" id))
            (is (= [1] vals))))

        (testing "and a later change patches with the new shape, not the old"
          (swap! (q) inc)
          (is (= ["patch" "panel" [2]] (next-event rdr)))))
      (finally
        (.close sock)
        (stop)
        (redefine-panel!)))))
