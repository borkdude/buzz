# scittle-split, squint branch

Electric Clojure's split-a-component-across-the-network idea, without a
ClojureScript build. The server evaluates its share of a component and compiles
the rest to JavaScript with [Squint](https://github.com/squint-cljs/squint),
running in Babashka.

    bb serve     # http://localhost:1341
    bb split     # print what the splitter makes of the demo component

Open two windows to see them stay in step.

The `scittle` branch does the same thing by shipping Clojure source and
interpreting it in the browser. This one compiles instead, so the page loads no
interpreter: [Reagami](https://github.com/borkdude/reagami) and Squint's core
come from a CDN, and everything else is compiled here.

Squint compiles keywords to strings and vectors to arrays, and Reagami's npm
build is itself Squint output, so hiccup crosses the network as ordinary JSON
and arrives as the exact shape Reagami already expects.

## Writing a component

The body is browser code. `(server ...)` marks what stays here:

```clojure
(defsplit todo-app []
  (let [todos (server (vals @db))]
    [:ul
     (for [{:keys [id title done]} todos]
       [:li {:key id}
        [:input {:type "checkbox" :checked done
                 :on-change (fn [_] (server (toggle! id)))}]
        [:span title]])]))
```

Two positions, two meanings:

- **value position** — `(server (vals @db))` is evaluated here on every render.
  It becomes a parameter of the browser function, and its result travels as a
  plain value.
- **inside a lambda** — `(server (toggle! id))` becomes a handler here. The
  browser gets `rpc!` in its place, carrying `id`.

`id` is a binding the *browser* made, in its own `for`. The splitter works out
which local names a server expression needs by walking the form and tracking
what is in scope, so nothing has to be declared or threaded by hand.

## What crosses the wire

`bb split` prints the browser function. Slots are hoisted to parameters and
`(server ...)` calls inside lambdas are gone:

```clojure
(fn [slot__378 slot__379]
  (let [todos slot__378 left slot__379]
    [:div
     [:h1 "todos"]
     [:ul (for [{:keys [id title done]} todos]
            [:li {:key id}
             [:input {:type "checkbox" :checked done
                      :on-change (fn [_] (rpc! "todo-app/1" [id]))}]
             [:span {:class (when done "done")} title]
             [:button.del {:on-click (fn [_] (rpc! "todo-app/2" [id]))} "×"]])]
     [:p.count left " left"]
     [counter]]))
```

## Transport

Two endpoints, neither of them bidirectional:

- `GET /events` — one SSE stream per browser, held open
- `POST /rpc` — an ordinary request, body `[session handler-id args]`

`curl -N localhost:1341/events` shows the whole conversation:

```js
data: ["session","2fbe6cf7-…"]
data: ["mount","todo-app","app","(function (slot__1, slot__2) { … })",[[{"id":1,"title":"…","done":false}],1,0]]
data: ["patch","todo-app",[[{"id":1,"title":"…","done":true}],0,0]]
```

The compiled function crosses once per connection, on the `:mount`. Everything
after it is values.

The wire is JSON rather than EDN. Squint has no EDN reader, and it does not need
one: keyword keys are already strings on the other side, so `{:id 1}` arrives as
`{"id": 1}` and `(:keys [id])` destructuring compiles to `SQ.get(m, "id")`.

An RPC's response is empty — `204`. The actual reply is whatever `:patch` the
write produces, on every stream watching that data. That is also why two windows
track each other with no extra work.

The session id exists because the RPC arrives on a separate request and has to
find the handlers belonging to that stream again. JSON escapes newlines inside
strings, so a value can never break out of its own `data:` line.

EventSource reconnects on its own, so the client has no retry loop. On reconnect
the server sends a fresh `:session` and `:mount`, and the page rebuilds without a
reload — kill the server and restart it with the page open to watch it come back.

There is no separate op for shipping source. One component covers the page, so
source and first values always travel together and `:mount` carries both.

## First paint

`GET /` is server-rendered. The same component renders here through
`reagami.ssr/render`, and the browser adopts those nodes instead of building
its own:

```clojure
(defn- index []
  (let [inst (app/todo-app)
        html (ssr/render (into [(:ssr inst)] ((:slots inst))))]
    …))
```

`defsplit` emits `:ssr` next to `:js`: the same converted form, compiled as
Clojure rather than to JavaScript, with every `on*` attribute blanked. Blanking
costs nothing, because Reagami's `split-attrs` drops `:key`, `:on-render` and
every `on*` key by name whatever the value — the HTML is identical either way.

The reason to blank them is that they hold browser code. Compiling those
handlers as Clojure would mean *analysing* them, and
`(set! (.. e -target -value) "")` is not a valid assignment target on the JVM.
Squint has no such trouble, since it emits `e.target.value = ""` as text and
never analyses it as Clojure — but the ssr copy is real Clojure and does.

Because every value the page shows lives on this side, the first paint is the
real page — click counts included, not just the parts that happen to be static.

The browser side needs no hydration code. `reagami/render` adopts
server-rendered children on its own, so `mount!` is unchanged. Two ways to check,
and they agree:

```js
window.ssrProbe.li === document.querySelector('#app li')  // true — same node
```

`index.html` stashes `window.ssrProbe` before the module runs. Reagami renders
this page from two different builds, the JVM one here and the Squint one in the
browser, and the second still adopts what the first produced.

## Rendering

Reagami, no React. The server never touches the DOM: it produces
hiccup-*generating code* plus values, and Reagami does all node creation and
diffing. The only place the DOM is mentioned is `mount!`:

```clojure
(defn- component [js]
  ((js/Function. "SQ" "rpc_BANG_" (str "return " js)) SQ rpc!))
```

The compiled component is an expression with `SQ` and `rpc_BANG_` left free.
Passing them as arguments to a `Function` keeps both out of the global scope, so
the page defines no globals of its own.

Reagami does not re-render on its own, which suits this design: a `:patch`
replaces the values and redraws, and `:key` on the `:li` keeps the diff honest.

Every value the page shows comes from the server, including the click count. That
is what keeps this to one component: Reagami renders one tree from one atom, so
browser-local state would have nowhere to live except a nested render started
from an `:on-render` hook. Putting the state on the server removes the need for
one. The visible consequence is that the counter is shared — click it in one
window and it moves in the other, the same way the todos do.

## Against the scittle branch

What the browser downloads before anything renders:

    scittle branch                          squint branch
    scittle.js       967 KB  (193 KB gz)    client.mjs      1.6 KB
    reagami core.cljc 28 KB, interpreted    reagami.mjs     9.5 KB (3.8 KB gz)
    client.cljs, interpreted                squint core      59 KB  (18 KB gz)

Both branches keep the same `defsplit`, the same splitter and the same SSR path.
They differ in what `:mount` carries and who reads it.

Scittle ships Clojure and keeps a reader and an interpreter in the browser, so a
value keeps its type across the wire and `curl` shows EDN. Squint ships
JavaScript, so the browser starts faster and stays smaller, but the wire is JSON
and loses keywords and integer keys on the way.

Squint also compiles once, at namespace load, where Scittle re-reads on every
`:mount`. Against that, Scittle can send a form it built a moment ago, which this
branch cannot without running the compiler per request.

## How it differs from Electric

Electric is a compiler. It builds a reactive dataflow graph, works out which
nodes sit on which side, and moves values between them at whatever granularity
the graph implies.

This is coarse. A component is the unit. Any `(server ...)` change re-runs every
slot thunk in that component and re-sends all of them. In exchange the whole
thing is a walk over a form: readable, printable, and debuggable with `curl`.

## Layout

    src/split/core.clj     the splitter and defsplit
    src/split/app.clj      demo db and component
    src/split/server.clj   http-kit, SSE, /rpc, static files
    public/client.cljs     browser runtime, compiled and served as /client.mjs
    public/index.html

`public/client.cljs` is Clojure that never reaches the browser as Clojure.
Squint compiles it per request, so editing it needs only a reload. It is an
ordinary ES module and imports Reagami by URL. Squint emits a bare specifier for
its own core, which is the one thing `index.html` has to map:

```html
<script type="importmap">
  {"imports": {"squint-cljs/core.js": "https://esm.sh/squint-cljs@0.14.208/core.js"}}
</script>
```

## Rough edges

- `(server ...)` must be written unqualified. The splitter matches the symbol,
  it does not resolve it.
- Scope tracking covers `fn`, `let`, `loop`, `for`, `doseq` and the `when-let`
  family. Anything else that binds names is not seen, and a handler would miss
  the argument.
- Destructuring is over-approximated: every symbol in a binding form counts as
  bound. Handlers can carry a name they don't need, never miss one they do.
- Slot values must survive JSON. Keywords, sets and namespaced keys do not
  round-trip, and integer map keys come back as strings.
- A browser binding that shadows a server name changes what a handler means, and
  nothing catches it. `(server (swap! clicks inc))` inside a lambda captures any
  name the browser scope has bound, so a `let` binding also called `clicks` would
  ship the rendered number as an argument instead of using the server's atom. The
  demo binds it as `n` for that reason.
- A reconnect re-sends and re-evaluates the compiled function. The client could
  tell the server what it already holds; it doesn't.
- Squint compiles at macro expansion, so a component that fails to compile fails
  at namespace load rather than at request time. That is the good direction, but
  the error arrives without a request to blame it on.
- Attribute names go through verbatim. Reagami is not React, so it is
  `:autofocus`, not `:auto-focus` — the React spelling renders an inert
  `auto-focus="true"` on both sides rather than failing.
- Server state lives in plain atoms, so restarting the server resets it.
- Everything the page shows is server state, so there is nowhere to put state
  that should stay in one browser. Adding that back means either a nested render
  or letting the client contribute slots of its own.
- One component per page, mounted once per connection. A `defsplit` cannot render
  another, and nesting one inside another is not implemented.
- `rpc!` is fire and forget. There is no error path back to the caller.
- Sessions are never expired, and an RPC only needs a session id to act. Fine on
  localhost; not an auth model.

## Note on trust

The browser runs whatever the server sends, so the stream carries the same
authority as a `<script>` tag. That is fine when both ends are yours, as here.
It is not a sandbox, and `js/Function` needs `unsafe-eval` if you set a CSP.
Compiling instead of interpreting does not change that: the output is still code
the browser is asked to run sight unseen.

The server only ever receives a handler id and JSON arguments, never code.
