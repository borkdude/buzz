(ns buzz.core
  "Splits component definitions into server and browser code.

  A component body is written as if it all ran in the browser. Forms wrapped in
  `(server ...)` are pulled out:

    value position   the expression is evaluated here on every render and its
                     result is passed to the browser as a plain value

    inside a lambda  the expression becomes a handler here and the browser gets
                     an `rpc!` call in its place, carrying the local bindings
                     the expression needs

  What remains is compiled to JavaScript by Squint. Later renders send only
  server values."
  (:require [buzz.impl.hub :as hub]
            [buzz.impl.page :as page]
            [buzz.impl.parts :as parts]
            [clojure.string :as str]
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

(defmacro request
  "Returns the current Ring request. In `(server ...)`, this is the request
  that opened the stream. In `(server! ...)`, this is the RPC request.

    (server (notes-for (whoami (request))))
    (server! (delete! (whoami (request)) (client id)))"
  [& _]
  (throw (ex-info "(request) used outside (server ...) or (server! ...)" {})))

(def ^:private marks
  {#'server :server, #'server! :server!, #'reply :reply,
   #'client :client, #'local-state :local-state, #'request :request})

(defn- mark
  [head]
  (when (symbol? head)
    (marks (try (resolve head) (catch Exception _ nil)))))

(def ^:private ^:dynamic *self*
  nil)

(declare ^:private split-part-body)
(declare ^:private handlers-form)

(defmacro defpart
  "Defines a Hiccup function that runs in the browser. Parts can recurse.
  Define server values and local state in `defui` and pass them as arguments."
  [nm argv & body]
  (when-let [p (some #(when (:server (meta %)) %) argv)]
    (throw (ex-info (str "^:server parameters are not supported in " nm ": " p
                         ". Pass the value or handler from defui.")
                    {:part nm :param p})))
  (let [qualified (symbol (str *ns*) (str nm))
        {:keys [js ssr-forms handlers parts req-sym]}
        (binding [*self* {:name nm :qualified qualified :arity (count argv)}]
          (split-part-body qualified argv body))]
    `(do (let [was# (when-let [v# (resolve '~nm)] (when (bound? v#) @v#))]
           (def ~nm (with-meta (fn ~argv ~@ssr-forms)
                      (parts/fn-part-meta
                       {:buzz/name '~qualified
                       :buzz/arity ~(count argv)
                       :buzz/js ~js
                       :buzz/parts '~(vec parts)
                       :buzz/handlers ~(handlers-form handlers req-sym)})))
           ;; Recompile callers only when the argument count changes.
           (if (and (parts/fn-part? was#)
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

(defn- lift-request
  "Replaces `(request)` forms in `expr` with `sym`. Returns the rewritten form
  and whether a replacement occurred."
  [expr sym]
  (let [used (atom false)
        out  (walk/postwalk
              (fn [x]
                (if (and (seq? x) (= :request (mark (first x))))
                  (if (= 1 (count x))
                    (do (reset! used true) sym)
                    (throw (ex-info "(request) takes no arguments" {:form x})))
                  x))
              expr)]
    [out @used]))

(defn- slot!
  "`(server ...)` in value position. Hoists the expression to a parameter of the
  client function. Nothing crosses from the browser here: a slot is evaluated
  before the browser renders, so there is nothing of its to read yet."
  [expr _scope acc]
  (when (some #(and (seq? %) (= :client (mark (first %)))) (tree-seq coll? seq expr))
    (throw (ex-info "(client ...) only works inside a handler, not in value position"
                    {:expr expr})))
  (let [sym (gensym "slot__")
        [expr req?] (lift-request expr (:req-sym @acc))]
    (when req? (swap! acc assoc :slot-request? true))
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
          [server-expr req?]  (lift-request server-expr (:req-sym @acc))
          id (str comp-id "/" (count (:handlers @acc)))]
      (swap! acc update :handlers conj
             [id {:params (mapv first pairs) :expr server-expr
                  :request req?
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

(def js-name
  "Returns a qualified part name as a JavaScript module binding."
  parts/js-name)

(defn- js-part-sym
  "Returns the symbol used for part calls and module declarations."
  [qualified]
  (symbol (js-name qualified)))

(defn- fn-part-call
  [{:keys [qualified arity simple]} args scope lambda? comp-id acc]
  (when-not (= arity (count args))
    (throw (ex-info (str simple " expects " arity
                         (if (= 1 arity) " argument" " arguments")
                         ", received " (count args))
                    {:part qualified :args (vec args)})))
  (swap! acc update :parts conj qualified)
  (swap! acc update :part-syms assoc (js-part-sym qualified) qualified)
  (apply list (js-part-sym qualified)
         (mapv #(conv % scope lambda? comp-id acc) args)))

(defn- conv
  "Rewrites `form` into the form the browser evaluates, recording slots and
  handlers in `acc` along the way."
  [form scope lambda? comp-id acc]
  (cond
    (and (seq? form) (seq form))
    (let [head (first form)
          args (rest form)
          mk   (when-not (and (simple-symbol? head) (scope head))
                 (mark head))]
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

        (= :request mk)
        (throw (ex-info "(request) is only valid inside (server ...) or (server! ...)"
                        {:form form}))
        (= 'quote head)  form
        (lambda-heads head) (conv-fn form scope comp-id acc)
        (let-heads head)    (conv-let form scope lambda? comp-id acc)
        (seq-heads head)    (conv-let form scope lambda? comp-id acc)
        :else
        (let [v     (part-var head scope)
              value (when v @v)]
          (cond
            ;; Resolve self-recursion before the part var exists.
            (and *self* (= head (:name *self*)) (not (scope head)))
            (fn-part-call {:qualified (:qualified *self*) :arity (:arity *self*)
                           :simple (:name *self*)}
                          args scope lambda? comp-id acc)

            (parts/fn-part? value)
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
                     ;; Omit handlers passed as arguments from server rendering.
                     (= 'rpc! (first form)) nil
                     :else (apply list (mapv ssr-form form)))
    :else form))

(def revision
  "Revision counter incremented when a defui or defpart is evaluated."
  parts/revision)

;; Keep each defui form so callers can be recompiled after an arity change.
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
  [handlers req-sym]
  (into {} (map (fn [[id h]]
                  [id {:fn `(fn ~(if (:request h)
                                   (into [req-sym] (:params h))
                                   (:params h))
                              ~(:expr h))
                       :request (boolean (:request h))
                       :reply (:reply h)}]))
        handlers))

(defn- split-part-body
  [qualified argv body]
  (let [acc   (atom {:slots [] :handlers [] :locals [] :parts #{} :part-syms {}
                     :req-sym (gensym "req__")})
        forms (mapv #(conv % (binder-syms argv) false (str qualified) acc) body)
        {:keys [slots handlers locals parts part-syms]} @acc
        nm    (name qualified)]
    (when (seq slots)
      (throw (ex-info (str "(server ...) in " nm " must be passed from defui: "
                           "(" nm " (server ...))")
                      {:part qualified :expr (:expr (first slots))})))
    (when (seq locals)
      (throw (ex-info (str "(local-state ...) in " nm
                           " must be created in defui and passed as an argument")
                      {:part qualified})))
    {:js        (to-js (apply list 'fn argv forms))
     ;; Restore part vars for server rendering.
     :ssr-forms (mapv ssr-form (walk/postwalk-replace part-syms forms))
     :handlers  handlers
     :req-sym   (:req-sym @acc)
     :parts     parts}))

(defn touch!
  "Increments the revision without recompiling components."
  []
  (swap! revision inc))

(def parts-closure
  "Returns metadata for every part reachable from `syms`."
  parts/parts-closure)

(defn part-handlers
  "Returns the merged handlers for every part reachable from `syms`."
  [syms]
  (into {} (mapcat (comp :buzz/handlers val)) (parts-closure syms)))

(defn split-body
  "Returns the pieces a component is made of. Server slots come first in the
  browser function's parameters, then the browser's own."
  [body comp-id]
  (let [acc   (atom {:slots [] :handlers [] :locals [] :parts #{} :part-syms {}
                     :req-sym (gensym "req__") :slot-request? false})
        forms (mapv #(conv % #{} false comp-id acc) body)
        {:keys [slots handlers locals parts part-syms req-sym slot-request?]} @acc
        params (into (mapv :sym slots) (mapv :sym locals))]
    {:js         (to-js (apply list 'fn params forms))
     ;; the initial values take the slots, so a local can start from what the
     ;; server sent rather than only from a literal
     :init-js    (to-js (list 'fn (mapv :sym slots) (mapv :init locals)))
     :locals     (count locals)
     :ssr-forms  (mapv ssr-form (walk/postwalk-replace part-syms forms))
     :slot-exprs (mapv :expr slots)
     :handlers   handlers
     :req-sym    req-sym
     :request?   slot-request?
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
        {:keys [js init-js locals ssr-forms slot-exprs slot-syms handlers parts
                req-sym request?]} (split-body body comp-id)]
    `(do
       (defn ~nm ~argv
         {:id       ~comp-id
          :js       ~js
          :init     ~init-js
          :locals   ~locals
          :parts    '~(vec parts)
          ;; :slots takes a request only when needed.
          :request  ~(boolean request?)
          :ssr      (fn ~slot-syms ~@ssr-forms)
          :slots    (fn ~(if request? [req-sym] []) ~(vec slot-exprs))
          ;; Resolve part handlers on every component call.
          :handlers (merge (part-handlers '~(vec parts))
                           ~(handlers-form handlers req-sym))})
       (register! '~(symbol (str *ns*) (str nm))
                  {:ns '~(ns-name *ns*) :form '~&form})
       (var ~nm))))

(def handler
  "Returns a Ring handler for `spec`. See `buzz.stream` for `:adapter`.

  Rendering is asynchronous: a write returns at once and rendering happens on
  a scheduler thread, at most once per `:render-interval-ms` (default 20).
  The first write renders immediately, writes inside the window collapse into
  one render that carries the latest state, so patches are sampled state, not
  every state. `:render-interval-ms 0` renders synchronously on the writing
  thread, which some tests want."
  page/handler)

(def connection
  "Returns the connection ID in `req`. Returns nil before the stream opens.
  A reconnect gets a new ID."
  page/connection)

(def token
  "Returns the Buzz browser token in `req`. The token persists across tabs and
  reconnects."
  page/token)

(def all
  "The topic every connection holds. Invalidating it renders every connection,
  which is what a `:watch` atom does."
  hub/all)

(def invalidate!
  "Marks topics changed. Only the connections holding one of them render, so a
  topic nobody holds costs nothing.

    (invalidate! [:todos \"alice\"])"
  hub/invalidate!)

(def observe
  "Reads `k` from a source and subscribes the current connection to it. Use it
  inside `(server ...)`, where the topics a connection holds are whatever its
  slots read.

    (server (observe todos [:todos (whoami (request))]))"
  hub/observe)

(def atom-source
  "A source over an atom, keyed by a path into it."
  hub/atom-source)

;; `buzz.source/Source` is the protocol an integration implements.
