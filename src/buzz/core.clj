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
  not a subscription: use a `server` slot for anything that should stay live.

  A second argument adds to the http response the answer arrives in, which is
  how a handler sets a cookie:

    (reply :ok {:headers {\"Set-Cookie\" \"session=abc; HttpOnly; Path=/\"}})"
  [& _]
  (throw (ex-info "(reply ...) used outside (server! ...)" {})))

(defmacro client
  "A browser expression whose value crosses into a `server!` form. A bare symbol
  in there always means the server, so anything from the browser says so.

    (server! (add! (client (.. e -target -value))))"
  [& _]
  (throw (ex-info "(client ...) used outside a (server! ...)" {})))

(defmacro local-state
  "State the browser owns: an atom holding `init`. Made once when the component
  mounts, not on every render, and watched, so changing it redraws without
  asking the server anything.

    (let [playing (local-state nil)]
      [:button {:on-click (fn [_] (reset! playing id))} \"play\"])"
  [& _]
  (throw (ex-info "(local-state ...) used outside defui" {})))

(def ^:private bare-marks
  '{server :server, server! :server!, reply :reply,
    client :client, local-state :local-state})

(def ^:private marks
  {#'server :server, #'server! :server!, #'reply :reply,
   #'client :client, #'local-state :local-state})

(defn- mark
  "Which mark a head symbol names, if any. A bare name matches by name, so a
  part keeps its marks wherever it is spliced. Anything else resolves, so an
  alias or a rename means what it names rather than nothing at all."
  [head]
  (when (symbol? head)
    (or (bare-marks head)
        (marks (try (resolve head) (catch Exception _ nil))))))

(def ^:private ^:dynamic *self*
  "The part being defined, while its own body converts. Its var is not there
  yet, so `defpart` says here what the name is about to mean."
  nil)

(declare ^:private split-part-body)
(declare ^:private handlers-form)

(defmacro defpart
  "A hiccup helper: a function the browser calls, so a part can call itself
  and a tree can draw a tree.

    (defpart node [n]
      [:li (:label n)
       [:ul (for [c (:children n)] (node c))]])

  The body sees its parameters and globals. Everything else enters at the
  call site: the component writes `(server ...)` where it calls the part, and
  the part receives a plain value. State and event handlers travel the same
  road, so a part that writes per connection state is given the handler:

    (row item (server (count @store))
         (fn [_] (server! (swap! store conj (client item)))))

  A `(server! ...)` over global state works in the body itself. Its handlers
  belong to the part, so a component that uses one is not renumbered by it.

  It is an ordinary `def`, so it resolves like anything else and a stale
  reference fails loudly."
  [nm argv & body]
  (when-let [p (some #(when (:server (meta %)) %) argv)]
    (throw (ex-info (str nm " has a ^:server parameter, " p
                         ". There are none: pass the value, or the handler, from the component")
                    {:part nm :param p})))
  (let [qualified (symbol (str *ns*) (str nm))
        {:keys [js ssr-forms handlers parts]}
        (binding [*self* {:name nm :qualified qualified :arity (count argv)}]
          (split-part-body nm argv body))]
    `(do (let [was# (when-let [v# (resolve '~nm)] (when (bound? v#) @v#))]
           (def ~nm (with-meta (fn ~argv ~@ssr-forms)
                      {::fn-part true
                       :buzz/name '~qualified
                       :buzz/arity ~(count argv)
                       :buzz/js ~js
                       :buzz/parts '~(vec parts)
                       :buzz/handlers ~(handlers-form handlers)}))
           ;; A changed arity invalidates every call, so the components are
           ;; expanded again and say so. Otherwise the callers hold a name,
           ;; and the name still means this part.
           (if (and (fn? was#) (::fn-part (meta was#))
                    (not= ~(count argv) (:buzz/arity (meta was#))))
             (recompile!)
             (touch!)))
         (var ~nm))))

(defn- part-var
  "The var a head symbol names, if it names one and is not shadowed."
  [head scope]
  (when (and (symbol? head) (not (scope head)))
    (when-let [v (try (resolve head) (catch Exception _ nil))]
      (when (and (var? v) (bound? v)) v))))

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
  (when (some #(and (seq? %) (= :client (mark (first %)))) (tree-seq coll? seq expr))
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
                       (if (and (seq? x) (= :client (mark (first x))))
                         (do (when-not (= 2 (count x))
                               (throw (ex-info "(client ...) takes one expression" {:form x})))
                             (let [sym (gensym "arg__")]
                               (swap! found conj [sym (second x)])
                               sym))
                         x))
                     expr)]
    [server-expr @found]))

(defn- reply-form? [x]
  (and (seq? x) (= :reply (mark (first x)))))

(defn- handler!
  "`(server! ...)` in an event handler. Registers what the server does and
  returns the call the browser makes in its place, carrying the `(client ...)`
  expressions as arguments. A trailing `(reply x)` says the response carries x,
  and `(reply x resp)` adds `resp` to the http response it arrives in."
  [forms scope comp-id acc]
  (let [tail    (last forms)
        answer? (reply-form? tail)
        _       (when (and answer? (not (#{2 3} (count tail))))
                  (throw (ex-info "(reply ...) takes a value and an optional response"
                                  {:form tail})))
        ;; Which shape of reply this is, is known here. A handler that asks for
        ;; nothing from the response is left exactly as it was.
        resp?   (and answer? (= 3 (count tail)))
        body    (cond
                  resp?   (concat (butlast forms) [(vec (rest tail))])
                  answer? (concat (butlast forms) [(second tail)])
                  :else   forms)
        expr    (if (= 1 (count body)) (first body) (cons 'do body))]
    (when (some reply-form? (tree-seq coll? seq expr))
      (throw (ex-info "(reply ...) must be the last form of a (server! ...)"
                      {:forms (vec forms)})))
    (let [[server-expr pairs] (lift-client expr)
          id (str comp-id "/" (count (:handlers @acc)))]
      (swap! acc update :handlers conj
             [id {:params (mapv first pairs) :expr server-expr
                  :reply (if resp? :response answer?)}])
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

(defn- js-part-sym
  "The symbol a part call compiles to. Munged the way the compiler munges a
  reference, so the call sites and the module definition agree."
  [nm]
  (symbol (munge (name nm))))

(defn- fn-part-call
  "A call to a function part: converted arguments around the module name. The
  qualified name is kept as well, so the ssr form can put it back and reach
  the var."
  [{:keys [qualified arity simple]} args scope lambda? comp-id acc]
  (when-not (= arity (count args))
    (throw (ex-info (str simple " takes " arity " arguments, given " (count args))
                    {:part qualified :args (vec args)})))
  (swap! acc update :parts conj qualified)
  (swap! acc update :part-syms assoc (js-part-sym simple) qualified)
  (apply list (js-part-sym simple)
         (mapv #(conv % scope lambda? comp-id acc) args)))

(defn- conv
  "Rewrites `form` into the form the browser evaluates, recording slots and
  handlers in `acc` along the way."
  [form scope lambda? comp-id acc]
  (cond
    (and (seq? form) (seq form))
    (let [head (first form)
          args (rest form)
          mk   (mark head)]
      (cond
        ;; Each marker has one legal place. Somewhere else is an error, never a
        ;; different meaning.
        (= :server mk)
        (if lambda?
          (throw (ex-info "(server ...) is a value. An event handler wants (server! ...)"
                          {:form form}))
          (slot! (if (= 1 (count args)) (first args) (cons 'do args)) scope acc))

        (= :server! mk)
        (if lambda?
          (handler! args scope comp-id acc)
          (throw (ex-info "(server! ...) is an effect, so it needs an event handler to be in"
                          {:form form})))

        (= :reply mk)
        (throw (ex-info "(reply ...) only goes at the end of a (server! ...)" {:form form}))
        ;; `(client init)` in value position is a slot the browser owns: an atom
        ;; the runtime makes from `init` and redraws on. Inside a handler the
        ;; code is already the browser's, so it would say nothing.
        ;; `(local-state init)` is state the browser owns: an atom made once at
        ;; mount, not per render, and watched.
        (= :local-state mk)
        (if lambda?
          (throw (ex-info "(local-state ...) declares state, so it belongs in the body rather than a handler"
                          {:form form}))
          (let [sym (gensym "local__")]
            (swap! acc update :locals conj
                   {:sym sym :init (conv (first args) scope false comp-id acc)})
            sym))

        ;; Anything reaching here is a `client` outside a `server!`, since the
        ;; ones inside are lifted out before the walk.
        (= :client mk)
        (throw (ex-info "(client ...) crosses a value into a (server! ...). Browser state is (local-state ...)"
                        {:form form}))
        (= 'quote head)  form
        (lambda-heads head) (conv-fn form scope comp-id acc)
        (let-heads head)    (conv-let form scope lambda? comp-id acc)
        (seq-heads head)    (conv-let form scope lambda? comp-id acc)
        :else
        (let [v     (part-var head scope)
              value (when v @v)]
          (cond
            ;; The part being defined calls itself. Its var is not there yet,
            ;; so `defpart` said what the name is about to mean.
            (and *self* (= head (:name *self*)) (not (scope head)))
            (fn-part-call {:qualified (:qualified *self*) :arity (:arity *self*)
                           :simple (:name *self*)}
                          args scope lambda? comp-id acc)

            ;; A part is a function the browser calls, so it can recurse and
            ;; its handlers keep its own name.
            (and (fn? value) (::fn-part (meta value)))
            (let [m (meta value)]
              (fn-part-call {:qualified (:buzz/name m) :arity (:buzz/arity m)
                             :simple (symbol (name (:buzz/name m)))}
                            args scope lambda? comp-id acc))

            :else
            (apply list (mapv #(conv % scope lambda? comp-id acc) form))))))

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
    (seq? form)    (cond
                     (= 'quote (first form)) form
                     ;; a handler passed to a part is an argument rather than
                     ;; an attribute, so the call the browser would make is
                     ;; blanked here by name
                     (= 'rpc! (first form)) nil
                     :else (apply list (mapv ssr-form form)))
    :else form))

;; Bumped every time a defui is evaluated, so re-evaluating one in a REPL is
;; enough to tell the browsers something changed.
(defonce revision (atom 0))

;; Every defui keeps its own form, so it can be expanded again. A component
;; compiles a call for each part it uses, so changing a part's arity leaves
;; every caller holding a call that no longer fits. Re-evaluating them all is
;; cheaper than working out which ones actually care.
(defonce ^:private components (atom {}))

(def ^:dynamic ^:private *recompiling* false)

(defn register!
  "Records a component so that a part change can expand it again. Public because
  `defui` expands into a call to it, and a macro cannot reach a private var from
  the namespace it expands in."
  [nm spec]
  (swap! components assoc nm spec)
  (when-not *recompiling* (swap! revision inc)))

(defn recompile!
  "Expands every defui again. Called when a part's arity changes."
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

(defn- handlers-form
  "The handler map as a form the definition embeds: id -> what the server
  does. `:reply` is false, true, or :response for a reply that also answers
  with an http response."
  [handlers]
  (into {} (map (fn [[id h]]
                  [id {:fn `(fn ~(:params h) ~(:expr h)) :reply (:reply h)}]))
        handlers))

(defn- split-part-body
  "The pieces a part is made of. A slot and a local belong to a component, so
  a part asking for one is told where it goes instead."
  [nm argv body]
  (let [acc   (atom {:slots [] :handlers [] :locals [] :parts #{} :part-syms {}})
        forms (mapv #(conv % (binder-syms argv) false (str nm) acc) body)
        {:keys [slots handlers locals parts part-syms]} @acc]
    (when (seq slots)
      (throw (ex-info (str "(server ...) in " nm " is a value the component owns. "
                           "Pass it as an argument: (" nm " (server ...))")
                      {:part nm :expr (:expr (first slots))})))
    (when (seq locals)
      (throw (ex-info (str "(local-state ...) in " nm " is state the component owns. "
                           "Make it there and pass the atom")
                      {:part nm})))
    {:js        (to-js (apply list 'fn argv forms))
     ;; the browser form names parts by their module name, and the same form
     ;; runs here for the first paint, so the name goes back to the var
     :ssr-forms (mapv ssr-form (walk/postwalk-replace part-syms forms))
     :handlers  handlers
     :parts     parts}))

(defn touch!
  "Bumps the revision without expanding anything. Editing a function part is
  enough: its callers hold a name, and the module is compiled per request.
  Public because `defpart` expands into a call to it."
  []
  (swap! revision inc))

(defn js-name
  "The name a part has in the compiled module."
  [sym]
  (munge (name sym)))

(defn parts-closure
  "Every function part reachable from `syms`: its qualified name and its
  definition. Walks what each part uses, so a part a part calls is included."
  [syms]
  (loop [todo (seq syms) seen {}]
    (if-let [[s & more] todo]
      (if (contains? seen s)
        (recur more seen)
        (let [m (some-> (try (resolve s) (catch Exception _ nil)) deref meta)]
          (if (::fn-part m)
            (recur (concat more (:buzz/parts m)) (assoc seen s m))
            (recur more seen))))
      seen)))

(defn part-handlers
  "The handlers of every part in `syms`, one map. Read when a component is
  called, so an edited part answers with its new code without touching its
  callers."
  [syms]
  (into {} (mapcat (comp :buzz/handlers val)) (parts-closure syms)))

(defn split-body
  "Returns the pieces a component is made of. Server slots come first in the
  browser function's parameters, then the browser's own."
  [body comp-id]
  (let [acc   (atom {:slots [] :handlers [] :locals [] :parts #{} :part-syms {}})
        forms (mapv #(conv % #{} false comp-id acc) body)
        {:keys [slots handlers locals parts part-syms]} @acc
        params (into (mapv :sym slots) (mapv :sym locals))]
    {:js         (to-js (apply list 'fn params forms))
     ;; the initial values take the slots, so a local can start from what the
     ;; server sent rather than only from a literal
     :init-js    (to-js (list 'fn (mapv :sym slots) (mapv :init locals)))
     :locals     (count locals)
     :ssr-forms  (mapv ssr-form (walk/postwalk-replace part-syms forms))
     :slot-exprs (mapv :expr slots)
     :handlers   handlers
     :parts      parts
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
        {:keys [js init-js locals ssr-forms slot-exprs slot-syms handlers parts]} (split-body body comp-id)]
    `(do
       (defn ~nm ~argv
         {:id       ~comp-id
          :js       ~js
          :init     ~init-js
          :locals   ~locals
          :parts    '~(vec parts)
          :ssr      (fn ~slot-syms ~@ssr-forms)
          :slots    (fn [] ~(vec slot-exprs))
          ;; the parts are read here, at each call, so an edited part answers
          ;; with its new handlers without this component being expanded again
          :handlers (merge (part-handlers '~(vec parts))
                           ~(handlers-form handlers))})
       (register! '~(symbol (str *ns*) (str nm))
                  {:ns '~(ns-name *ns*) :form '~&form})
       (var ~nm))))
