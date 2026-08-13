(ns buzz.core-test
  (:require [buzz.core :as b :refer [client defpart defui local-state server server!]]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;; State the server owns. A component reads it, and a handler changes it.
(def clicks (atom 0))

(defui counter []
  (let [n (server @clicks)]
    [:div
     [:p n]
     [:button {:on-click (fn [_] (server! (swap! clicks inc)))} "+1"]]))

;; Calling a defui gives an instance: what the browser runs, and what this side
;; keeps to feed it.
(deftest a-component-splits-into-slots-and-handlers
  (reset! clicks 0)
  (let [inst (counter)]

    (testing "a value position (server ...) becomes a slot, read again each time"
      (is (= [0] ((:slots inst))))
      (swap! clicks inc)
      (is (= [1] ((:slots inst))))
      (testing "and the browser function takes one parameter for it"
        (is (re-find #"function \(\w+\)" (:js inst)))))

    (testing "a (server! ...) in a handler becomes an entry the browser names"
      (is (= ["counter/0"] (keys (:handlers inst))))
      (is (false? (:reply (get (:handlers inst) "counter/0")))
          "nothing is sent back without a reply")
      (testing "and the browser calls it rather than doing the work"
        (is (re-find #"rpc_BANG_\(\"counter/0\", \[\]\)" (:js inst)))))

    (testing "running the handler is what changes the state"
      ((:fn (get (:handlers inst) "counter/0")))
      (is (= 2 @clicks))
      (testing "so the next render sends the new value"
        (is (= [2] ((:slots inst))))))))

(defn- refusal
  "The message a defui refuses a form with. A macro error arrives wrapped on the
  JVM and bare under SCI, so this unwraps to the cause either way. The forms are
  qualified because `eval` resolves in whichever namespace the runner is in."
  [form]
  (try
    (eval form)
    nil
    (catch Throwable e
      (loop [e e] (if-let [c (ex-cause e)] (recur c) (ex-message e))))))

;; Each mark has one place. Somewhere else is an error rather than a second
;; meaning, which is what stops a form quietly doing the wrong thing.
(deftest a-mark-in-the-wrong-place-is-an-error
  (testing "a value cannot be asked for from a handler"
    (is (re-find #"is a value"
                 (refusal '(buzz.core/defui a []
                             [:p {:on-click (fn [_] (server (inc 1)))}])))))

  (testing "an effect cannot happen during a render"
    (is (re-find #"is an effect"
                 (refusal '(buzz.core/defui b [] [:p (server! (prn 1))])))))

  (testing "a reply is the answer, so it comes last"
    (is (re-find #"must be the last form"
                 (refusal '(buzz.core/defui c []
                             [:p {:on-click (fn [_] (server!
                                                     (reply 1) (prn 2)))}])))))

  (testing "client marks a value crossing, not browser state"
    (is (re-find #"crosses a value into"
                 (refusal '(buzz.core/defui d [] [:p (client 1)])))))

  (testing "browser state is declared in the body, not in a handler"
    (is (re-find #"declares state"
                 (refusal '(buzz.core/defui e []
                             [:p {:on-click (fn [_] (local-state nil))}]))))))

;; A mark names a var, so it means the same whether it was referred or reached
;; through an alias.
(defui aliased []
  [:div
   [:p (b/server @clicks)]
   [:button {:on-click (fn [_] (b/server! (swap! clicks + (b/client 1))
                                          (b/reply @clicks)))}
    "+1"]])

(deftest an-alias-marks-as-well-as-a-referred-name
  (reset! clicks 0)
  (let [inst (aliased)
        h    (get (:handlers inst) "aliased/0")]

    (testing "b/server is a slot"
      (is (= [0] ((:slots inst)))))

    (testing "b/server! is a handler"
      (is (= ["aliased/0"] (keys (:handlers inst)))))

    (testing "b/client became a parameter the browser supplies"
      (is (= 5 ((:fn h) 5)))
      (is (= 5 @clicks)))

    (testing "b/reply says the response carries the value"
      (is (true? (:reply h))))))

;; A part is not called. It is spliced into whichever component uses it, so what
;; is inside belongs to that component. `item` is a browser value. `store` is
;; marked ^:server, so it is substituted rather than bound and keeps meaning
;; what it means where the part was used.
(defpart row [item ^:server store]
  [:li {:on-click (fn [_] (server! (swap! store conj (client item))))}
   item " of " (server (count @store))])

(defui shelf [store]
  [:ul (row "a" store) (row "b" store)])

(deftest a-part-is-spliced-into-the-component-that-uses-it
  (let [store (atom [])
        inst  (shelf store)]

    (testing "the handlers are named after the component, not the part"
      (is (= ["shelf/0" "shelf/1"] (sort (keys (:handlers inst))))))

    (testing "each use of the part brings its own slot"
      (is (= [0 0] ((:slots inst)))))

    (testing "a ^:server parameter reads the atom the component was given"
      ((:fn (get (:handlers inst) "shelf/0")) "a")
      (is (= ["a"] @store))
      (is (= [1 1] ((:slots inst)))))

    (testing "an ordinary parameter stays a browser value"
      (is (re-find #"\"a\"" (:js inst)))
      (is (not (some #{"a"} ((:slots inst))))))

    (testing "the wrong number of arguments is an error"
      (is (re-find #"takes 2 arguments, given 1"
                   (refusal '(buzz.core/defui bad []
                               [:ul (buzz.core-test/row "a")])))))))

;; The one mark the server never sees. A local is an atom the browser makes at
;; mount, so nothing about it travels except the code that builds it.
(def store (atom 5))

(defui widget []
  (let [n    (server @store)
        open (local-state false)
        note (local-state "hi")]
    [:p n (str @open) @note]))

(deftest local-state-belongs-to-the-browser
  (let [inst (widget)]

    (testing "a local is not a slot, and is counted on its own"
      (is (= [5] ((:slots inst))))
      (is (= 2 (:locals inst))))

    (testing "the initial values are built in the browser rather than sent"
      (is (str/includes? (:init inst) "[false, \"hi\"]")))

    (testing "the browser function takes the slots first, then the locals"
      (is (re-find #"function \(slot__\d+, local__\d+, local__\d+\)" (:js inst))))

    (testing "nothing about a local reaches the server"
      (is (empty? (:handlers inst)))
      (is (= 1 (count ((:slots inst))))))))
