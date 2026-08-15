# Parts

A part is a hiccup helper defined with `defpart`. It used to be spliced into
whichever component used it. Now it compiles to a function the browser calls,
unless its body needs the caller, in which case it is spliced as before. This
page describes both, and what the change makes possible.

## Function parts

Write a part the way you would write a function. It may call itself, so a tree
can draw a tree:

```clojure
(defpart node [r]
  [:li (:label r)
   [:button {:on-click (fn [_] (server! (bump! (client (:id r)))))} "!"]
   (when (seq (:children r))
     [:ul (for [c (:children r)] (node c))])])

(defui viewer []
  [:ul (node (server @tree))])
```

The body sees its parameters and globals. Server data enters as an argument:
the caller writes `(server ...)` where it calls the part, and the part
receives a plain browser value. One slot carries the whole tree, and the
recursion walks it in the browser.

`(server! ...)` works inside a function part. Its handlers are registered
under the part's own name, `node/0` above, so using a part does not renumber
the component and reordering calls changes nothing on the wire. The handler
closes over globals and receives `(client ...)` arguments. It never sees a
mount's `:state`, because the part is compiled once, not once per connection.

The component compiles to a call, not a copy. The module the browser imports
defines each part once:

```js
const node = (function (r) { ... node(c) ... });
```

The first paint calls the part on the server: `defpart` defines an ordinary
function whose body is the server-renderable form, so recursion terminates on
the data it is given.

## Editing a part

Evaluate a `defpart` again and every open page updates. The module and the
handler maps are read per request, so the components that call the part are
not expanded again. Two edits still expand them, because the callers compiled
against what changed: giving the part a different number of parameters, and
changing it from a spliced part into a function part or back.

## Spliced parts

A part whose body needs the caller is spliced into it, which is what every
part used to be. Three things ask for that:

- a `^:server` parameter, which substitutes something that only exists on the
  server, such as an atom the component was given
- a value position `(server ...)`, since a slot belongs to a component
- a `(local-state ...)`, since a local belongs to a component

```clojure
(defpart row [item ^:server store]
  [:li {:on-click (fn [_] (server! (swap! store conj (client item))))}
   item " of " (server (count @store))])
```

A spliced part's handlers belong to the component it is spliced into, and its
slots are hoisted there, so each use brings its own. Editing one expands every
component again.

A spliced part cannot call itself. The splice would never end, so it is an
error:

```
row is spliced into itself: app/row
```

## Limitations

- Mutual recursion needs the callee to exist first. Define `b` before `a`, or
  evaluate `a` again once `b` exists, so the dependency is recorded.
- Part names share one module scope. Two function parts with the same name in
  different namespaces collide there.
- A spliced part could read names from the caller's scope that were never
  parameters. A function part cannot, so a part that leaned on that splices
  or breaks. Pass what the part needs.
