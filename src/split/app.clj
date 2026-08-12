(ns split.app
  (:require [clojure.string :as str]
            [split.core :refer [defsplit server]]))

;; Stands in for a database. Every connected browser renders from these atoms,
;; so two windows stay in step without either of them knowing about the other.

(defonce db (atom (sorted-map)))
(defonce next-id (atom 0))
(defonce clicks (atom 0))

(defn add! [title]
  (let [title (some-> title str/trim not-empty)]
    (when title
      (let [id (swap! next-id inc)]
        (swap! db assoc id {:id id :title title :done false})))))

(defn toggle! [id] (swap! db update-in [id :done] not))
(defn delete! [id] (swap! db dissoc id))

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

(defsplit todo-app []
  (let [todos (server (vals @db))
        left  (server (count (remove :done (vals @db))))
        n     (server @clicks)]
    [:div
     [:h1 "todos"]
     [:input.new {:placeholder "what needs doing?"
                  :autofocus true
                  :on-key-down (fn [e]
                                 (when (= "Enter" (.-key e))
                                   (let [v (.. e -target -value)]
                                     (server (add! v))
                                     (set! (.. e -target -value) ""))))}]
     [:ul
      (for [{:keys [id title done]} todos]
        [:li {:key id}
         [:input {:type "checkbox"
                  :checked done
                  :on-change (fn [_] (server (toggle! id)))}]
         [:span {:class (when done "done")} title]
         [:button.del {:on-click (fn [_] (server (delete! id)))} "×"]])]
     [:p.count left " left"]
     ;; State here is server state, so this counter is shared: click it in one
     ;; window and it moves in the other.
     [:p.local "clicks, counted on the server: "
      [:button {:on-click (fn [_] (server (swap! clicks inc)))} n]]]))
