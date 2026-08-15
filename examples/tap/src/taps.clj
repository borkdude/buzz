(ns taps
  "View `tap>` values in a browser."
  (:require [buzz.core :refer [client defpart defui local-state reply server server!]]
            [buzz.handler :as buzz]
            [org.httpkit.server :as http]))

(def ^:private port 1370)
(def ^:private nrepl-port 1670)

(def ^:private max-depth 4)
(def ^:private max-children 20)
(def ^:private max-text 120)

(def ^:private keep-n 25)

(defonce log (atom []))
(defonce ^:private counter (atom 0))

(def ^:private clock (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss"))

(defn- now [] (.format (java.time.LocalTime/now) clock))

(defn- clip [s n]
  (if (> (count s) n) (str (subs s 0 n) "…") s))

(defn- preview-str [v]
  (binding [*print-length* 10 *print-level* 3]
    (pr-str v)))

(defn- kind [v]
  (cond (nil? v)     "nil"
        (boolean? v) "bool"
        (number? v)  "num"
        (string? v)  "str"
        (keyword? v) "kw"
        (map? v)     "map"
        (set? v)     "set"
        (vector? v)  "vec"
        (seq? v)     "seq"
        :else        "other"))

(defn- summary [v]
  (let [[open close] (cond (map? v)    ["{" "}"]
                           (set? v)    ["#{" "}"]
                           (vector? v) ["[" "]"]
                           :else       ["(" ")"])]
    (str open (if (counted? v) (count v) "…") close)))

(defn- children [v]
  (if (map? v)
    (map (fn [e] [(pr-str (key e)) (val e)]) (take max-children v))
    (map (fn [x] [nil x]) (take max-children v))))

;; Rows are flat, so each one carries its depth and the paths above it.
(defn- add-rows [acc v path depth ancestors label]
  (let [branch? (and (coll? v) (seq v) (< depth max-depth))
        acc     (conj acc {:path path
                           :depth depth
                           :ancestors ancestors
                           :key label
                           :kind (kind v)
                           :branch branch?
                           :text (if (coll? v) (summary v) (clip (pr-str v) max-text))})]
    (if-not branch?
      acc
      (let [under (conj ancestors path)
            acc   (reduce (fn [a [i [k child]]]
                            (add-rows a child (str path "." i) (inc depth) under k))
                          acc
                          (map-indexed vector (children v)))]
        ;; Do not count a lazy sequence.
        (if (seq (drop max-children v))
          (conj acc {:path (str path ".more") :depth (inc depth) :ancestors under
                     :key nil :kind "more" :branch false :text "…"})
          acc)))))

(defn- rows [v id]
  (add-rows [] v (str id) 0 [] nil))

(defn record! [v]
  (let [id (swap! counter inc)]
    (swap! log (fn [l]
                 (into [{:id id
                         :at (now)
                         :value v
                         :preview (clip (preview-str v) 200)
                         :rows (rows v id)}]
                       (take (dec keep-n))
                       l)))))

;; Avoid duplicate listeners after a reload.
(defonce listener
  (let [f (fn [v] (record! v))]
    (add-tap f)
    f))

(defn clear! [] (reset! log []))

(defn- entry [id] (first (filter #(= id (:id %)) @log)))

(defonce copied (atom nil))

(defn copy! [id]
  (if-let [e (entry id)]
    (do (reset! copied (:value e))
        (binding [*print-length* 200 *print-level* 10]
          (pr-str (:value e))))
    ""))

(def ^:private samples
  [["a map"    (fn [] {:user {:name "alice" :roles #{:admin :user}}
                       :counts {:read 12 :write 3}
                       :ok true})]
   ["a vector" (fn [] (vec (range 40)))]
   ["a seq"    (fn [] (iterate inc 0))]
   ["an object" (fn [] (java.time.Instant/now))]
   ["a string" (fn [] (apply str (repeat 30 "long ")))]])

(defn tap-sample! [label]
  (when-let [f (some (fn [[l f]] (when (= l label) f)) samples)]
    (tap> (f))))

;; Keep tapped values on the server.
(defn- shown [entries]
  (mapv #(select-keys % [:id :at :preview :rows]) entries))

(defpart tree-row [r folded]
  [:div {:key (:path r) :class (str "row d" (:depth r))}
   (if (:branch r)
     [:button.fold {:on-click (fn [_] (swap! folded (fn [m] (assoc m (:path r)
                                                                  (not (get m (:path r)))))))}
      (if (get @folded (:path r)) "▸" "▾")]
     [:span.fold "·"])
   (when (:key r) [:span.k (:key r)])
   [:span {:class (str "v t-" (:kind r))} (:text r)]])

(defui viewer []
  (let [items  (server (shown @log))
        n      (server (count @log))
        open   (local-state {})
        folded (local-state {})
        said   (local-state nil)]
    [:div
     [:h1 "taps"]
     [:p.hint "server taps, newest first. "
      [:code "(tap> :hello)"] " from a repl, or press one of these:"]
     [:p.samples
      (for [s (server (mapv first samples))]
        [:button {:key s :on-click (fn [_] (server! (tap-sample! (client s))))} s])
      [:button.clear {:on-click (fn [_] (server! (clear!)))} "clear"]]
     [:p.count n " kept"]
     [:ul.entries
      (for [e items]
        [:li {:key (:id e)}
         [:div.head
          [:button.toggle {:on-click (fn [_] (swap! open (fn [m] (assoc m (:id e)
                                                                       (not (get m (:id e)))))))}
           (if (get @open (:id e)) "−" "+")]
          [:span.time (:at e)]
          [:code.preview (:preview e)]
          [:button.copy {:on-click (^:async fn [_]
                                    (let [edn (await (server! (reply (copy! (client (:id e))))))]
                                      (await (js/navigator.clipboard.writeText edn))
                                      (reset! said (str "copied " (count edn)
                                                        " characters and set @taps/copied"))))}
           "copy"]]
         (when (get @open (:id e))
           [:div.tree
            (for [r (:rows e)
                  :when (not (some (fn [a] (get @folded a)) (:ancestors r)))]
              (tree-row r folded))])])]
     [:p.said (or @said "")]]))

(def ui
  (buzz/handler {:index "public/index.html"
                 :watch [log]
                 :mounts [{:el "app" :component (fn [_] (viewer))}]}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn- nrepl! []
  (if-let [start (try (requiring-resolve 'babashka.nrepl.server/start-server!)
                      (catch Exception _ nil))]
    (do (start {:port nrepl-port})
        (println (str "nrepl://localhost:" nrepl-port)))
    (println "no nREPL found. Start one and (require 'taps)")))

(defn -main [& _]
  (http/run-server app {:port port :ip "127.0.0.1"})
  (println (str "http://localhost:" port))
  (nrepl!)
  (tap> {:hello "from the server" :at (java.time.LocalTime/now)})
  @(promise))


(comment
  (def my-object (Object.))
  (tap> my-object)
  (identical? @copied my-object)
  )
