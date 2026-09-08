# Functions

Use `buzz/defn` to define a function for the browser and the server. Squint
compiles it for the browser. The first paint and server code call the function
compiled on the JVM. The function can return Hiccup or any other value, and
can call itself:

```clojure
(buzz/defn node [r]
  [:li (:label r)
   [:button {:on-click (fn [_] (server! (bump! (client (:id r)))))} "!"]
   (when (seq (:children r))
     [:ul (for [c (:children r)] (node c))])])

(defui viewer []
  [:ul (node (server @tree))])
```

`defpart` is the former name of `buzz/defn`.

A plain `defn` is not compiled for the browser. Calling one from `defui` fails
in the browser with a ReferenceError.

## Arguments

A `buzz/defn` cannot contain `(server ...)` or `(local-state ...)`. Use these
forms in `defui` and pass their results as arguments:

```clojure
(buzz/defn row [item selected]
  [:li {:class (when (= item @selected) "selected")
        :on-click (fn [_] (reset! selected item))}
   item])

(defui shelf [store]
  (let [items    (server @store)
        selected (local-state nil)]
    [:ul (for [item items]
           (row item selected))]))
```

`selected` is browser state. The function receives the atom as an argument and
can read or update it.

## Handlers

A `buzz/defn` can contain `(server! ...)`. Wrap browser values in
`(client ...)` when sending them to the server.

Use `(buzz/request)` in a handler to access connection-scoped state:

```clojure
(defonce carts (atom {}))

(buzz/defn clear-button []
  [:button
   {:on-click (fn [_]
                (server! (swap! carts assoc
                                (buzz/connection (buzz/request))
                                [])))}
   "clear"])
```

## Browser and server branches

Use `host` where the browser and the server need different code. The browser
runs the `:cljs` branch. Server calls, including the first paint, run the
`:clj` branch. A missing branch uses `:default`, or nil:

```clojure
(buzz/defn parse-number [s]
  (host :clj (Double/parseDouble s) :cljs (js/parseFloat s)))
```

A `:cljs` branch on its own makes a browser-only function:

```clojure
(buzz/defn commit! [pending* k]
  (host :cljs (fn [raw]
                (let [v (js/parseFloat raw)]
                  (when-not (js/isNaN v)
                    (server! (save! (client k) (client v))))
                  (swap! pending* dissoc k)))))
```

`host` is valid in `defui` and `buzz/defn` bodies, including event handlers.
It is refused inside `server` and `server!`. Outside `defui` and `buzz/defn`,
it uses the `:clj` branch, with the same fallback.

Use `host` for browser-only code in a `local-state` initial value. Initial
values are also computed for the first paint.

Inside a `fn`, `js/` interop throws if called during the first paint. Event
handlers do not run during the first paint and need no `host`. Outside a
`fn`, wrap browser-only code in `host :cljs` to load the definition.

Read server state with `server` and run server actions with `server!`.
Use `host` to select code for each runtime.

## Editing in the REPL

Re-evaluate a `buzz/defn` in the REPL to hot-reload open pages.

## Limitations

- A `buzz/defn` takes one arity and a fixed number of arguments.
- A `buzz/defn` can call only functions that are already defined. Mutual
  recursion requires re-evaluating the first definition after both exist.
