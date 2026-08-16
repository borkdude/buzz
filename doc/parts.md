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

To update a per-connection atom, define the handler in `defui` and pass it to
the part:

```clojure
(defpart clear-button [clear!]
  [:button {:on-click clear!} "clear"])

(defui cart [items]
  (let [clear! (fn [_] (server! (reset! items [])))]
    (clear-button clear!)))
```

`items` can be an atom from the mount's `:state` map.

## Editing a part

Re-evaluate a `defpart` to update open pages. Buzz recompiles the components
that use it only when the number of parameters changes.

## Limitations

- A `defpart` can call only parts that are already defined. Mutual recursion
  requires re-evaluating the first definition after both parts exist.
- A function passed to a part can contain `(server! ...)`, but not other
  browser-only forms. For example, this does not compile:

  ```clojure
  (defpart submit-button [on-submit]
    [:button {:on-click on-submit} "save"])

  (defui editor []
    (submit-button
     (fn [_]
       (js/console.log "saving")
       (server! (persist!)))))
  ```
