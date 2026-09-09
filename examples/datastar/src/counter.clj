(ns counter
  (:require [buzz.core :as buzz]
            [buzz.datastar :as ds :refer [expr]]
            [org.httpkit.server :as http]))

(defonce clicks (atom 0))

(def counter-source (buzz/atom-source clicks))

(defn add! [_req _args]
  (swap! clicks inc))

;; The count is server state: a fragment that renders again when it changes.
;; The help text is browser state: a signal that never leaves the tab.
(def open (ds/signal :open false))

(defn page [_req]
  [:div
   (ds/fragment :count
     (fn [] [:p "clicked " (buzz/observe counter-source []) " times"]))
   [:button {:data-on:click (ds/action #'add!)} "add"]
   [:button {:data-on:click (expr (swap! open not))} "help"]
   [:p {:data-show (expr @open)} "Every click on add is shared with every open tab."]])

(def ui (ds/handler {:title "counter" :render #'page}))

(defn -main [& _]
  (http/run-server (fn [req] (or (ui req) {:status 404 :body "not found"}))
                   {:port 1360})
  (println "http://localhost:1360")
  @(promise))
