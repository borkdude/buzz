(ns buzz.dlv
  "A Datalevin browser over a MusicBrainz sample: a query editor with canned
  queries, results as a table, and a query log shared by every viewer."
  (:require [buzz.core :as buzz :refer [client defpart defui local-state reply server server!]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]))

;; Datalevin is a pod on babashka and a library on the JVM. The vars resolve
;; at load time, the code below does not care which one it got.
(def ^:private bb? (some? (System/getProperty "babashka.version")))

(when bb?
  ((requiring-resolve 'babashka.pods/load-pod) 'huahaiy/datalevin "1.0.2"))

(let [dl (fn [n] @(requiring-resolve
                   (symbol (if bb? "pod.huahaiy.datalevin" "datalevin.core") n)))]
  (def ^:private dl-q (dl "q"))
  (def ^:private dl-get-conn (dl "get-conn"))
  (def ^:private dl-transact! (dl "transact!"))
  (def ^:private dl-db (dl "db")))

;; resources/seed.edn holds a MusicBrainz sample: 8 artists, their studio
;; albums, and the tracks of each artist's first album.
(def ^:private seed (edn/read-string (slurp (io/resource "seed.edn"))))

(defonce ^:private conn
  (let [c (dl-get-conn "db/mbrainz" (:schema seed))]
    (when (empty? (dl-q '[:find ?e :where [?e :artist/name]] (dl-db c)))
      (println "seeding" (count (:tx seed)) "entities")
      (dl-transact! c (:tx seed)))
    c))

(def ^:private canned
  [{:label "Artists"
    :q "[:find ?name ?country ?since\n :where\n [?a :artist/name ?name]\n [?a :artist/country ?country]\n [?a :artist/start-year ?since]]"}
   {:label "Albums of Radiohead"
    :q "[:find ?title ?year\n :where\n [?a :artist/name \"Radiohead\"]\n [?r :release/artist ?a]\n [?r :release/title ?title]\n [?r :release/year ?year]]"}
   {:label "Albums per artist"
    :q "[:find ?name (count ?r)\n :where\n [?r :release/artist ?a]\n [?a :artist/name ?name]]"}
   {:label "Tracks over 8 minutes"
    :q "[:find ?artist ?track ?minutes\n :where\n [?t :track/duration-ms ?ms]\n [(> ?ms 480000)]\n [(quot ?ms 60000) ?minutes]\n [?t :track/title ?track]\n [?t :track/release ?r]\n [?r :release/artist ?a]\n [?a :artist/name ?artist]]"}
   {:label "Albums of the sixties"
    :q "[:find ?artist ?title ?year\n :where\n [?r :release/year ?year]\n [(<= 1960 ?year 1969)]\n [?r :release/title ?title]\n [?r :release/artist ?a]\n [?a :artist/name ?artist]]"}])

;; What everyone ran, newest first, so viewers can steal each other's queries.
(defonce ^:private query-log (atom []))

(def ^:private max-rows 200)

(defn- columns [form]
  (->> (rest form) (take-while #(not (keyword? %))) (mapv pr-str)))

(defn run-query! [qstr]
  (try
    (let [form (edn/read-string qstr)
          t0 (System/nanoTime)
          res (dl-q form (dl-db conn))
          ms (quot (- (System/nanoTime) t0) 1000000)]
      (swap! query-log (fn [l] (vec (take 20 (cons {:q qstr :count (count res) :ms ms} l)))))
      {:cols (columns form)
       :rows (mapv vec (take max-rows res))
       :count (count res)
       :ms ms
       :truncated (> (count res) max-rows)})
    (catch Throwable e
      {:error (ex-message e)})))

(def ^:private stat-queries
  {:artists '[:find ?e :where [?e :artist/name]]
   :albums '[:find ?e :where [?e :release/title]]
   :tracks '[:find ?e :where [?e :track/title]]})

(defn- stats []
  (update-vals stat-queries #(count (dl-q % (dl-db conn)))))

(defpart result-view [r]
  (cond
    (nil? r) [:p.hint "Run a query, or click one on the left."]
    (:error r) [:div.error (:error r)]
    :else
    [:div
     [:p.meta (:count r) " rows in " (:ms r) "ms"
      (when (:truncated r) (str ", showing " (count (:rows r))))]
     [:table
      [:thead [:tr (for [c (:cols r)] [:th {:key c} c])]]
      [:tbody
       (map-indexed (fn [i row]
                      [:tr {:key i} (for [v row] [:td (str v)])])
                    (:rows r))]]]))

(defui browser []
  (let [counts (server (stats))
        cans   (server canned)
        log    (server @query-log)
        qtext  (local-state (server (:q (first canned))))
        result (local-state nil)]
    [:div.app
     [:div.bar
      [:h1 "mbrainz"]
      [:span.stats (:artists counts) " artists · " (:albums counts) " albums · "
       (:tracks counts) " tracks"]]
     [:div.cols
      [:div.side
       [:h2 "Queries"]
       (for [c cans]
         [:button.canned {:key (:label c)
                          :on-click (fn [_] (reset! qtext (:q c)))}
          (:label c)])
       [:h2 "Everyone ran"]
       (map-indexed (fn [i e]
                      [:button.logq {:key i
                                     :on-click (fn [_] (reset! qtext (:q e)))}
                       (str (:count e) " rows · " (:ms e) "ms")])
                    log)]
      [:div.main
       [:textarea {:value @qtext
                   :spellcheck "false"
                   :on-input (fn [e] (reset! qtext (.. e -target -value)))}]
       [:div.actions
        [:button.run {:on-click (^:async fn [_]
                                  (reset! result
                                          (await (server! (reply (run-query! (client @qtext)))))))}
         "Run"]]
       (result-view @result)]]
     [:p.credit [:a {:href "https://github.com/borkdude/buzz"} "Made with Buzz"]]]))

(def ui
  (buzz/handler {:index (io/file (.toURI (io/resource "dlv.html")))
                 :watch [query-log]
                 :mounts [{:el "app" :ui #'browser}]}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn -main [& _]
  (http/run-server app {:port 1395 :ip "127.0.0.1"})
  (println "http://127.0.0.1:1395")
  @(promise))
