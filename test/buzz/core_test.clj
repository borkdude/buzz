(ns buzz.core-test
  (:require [buzz.core :as b :refer [defui server server!]]
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
