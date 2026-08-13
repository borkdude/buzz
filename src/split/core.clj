(ns split.core
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
            [squint.compiler :as squint]))

(defmacro server
  "Marker. Only has meaning inside `defsplit`."
  [& _]
  (throw (ex-info "(server ...) used outside defsplit" {})))

(defmacro defpart
  "A hiccup helper. Unlike a function, this is spliced into whichever component
  uses it, before the body is walked, so `(server ...)` inside one is seen and
  its handlers belong to the enclosing component.

  It is an ordinary `def`, so it resolves like anything else and a stale
  reference fails loudly. Editing one does not recompile its users, for the same
  reason editing a macro does not: re-evaluate the component, or the namespace."
  [nm argv & body]
  `(def ~nm {::part true :params '~argv :body '~body}))

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
  client function."
  [expr scope acc]
  (when-let [free (seq (filter scope (syms expr)))]
    (throw (ex-info (str "(server ...) in value position cannot see browser bindings: "
                         (pr-str (vec (distinct free))))
                    {:expr expr :free (vec (distinct free))})))
  (let [sym (gensym "slot__")]
    (swap! acc update :slots conj {:sym sym :expr expr})
    sym))

(defn- handler!
  "`(server ...)` inside a lambda. Registers a handler and returns the call the
  browser makes in its place."
  [expr scope comp-id acc]
  (let [free (into [] (comp (filter scope) (distinct)) (syms expr))
        id   (str comp-id "/" (count (:handlers @acc)))]
    (swap! acc update :handlers conj [id {:params free :expr expr}])
    (list 'rpc! id (vec free))))

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
        (= 'server head) (let [expr (if (= 1 (count args)) (first args) (cons 'do args))]
                           (if lambda?
                             (handler! expr scope comp-id acc)
                             (slot! expr scope acc)))
        (= 'quote head)  form
        (lambda-heads head) (conv-fn form scope comp-id acc)
        (let-heads head)    (conv-let form scope lambda? comp-id acc)
        (seq-heads head)    (conv-let form scope lambda? comp-id acc)
        :else
        ;; A part becomes a `let` binding its parameters to the forms it was
        ;; called with, then is walked like anything else. Destructuring and
        ;; scope tracking come for free that way.
        (if-let [p (part head scope)]
          (let [params (:params p)]
            (when-not (= (count params) (count args))
              (throw (ex-info (str head " takes " (count params) " arguments, given " (count args))
                              {:part head :params params :args (vec args)})))
            (conv (apply list 'let (vec (interleave params args)) (:body p))
                  scope lambda? comp-id acc))
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

;; Bumped every time a defsplit is evaluated, so re-evaluating one in a REPL is
;; enough to tell the browsers something changed.
(defonce revision (atom 0))

(defn- to-js
  "Compiles the browser form to a self-contained JavaScript expression. `SQ` and
  `rpc_BANG_` are left free, so the browser supplies both as arguments rather
  than through globals. Squint runs here, at macro expansion, so the result is a
  string constant like any other."
  [form]
  (squint/compile-string (pr-str form)
                         {:context :expr :core-alias "SQ" :elide-imports true}))

(defn split-body
  "Returns {:js :ssr-forms :slot-exprs :handlers} for a component body."
  [body comp-id]
  (let [acc   (atom {:slots [] :handlers []})
        forms (mapv #(conv % #{} false comp-id acc) body)
        {:keys [slots handlers]} @acc]
    {:js         (to-js (apply list 'fn (mapv :sym slots) forms))
     :ssr-forms  (mapv ssr-form forms)
     :slot-exprs (mapv :expr slots)
     :handlers   handlers
     :slot-syms  (mapv :sym slots)}))

(defmacro defsplit
  "Defines a component. Calling it returns an instance:

    {:id       stable name, used as the key on the wire
     :js       the browser function as JavaScript, compiled once
     :ssr      the same function, compiled here, for the first paint
     :slots    thunk returning the current values for that function
     :handlers id -> fn, called when the browser sends an :rpc}"
  [nm argv & body]
  (let [comp-id (str nm)
        {:keys [js ssr-forms slot-exprs slot-syms handlers]} (split-body body comp-id)]
    `(do
       (defn ~nm ~argv
         {:id       ~comp-id
          :js       ~js
          :ssr      (fn ~slot-syms ~@ssr-forms)
          :slots    (fn [] ~(vec slot-exprs))
          :handlers ~(into {} (map (fn [[id h]] [id `(fn ~(:params h) ~(:expr h))])) handlers)})
       (swap! revision inc)
       (var ~nm))))
