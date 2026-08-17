(ns buzz.tap-viewer
  "View `tap>` values in a browser."
  (:require [buzz.core :as buzz :refer [client defpart defui local-state reply request server server!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
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

(defn- entries-at [v start n]
  (if (map? v)
    (map (fn [e] [(pr-str (key e)) (val e)]) (take n (drop start (seq v))))
    (map (fn [x] [nil x]) (take n (drop start v)))))

;; How far each viewer opened each path: connection -> path -> clicks.
(defonce expanded (atom {}))

(defn show-more! [req path]
  (swap! expanded update-in [(buzz/connection req) path] (fnil inc 0)))

;; The widest slice one bucket covers, so a value never shows more than
;; `max-children` buckets.
(defn- bucket-size [n]
  (loop [b max-children]
    (if (<= n (* b max-children)) b (recur (* b max-children)))))

(declare ^:private node-of)

;; Long counted values split into range buckets like the devtools console.
;; Only the first bucket of a slice opens on its own, the rest materialize
;; when clicked, so an opened path costs one slice and not the whole value.
(defn- slice-nodes [exp v path depth start end]
  (if (<= (- end start) max-children)
    (vec (map-indexed (fn [i [k child]]
                        (node-of exp child (str path "." (+ start i)) (inc depth) k))
                      (entries-at v start (- end start))))
    (let [b (bucket-size (- end start))]
      (vec (for [s (range start end b)]
             (let [e  (min end (+ s b))
                   bp (str path ".r" s "-" e)
                   t  (str "[" s " … " (dec e) "]")]
               (if (or (= s start) (get exp bp))
                 {:path bp :key nil :kind "range" :branch true :text t
                  :children (slice-nodes exp v path depth s e)}
                 {:path bp :key nil :kind "range" :branch false :text t
                  :more bp})))))))

;; A node keeps its path so folding it survives a redraw. A clipped node
;; carries `:more` with the path a click expands.
(defn- node-of [exp v path depth label]
  (let [clicks  (get exp path 0)
        deep?   (and (>= depth max-depth) (zero? clicks))
        branch? (and (coll? v) (seq v) (not deep?))
        n       {:path path
                 :key label
                 :kind (kind v)
                 :branch branch?
                 :text (if (coll? v) (summary v) (clip (pr-str v) max-text))}]
    (cond
      (and (coll? v) (seq v) deep?)
      (assoc n :more path)

      (not branch?)
      n

      (counted? v)
      (assoc n :children (slice-nodes exp v path depth 0 (count v)))

      ;; A lazy sequence has no count to bucket, so it realizes one horizon
      ;; of elements and buckets those. The trailing click adds a horizon.
      ;; The first click on a depth-clipped node shows it at all, so only
      ;; the clicks after that add horizons.
      :else
      (let [pages   (if (>= depth max-depth) (max clicks 1) (inc clicks))
            horizon (* max-children max-children pages)
            taken   (vec (take (inc horizon) v))
            cut     (min (count taken) horizon)]
        (assoc n :children
               (cond-> (slice-nodes exp taken path depth 0 cut)
                 (> (count taken) horizon)
                 (conj {:path (str path ".more") :key nil :kind "more"
                        :branch false :text "…" :more path})))))))

(defn record! [v]
  (let [id (swap! counter inc)]
    (swap! log (fn [l]
                 (into [{:id id
                         :at (now)
                         :value v
                         :preview (clip (preview-str v) 200)}]
                       (take (dec keep-n))
                       l)))))

;; Avoid duplicate listeners after a reload.
(defonce listener
  (let [f (fn [v] (record! v))]
    (add-tap f)
    f))

(defn clear! [] (reset! log []))

(defn- entry [id] (first (filter #(= id (:id %)) @log)))

(defonce selected (atom nil))

;; An element path is the entry id followed by indexes into `entries-at`
;; order, so it addresses one element of any collection kind.
(defn- value-at [v idxs]
  (reduce (fn [v i] (second (first (entries-at v i 1)))) v idxs))

(defn select! [path]
  (let [[id & idxs] (str/split (str path) #"\.")]
    (if-let [e (entry (parse-long id))]
      (let [v (value-at (:value e) (map parse-long idxs))]
        ;; Selecting what is already selected changes nothing.
        (when-not (identical? v @selected)
          (reset! selected v)
          ;; The selection comes back as a fresh entry on top to drill into.
          (record! v))
        (binding [*print-length* 200 *print-level* 10]
          (pr-str v)))
      "")))

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

;; Keep tapped values on the server. The tree is built per connection, so one
;; viewer opening a long value costs nobody else anything.
(defn- shown [entries exp]
  (mapv (fn [e] (-> (select-keys e [:id :at :preview])
                    (assoc :tree (node-of exp (:value e) (str (:id e)) 0 nil))))
        entries))

;; A node renders its children by calling itself, so folding a branch drops the
;; whole subtree.
(defpart tree-node [n folded said]
  [:div {:key (:path n)}
   [:div.row
    (if (:branch n)
      [:button.fold {:on-click (fn [_] (swap! folded (fn [m] (assoc m (:path n)
                                                                    (not (get m (:path n)))))))}
       (if (get @folded (:path n)) "▸" "▾")]
      [:span.fold "·"])
    (when (:key n) [:span.k (:key n)])
    (if (:more n)
      [:button.more {:on-click (fn [_] (server! (show-more! (request)
                                                            (client (:more n)))))}
       (:text n)]
      [:span {:class (str "v t-" (:kind n))} (:text n)])
    (when-not (or (:more n) (= "range" (:kind n)))
      [:button.select {:on-click (^:async fn [_]
                                (let [edn (await (server! (reply (select! (client (:path n))))))]
                                  (await (js/navigator.clipboard.writeText edn))
                                  (reset! said (str "selected " (count edn)
                                                    " characters and set @buzz.tap-viewer/selected"))))}
       "select"])]
   (when (and (:branch n) (not (get @folded (:path n))))
     [:div.kids (for [c (:children n)] (tree-node c folded said))])])

(defpart entry-item [e open folded said]
  [:li {:key (:id e)}
   [:div.head
    [:button.toggle {:on-click (fn [_] (swap! open (fn [m] (assoc m (:id e)
                                                                  (not (get m (:id e)))))))}
     (if (get @open (:id e)) "−" "+")]
    [:span.time (:at e)]
    [:code.preview (:preview e)]
    [:button.select {:on-click (^:async fn [_]
                              (let [edn (await (server! (reply (select! (client (:id e))))))]
                                (await (js/navigator.clipboard.writeText edn))
                                (reset! said (str "selected " (count edn)
                                                  " characters and set @buzz.tap-viewer/selected"))))}
     "select"]]
   (when (get @open (:id e))
     [:div.tree (tree-node (:tree e) folded said)])])

(defui viewer []
  (let [items  (server (shown @log (get @expanded (buzz/connection (request)))))
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
     [:ul.entries (for [e items] (entry-item e open folded said))]
     [:p.said (or @said "")]]))

;; From the classpath rather than the working directory, so the viewer also
;; serves its page when pulled into another project as a git dep.
(def ui
  (buzz/handler {:index (io/file (.toURI (io/resource "taps.html")))
                 :watch [log expanded]
                 :mounts [{:el "app" :ui #'viewer}]
                 :on-close (fn [req] (swap! expanded dissoc (buzz/connection req)))}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn- nrepl! []
  (if-let [start (try (requiring-resolve 'babashka.nrepl.server/start-server!)
                      (catch Exception _ nil))]
    (do (start {:port nrepl-port})
        (println (str "nrepl://localhost:" nrepl-port)))
    (println "no nREPL found. Start one and (require 'buzz.tap-viewer)")))

;; For a REPL that is already running. Returns instead of blocking, and
;; leaves starting an nREPL to the host.
(defn serve! []
  (http/run-server app {:port port :ip "127.0.0.1"})
  (println (str "http://localhost:" port))
  (tap> {:hello "from the server" :at (java.time.LocalTime/now)})
  nil)

(defn -main [& _]
  (serve!)
  (nrepl!)
  @(promise))


(comment
  (def my-object (Object.))
  (tap> my-object)
  (identical? @selected my-object)
  (reset! selected nil)
  )
