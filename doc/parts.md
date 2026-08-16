# Parts

`defpart` defines a Hiccup function that runs in the browser. Parts can call
themselves:

```clojure
(defpart node [r]
  [:li (:label r)
   [:button {:on-click (fn [_] (server! (bump! (client (:id r)))))} "!"]
   (when (seq (:children r))
     [:ul (for [c (:children r)] (node c))])])

(defui viewer []
  [:ul (node (server @tree))])
```

The server calls the same function for the first render.

## Arguments

A `defpart` cannot contain `(server ...)` or `(local-state ...)`. Use these
forms in `defui` and pass their results to the part:

```clojure
(defpart row [item selected]
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

(defpart clear-button []
  [:button
   {:on-click (fn [_]
                (server! (swap! carts assoc
                                (buzz/connection (buzz/request))
                                [])))}
   "clear"])
```

## Editing a part in the REPL

Re-evaluate a `defpart` in the REPL to hot-reload open pages.

## Limitations

- A `defpart` can call only parts that are already defined. Mutual recursion
  requires re-evaluating the first definition after both parts exist.
- Functions passed to parts must also compile on the JVM for server rendering.
  Keep `js/` and `await` code in the part itself:

  ```clojure
  (defpart submit-button [on-submit]
    [:button {:on-click (fn [e]
                          (js/console.log "saving")
                          (on-submit e))}
     "save"])

  (defui editor []
    (submit-button (fn [_] (server! (persist!)))))
  ```
