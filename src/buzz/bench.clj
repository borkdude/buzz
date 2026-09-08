(ns buzz.bench
  "A table big enough to show what the design costs. Operations are triggered
  over HTTP so the browser knows when it asked, and can time the whole loop:
  server work, wire, and render."
  (:require [babashka.nrepl.server :as nrepl]
            [buzz.core :as buzz :refer [client defpart defui observe server server!]]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(defonce rows (atom []))
(defonce next-id (atom 0))

(def ^:private rows-source (buzz/atom-source rows))

(def ^:private words
  ["quiet" "loud" "red" "blue" "fast" "slow" "table" "chair" "wire" "signal"])

(defn- label [] (str/join " " (repeatedly 3 #(rand-nth words))))

(defn create! [n]
  (reset! rows (vec (for [_ (range n)]
                      {:id (swap! next-id inc) :label (label)}))))

(defn update-one! []
  (when (seq @rows)
    (swap! rows update 0 assoc :label (label))))

(defn update-every-10th! []
  (swap! rows (fn [rs] (vec (map-indexed (fn [i r]
                                           (if (zero? (mod i 10))
                                             (assoc r :label (label))
                                             r))
                                         rs)))))

(defn remove-row! [id]
  (swap! rows (fn [rs] (vec (remove #(= id (:id %)) rs)))))

(defn clear! [] (reset! rows []))

(defpart row [{:keys [id label]}]
  [:tr {:key id}
   [:td.id id]
   [:td.label label]
   [:td [:button.rm {:on-click (fn [_] (server! (remove-row! (client id))))} "x"]]])

(defui table []
  (let [items (server (observe rows-source []))
        n     (server (count (observe rows-source [])))]
    [:div
     [:p.count n " rows"]
     [:table [:tbody.rows (for [r items] (row r))]]]))

(def ops
  {"create-100"  #(create! 100)
   "create-1000" #(create! 1000)
   "create-5000" #(create! 5000)
   "update-one"  update-one!
   "update-10th" update-every-10th!
   "clear"       clear!})

(defn routes [req]
  (when (str/starts-with? (:uri req) "/op/")
    (if-let [f (ops (subs (:uri req) 4))]
      (let [t (System/nanoTime)
            _ (f)
            ms (/ (- (System/nanoTime) t) 1e6)]
        {:status 200
         :headers {"Content-Type" "text/plain"}
         :body (str ms)})
      {:status 404 :body "no such op"})))

(def ui
  (buzz/handler {:index "public/bench.html"
                 :mounts [{:el "app" :ui #'table}]}))

(defn app [req]
  (or (routes req) (ui req) {:status 404 :body "not found"}))

(defn -main [& args]
  (create! 100)
  (http/run-server app {:port 1342})
  (println "http://localhost:1342")
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1668})
    (println "nrepl://localhost:1668"))
  @(promise))
