(ns counters
  "Two pages over one atom. Each page reads one key, so a write to the other
  key renders nothing. The slot prints when it runs, so the terminal shows
  which pages a write reached."
  (:require [buzz.core :as buzz :refer [defui request server server!]]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(defonce state (atom {:a 0 :b 0}))

(def ^:private counts (buzz/atom-source state))

(defn- page-key [req]
  (if (str/starts-with? (:uri req) "/b") :b :a))

(defn- read-count [req]
  (let [k (page-key req)
        v (buzz/observe counts [k])]
    (prn :slot-ran k :value v)
    v))

(defui panel []
  [:div
   [:h1 "page " (server (name (page-key (request))))]
   [:p "count " (server (read-count (request)))]
   [:p
    [:button {:on-click (fn [_] (server! (swap! state update :a inc)))} "a + 1"]
    " "
    [:button {:on-click (fn [_] (server! (swap! state update :b inc)))} "b + 1"]]
   [:p [:a {:href "/a"} "page a"] " " [:a {:href "/b"} "page b"]]])

(def ^:private a-ui (buzz/handler {:title "a" :path "/a"
                                   :mounts [{:el "app" :ui #'panel}]}))

(def ^:private b-ui (buzz/handler {:title "b" :path "/b"
                                   :mounts [{:el "app" :ui #'panel}]}))

(defn app [req]
  (or (a-ui req)
      (b-ui req)
      {:status 303 :headers {"Location" "/a"}}))

(defn -main [& _]
  (http/run-server app {:port 1370 :ip "127.0.0.1"})
  (println "http://localhost:1370/a and http://localhost:1370/b")
  @(promise))
