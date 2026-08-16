(ns buzz.core-test
  (:require [buzz.core :as b :refer [client defpart defui local-state reply request server server!]]
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
;; meaning, which is what stops a form quietly doing the wrong thing. The
;; marks are qualified: they resolve like any var, and the runner's namespace
;; refers none of them.
(deftest a-mark-in-the-wrong-place-is-an-error
  (testing "a value cannot be asked for from a handler"
    (is (re-find #"is a value"
                 (refusal '(buzz.core/defui a []
                             [:p {:on-click (fn [_] (buzz.core/server (inc 1)))}])))))

  (testing "an effect cannot happen during a render"
    (is (re-find #"is an effect"
                 (refusal '(buzz.core/defui b [] [:p (buzz.core/server! (prn 1))])))))

  (testing "a reply is the answer, so it comes last"
    (is (re-find #"must be the last form"
                 (refusal '(buzz.core/defui c []
                             [:p {:on-click (fn [_] (buzz.core/server!
                                                     (buzz.core/reply 1) (prn 2)))}])))))

  (testing "client marks a value crossing, not browser state"
    (is (re-find #"crosses a value into"
                 (refusal '(buzz.core/defui d [] [:p (buzz.core/client 1)])))))

  (testing "a reply takes a value and at most a response"
    (is (re-find #"takes a value and an optional response"
                 (refusal '(buzz.core/defui f []
                             [:p {:on-click (fn [_] (buzz.core/server! (buzz.core/reply 1 2 3)))}])))))

  (testing "browser state is declared in the body, not in a handler"
    (is (re-find #"declares state"
                 (refusal '(buzz.core/defui e []
                             [:p {:on-click (fn [_] (buzz.core/local-state nil))}]))))))

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

(defpart row [item n add!]
  [:li {:on-click add!} item " of " n])

(defui shelf [store]
  [:ul (row "a" (server (count @store))
            (fn [_] (server! (swap! store conj (client "a")))))
       (row "b" (server (count @store))
            (fn [_] (server! (swap! store conj (client "b")))))])

(deftest a-part-takes-server-values-as-arguments
  (let [store (atom [])
        inst  (shelf store)]

    (testing "the component owns the slots"
      (is (= [0 0] ((:slots inst)))))

    (testing "the component owns passed handlers"
      (is (= ["shelf/0" "shelf/1"] (sort (keys (:handlers inst))))))

    (testing "a passed handler captures component arguments"
      ((:fn (get (:handlers inst) "shelf/0")) "a")
      (is (= ["a"] @store))
      (is (= [1 1] ((:slots inst)))))

    (testing "the wrong argument count is rejected"
      (is (re-find #"expects 3 arguments, received 1"
                   (refusal '(buzz.core/defui bad []
                               [:ul (buzz.core-test/row "a")])))))))

(deftest a-mark-that-needs-a-component-is-refused-in-a-part
  (testing "server is rejected"
    (is (re-find #"must be passed from defui"
                 (refusal '(buzz.core/defpart p1 [] [:li (buzz.core/server 1)])))))

  (testing "local-state is rejected"
    (is (re-find #"must be created in defui"
                 (refusal '(buzz.core/defpart p2 [] [:li @(buzz.core/local-state 0)])))))

  (testing "server parameters are rejected"
    (is (re-find #"Pass the value or handler from defui"
                 (refusal '(buzz.core/defpart p3 [^:server q] [:li q]))))))

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

(defui seeded []
  (let [q (local-state (server @store))]
    [:p @q]))

;; A local is made once, in the browser, from whatever the server sent for that
;; render. The values it reads have to be in scope where it is built.
(deftest a-local-can-start-from-a-server-value
  (let [inst (seeded)]

    (testing "the server value is a slot like any other"
      (is (= [5] ((:slots inst))))
      (is (= 1 (:locals inst))))

    (testing "and the initial values take the slots, so the one it reads is bound"
      (let [slot   (re-find #"slot__\d+" (:init inst))
            params (second (re-find #"function \(([^)]*)\)" (:init inst)))]
        (is (some? slot))
        (is (str/includes? params slot))))))

(def basket (atom []))

(defpart fruit-row [item]
  [:li.fruit item
   [:button {:on-click (fn [_] (server! (swap! basket conj (client item))))} "add"]])

(defui fruit-list []
  [:ul (fruit-row "apple") (fruit-row "pear")])

(deftest a-function-part-is-called-rather-than-spliced
  (reset! basket [])
  (let [inst (fruit-list)]

    (testing "the component calls the part by name"
      (is (re-find #"buzz_DOT_core_test_SLASH_fruit_row\(\"apple\"\)" (:js inst)))
      (is (not (str/includes? (:js inst) "swap"))))

    (testing "the component records the part dependency"
      (is (= ['buzz.core-test/fruit-row] (:parts inst))))

    (testing "the part owns its handler"
      (is (= ["buzz.core-test/fruit-row/0"] (keys (:handlers inst))))
      ((:fn (get (:handlers inst) "buzz.core-test/fruit-row/0")) "apple")
      (is (= ["apple"] @basket)))

    (testing "the part adds no slots"
      (is (= [] ((:slots inst)))))

    (testing "server rendering calls the part"
      (is (str/includes? (pr-str ((:ssr inst))) "apple")))

    (testing "the wrong argument count is rejected"
      (is (re-find #"expects 1 argument, received 2"
                   (refusal '(buzz.core/defui bad-call []
                               [:ul (buzz.core-test/fruit-row "a" "b")])))))))

(def forest-data
  (atom {:label "root"
         :children [{:label "a" :children [{:label "a1" :children []}]}
                    {:label "b" :children []}]}))

(defpart branch [n]
  [:li (:label n)
   (when (seq (:children n))
     [:ul (for [c (:children n)] (branch c))])])

(defui forest []
  [:ul.forest (branch (server @forest-data))])

(deftest a-function-part-can-call-itself
  (let [inst (forest)]

    (testing "the component passes the tree through one slot"
      (is (re-find #"buzz_DOT_core_test_SLASH_branch\(slot__\d+\)" (:js inst))))

    (testing "the compiled part calls itself"
      (is (str/includes? (:buzz/js (meta branch)) "buzz_DOT_core_test_SLASH_branch(")))

    (testing "server rendering walks the whole tree"
      (is (str/includes? (pr-str (apply (:ssr inst) ((:slots inst)))) "a1")))))

(defpart tally-button []
  [:button {:on-click (fn [_] (server! (swap! clicks inc)))} "+"])

(defui tally-panel []
  [:div (tally-button)])

(defn- redefine! [form]
  (binding [*ns* (the-ns 'buzz.core-test)]
    (eval form)))

(deftest an-edited-function-part-reaches-through-its-callers
  (let [before (:js (tally-panel))
        rev    @b/revision]
    (try
      (redefine! '(buzz.core/defpart tally-button []
                    [:button {:on-click (fn [_] (server! (swap! clicks + 2)))} "+2"]))
      (let [inst (tally-panel)]
        (testing "the component was not expanded again"
          (is (= before (:js inst))))
        (testing "the revision changes"
          (is (= (inc rev) @b/revision)))
        (testing "the new handler is used"
          (reset! clicks 0)
          ((:fn (get (:handlers inst) "buzz.core-test/tally-button/0")))
          (is (= 2 @clicks))))
      (finally
        (redefine! '(buzz.core/defpart tally-button []
                      [:button {:on-click (fn [_] (server! (swap! clicks inc)))} "+"]))))))

(defpart delete [x]
  [:li.trash x])

(defui trash-list []
  [:ul (delete "old")])

(deftest a-part-may-be-named-after-a-reserved-word
  (let [inst (trash-list)]
    (is (re-find #"buzz_DOT_core_test_SLASH_delete\(\"old\"\)" (:js inst)))
    (is (str/includes? (pr-str ((:ssr inst))) "old"))))

;; The sixth mark. Inside a slot it is the request that opened the stream,
;; inside a handler the rpc carrying the call, and it never crosses to the
;; browser.
(defui greeter2 []
  [:div
   [:p (server (get-in (request) [:headers "x-user"] "nobody"))]
   [:button {:on-click (fn [_] (server! (reply (get-in (b/request) [:headers "x-user"]))))}
    "who"]])

(deftest a-mark-for-the-request
  (let [inst (greeter2)]

    (testing "a slot that asks says so, and its fn takes the request"
      (is (true? (:request inst)))
      (is (= ["alice"] ((:slots inst) {:headers {"x-user" "alice"}}))))

    (testing "a handler that asks is flagged, and receives it first"
      (let [h (get (:handlers inst) "greeter2/0")]
        (is (true? (:request h)))
        (is (= "bob" ((:fn h) {:headers {"x-user" "bob"}})))))

    (testing "the browser function knows nothing about any of it"
      (is (not (str/includes? (:js inst) "x-user"))))))

(defui quiet []
  [:p (server @clicks)
   [:button {:on-click (fn [_] (server! (swap! clicks inc)))} "+"]])

(deftest a-component-that-never-asks-pays-nothing
  (let [inst (quiet)]
    (is (false? (:request inst)))
    (is (false? (:request (get (:handlers inst) "quiet/0"))))
    (is (= [(deref clicks)] ((:slots inst))))))

(defpart who-button []
  [:button {:on-click (fn [_] (server! (reply (get-in (request) [:headers "x-user"]))))}
   "who"])

(defui who-panel []
  [:div (who-button)])

(deftest a-part-handler-reaches-the-request
  (let [inst (who-panel)
        h    (get (:handlers inst) "buzz.core-test/who-button/0")]
    (is (true? (:request h)))
    (is (= "carol" ((:fn h) {:headers {"x-user" "carol"}})))))

(deftest the-request-is-refused-in-browser-code
  (testing "in value position"
    (is (re-find #"only valid inside"
                 (refusal '(buzz.core/defui r1 [] [:p (buzz.core/request)])))))
  (testing "in a local-state init"
    (is (re-find #"only valid inside"
                 (refusal '(buzz.core/defui r2 []
                             (let [q (buzz.core/local-state (buzz.core/request))] [:p @q])))))))

(defui shadowed []
  (let [server (fn [x] [:em x])]
    [:p (server 1)]))

(deftest a-shadowed-mark-name-is-browser-code
  (let [inst (shadowed)]
    (is (= [] ((:slots inst))))
    (is (empty? (:handlers inst)))
    (is (str/includes? (:js inst) "(1)"))))
