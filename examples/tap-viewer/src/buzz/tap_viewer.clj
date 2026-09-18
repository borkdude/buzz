(ns buzz.tap-viewer
  "View `tap>` values in a browser."
  (:require [buzz.core :as buzz :refer [client defui local-state reply request server server!]]
            [clojure.datafy :as d]
            [clojure.java.io :as io]
            [org.httpkit.server :as http]))

(def ^:private nrepl-port 1670)

(def ^:private max-depth 4)
(def ^:private max-children 20)
(def ^:private max-text 120)
(def ^:private max-cols 12)
(def ^:private max-frames 8)

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

(defn- scalar? [v]
  (or (nil? v) (boolean? v) (number? v) (string? v) (keyword? v) (symbol? v) (char? v)))

(defn- opaque? [v]
  (and (not (coll? v)) (not (scalar? v))))

(defn- class-name [v] (some-> v class .getName))

;; datafy runs for each visible node on every redraw.
(defn- project [v]
  (let [data (try (d/datafy v) (catch Throwable _ v))]
    (if (identical? data v)
      [v (when (opaque? v) (class-name v))]
      [data (str (or (:clojure.datafy/class (meta data)) (class-name v)))])))

(defn- beaned [v]
  (try (let [m (dissoc (bean v) :class)]
         (when (seq m) m))
       (catch Throwable _ nil)))

(defn- shown-meta [v]
  (let [m (dissoc (meta v) :clojure.datafy/obj :clojure.datafy/class)]
    (when (seq m) m)))

(defn- entries-at [v start n]
  (if (map? v)
    (map (fn [e] [(key e) (val e)]) (take n (drop start (seq v))))
    (map-indexed (fn [i x] [(+ start i) x]) (take n (drop start v)))))

(defn- label-of [coll k]
  (when (map? coll) (clip (pr-str k) 40)))

;; Pass nil as the nav key for sets and sequences.
(defn- navigate [coll k v]
  (let [k (when (or (map? coll) (vector? coll)) k)]
    (try (d/nav coll k v) (catch Throwable _ v))))

(defn- frame-str [[cls method file line]]
  (str cls "/" method " (" file ":" line ")"))

(defn- error-of [t]
  (let [{:keys [cause via trace]} (Throwable->map t)
        top (first via)]
    {:type (str (:type top))
     :message (:message top)
     :cause (when (not= cause (:message top)) cause)
     :frames (mapv frame-str (take max-frames trace))
     :dropped (max 0 (- (count trace) max-frames))}))

;; Expansion state: connection -> path -> {:clicks n}.
(defonce expanded (atom {}))

;; Cache rendered values for selection: connection -> path -> [value data].
(defonce ^:private values (atom {}))

(def ^:private ^:dynamic *seen* nil)
(def ^:private ^:dynamic *chosen* nil)

(def ^:private taps (buzz/atom-source log))
;; Each connection observes its own expansion state.
(def ^:private folds (buzz/atom-source expanded))

(defn show-more! [req path]
  (swap! expanded update-in [(buzz/connection req) path :clicks] (fnil inc 0)))

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
                        (node-of exp (navigate v k child) (str path "." (+ start i))
                                 (inc depth) (label-of v k)))
                      (entries-at v start (- end start))))
    (let [b (bucket-size (- end start))]
      (vec (for [s (range start end b)]
             (let [e  (min end (+ s b))
                   bp (str path ".r" s "-" e)
                   t  (str "[" s " … " (dec e) "]")]
               (if (or (= s start) (get-in exp [bp :clicks]))
                 {:path bp :key nil :kind "range" :branch true :text t
                  :children (slice-nodes exp v path depth s e)}
                 {:path bp :key nil :kind "range" :branch false :text t
                  :more bp})))))))

;; Metadata nodes use a separate path.
(defn- extra-nodes [exp v path depth]
  (if-let [m (shown-meta v)]
    [(assoc (node-of exp m (str path ".meta") (inc depth) "^") :extra true)]
    []))

(defn- table-cols [kids]
  (let [rows (remove :extra kids)]
    (when (and (> (count rows) 1) (every? (fn [r] (= "map" (:kind r))) rows))
      (let [cells (fn [r] (map :key (remove :extra (:children r))))
            cols  (vec (take max-cols (distinct (mapcat cells rows))))]
        (when (seq cols) cols)))))

;; A node keeps its path so folding it survives a redraw. A clipped node
;; carries `:more` with the path a click expands.
(defn- node-of [exp v path depth label]
  (let [clicks     (get-in exp [path :clicks] 0)
        [data cls] (project v)
        data       (if (and (identical? data v) (opaque? v) (pos? clicks))
                     (or (beaned v) data)
                     data)
        proj?      (not (identical? data v))
        deep?      (and (>= depth max-depth) (zero? clicks))
        ;; Expand datafied children only after a click.
        held?      (and proj? (zero? clicks))
        full?      (and (coll? data) (seq data))
        kids       (cond
                     (or deep? held? (not full?))
                     []

                     (counted? data)
                     (slice-nodes exp data path depth 0 (count data))

                     ;; Each click adds a page after the node is expanded.
                     :else
                     (let [pages   (if (or proj? (>= depth max-depth)) (max clicks 1) (inc clicks))
                           horizon (* max-children max-children pages)
                           taken   (vec (take (inc horizon) data))
                           cut     (min (count taken) horizon)]
                       (cond-> (slice-nodes exp taken path depth 0 cut)
                         (> (count taken) horizon)
                         (conj {:path (str path ".more") :key nil :kind "more"
                                :branch false :text "\u2026" :more path}))))
        kids       (into (extra-nodes exp v path depth) kids)
        ;; Expand datafied values or bean properties on click.
        more?      (or (and full? (or deep? held?))
                       (and (opaque? v) (zero? clicks)))
        cols       (table-cols kids)]
    (when *seen* (vswap! *seen* assoc path [v data]))
    (cond-> {:path path
             :key label
             :kind (kind data)
             :branch (boolean (seq kids))
             :text (if (coll? v) (summary data) (clip (pr-str v) max-text))}
      cls                     (assoc :class cls)
      (and (some? *chosen*)
           (identical? v *chosen*)) (assoc :selected true)
      more?                   (assoc :more path)
      (seq kids)              (assoc :children kids)
      cols                    (assoc :cols cols)
      (instance? Throwable v) (assoc :error (error-of v)))))

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

(defonce selected (atom nil))

;; Update the selection highlight in every viewer.
(def ^:private picks (buzz/atom-source selected))

;; Use the newest tap when the selection is nil.
(defn- current [pick entries]
  (if (some? pick) pick (:value (first entries))))

;; Copy the data as EDN and keep the original object in selected.
(defn- at-path [req path]
  (get-in @values [(buzz/connection req) (str path)]))

(defn select! [req path]
  (when-let [[v _] (at-path req path)]
    ;; Selecting what is already selected changes nothing.
    (when-not (identical? v @selected)
      (reset! selected v)
      ;; The selection comes back as a fresh entry on top to drill into.
      (when-not (identical? v (:value (first @log)))
        (record! v)))
    nil))

;; Copying takes the data, so a projection reaches the clipboard as something
;; a reader accepts.
(defn copy! [req path]
  (if-let [[_ data] (at-path req path)]
    (binding [*print-length* 200 *print-level* 10]
      (pr-str data))
    ""))

;; Bind % to the current value and record the result.
(defn eval! [s]
  (try
    (let [form (binding [*read-eval* false] (read-string s))
          f    (binding [*ns* (the-ns 'buzz.tap-viewer)]
                 (eval (list 'fn '[%] form)))
          v    (f (current @selected @log))]
      ;; Skip results identical to the newest entry.
      (when-not (identical? v (:value (first @log)))
        (record! v))
      (binding [*print-length* 20 *print-level* 4]
        (clip (str "=> " (pr-str v)) 200)))
    (catch Throwable t
      (record! t)
      (clip (str (.getName (class t)) ": " (ex-message t)) 200))))

(def ^:private samples
  [["a map"    (fn [] {:user {:name "alice" :roles #{:admin :user}}
                       :counts {:read 12 :write 3}
                       :ok true})]
   ["a vector" (fn [] (vec (range 40)))]
   ["a seq"    (fn [] (iterate inc 0))]
   ["a table"  (fn [] (mapv (fn [i] {:id i :name (str "row " i) :ok (even? i)}) (range 6)))]
   ["a class"  (fn [] java.time.Instant)]
   ["an error" (fn [] (ex-info "boom" {:x 1} (Exception. "root cause")))]
   ["an object" (fn [] (java.time.Instant/now))]
   ["a string" (fn [] (apply str (repeat 30 "long ")))]])

(defn tap-sample! [label]
  (when-let [f (some (fn [[l f]] (when (= l label) f)) samples)]
    (tap> (f))))

;; Keep tapped values on the server. The tree is built per connection, so one
;; viewer opening a long value costs nobody else anything.
(defn- shown [conn entries exp pick]
  (let [seen (volatile! {})
        out  (binding [*seen* seen
                       *chosen* (current pick entries)]
               (mapv (fn [e] (-> (select-keys e [:id :at :preview])
                                 (assoc :tree (node-of exp (:value e) (str (:id e)) 0 nil))))
                     entries))]
    (when conn (swap! values assoc conn @seen))
    out))

(buzz/defn error-panel [e]
  [:div.err
   [:div.err-msg (:type e) ": " (:message e)]
   (when (:cause e) [:div.err-more "caused by: " (:cause e)])
   [:ol.err-trace
    (map-indexed (fn [i f] [:li {:key i} f]) (:frames e))]
   (when (pos? (:dropped e))
     [:div.err-more (:dropped e) " more frames"])])

(buzz/defn table-view [n]
  (let [rows (filter (fn [r] (not (:extra r))) (:children n))
        cols (:cols n)]
    [:table.tbl
     [:thead [:tr [:th.rk ""] (for [c cols] [:th {:key c} c])]]
     [:tbody
      (for [r rows]
        [:tr {:key (:path r)}
         [:td.rk (or (:key r) "")]
         (for [c cols]
           [:td {:key c}
            (let [cell (some (fn [k] (when (= c (:key k)) k)) (:children r))]
              (when cell [:span {:class (str "v t-" (:kind cell))} (:text cell)]))])])]]))

;; A node renders its children by calling itself, so folding a branch drops the
;; whole subtree.
(buzz/defn tree-node [n folded views said]
  (let [view   (get @views (:path n))
        err?   (and (:error n) (not= "data" view))
        table? (and (:cols n) (= "table" view))]
    [:div {:key (:path n)}
     [:div {:class (if (:selected n) "row sel" "row")}
      (if (:branch n)
        [:button.fold {:on-click (fn [_] (swap! folded (fn [m] (assoc m (:path n)
                                                                     (not (get m (:path n)))))))}
         (if (get @folded (:path n)) "▸" "▾")]
        [:span.fold "·"])
      (when (:key n) [:span.k (:key n)])
      (when (:class n) [:span.cls (:class n)])
      (if (:more n)
        [:button.more {:on-click (fn [_] (server! (show-more! (request)
                                                             (client (:more n)))))}
         (:text n)]
        [:span {:class (str "v t-" (:kind n))} (:text n)])
      (when (:error n)
        [:button.view {:on-click (fn [_] (swap! views (fn [m] (assoc m (:path n)
                                                                    (if (= "data" (get m (:path n)))
                                                                      "error" "data")))))}
         (if err? "data" "error")])
      (when (:cols n)
        [:button.view {:on-click (fn [_] (swap! views (fn [m] (assoc m (:path n)
                                                                    (if (= "table" (get m (:path n)))
                                                                      "tree" "table")))))}
         (if table? "tree" "table")])
      (when-not (or (= "range" (:kind n)) (= "more" (:kind n)))
        [:button.select {:on-click (fn [_]
                                     (server! (select! (request) (client (:path n))))
                                     (reset! said "selected, also in @buzz.tap-viewer/selected"))}
         "select"])
      (when-not (or (= "range" (:kind n)) (= "more" (:kind n)))
        [:button.copy {:on-click (^:async fn [_]
                                   (let [edn (await (server! (reply (copy! (request) (client (:path n))))))]
                                     (await (.catch (js/navigator.clipboard.writeText edn) (fn [_] nil)))
                                     (reset! said (str "copied " (count edn) " characters"))))
                       :title "copy"}
         [:svg {:class "icon" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2"}]
          [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]])]
     (when err? (error-panel (:error n)))
     (when (and (:branch n) (not (get @folded (:path n))))
       (if table?
         (table-view n)
         [:div.kids (for [c (:children n)] (tree-node c folded views said))]))]))

(buzz/defn entry-item [e open folded views said]
  [:li {:key (:id e)}
   [:div.head
    [:button.toggle {:on-click (fn [_] (swap! open (fn [m] (assoc m (:id e)
                                                                  (not (get m (:id e)))))))}
     (if (get @open (:id e)) "−" "+")]
    [:span.time (:at e)]
    [:code.preview (:preview e)]
    [:button.select {:on-click (fn [_]
                                 (server! (select! (request) (client (:id e))))
                                 (reset! said "selected, also in @buzz.tap-viewer/selected"))}
     "select"]
    [:button.copy {:on-click (^:async fn [_]
                               (let [edn (await (server! (reply (copy! (request) (client (:id e))))))]
                                 (await (.catch (js/navigator.clipboard.writeText edn) (fn [_] nil)))
                                 (reset! said (str "copied " (count edn) " characters"))))
                   :title "copy"}
     [:svg {:class "icon" :viewBox "0 0 24 24" :fill "none" :stroke "currentColor" :stroke-width "2"}
          [:rect {:x "9" :y "9" :width "13" :height "13" :rx "2"}]
          [:path {:d "M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1"}]]]]
   (when (get @open (:id e))
     [:div.tree (tree-node (:tree e) folded views said)])])

(defui viewer []
  (let [items  (server (shown (buzz/connection (request))
                              (buzz/observe taps [])
                              (buzz/observe folds [(buzz/connection (request))])
                              (buzz/observe picks [])))
        open   (local-state {})
        folded (local-state {})
        views  (local-state {})
        said   (local-state nil)]
    [:div
     [:h1 "taps"]
     [:p.hint "server taps, newest first. "
      [:code "(tap> :hello)"] " from a repl, or press one of these:"]
     [:p.samples
      (for [s (server (mapv first samples))]
        [:button {:key s :on-click (fn [_] (server! (tap-sample! (client s))))} s])
      [:button.clear {:on-click (fn [_] (server! (clear!)))} "clear"]]
     [:p.eval
      [:input.expr {:placeholder "Type an expression, e.g. (count %), and press Enter. % is your selection."
                    :on-key-down (^:async fn [e]
                                   (when (= "Enter" (.-key e))
                                     (reset! said (await (server! (reply (eval! (client (.. e -target -value)))))))))}]]
     [:p.said (or @said "")]
     [:ul.entries (for [e items] (entry-item e open folded views said))]
     [:p.credit [:a {:href "https://github.com/borkdude/buzz"} "Made with Buzz"]]]))

;; From the classpath rather than the working directory, so the viewer also
;; serves its page when pulled into another project as a git dep.
(def ui
  (buzz/handler {:index (io/file (.toURI (io/resource "taps.html")))
                 :mounts [{:el "app" :ui #'viewer}]
                 :on-close (fn [req]
                             (let [c (buzz/connection req)]
                               (swap! expanded dissoc c)
                               (swap! values dissoc c)))}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn- nrepl! []
  (if-let [start (try (requiring-resolve 'babashka.nrepl.server/start-server!)
                      (catch Exception _ nil))]
    (try (start {:port nrepl-port})
         (println (str "nrepl://localhost:" nrepl-port))
         (catch java.net.BindException _
           (println (str "nREPL port " nrepl-port " is in use. Skipping nREPL startup."))))
    (println "no nREPL found. Start one and (require 'buzz.tap-viewer)")))

;; Start the viewer and return its port.
(defn serve!
  [{:keys [port host] :or {port 1370 host "127.0.0.1"}}]
  (let [start  (fn [p] (http/run-server app {:port p :ip host}))
        ;; Port 0 selects an available port.
        server (try (start port)
                    (catch java.net.BindException _
                      (println (str "Port " port " is in use. Selecting an available port."))
                      (start 0)))
        port   (:local-port (meta server))]
    (println (str "http://" host ":" port))
    (tap> {:hello "from the server" :at (java.time.LocalTime/now)})
    port))

(defn -main [& _]
  (serve! {})
  (nrepl!)
  @(promise))


(comment
  (def my-object (Object.))
  (tap> my-object)
  (identical? @selected my-object)
  (reset! selected nil)
  )
