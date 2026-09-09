(ns counter
  (:require [buzz.core :as buzz]
            [buzz.datastar :as ds]
            [org.httpkit.server :as http]))

(defonce clicks (atom 0))

(def counter-source (buzz/atom-source clicks))

(defn add! [_req {:keys [step]}]
  (swap! clicks + step))

(defn page [_req]
  [:div (ds/signals {:step 1})
   (ds/fragment :count
     (fn [] [:p "clicked " (buzz/observe counter-source []) " times"]))
   [:button {:data-on:click (ds/action #'add! {:step (ds/signal :step)})} "add"]
   [:button {:data-on:click "$step = $step + 1"} "step "
    [:span {:data-text "$step"}]]])

(def ui (ds/handler {:title "counter" :render page}))

(defn -main [& _]
  (http/run-server (fn [req] (or (ui req) {:status 404 :body "not found"}))
                   {:port 1360})
  (println "http://localhost:1360")
  @(promise))
