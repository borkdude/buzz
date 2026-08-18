(ns buzz.whiteboard
  "A shared whiteboard. Everyone draws on the same board and sees everyone
  else's cursor, live."
  (:require [buzz.core :as buzz :refer [client defui local-state request server server!]]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

(def ^:private palette
  ["#e6194b" "#3cb44b" "#4363d8" "#f58231" "#911eb4" "#42d4f4" "#f032e6" "#9a6324"])

(def ^:private max-strokes 500)

;; Finished strokes, shared by everyone. Stored as ready polyline hiccup:
;; every message re-renders every slot for every connection, so the work of
;; turning points into a polyline happens once, when the stroke ends.
(defonce strokes (atom []))

;; Per connection: assigned color, cursor position, stroke in progress.
(defonce live (atom {}))

;; One count per `server!` call, so the page shows what a drawing session
;; costs in messages. Deliberately not in the handler's `:watch`: watched,
;; it would broadcast a patch to every connection on every message. The
;; count rides along whenever another change renders.
(defonce msgs (atom 0))

(defn- color-of [conn]
  (nth palette (mod (hash conn) (count palette))))

(defn- ensure-conn [l conn]
  (update l conn (fn [m] (assoc m :color (or (:color m) (color-of conn))))))

(defn- polyline [{:keys [color points]}]
  [:polyline {:points (str/join " " (map (fn [[x y]] (str x "," y)) points))
              :fill "none" :stroke color :stroke-width 3
              :stroke-linecap "round" :stroke-linejoin "round"}])

(defn cursor! [req p]
  (swap! msgs inc)
  (let [c (buzz/connection req)]
    (swap! live (fn [l] (-> (ensure-conn l c) (assoc-in [c :cursor] p))))))

(defn start! [req p]
  (swap! msgs inc)
  (let [c (buzz/connection req)]
    (swap! live (fn [l] (-> (ensure-conn l c)
                            (assoc-in [c :stroke] [p])
                            (assoc-in [c :cursor] p))))))

;; `ps` is a vector of points: the client buffers pointer moves and flushes
;; once per animation frame, so one message carries a frame's worth.
(defn draw! [req ps]
  (swap! msgs inc)
  (let [c (buzz/connection req)]
    (swap! live (fn [l] (cond-> (assoc-in (ensure-conn l c) [c :cursor] (peek ps))
                          (get-in l [c :stroke])
                          (update-in [c :stroke] into ps))))))

(defn end! [req ps]
  (swap! msgs inc)
  (let [c (buzz/connection req)
        {:keys [stroke color]} (get @live c)
        stroke (into (or stroke []) ps)]
    (when (> (count stroke) 1)
      (let [line (polyline {:color color :points stroke})]
        (swap! strokes (fn [ss] (vec (take-last max-strokes (conj ss line)))))))
    (swap! live update c dissoc :stroke)))

(defn clear! []
  (swap! msgs inc)
  (reset! strokes [])
  (swap! live update-vals #(dissoc % :stroke)))

(defn leave! [req]
  (swap! live dissoc (buzz/connection req)))

;;;; Display data, all computed on the server

(defn- wip-lines [l]
  (into [:g]
        (keep (fn [{:keys [stroke color]}]
                (when stroke (polyline {:color color :points stroke})))
              (vals l))))

(defn- other-cursors [l self]
  (into [:g]
        (keep (fn [[conn {:keys [cursor color]}]]
                (when (and cursor (not= conn self))
                  [:circle {:cx (first cursor) :cy (second cursor)
                            :r 5 :fill color :opacity 0.8}]))
              l)))

;;;; UI

(defui board []
  (let [done     (server (into [:g] @strokes))
        wip      (server (wip-lines @live))
        others   (server (other-cursors @live (buzz/connection (request))))
        stats    (server {:here (count @live) :strokes (count @strokes) :msgs @msgs})
        my-color (server (color-of (buzz/connection (request))))
        drawing  (local-state false)
        ;; pointer moves buffer here and flush once per animation frame:
        ;; stroke points accumulate, a hover cursor only keeps the latest
        buf       (local-state [])
        cur       (local-state nil)
        scheduled (local-state false)]
    [:div.wb
     [:div.bar
      [:span "you draw in "]
      [:svg {:width 14 :height 14} [:circle {:cx 7 :cy 7 :r 6 :fill my-color}]]
      [:span.stats (:here stats) " here · " (:strokes stats) " strokes · "
       (:msgs stats) " messages"]
      [:button.clear {:on-click (fn [_] (server! (clear!)))} "clear"]]
     [:svg#board
      {:viewBox "0 0 900 560"
       :on-pointerdown
       (fn [e]
         (let [r (.getBoundingClientRect (.-currentTarget e))
               x (js/Math.round (* (- (.-clientX e) (.-left r)) (/ 900 (.-width r))))
               y (js/Math.round (* (- (.-clientY e) (.-top r)) (/ 560 (.-height r))))]
           (.setPointerCapture (.-currentTarget e) (.-pointerId e))
           (reset! drawing true)
           (server! (start! (request) (client [x y])))))
       :on-pointermove
       (fn [e]
         (let [r (.getBoundingClientRect (.-currentTarget e))
               x (js/Math.round (* (- (.-clientX e) (.-left r)) (/ 900 (.-width r))))
               y (js/Math.round (* (- (.-clientY e) (.-top r)) (/ 560 (.-height r))))]
           (if @drawing
             (swap! buf conj [x y])
             (reset! cur [x y]))
           (when-not @scheduled
             (reset! scheduled true)
             (js/requestAnimationFrame
              (fn [_]
                (reset! scheduled false)
                (let [ps @buf p @cur]
                  (reset! buf [])
                  (reset! cur nil)
                  (cond (seq ps) (server! (draw! (request) (client ps)))
                        p (server! (cursor! (request) (client p))))))))))
       :on-pointerup
       (fn [_]
         (reset! drawing false)
         (let [ps @buf]
           (reset! buf [])
           (server! (end! (request) (client ps)))))
       ;; a tab switch or lost capture fires pointercancel instead of
       ;; pointerup; without this the stroke in progress lingers
       :on-pointercancel
       (fn [_]
         (reset! drawing false)
         (let [ps @buf]
           (reset! buf [])
           (server! (end! (request) (client ps)))))}
      done wip others]
     [:p.credit [:a {:href "https://github.com/borkdude/buzz"} "Made with Buzz"]]]))

(def ui
  (buzz/handler {:index (io/file (.toURI (io/resource "whiteboard.html")))
                 :watch [strokes live]
                 :mounts [{:el "app" :ui #'board}]
                 :on-close (fn [req] (leave! req))}))

(defn app [req]
  (or (ui req) {:status 404 :body "not found"}))

(defn serve! [{:keys [port host] :or {port 1390 host "127.0.0.1"}}]
  (http/run-server app {:port port :ip host})
  (println (str "http://" host ":" port))
  nil)

(defn -main [& _]
  (serve! {:host (or (System/getenv "HOST") "127.0.0.1")
           :port (or (some-> (System/getenv "PORT") parse-long) 1390)})
  @(promise))
