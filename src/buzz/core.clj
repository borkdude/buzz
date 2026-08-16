(ns buzz.core
  "The whole of Buzz: the compiler that splits a component between server and
  browser, and the Ring handler that serves what it produces.

  A component body is written as if it all ran in the browser. Forms wrapped in
  `(server ...)` are pulled out:

    value position   the expression is evaluated here on every render and its
                     result is passed to the browser as a plain value

    inside a lambda  the expression becomes a handler here and the browser gets
                     an `rpc!` call in its place, carrying the local bindings
                     the expression needs

  What is left is compiled to JavaScript by Squint, here, once. Renders after
  the first one send only the values.

  `handler` turns a spec into a Ring handler for one page:

    (def ui
      (handler {:title \"todos\"
                :watch [db]                           ; patch everyone on change
                :mounts [{:el \"app\" :ui #'todo-app}]}))

    (defn app [req] (or (ui req) (my-static-files req)))

  A `:ui` mount names its component by var (or thunk), called with no
  arguments: nothing closes over a connection, so one instance serves them
  all and per connection facts come from `(request)`. Atoms in `:watch` are
  watched for every connection. State belongs to the application, in its own
  atoms, keyed by what it reads from `(request)`. Requests the page does not
  own return nil, so the application composes. The server is anyone who can
  run Ring plus one `buzz.stream` adapter, and http-kit is only the bundled
  default."
  (:require [babashka.fs :as fs]
            [buzz.stream :as stream]
            [cheshire.core :as json]
            [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.walk :as walk]
            [reagami.ssr :as ssr]
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
  "The request that caused this code to run: inside a `(server ...)` slot the
  one that opened the stream, inside a `(server! ...)` the rpc carrying the
  call. Server side only, so it never reaches the browser.

    (server (notes-for (whoami (request))))
    (server! (delete! (whoami (request)) (client id)))"
  [& _]
  (throw (ex-info "(request) used outside (server ...) or (server! ...)" {})))

(def ^:private bare-marks
  '{server :server, server! :server!, reply :reply,
    client :client, local-state :local-state, request :request})

(def ^:private marks
  {#'server :server, #'server! :server!, #'reply :reply,
   #'client :client, #'local-state :local-state, #'request :request})

(defn- mark
  "Which mark a head symbol names, if any. A bare name matches by name, so a
  part keeps its marks wherever it is spliced. Anything else resolves, so an
  alias or a rename means what it names rather than nothing at all."
  [head]
  (when (symbol? head)
    (or (bare-marks head)
        (marks (try (resolve head) (catch Exception _ nil))))))

(def ^:private ^:dynamic *self*
  "Metadata for the part being compiled. Used for self-recursion before its
  var exists."
  nil)

(declare ^:private split-part-body)
(declare ^:private handlers-form)

(defmacro defpart
  "Defines a Hiccup function for use in components. Parts run in the browser
  and can call themselves. Pass server values, local state and per-connection
  handlers from `defui` as arguments."
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
                      {::fn-part true
                       :buzz/name '~qualified
                       :buzz/arity ~(count argv)
                       :buzz/js ~js
                       :buzz/parts '~(vec parts)
                       :buzz/handlers ~(handlers-form handlers req-sym)}))
           ;; Recompile callers only when the argument count changes.
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

(defn- lift-request
  "Replaces every `(request)` in `expr` with `sym`. Returns the expression and
  whether it asked. A call with arguments is left alone unless it names the
  mark's var, so a function that happens to be called request keeps working."
  [expr sym]
  (let [used (atom false)
        out  (walk/postwalk
              (fn [x]
                (if (and (seq? x) (= :request (mark (first x))))
                  (cond
                    (= 1 (count x))
                    (do (reset! used true) sym)

                    (marks (try (resolve (first x)) (catch Exception _ nil)))
                    (throw (ex-info "(request) takes no arguments" {:form x}))

                    :else x)
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

(defn js-name
  "Returns a qualified part name as a JavaScript module binding."
  [sym]
  (str/replace (munge (str sym)) "." "_DOT_"))

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

        ;; The ones inside server forms are lifted out before the walk, so
        ;; this is browser code asking for a server value.
        (= :request mk)
        (throw (ex-info "(request) is a server value. It only means something inside (server ...) or (server! ...)"
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
                     ;; Omit handlers passed as arguments from server rendering.
                     (= 'rpc! (first form)) nil
                     :else (apply list (mapv ssr-form form)))
    :else form))

;; Bumped every time a defui is evaluated, so re-evaluating one in a REPL is
;; enough to tell the browsers something changed.
(defonce revision (atom 0))

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
  "Returns a handler map form for embedding in a definition. A handler that
  asked for the request takes it as its first parameter, and says so, so the
  rpc endpoint knows to pass it."
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
  "Compiles a part body and rejects component-owned state."
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

(defn parts-closure
  "Returns metadata for every part reachable from `syms`."
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
          ;; whether any slot asked for the request, so the caller knows
          ;; which arity :slots is
          :request  ~(boolean request?)
          :ssr      (fn ~slot-syms ~@ssr-forms)
          :slots    (fn ~(if request? [req-sym] []) ~(vec slot-exprs))
          ;; Resolve part handlers on every component call.
          :handlers (merge (part-handlers '~(vec parts))
                           ~(handlers-form handlers req-sym))})
       (register! '~(symbol (str *ns*) (str nm))
                  {:ns '~(ns-name *ns*) :form '~&form})
       (var ~nm))))

;; ---------------------------------------------------------------------------
;; The page. From here down, buzz.core is the Ring handler for what the
;; compiler above produced: the page, the modules, the stream and the rpc.

;; Two endpoints. GET /events is one long-lived SSE stream per browser, POST
;; /rpc is a plain request. Nothing is bidirectional, so there is no upgrade to
;; negotiate and no socket to nurse: EventSource reconnects on its own.
;;
;; Each handler owns a registry of its connections,
;; session -> {:ch channel :owner browser token
;;             :mounted [{:el :spec :sent :req :instance}]},
;; so an id from one page cannot be found by another one's endpoint, and a
;; write watched by one page runs no other page's slots. The heartbeat and the
;; reload walk every registry, which is what this set is for.
(defonce ^:private registries (atom #{}))

;; A session id arrives in the rpc body, so on its own it lets anyone who learns
;; one act as the connection it belongs to, whatever they signed in as. Buzz
;; gives a browser a token of its own and wants it back with every rpc.
;;
;; Its own, rather than whatever the application already sets, because those
;; cookies change for reasons that have nothing to do with a connection and a
;; stream that quietly stopped answering when an unrelated one was written would
;; be very hard to explain.
;;
;; The browser rather than the connection, because a cookie belongs to an origin
;; and two tabs of one page would otherwise take the token away from each other.
;;
;; Lax rather than Strict, because Strict is withheld on a cross site
;; navigation, so arriving from a link elsewhere would look like a browser with
;; no token and mint a second one over the first. Lax is still withheld from a
;; cross site post, which is the half that guards the rpc.
(def ^:private token-cookie "buzz-browser")

;; Built once. Every rpc reads the cookies, and compiling this per call cost
;; about as much as parsing the request body.
(def ^:private token-re
  (re-pattern (str "(?:^|;\\s*)" token-cookie "=([^;]+)")))

(defn- browser-token [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find token-re)
           second))

(defn- token-headers
  "What gives a browser a token. HttpOnly, so nothing on the page can read it
  and carry it elsewhere."
  [token]
  {"Set-Cookie" (str token-cookie "=" token "; Path=/; HttpOnly; SameSite=Lax")})

(defn connection
  "The id of the connection `req` belongs to: the same value in a slot and in
  every rpc that connection sends, so it keys state a tab owns. Nil during
  the first paint, which belongs to no connection yet."
  [req]
  (::connection req))

(defn token
  "The browser token in `req`: one value per browser, minted by Buzz. Stable
  across tabs and reconnects, so it keys state a browser owns."
  [req]
  (browser-token req))

(defn- event!
  "One SSE frame. JSON escapes newlines inside strings, so a value can never
  break out of its own `data:` line."
  [ch msg]
  (stream/send! ch (str "data: " (json/generate-string msg) "\n\n")))

;; A watched atom says something changed somewhere, not that this mount cares.
;; Rather than have each mount declare what it reads, which can drift from what
;; its slots actually do, run the slots and send nothing when the values are the
;; same as last time. Unchanged slots are usually the identical objects, so the
;; comparison stops at the first identity check.
(defn- slot-vals
  "The current slot values of one mount. The instance says whether its slots
  read the request, so a page that never asks pays nothing."
  [{:keys [instance req]}]
  (if (:request instance) ((:slots instance) req) ((:slots instance))))

(defn- patch! [ch {:keys [instance sent] :as mount}]
  (let [vals (slot-vals mount)]
    (when (not= vals @sent)
      (reset! sent vals)
      (event! ch ["patch" (:id instance) vals]))))

;; A mount names its component by var (or thunk), so nothing closes over a
;; connection and one instance serves them all. Cached per revision: a reload
;; builds the next one, and every connection sees it.
(defn- shared-instance [ui]
  (let [cache (atom nil)]
    (fn []
      (let [rev @revision c @cache]
        (if (and c (= rev (:rev c)))
          (:inst c)
          (:inst (reset! cache {:rev rev :inst (if (var? ui) ((deref ui)) (ui))})))))))

(defn- build [{:keys [el] :as spec} req]
  {:el el :spec spec :sent (atom ::none) :req req
   :instance ((::instance spec))})

(defn- open-stream [registry session ch req mounts token]
  ;; The session id is what an RPC arrives with, so it goes out only once there
  ;; is something here to find under it. Building the mounts renders every
  ;; component, which is long enough for a browser to have answered.
  (let [mounted (mapv #(build % (assoc req ::connection session)) mounts)]
    (swap! registry assoc session {:ch ch :mounted mounted :owner token})
    (event! ch ["session" session])
    (doseq [{:keys [el instance sent] :as m} mounted]
      (let [vals (slot-vals m)]
        (reset! sent vals)
        (event! ch ["mount" (:id instance) el vals])))))

;; The stream is the one response a plain Ring handler cannot make, so it is
;; the one place an adapter is asked to help: answer the request, keep it
;; open, and say when the client went away.
(defn- events [registry adapter req mounts on-close]
  (let [session (str (random-uuid))
        held    (browser-token req)
        token   (or held (str (random-uuid)))]
    (adapter req
             {:status 200
              :headers (cond-> {"Content-Type" "text/event-stream"
                                "Cache-Control" "no-cache"
                                "X-Accel-Buffering" "no"}
                         (nil? held) (merge (token-headers token)))
              :on-open  (fn [ch] (open-stream registry session ch req mounts token))
              :on-close (fn []
                          (swap! registry dissoc session)
                          ;; the app hears the id its request-keyed state
                          ;; used, so it can let go of it
                          (when on-close (on-close session)))})))

(defn- json-response [status body]
  {:status status
   :headers {"Content-Type" "application/json"}
   :body (json/generate-string body)})

;; A handler that threw used to escape into http-kit, so the browser saw nothing
;; and the log said nothing about which handler it was. Now it answers and the
;; browser's promise rejects. The detail stays here: an exception message can
;; carry more than a browser should be told.
;;
;; Two things have to agree before a handler runs. The header, because a
;; request that carries one is not a form a page elsewhere can post: it needs a
;; preflight, and Buzz answers none. Same site is not the same as same origin,
;; so a neighbouring subdomain would otherwise be allowed to try. The token,
;; because a session id on its own says nothing about who is asking. The page
;; needs no check of its own any more: each handler looks in its own registry,
;; so another page's session id finds nothing here.
(defn- rpc [registry req]
  (let [[session handler-id args] (json/parse-string (slurp (:body req)))
        conn (get @registry session)]
    (if-let [h (and (get-in req [:headers "x-buzz-rpc"])
                    conn
                    (= (:owner conn) (browser-token req))
                    (some #(get (:handlers (:instance %)) handler-id)
                          (:mounted conn)))]
      (try
        (let [v (apply (:fn h) (if (:request h)
                                 (cons (assoc req ::connection session) args)
                                 args))]
          (case (:reply h)
            ;; `(reply v resp)`, so the handler answered with both
            :response (let [[value resp] v]
                        (-> (json-response 200 value)
                            (update :headers merge (:headers resp))
                            (merge (dissoc resp :headers))))
            true      (json-response 200 v)
            {:status 204}))
        (catch Exception e
          (println "buzz:" handler-id "failed on" (pr-str args) "-" (ex-message e))
          (json-response 500 {:error "handler failed"})))
      (json-response 404 {:error "no such handler"}))))

;; The reply to an RPC is not the response. It is whatever :patch the write
;; happens to produce, on every stream of this handler watching that data. The
;; walk is per registry, so a write watched by one page runs no other page's
;; slots.
(defn- broadcast-patch! [registry]
  (fn [_ _ _ _]
    (doseq [{:keys [ch mounted]} (vals @registry)
            m mounted]
      (patch! ch m))))

;; Re-evaluating a defui in a REPL bumps the revision. Rebuild each
;; connection's instances so their handler ids match the new code, then tell the
;; browser to import the components again under a fresh URL. Browser state is
;; the browser's, so a reload does not clear what someone had typed.
(defn- reload-all! [_ _ _ rev]
  (doseq [registry @registries
          [session {:keys [ch mounted]}] @registry]
    (let [rebuilt (mapv (fn [m] (assoc m :instance ((::instance (:spec m))))) mounted)]
      (swap! registry assoc-in [session :mounted] rebuilt)
      ;; the slots may have changed shape, so this one always goes out
      (doseq [{:keys [instance sent] :as m} rebuilt]
        (let [vals (slot-vals m)]
          (reset! sent vals)
          (event! ch ["reload" rev (:id instance) vals]))))))

(add-watch revision ::reload reload-all!)

;; Idle streams get dropped by proxies. A comment frame is ignored by
;; EventSource and keeps the connection accounted for.
;; One for the whole process rather than one per page, since it walks every
;; registry there is.
(defonce ^:private heartbeat
  (delay
    (future
      (loop []
        (Thread/sleep 25000)
        (doseq [registry @registries
                {:keys [ch]} (vals @registry)]
          (stream/send! ch ": ping\n\n"))
        (recur)))))

;; The runtime is written in Clojure and compiled here. Nothing interprets
;; Clojure in the browser, so the page loads no interpreter at all. These two
;; ship with the library rather than with the application.
(def ^:private js-headers
  {"Content-Type" "text/javascript"
   ;; compiled per request, so never let a stale copy survive an edit
   "Cache-Control" "no-store"})

;; A handler can be mounted under a path, and the browser has to ask the same
;; handler for its stream and its modules rather than whichever one owns the
;; root. The runtime is compiled per request anyway, so the path goes into it
;; instead of being smuggled through a global on the page. Longest first, or
;; "/rpc" would eat the start of "/rpc.mjs".
(defn- at-path [src path]
  (if (str/blank? path)
    src
    (reduce (fn [s u] (str/replace s (str \" u) (str \" path u)))
            src
            ["/components.mjs" "/rpc.mjs" "/events" "/rpc"])))

(defn- runtime-module [n path]
  {:status 200
   :headers js-headers
   :body (squint/compile-string (at-path (slurp (io/resource (str "buzz/" n))) path))})

;; The components are an ordinary module too. `defui` already compiled each
;; one to a JavaScript expression, so this only has to give them their imports
;; and a name. The browser imports the result and never evaluates a string.
(defn- components-module [mounts path]
  (let [insts (map #((::instance %)) mounts)
        ;; Resolve parts per request so edits do not require recompiling callers.
        parts (parts-closure (mapcat :parts insts))]
    {:status 200
     :headers js-headers
     :body (str "import * as SQ from \"squint-cljs/core.js\";\n"
                "import { rpc_BANG_ } from \"" path "/rpc.mjs\";\n"
                (str/join (for [[sym m] parts]
                            (str "const " (js-name sym) " = " (:buzz/js m) ";\n")))
                "export const registry = {\n"
                (str/join ",\n"
                          (map #(str "  " (pr-str (:id %)) ": {f: " (:js %)
                                     ", init: " (:init %)
                                     ", nlocals: " (:locals % 0) "}")
                               insts))
                "\n};\n")}))

;; Nothing here evaluates code the browser was handed, so the page can say so
;; and let the browser hold it to that. Without 'unsafe-eval' a stray `eval` or
;; `new Function` fails loudly instead of quietly working. esm.sh appears in
;; connect-src as well as script-src because devtools fetches source maps
;; through connect-src, which grants nothing new.
(defn- csp [nonce]
  (str "default-src 'none'; "
       "script-src 'self' https://esm.sh 'nonce-" nonce "'; "
       "style-src 'nonce-" nonce "'; "
       "connect-src 'self' https://esm.sh; "
       "media-src 'self'; "
       "img-src 'self' data:; "
       "base-uri 'none'"))

;; First paint. Each component renders here from the same converted form, with
;; handlers blanked, which changes no output because Reagami's ssr drops every
;; `on*` attribute by name anyway. Reagami adopts these nodes in the browser
;; instead of rebuilding them. There is no connection yet, so `(request)` in a
;; slot is the page request and its connection id is nil.
(defn- first-paint [spec req]
  (let [mount (build spec req)
        inst  (:instance mount)
        ;; a browser slot has no value yet, so the first paint renders whatever
        ;; the component makes of an empty one
        locals (repeatedly (:locals inst 0) #(atom nil))]
    (ssr/render (into [(:ssr inst)] (concat (slot-vals mount) locals)))))

;; The version comes from this library rather than from the page, so an import
;; map cannot drift away from the Squint that compiled the components.
(def ^:private squint-core "https://esm.sh/squint-cljs@0.14.208/core.js")

(defn- scripts [nonce path]
  (str "<script type=\"importmap\" nonce=\"" nonce "\">\n"
       "{\"imports\": {\"squint-cljs/core.js\": \"" squint-core "\"}}\n"
       "</script>\n"
       "<script type=\"module\" src=\"" path "/client.mjs\"></script>\n"))

(defn- escape [s]
  (str/escape (str s) {\& "&amp;" \< "&lt;" \> "&gt;" \" "&quot;"}))

;; Without an `:index` the page is boilerplate: a div per mount and the two
;; script tags. Buzz knows all of it, so it writes the page instead.
(defn- generated-page [nonce req {:keys [title head mounts path]}]
  (str "<!DOCTYPE html>\n<html>\n<head>\n<meta charset=\"utf-8\">\n"
       "<title>" (escape (or title "buzz")) "</title>\n"
       head
       "</head>\n<body>\n"
       (str/join (for [{:keys [el] :as mount} mounts]
                   (str "<div id=\"" (escape el) "\">" (first-paint mount req) "</div>\n")))
       (scripts nonce (or path ""))
       "</body>\n</html>\n"))

;; With an `:index` the page is yours. Buzz fills each `<!--el-->` with the
;; first paint and every NONCE with the one in the header.
(defn- rendered-page [nonce req {:keys [index mounts]}]
  (-> (reduce (fn [html {:keys [el] :as mount}]
                (str/replace html (str "<!--" el "-->") (first-paint mount req)))
              (slurp (fs/file index))
              mounts)
      (str/replace "NONCE" nonce)))

;; The token is handed out here as well as at the stream, so that a browser
;; opening two tabs at once already has one before either stream asks.
(defn- index-page [req spec]
  (let [nonce (str (random-uuid))]
    {:status 200
     :headers (cond-> {"Content-Type" "text/html"
                       "Content-Security-Policy" (csp nonce)}
                (nil? (browser-token req))
                (merge (token-headers (str (random-uuid)))))
     :body (if (:index spec) (rendered-page nonce req spec) (generated-page nonce req spec))}))

(defn handler
  "Returns a Ring handler for the page described by `spec`. Requests it does not
  own get nil, so an application can compose it with whatever else it serves and
  run whichever server it likes.

  The page belongs to the handler this returns, so an application can serve more
  than one of them. Give each one a `:path` and it answers under that, stream
  and modules included:

    (handler {:path \"/admin\" :mounts [...]})   ; the page is /admin

  The stream is served through `buzz.stream`: give `:adapter` a fn of a
  request and the stream's callbacks to run on another server. Without one
  the bundled http-kit adapter is used.

  Installs watches and starts the heartbeat as a side effect of being called."
  [{:keys [watch mounts path] :as spec}]
  (doseq [m mounts]
    (when (or (:state m) (:component m))
      (throw (ex-info (str "a mount is {:el ... :ui #'component}. :state and :component are gone: "
                           "derive per connection facts from (request) and key your own atoms")
                      {:mount (select-keys m [:el])})))
    (when-not (:ui m)
      (throw (ex-info "a mount needs :ui, a component var or thunk" {:mount m}))))
  @heartbeat
  (let [adapter  (or (:adapter spec)
                     ;; the bundled http-kit adapter, loaded only when asked
                     ;; for, so nothing here names http-kit
                     @(requiring-resolve 'buzz.httpkit/adapter))
        registry (atom {})
        _      (swap! registries conj registry)
        _      (doseq [a watch]
                 ;; keyed by this handler's registry, so two handlers watching
                 ;; one atom each broadcast to their own connections
                 (add-watch a [::render registry] (broadcast-patch! registry)))
        mounts (mapv (fn [m] (assoc m ::instance (shared-instance (:ui m)))) mounts)
        spec   (assoc spec :mounts mounts)
        path   (or path "")
        routes (cond-> {(str path "/")               :page
                        (str path "/client.mjs")     :client
                        (str path "/rpc.mjs")        :rpc-module
                        (str path "/components.mjs") :components
                        (str path "/events")         :events
                        (str path "/rpc")            :rpc}
                 ;; /admin and /admin/ are the same page
                 (seq path) (assoc path :page))]
    ;; the registry rides on the handler for whoever holds the fn, which is
    ;; how the tests look inside without a global to reach for
    (with-meta
      (fn [req]
        (case (routes (:uri req))
          :page       (index-page req spec)
          :client     (runtime-module "client.cljs" path)
          :rpc-module (runtime-module "rpc.cljs" path)
          :components (components-module mounts path)
          :events     (events registry adapter req mounts (:on-close spec))
          :rpc        (rpc registry req)
          nil))
      {::registry registry})))
