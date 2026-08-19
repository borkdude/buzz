(ns buzz.dlv
  "A Datalevin browser over a MusicBrainz sample: a query editor with canned
  queries, results as a table, and a query log shared by every viewer."
  (:require [buzz.core :as buzz :refer [client defpart defui local-state observe reply server server!]]
            [buzz.dlv.source :as dlv]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [datalevin.core :as d]
            [org.httpkit.server :as http]))

;; resources/seed.edn holds a MusicBrainz sample: 8 artists, their studio
;; albums, and the tracks of each artist's first album.
(def ^:private seed (edn/read-string (slurp (io/resource "seed.edn"))))

;; What everyone ran lives in the database too, so the page reads it with a
;; query like any other and the source notices the write.
(def ^:private log-schema
  {:query/text {:db/valueType :db.type/string}
   :query/rows {:db/valueType :db.type/long}
   :query/ms   {:db/valueType :db.type/long}
   :query/at   {:db/valueType :db.type/long}})

(defonce ^:private conn
  (let [c (d/get-conn "db/mbrainz" (merge (:schema seed) log-schema))]
    (when (empty? (d/q '[:find ?e :where [?e :artist/name]] (d/db c)))
      (println "seeding" (count (:tx seed)) "entities")
      (d/transact! c (:tx seed)))
    c))

(def ^:private db (dlv/datalevin-source conn))

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

(def ^:private max-rows 200)
(def ^:private max-log 20)

(defn- columns [form]
  (->> (rest form) (take-while #(not (keyword? %))) (mapv pr-str)))

;; The log entry and the retractions that keep the log short go in one
;; transaction, so a run notifies the log query once.
(defn- log! [qstr rows ms]
  (let [olds (->> (d/q '[:find ?e ?at :where [?e :query/at ?at]] (d/db conn))
                  (sort-by second >)
                  (drop (dec max-log))
                  (map first))]
    (d/transact! conn (into [{:query/text qstr
                              :query/rows rows
                              :query/ms ms
                              :query/at (System/currentTimeMillis)}]
                            (map (fn [e] [:db/retractEntity e]))
                            olds))))

(defn run-query! [qstr]
  (try
    (let [form (edn/read-string qstr)
          t0 (System/nanoTime)
          res (d/q form (d/db conn))
          ms (quot (- (System/nanoTime) t0) 1000000)]
      (log! qstr (count res) ms)
      {:cols (columns form)
       :rows (mapv vec (take max-rows res))
       :count (count res)
       :ms ms
       :truncated (> (count res) max-rows)})
    (catch Throwable e
      {:error (ex-message e)})))

;; Four subscribed queries. A run writes `:query/*` attributes, which only the
;; log query reads, so the three count queries never run again.
(def ^:private artists-q '[:find (count ?e) :where [?e :artist/name]])
(def ^:private albums-q  '[:find (count ?e) :where [?e :release/title]])
(def ^:private tracks-q  '[:find (count ?e) :where [?e :track/title]])

(def ^:private log-q
  '[:find ?text ?rows ?ms ?at
    :where
    [?e :query/text ?text]
    [?e :query/rows ?rows]
    [?e :query/ms ?ms]
    [?e :query/at ?at]])

(defn- one [res] (ffirst res))

(defn- stats []
  {:artists (one (observe db artists-q))
   :albums  (one (observe db albums-q))
   :tracks  (one (observe db tracks-q))})

(def ^:private labels
  {artists-q "artists" albums-q "albums" tracks-q "tracks" log-q "log"})

(defn- recent []
  {:entries (->> (observe db log-q)
                 (sort-by #(nth % 3) >)
                 (mapv (fn [[text rows ms _]] {:q text :count rows :ms ms})))
   :runs (->> (dlv/runs db)
              (mapv (fn [[q n]] {:label (labels q "?") :n n}))
              (sort-by :label)
              vec)})

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
        log    (server (recent))
        editor (local-state nil)
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
                          :on-click (fn [_]
                                      (when-let [v @editor]
                                        (.dispatch v {:changes {:from 0
                                                                :to (.. v -state -doc -length)
                                                                :insert (:q c)}})))}
          (:label c)])
       [:h2 "Everyone ran"]
       [:p.stats "re-run: "
        (for [r (:runs log)] [:span {:key (:label r)} (:label r) " " (:n r) " "])]
       (map-indexed (fn [i e]
                      [:button.logq {:key i
                                     :on-click (fn [_]
                                                 (when-let [v @editor]
                                                   (.dispatch v {:changes {:from 0
                                                                           :to (.. v -state -doc -length)
                                                                           :insert (:q e)}})))}
                       (str (:count e) " rows · " (:ms e) "ms")])
                    (:entries log))]
      [:div.main
       ;; CodeMirror owns this node: since reagami 0.2.41 a childless node's
       ;; foreign DOM is left alone across renders. Three findings from
       ;; getting here: the editor constructs in a `setTimeout`, because CM's
       ;; extension resolution is deeply recursive and the hook already sits
       ;; at the bottom of the render stack, deep enough together to overflow
       ;; it; the CSP nonce rides in a meta tag so CM's injected styles pass
       ;; the page's CSP; and clojure-mode's bundled theme
       ;; (`default_extensions`) uses style syntax that modern style-mod
       ;; rejects, so the language is assembled from its parser and tag map
       ;; with the standard highlight style instead.
       [:div#editor
        {:on-render
         (fn [{:keys [node lifecycle state save]}]
           (case lifecycle
             :mount
             (js/setTimeout
              (fn []
                (let [cm    (.-view js/window.CM)
                      lang  (.-language js/window.CM)
                      hl    (.-highlight js/window.CM)
                      clj   (.-cljMode js/window.CM)
                      nonce (.-content (js/document.querySelector "meta[name=csp-nonce]"))
                      EditorView (.-EditorView cm)
                      ;; clojure-mode's bundled theme uses style syntax that
                      ;; modern style-mod rejects, so the language is built
                      ;; from its parser and tag map instead, and bracket
                      ;; auto-closing comes from the standard closeBrackets.
                      parser (.configure (.-parser clj)
                                         {:props [((.-styleTags hl) (.-style_tags clj))]})
                      clj-lang (.define (.-LRLanguage lang) {:parser parser})
                      LanguageSupport (.-LanguageSupport lang)
                      view (new EditorView
                                {:doc (:q (first cans))
                                 :parent node
                                 :cspNonce nonce
                                 :extensions
                                 [(.of (.-keymap cm) (.-complete_keymap clj))
                                  (new LanguageSupport clj-lang)
                                  ((.-closeBrackets (.-autocomplete js/window.CM)))
                                  ((.-syntaxHighlighting lang) (.-defaultHighlightStyle lang))]})]
                  (reset! editor view)
                  (save view)))
              0)
             :update (save state)
             :unmount (when state (.destroy state))))}]
       [:div.actions
        [:button.run {:on-click (^:async fn [_]
                                  (when-let [v @editor]
                                    (reset! result
                                            (await (server! (reply (run-query!
                                                                    (client (.toString (.. v -state -doc))))))))))}
         "Run"]]
       (result-view @result)]]
     [:p.credit [:a {:href "https://github.com/borkdude/buzz"} "Made with Buzz"]]]))

(def ui
  (buzz/handler {:index (io/file (.toURI (io/resource "dlv.html")))
                 :mounts [{:el "app" :ui #'browser}]}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn serve!
  "Starts the server and returns, for a REPL."
  [{:keys [port host] :or {port 1395 host "127.0.0.1"}}]
  (http/run-server app {:port port :ip host})
  (println (str "http://" host ":" port))
  nil)

(defn -main [& _]
  (serve! {})
  @(promise))
