# Parts

A part is a hiccup helper defined with `defpart`. It compiles to a function
the browser calls, so it may call itself and a tree can draw a tree:

```clojure
(defpart node [r]
  [:li (:label r)
   [:button {:on-click (fn [_] (server! (bump! (client (:id r)))))} "!"]
   (when (seq (:children r))
     [:ul (for [c (:children r)] (node c))])])

(defui viewer []
  [:ul (node (server @tree))])
```

The body sees its parameters and globals. One slot carries the whole tree,
and the recursion walks it in the browser.

## Data down, actions up

A component owns everything that crosses the wire. `(server ...)` and
`(local-state ...)` are written in a `defui`, so its head is the whole list
of what the page receives. A part is a pure function of what is passed down:

```clojure
(defpart row [item n add!]
  [:li {:on-click add!} item " of " n])

(defui shelf [store]
  [:ul (row "a" (server (count @store))
            (fn [_] (server! (swap! store conj (client "a")))))])
```

The value arrives as a plain argument. The handler does too, and it converts
in the component's body, so it closes over what the component was given, a
mount's per connection `:state` included.

Writing one of the component marks in a part is an error that says where it
goes:

```
(server ...) in row is a value the component owns. Pass it as an argument: (row (server ...))
(local-state ...) in row is state the component owns. Make it there and pass the atom
```

## Handlers in the part

A `(server! ...)` over global state works in the body itself. Its handlers
are registered under the part's own name, `node/0` above, so using a part
does not renumber a component and reordering calls changes nothing on the
wire. Such a handler closes over globals and receives `(client ...)`
arguments. It never sees a mount's `:state`: the part is compiled once, not
once per connection, which is what the passed handler above is for.

## What the browser gets

The component compiles to a call, not a copy. The module the browser imports
defines each part once:

```js
const node = (function (r) { ... node(c) ... });
```

The first paint calls the part on the server: `defpart` defines an ordinary
function whose body is the server renderable form, so recursion terminates on
the data it is given.

## Editing a part

Evaluate a `defpart` again and every open page updates. The module and the
handler maps are read per request, so the components that call the part are
not expanded again. Changing the number of parameters is the exception: the
callers compiled against it, so they are expanded again and a call that no
longer fits fails loudly.

## Limitations

- Mutual recursion needs the callee to exist first. Define `b` before `a`, or
  evaluate `a` again once `b` exists, so the dependency is recorded.
- Part names share one module scope. Two parts with the same name in
  different namespaces collide there.
- A handler passed down as an argument is blanked for the first paint by the
  `rpc!` call inside it. A passed function that touches `js/` or `await`
  outside one does not compile on the server yet.
