(ns buzz.core-test
  (:require [buzz.core :refer [defui server server!]]
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
