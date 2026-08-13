(ns buzz.core
  "Splits one component definition into a part that runs here and a part that
  runs in the browser.

  A component body is written as if it all ran in the browser. Forms wrapped in
  `(server ...)` are pulled out:

    value position   the expression is evaluated here on every render and its
                     result is passed to the browser as a plain value

    inside a lambda  the expression becomes a handler here and the browser gets
                     an `rpc!` call in its place, carrying the local bindings
                     the expression needs

  What is left is compiled to JavaScript by Squint, here, once. Renders after
  the first one send only the values."
  (:require [clojure.string :as str]
            [clojure.walk :as walk]
            [squint.compiler :as squint]))

(defmacro server
  "A value from the server. Evaluated here on every render and pushed to the
  browser as a slot. Value position only, so an event handler uses `server!`."
  [& _]
  (throw (ex-info "(server ...) used outside defui" {})))

(defmacro server!
  "Something for the server to do, from an event handler. The browser gets a
  promise: empty when it worked, rejected when it did not. Wrap the last form in
  `reply` to send a value back.

    (server! (delete! (client id)))
    (server! (swap! db dissoc (client id)) (reply :ok))
    (server! (reply (taken? (client name))))"
  [& _]
  (throw (ex-info "(server! ...) used outside defui" {})))

(defmacro reply
  "Marks what a `server!` answers with. Last form only. A reply is a snapshot,
  not a subscription: use a `server` slot for anything that should stay live."
  [& _]
  (throw (ex-info "(reply ...) used outside (server! ...)" {})))

(defmacro client
  "A browser expression whose value crosses into a `server!` form. A bare symbol
  in there always means the server, so anything from the browser says so.

    (server! (add! (client (.. e -target -value))))

  In value position it is a slot the browser owns: an atom made from `init`,
  watched, so setting it redraws without asking the server."
  [& _]
  (throw (ex-info "(client ...) used outside defui" {})))

(defmacro defpart
  "A hiccup helper. Unlike a function, this is spliced into whichever component
  uses it, before the body is walked, so `(server ...)` inside one is seen and
  its handlers belong to the enclosing component.

  Parameters carry browser values. Mark one `^:server` to pass something that
  only exists here, such as an atom a component was given:

    (defpart row [item ^:server store]
      [:li {:on-click (fn [_] (server (swap! store conj item)))} item])

  It is an ordinary `def`, so it resolves like anything else and a stale
  reference fails loudly. Evaluating one expands every component again, so a
  change reaches the browser without touching the components themselves."
  [nm argv & body]
  `(do (def ~nm {::part true :params '~argv :body '~body})
       (recompile!)
       (var ~nm)))

(defn- part
  "The part a head symbol names, if it names one and is not shadowed."
  [head scope]
  (when (and (simple-symbol? head) (not (scope head)))
    (when-let [v (resolve head)]
      (when (and (var? v) (bound? v))
        (let [value @v]
          (when (and (map? value) (::part value)) value))))))

(def ^:private lambda-heads '#{fn fn*})
(def ^:private let-heads '#{let let* loop loop* when-let if-let when-some if-some})
(def ^:private seq-heads '#{for doseq})

(defn- syms
  "Every simple symbol in `form`, in depth-first order."
  [form]
  (filter simple-symbol? (tree-seq coll? seq form)))

(defn- binder-syms
  "The names a binding form introduces. Over-approximates destructuring, which
  is safe here: an extra name can only widen what a handler carries."
  [form]
  (disj (set (syms form)) '&))

(declare ^:private conv)

(defn- slot!
  "`(server ...)` in value position. Hoists the expression to a parameter of the
  client function. Nothing crosses from the browser here: a slot is evaluated
  before the browser renders, so there is nothing of its to read yet."
  [expr _scope acc]
  (when (some #(and (seq? %) (= 'client (first %))) (tree-seq coll? seq expr))
    (throw (ex-info "(client ...) only works inside a handler, not in value position"
                    {:expr expr})))
  (let [sym (gensym "slot__")]
    (swap! acc update :slots conj {:sym sym :expr expr})
    sym))

(defn- lift-client
  "Replaces every `(client expr)` with a fresh name. Returns the expression the
  server runs and the [name expr] pairs the browser has to supply."
  [expr]
  (let [found (atom [])
        server-expr (walk/postwalk
                     (fn [x]
                       (if (and (seq? x) (= 'client (first x)))
                         (do (when-not (= 2 (count x))
                               (throw (ex-info "(client ...) takes one expression" {:form x})))
                             (let [sym (gensym "arg__")]
                               (swap! found conj [sym (second x)])
                               sym))
                         x))
                     expr)]
    [server-expr @found]))

(defn- reply-form? [x]
  (and (seq? x) (= 'reply (first x))))

(defn- handler!
  "`(server! ...)` in an event handler. Registers what the server does and
  returns the call the browser makes in its place, carrying the `(client ...)`
  expressions as arguments. A trailing `(reply x)` says the response carries x."
  [forms scope comp-id acc]
  (let [tail    (last forms)
        answer? (reply-form? tail)
        _       (when (and answer? (not= 2 (count tail)))
                  (throw (ex-info "(reply ...) takes one expression" {:form tail})))
        body    (if answer?
                  (concat (butlast forms) [(second tail)])
                  forms)
        expr    (if (= 1 (count body)) (first body) (cons 'do body))]
    (when (some reply-form? (tree-seq coll? seq expr))
      (throw (ex-info "(reply ...) must be the last form of a (server! ...)"
                      {:forms (vec forms)})))
    (let [[server-expr pairs] (lift-client expr)
          id (str comp-id "/" (count (:handlers @acc)))]
      (swap! acc update :handlers conj
             [id {:params (mapv first pairs) :expr server-expr :reply answer?}])
      (list 'rpc! id (mapv #(conv (second %) scope true comp-id acc) pairs)))))

(defn- conv-bindings
  "Walks a binding vector left to right, so each init sees the names bound
  before it. Returns [scope converted-vector]."
  [bvec scope lambda? comp-id acc]
  (reduce (fn [[sc out] [b init]]
            (cond
              (= :let b)   (let [[sc' inner] (conv-bindings init sc lambda? comp-id acc)]
                             [sc' (conj out b inner)])
              (keyword? b) [sc (conj out b (conv init sc lambda? comp-id acc))]
              :else        [(into sc (binder-syms b))
                            (conj out b (conv init sc lambda? comp-id acc))]))
          [scope []]
          (partition 2 bvec)))

(defn- conv-arity [params body scope comp-id acc]
  (let [sc (into scope (binder-syms params))]
    (cons params (mapv #(conv % sc true comp-id acc) body))))

(defn- conv-fn [form scope comp-id acc]
  (let [[head & more] form
        fname (when (simple-symbol? (first more)) (first more))
        more  (if fname (rest more) more)
        scope (cond-> scope fname (conj fname))
        tail  (if (vector? (first more))
                (conv-arity (first more) (rest more) scope comp-id acc)
                (mapv #(conv-arity (first %) (rest %) scope comp-id acc) more))]
    (apply list (concat [head] (when fname [fname]) tail))))

(defn- conv-let [form scope lambda? comp-id acc]
  (let [[head bvec & body] form
        [scope' bvec'] (conv-bindings bvec scope lambda? comp-id acc)]
    (apply list head bvec' (mapv #(conv % scope' lambda? comp-id acc) body))))

(defn- conv
  "Rewrites `form` into the form the browser evaluates, recording slots and
  handlers in `acc` along the way."
  [form scope lambda? comp-id acc]
  (cond
    (and (seq? form) (seq form))
    (let [head (first form)
          args (rest form)]
      (cond
        ;; Each marker has one legal place. Somewhere else is an error, never a
        ;; different meaning.
        (= 'server head)
        (if lambda?
          (throw (ex-info "(server ...) is a value. An event handler wants (server! ...)"
                          {:form form}))
          (slot! (if (= 1 (count args)) (first args) (cons 'do args)) scope acc))

        (= 'server! head)
        (if lambda?
          (handler! args scope comp-id acc)
          (throw (ex-info "(server! ...) is an effect, so it needs an event handler to be in"
                          {:form form})))

        (= 'reply head)
        (throw (ex-info "(reply ...) only goes at the end of a (server! ...)" {:form form}))
        ;; `(client init)` in value position is a slot the browser owns: an atom
        ;; the runtime makes from `init` and redraws on. Inside a handler the
        ;; code is already the browser's, so it would say nothing.
        (= 'client head) (if lambda?
                           (throw (ex-info "(client ...) inside a handler only marks a value crossing into (server ...)"
                                           {:form form}))
                           (let [sym (gensym "local__")]
                             (swap! acc update :locals conj
                                    {:sym sym :init (conv (first args) scope false comp-id acc)})
                             sym))
        (= 'quote head)  form
        (lambda-heads head) (conv-fn form scope comp-id acc)
        (let-heads head)    (conv-let form scope lambda? comp-id acc)
        (seq-heads head)    (conv-let form scope lambda? comp-id acc)
        :else
        ;; A part becomes a `let` binding its parameters to the forms it was
        ;; called with, then is walked like anything else. Destructuring and
        ;; scope tracking come for free that way.
        ;;
        ;; A `^:server` parameter is substituted into the body instead of bound,
        ;; so it never enters browser scope and a `(server ...)` using it keeps
        ;; resolving where the part was called from.
        (if-let [p (part head scope)]
          (let [params (:params p)]
            (when-not (= (count params) (count args))
              (throw (ex-info (str head " takes " (count params) " arguments, given " (count args))
                              {:part head :params params :args (vec args)})))
            (let [pairs  (map vector params args)
                  server? #(:server (meta (first %)))
                  subs   (into {} (map (fn [[p a]] [p a])) (filter server? pairs))
                  binds  (vec (mapcat identity (remove server? pairs)))
                  body   (cond->> (:body p)
                           (seq subs) (walk/postwalk-replace subs))]
              (conv (apply list 'let binds body) scope lambda? comp-id acc)))
          (apply list (mapv #(conv % scope lambda? comp-id acc) form)))))

    (vector? form) (mapv #(conv % scope lambda? comp-id acc) form)
    (map? form)    (into {} (mapv (fn [[k v]] [(conv k scope lambda? comp-id acc)
                                               (conv v scope lambda? comp-id acc)])
                                  form))
    (set? form)    (into #{} (mapv #(conv % scope lambda? comp-id acc) form))
    :else form))

(defn- ssr-form
  "The same form, but renderable here. Reagami's ssr drops `:key`, `:on-render`
  and every `on*` attribute by name whatever the value, so blanking a handler
  changes no output — it only removes browser code that would otherwise have to
  analyse on the JVM, which `(set! (.. e -target -value) \"\")` does not."
  [form]
  (cond
    (map? form)
    (into {} (mapv (fn [[k v]]
                     [k (if (and (keyword? k)
                                 (str/starts-with? (name k) "on"))
                          nil
                          (ssr-form v))])
                   form))

    (vector? form) (mapv ssr-form form)
    (set? form)    (into #{} (mapv ssr-form form))
    (seq? form)    (if (= 'quote (first form))
                     form
                     (apply list (mapv ssr-form form)))
    :else form))

;; Bumped every time a defui is evaluated, so re-evaluating one in a REPL is
;; enough to tell the browsers something changed.
(defonce revision (atom 0))

;; Every defui keeps its own form, so it can be expanded again. A part is
;; inlined at expansion time, which means editing one leaves every component
;; that used it holding stale JavaScript. Re-evaluating them all is cheaper than
;; working out which ones actually care.
(defonce ^:private components (atom {}))

(def ^:dynamic ^:private *recompiling* false)

(defn recompile!
  "Expands every defui again. Called when a part changes."
  []
  (binding [*recompiling* true]
    (doseq [[_ {:keys [ns form]}] @components]
      (binding [*ns* (the-ns ns)]
        (eval form))))
  (swap! revision inc))

(defn- to-js
  "Compiles the browser form to a self-contained JavaScript expression. `SQ` and
  `rpc_BANG_` are left free, so the browser supplies both as arguments rather
  than through globals. Squint runs here, at macro expansion, so the result is a
  string constant like any other.

  The form goes to `compile*` as data rather than through `compile-string`.
  Printing it would drop metadata, and squint reads `^:async` from there."
  [form]
  (:body (squint/compile* [form]
                          {:context :expr :core-alias "SQ" :elide-imports true})))

(defn split-body
  "Returns the pieces a component is made of. Server slots come first in the
  browser function's parameters, then the browser's own."
  [body comp-id]
  (let [acc   (atom {:slots [] :handlers [] :locals []})
        forms (mapv #(conv % #{} false comp-id acc) body)
        {:keys [slots handlers locals]} @acc
        params (into (mapv :sym slots) (mapv :sym locals))]
    {:js         (to-js (apply list 'fn params forms))
     :init-js    (to-js (list 'fn [] (mapv :init locals)))
     :locals     (count locals)
     :ssr-forms  (mapv ssr-form forms)
     :slot-exprs (mapv :expr slots)
     :handlers   handlers
     :slot-syms  params}))

(defmacro defui
  "Defines a component. Calling it returns an instance:

    {:id       stable name, used as the key on the wire
     :js       the browser function as JavaScript, compiled once
     :ssr      the same function, compiled here, for the first paint
     :slots    thunk returning the current values for that function
     :handlers id -> fn, called when the browser sends an :rpc}"
  [nm argv & body]
  (let [comp-id (str nm)
        {:keys [js init-js locals ssr-forms slot-exprs slot-syms handlers]} (split-body body comp-id)]
    `(do
       (defn ~nm ~argv
         {:id       ~comp-id
          :js       ~js
          :init     ~init-js
          :locals   ~locals
          :ssr      (fn ~slot-syms ~@ssr-forms)
          :slots    (fn [] ~(vec slot-exprs))
          :handlers ~(into {} (map (fn [[id h]]
                                     [id {:fn `(fn ~(:params h) ~(:expr h))
                                          :reply (boolean (:reply h))}]))
                           handlers)})
       (swap! components assoc '~(symbol (str *ns*) (str nm))
              {:ns '~(ns-name *ns*) :form '~&form})
       (when-not *recompiling* (swap! revision inc))
       (var ~nm))))
