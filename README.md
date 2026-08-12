# scittle-split

Electric Clojure's split-a-component-across-the-network idea, done at runtime
instead of by a compiler. The server evaluates its share of a component, prints
the rest, and Scittle evaluates that in the browser.

    bb serve     # http://localhost:1341
    bb split     # print what the splitter makes of the demo component

Open two windows to see them stay in step.

No build step, no bundler, no React. The page loads Scittle, then
[Reagami](https://github.com/borkdude/reagami) straight from its repo, then one
runtime file.

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

```clojure
data: [:session "2fbe6cf7-…"]
data: [:mount "todo-app" "app" "(fn [s1 s2 s3] …)" [({:id 1 :title "…" :done false}) 1 0]]
data: [:patch "todo-app" [({:id 1 :title "…" :done true}) 0 0]]
```

The source crosses once per connection, on the `:mount`. Everything after it is
values.

An RPC's response is empty — `204`. The actual reply is whatever `:patch` the
write produces, on every stream watching that data. That is also why two windows
track each other with no extra work.

The session id exists because the RPC arrives on a separate request and has to
find the handlers belonging to that stream again. `pr-str` escapes newlines
inside strings, so a value can never break out of its own `data:` line, and the
server reads RPC bodies with `clojure.edn/read-string`, which does not eval.

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

`defsplit` emits `:ssr` next to `:src`: the same converted form, compiled here
as an ordinary function, with every `on*` attribute blanked. Blanking costs
nothing, because Reagami's `split-attrs` drops `:key`, `:on-render` and every
`on*` key by name whatever the value — the HTML is identical either way.

The reason to blank them is that they hold browser code. Evaluating the browser
source on this side would mean *analysing* those handlers, and
`(set! (.. e -target -value) "")` is not a valid assignment target on the JVM.
SCI rejects it when the `fn` is built, not when it is called, so a handler that
never runs is still enough to fail the whole component. Emitting `:ssr` from the
macro sidesteps it: no string is evaluated at runtime and nothing browser-shaped
is left to analyse.

Because every value the page shows lives on this side, the first paint is the
real page — click counts included, not just the parts that happen to be static.

The browser side needs no hydration code. `reagami/render` adopts
server-rendered children on its own, so `mount!` is unchanged. Two ways to check,
and they agree:

```clojure
@last-render                                              ; {:created 1, :adopted 23}
```
```js
window.ssrProbe.li === document.querySelector('#app li')  // true — same node
```

`index.html` stashes `window.ssrProbe` before Scittle runs, so node identity is
an independent check on the counts. That mattered while the page still used a
nested render for local state: Reagami keeps one global stats object, so a render
started from an `:on-render` hook clobbered the enclosing render's numbers
([reagami#70](https://github.com/borkdude/reagami/issues/70)). Nothing nests any
more, so the counts are trustworthy again.

## Rendering

Reagami, no React. The server never touches the DOM: it produces
hiccup-*generating code* plus values, and Reagami does all node creation and
diffing. The only place the DOM is mentioned is `mount!`:

```clojure
(defn- mount! [id el src vals]
  (let [f    (js/scittle.core.eval_string src)
        a    (atom vals)
        node (js/document.getElementById el)
        draw #(reagami/render node (into [f] @a))]
    (add-watch a ::draw (fn [_ _ _ _] (draw)))
    (swap! instances assoc id a)
    (draw)))
```

Reagami does not re-render on atom derefs, which suits this design: a `:patch`
is a `reset!`, the watch redraws, and `:key` on the `:li` keeps the diff honest.

Every value the page shows comes from the server, including the click count. That
is what keeps this to one component: Reagami renders one tree from one atom, so
browser-local state would have nowhere to live except a nested render started
from an `:on-render` hook. Putting the state on the server removes the need for
one. The visible consequence is that the counter is shared — click it in one
window and it moves in the other, the same way the todos do.

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
    public/client.cljs     browser runtime, loaded once
    public/index.html

`public/client.cljs` declares `(ns user …)` and is loaded last. Scittle's default
namespace is `user`, and the server's components are evaluated with
`scittle.core.eval_string` in whatever namespace is current — so ending in `user`
is what lets them see `rpc!` with no require. Reagami's own source
leaves the current namespace at `reagami.core`, which is why the `ns` form is
there rather than nothing at all. Load order in `index.html` is load-bearing.

## Rough edges

- `(server ...)` must be written unqualified. The splitter matches the symbol,
  it does not resolve it.
- Scope tracking covers `fn`, `let`, `loop`, `for`, `doseq` and the `when-let`
  family. Anything else that binds names is not seen, and a handler would miss
  the argument.
- Destructuring is over-approximated: every symbol in a binding form counts as
  bound. Handlers can carry a name they don't need, never miss one they do.
- Slot values must print as EDN.
- A browser binding that shadows a server name changes what a handler means, and
  nothing catches it. `(server (swap! clicks inc))` inside a lambda captures any
  name the browser scope has bound, so a `let` binding also called `clicks` would
  ship the rendered number as an argument instead of using the server's atom. The
  demo binds it as `n` for that reason.
- A reconnect re-sends and re-evaluates the component source. The client could
  tell the server which sources it already holds; it doesn't.
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

The browser evaluates whatever the server sends, so the stream carries the same
authority as a `<script>` tag. That is fine when both ends are yours, as here.
It is not a sandbox, and it needs `unsafe-eval` if you set a CSP.

Sending code the *other* way — browser to server — is a different matter, and is
where SCI is actually load-bearing: a locked-down `sci/eval-string` with a
whitelisted namespace set is a real boundary. This demo does not do that; the
server only ever receives a handler id and EDN arguments.
