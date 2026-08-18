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
       (map-indexed (fn [i e]
                      [:button.logq {:key i
                                     :on-click (fn [_]
                                                 (when-let [v @editor]
                                                   (.dispatch v {:changes {:from 0
                                                                           :to (.. v -state -doc -length)
                                                                           :insert (:q e)}})))}
                       (str (:count e) " rows · " (:ms e) "ms")])
                    log)]
      [:div.main
       ;; CodeMirror owns this node's shadow root. `:on-render` is the escape
       ;; hatch for third-party widgets, but Reagami manages the light
       ;; children of every node it renders, so the widget's DOM lives in a
       ;; shadow root, which Reagami never touches. Three more findings from
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
                      shadow (.attachShadow node {:mode "open"})
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
                                 :root shadow
                                 :parent shadow
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
                 :watch [query-log]
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
