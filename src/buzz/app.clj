(ns buzz.app
  (:require [babashka.nrepl.server :as nrepl]
            [buzz.core :as buzz :refer [client defpart defui local-state observe reply
                                        server server!]]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

;; Stands in for a database. Every connected browser renders from these atoms,
;; so two windows stay in step without either of them knowing about the other.

(defonce db (atom (sorted-map)))
(defonce next-id (atom 0))
(defonce clicks (atom 0))

;; Slots read through these, so a write reaches the connections that read the
;; key it changed.
(def ^:private todos-source (buzz/atom-source db))
(def ^:private clicks-source (buzz/atom-source clicks))
#_(swap! clicks inc)
(defn add! [title]
  (let [title (some-> title str/trim not-empty)]
    (when title
      (let [id (swap! next-id inc)]
        (swap! db assoc id {:id id :title title :done false})))))

(defn toggle! [id] (swap! db update-in [id :done] not))
(defn delete! [id] (swap! db dissoc id))

(defn matching
  "The todos a query selects. Runs here, because the data is here."
  [todos q]
  (let [q (str/lower-case (str/trim (or q "")))]
    (cond->> (vals todos)
      (seq q) (filter #(str/includes? (str/lower-case (:title %)) q)))))

(defn seed! []
  (when (empty? @db)
    (add! "ship code, not JSON")
    (add! "patch with values after that")))

;; One component for the whole page. The body is browser code except for the
;; `(server ...)` forms.
;;
;; `(server (vals @db))` sits in value position, so it is evaluated here and the
;; browser sees the result. `(server! (toggle! (client id)))` sits inside a handler, so the
;; browser gets an `rpc!` call carrying `id` — which is a binding the browser
;; itself introduced, in the `for`.

(defpart todo-row [{:keys [id title done]}]
  [:li {:key id}
   [:input {:type "checkbox"
            :checked done
            :on-change (fn [_] (server! (toggle! (client id))))}]
   [:span {:class (when done "done")} title]
   [:button.del {:on-click (fn [_] (server! (delete! (client id))))} "×"]])

#_(todo-row {:id 1 :title "ship code, not JSON" :done false})

;; Search text by connection ID. The close hook removes disconnected entries.

(defn refuse! [] (throw (ex-info "the server said no" {})))

(defonce queries (atom {}))

;; Keyed by connection ID, so a keystroke wakes the connection that typed it.
(def ^:private query-source (buzz/atom-source queries))

(defn- my-query [req] (or (observe query-source [(buzz/connection req)]) ""))

(defui todo-app []
  (let [todos (server (matching (observe todos-source []) (my-query (buzz/request))))
        left  (server (count (remove :done (vals (observe todos-source [])))))
        n     (server (observe clicks-source []))
        said  (local-state nil)]
    [:div
     [:h1 "todos!"]
     ;; Deliberately not `:value`. The browser owns what is in this box and
     ;; echoes it instantly. Binding it to a server slot would let a patch
     ;; overwrite what was typed while the round trip was still in the air.
     [:input.search {:placeholder "search"
                     :on-input (fn [e]
                                 (server! (swap! queries assoc (buzz/connection (buzz/request))
                                                 (client (.. e -target -value)))))}]
     [:input.new {:placeholder (str "what needs doing, " (server (System/getProperty "user.name"))
                                    "?")
                  :autofocus true
                  :on-key-down (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (server! (add! (client (.. e -target -value))))
                                   (set! (.. e -target -value) "")))}]
     [:ul (for [t todos] (todo-row t))]
     [:p.count left " left"]
     ;; State here is server state, so this counter is shared: click it in one
     ;; window and it moves in the other.
     [:p.local "clicks, counted on the server: "
      [:button {:on-click (fn [_] (server! (swap! clicks inc)))} n]]
     ;; `reply` answers, and a handler that throws rejects. Both land in browser
     ;; state rather than on the page by themselves.
     [:p.said
      [:button.ask {:on-click (^:async fn [_]
                                (reset! said (await (server! (reply (str "there are "
                                                                         (count @db)
                                                                         " todos"))))))}
       "ask"]
      [:button.boom {:on-click (^:async fn [_]
                                 (try
                                   (await (server! (refuse!)))
                                   (reset! said "no error?")
                                   (catch :default e (reset! said (.-message e)))))}
       "break"]
      " " (or @said "")]]))

;; A second component, mounted at its own element. It is a sibling of `todo-app`,
;; not a child: separate trees, separate slots, separate patches.

(defui stats []
  (let [total (server (count (observe todos-source [])))
        done  (server (count (filter :done (vals (observe todos-source [])))))]
    [:p.stats total " total, " done " done"]))

;; Every connection reads the whole of `db` and `clicks`, so a change there
;; reaches all of them. Each connection reads its own key of `queries`, so a
;; keystroke wakes one connection and no other one runs a slot.

(def ui
  (buzz/handler {:index "public/index.html"
                 :mounts [{:el "app" :ui #'todo-app}
                          {:el "stats" :ui #'stats}]
                 :on-close (fn [conn] (swap! queries dissoc conn))}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn -main [& args]
  (seed!)
  (http/run-server app {:port 1341})
  (println "http://localhost:1341")
  (when (some #{"--nrepl"} args)
    (nrepl/start-server! {:port 1667})
    (println "nrepl://localhost:1667"))
  @(promise))
