(ns split.app
  (:require [clojure.string :as str]
            [split.core :refer [defpart defsplit server]]))

;; Stands in for a database. Every connected browser renders from these atoms,
;; so two windows stay in step without either of them knowing about the other.

(defonce db (atom (sorted-map)))
(defonce next-id (atom 0))
(defonce clicks (atom 0))
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
  [q]
  (let [q (str/lower-case (str/trim (or q "")))]
    (cond->> (vals @db)
      (seq q) (filter #(str/includes? (str/lower-case (:title %)) q)))))

(defn seed! []
  (when (empty? @db)
    (add! "ship code, not JSON")
    (add! "patch with values after that")))

;; One component for the whole page. The body is browser code except for the
;; `(server ...)` forms.
;;
;; `(server (vals @db))` sits in value position, so it is evaluated here and the
;; browser sees the result. `(server (toggle! id))` sits inside a handler, so the
;; browser gets an `rpc!` call carrying `id` — which is a binding the browser
;; itself introduced, in the `for`.

;; Spliced into `todo-app`, so `id` here is a binding the browser made and the
;; handlers below belong to the enclosing component.

(defpart todo-row [{:keys [id title done]}]
  [:li {:key id}
   [:input {:type "checkbox"
            :checked done
            :on-change (fn [_] (server (toggle! id)))}]
   [:span {:class (when done "done")} title]
   [:button.del {:on-click (fn [_] (server (delete! id)))} "×"]])

#_(todo-row {:id 1 :title "ship code, not JSON" :done false})

;; `query` is an atom the server makes per connection, so the search box is not
;; shared between windows the way `clicks` is. It has to live here because a
;; value-position `(server ...)` cannot see anything the browser bound.

(defsplit todo-app [query]
  (let [todos (server (matching @query))
        left  (server (count (remove :done (vals @db))))
        n     (server @clicks)]
    [:div
     [:h1 "todos!"]
     ;; Deliberately not `:value`. The browser owns what is in this box and
     ;; echoes it instantly. Binding it to a server slot would let a patch
     ;; overwrite what was typed while the round trip was still in the air.
     [:input.search {:placeholder "search"
                     :on-input (fn [e]
                                 (let [v (.. e -target -value)]
                                   (server (reset! query v))))}]
     [:input.new {:placeholder (str "what needs doing, " (server (System/getProperty "user.name"))
                                    "?")
                  :autofocus true
                  :on-key-down (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (let [v (.. e -target -value)]
                                     (server (add! v))
                                     (set! (.. e -target -value) ""))))}]
     [:ul (for [t todos] (todo-row t))]
     [:p.count left " left"]
     ;; State here is server state, so this counter is shared: click it in one
     ;; window and it moves in the other.
     [:p.local "clicks, counted on the server: "
      [:button {:on-click (fn [_] (server (swap! clicks inc)))} n]]]))
