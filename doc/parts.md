# Parts

Use `buzz/defn` to define a function for the browser and the server. Squint
compiles it for the browser. The first paint and server code call the function
compiled on the JVM. A part can return Hiccup or any other value, and can call
itself:

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

A part cannot contain `(server ...)` or `(local-state ...)`. Use these forms
in `defui` and pass their results to the part:

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

`selected` is browser state. The part receives the atom as an argument and can
read or update it.

## Handlers

A part can contain `(server! ...)`. Wrap browser values in `(client ...)` when
sending them to the server.

Use `(buzz/request)` in a part handler to access connection-scoped state:

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

A part body runs on both sides, so it must compile on the JVM and as Squint.
Use `host` where the two sides need different code. The browser runs the
`:cljs` branch and the first paint runs the `:clj` branch. A missing branch is
`:default`, or nil:

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
It is refused inside `server` and `server!`. Outside those forms it returns
the `:clj` branch.

## Editing a part in the REPL

Re-evaluate a `buzz/defn` in the REPL to hot-reload open pages.

## Limitations

- A `buzz/defn` takes one arity and a fixed number of arguments.
- A `buzz/defn` can call only parts that are already defined. Mutual recursion
  requires re-evaluating the first definition after both parts exist.
- Functions passed to parts must also compile on the JVM for server rendering.
  Keep `js/` and `await` code in the part itself, or in a `host` form:

  ```clojure
  (buzz/defn submit-button [on-submit]
    [:button {:on-click (fn [e]
                          (js/console.log "saving")
                          (on-submit e))}
     "save"])

  (defui editor []
    (submit-button (fn [_] (server! (persist!)))))
  ```
