# Buzz

> ⚠️ **WARNING**: This project is highly experimental and the API will surely change. Use only for non-serious projects.

Use Buzz to write an interactive web UI and its server code in one Clojure
definition. Write Hiccup and event handlers as browser code, with `server`
expressions for server values and `server!` calls for server actions. Local
interactions run in the browser, while changes to observed server state push
new values into the same component. The browser renders the UI from those
values and its own local state.

[Squint](https://github.com/squint-cljs/squint) compiles the browser half and
[Reagami](https://github.com/borkdude/reagami) renders it.

Run Buzz with Babashka or Java 21 or later. You do not need a ClojureScript
build or Node.js.

Try the demo from this repository:

    bb serve    # a demo on http://localhost:1341

Two applications written with Buzz:
[tube-pod](https://github.com/borkdude/tube-pod), a panel that turns videos
into a podcast feed, and
[multi-snake](https://github.com/borkdude/multi-snake), a multiplayer snake
game. [Play it here](https://multi-snake.michielborkent.nl).

## Quickstart

Create a project with two files. In `deps.edn`, replace `<latest>` with a
Buzz commit SHA:

```clojure
{:paths ["src"]
 :deps {io.github.borkdude/buzz
        {:git/sha "<latest>"}}}
```

`src/counter.clj`:

```clojure
(ns counter
  (:require [buzz.core :as buzz :refer [client defui local-state observe server server!]]
            [org.httpkit.server :as http]))

(defonce clicks (atom 0))

(def counter-source (buzz/atom-source clicks))

(defui counter []
  (let [n    (server (observe counter-source []))
        step (local-state 1)]
    [:div
     [:p "clicked " n " times"]
     [:button {:on-click (fn [_] (server! (swap! clicks + (client @step))))} "add"]
     [:button {:on-click (fn [_] (swap! step inc))} (str "step " @step)]]))

(def ui
  (buzz/handler {:title "counter"
                 :mounts [{:el "app" :ui #'counter}]}))

(defn -main [& _]
  (http/run-server (fn [req] (or (ui req) {:status 404 :body "not found"}))
                   {:port 1350})
  (println "http://localhost:1350")
  @(promise))
```

Then run it:

    clojure -M -m counter

Open http://localhost:1350 in two tabs. Click **add** to update the count in
both tabs. Click **step** to change how much the current tab adds.

## Server calls and local state

Write the body of `defui` as browser code. Use these forms to read server
values, call server actions, and keep local state:

- `(server expr)` reads a server value. Buzz evaluates the expression again
  when observed state changes and sends the result to the browser.
- `(server! expr)` runs a server action, such as saving a form. Call it from
  an event handler. It returns a JavaScript promise.
- `(client expr)` passes a browser value to a `server!` action.
- `(local-state init)` creates a browser-local atom. Use `deref`, `reset!`,
  and `swap!` to read and change it. Each mount keeps its own atom across
  renders. The initial value can use a `server` expression.

Use `reply` inside `server!` to return a value to the browser. Supply a Ring
response map as the second argument to set a cookie or other response headers:

```clojure
(server! (reply :ok {:headers {"Set-Cookie" "session=abc; HttpOnly; Path=/"}}))
```

## Parts

Use `buzz/defn` to define a function for the browser and the server:

```clojure
(buzz/defn row [item]
  [:li (:title item)])
```

Call `(row item)` inside `defui` or another part. Parts can call themselves
recursively and use `server!` for actions. Define `server` and `local-state`
in `defui`, then pass their values as arguments. Use `host` where the browser
and the server need different code. See [doc/parts.md](doc/parts.md).

## Mounting

Use `buzz/handler` to serve a page and its components. Add it to your Ring
application with `or`. It returns `nil` for routes it does not handle:

```clojure
(defn app [req]
  (or (ui req) (my-other-routes req)))
```

Add an entry to `:mounts` for each component on the page. Set `:el` to the
HTML element ID and `:ui` to the component var. Re-evaluate the var to update
open pages:

```clojure
:mounts [{:el "app" :ui #'todo-app}]
```

Use the default http-kit adapter, or supply `:adapter` for another Ring
server. See [buzz.stream](src/buzz/stream.clj) for the adapter contract.

Set `:render-interval-ms` to control how often server values update, in
milliseconds (default 20). Updates run asynchronously for each open page.
The first change triggers an update immediately. Changes within the interval
are combined into one update with the latest state, so a counter can step
from 3 to 7. Set the interval to 0 to wait for affected pages' server values
to be sent before a write returns.

Set `:path` to serve a page at another URL. Its event stream and JavaScript
modules use the same prefix:

```clojure
(def admin (buzz/handler {:path "/admin" :mounts [...]}))   ; the page is /admin
(def home  (buzz/handler {:mounts [...]}))                  ; the page is /

(defn app [req] (or (admin req) (home req) {:status 404 :body "not found"}))
```

## Sources

Use `buzz/atom-source` to create a source and `buzz/observe` inside
`server` to read a path from it. Changes to that path update the pages
that read it.

```clojure
(defonce todos (atom {"alice" [] "bob" []}))

(def by-user (buzz/atom-source todos))

(defui board []
  [:ul (for [t (server (buzz/observe by-user [(whoami (request))]))]
         [:li t])])
```

Adding a todo for alice updates alice's open pages. Bob's pages
keep their current values. Each affected page runs all its server
expressions again.

Use `[]` to observe the whole atom:

```clojure
(server (buzz/observe by-user []))
```

Changes to any user's todos now update every page reading the map.

Use `observe` for state changes that should trigger a render. A direct read,
such as `@todos`, refreshes only when another change triggers a render.

Implement [buzz.source/Source](src/buzz/source.clj) to observe other data
sources. Return a dereferenceable handle from `-subscribe` and release it
in `-unsubscribe`. See [examples/datalevin](examples/datalevin) for a
database source.

See [examples/observe](examples/observe) for two counters that update
independently.

## Request

Use `(buzz/request)` inside `(server ...)` and `(server! ...)` to read the
current Ring request. During the initial HTML render, this is the page
request. Later `server` evaluations use the request that opened the event
stream. A `server!` action uses the request that called it.

Keep state in application atoms. Use `(buzz/token (buzz/request))` as a key for
browser-scoped state and `(buzz/connection (buzz/request))` for
connection-scoped state. See [examples/auth](examples/auth) for per-user state
and authentication.

```clojure
(defonce queries (atom {}))   ; connection id -> search text
(def query-source (buzz/atom-source queries))

(defn- my-query [req]
  (or (buzz/observe query-source [(buzz/connection req)]) ""))
(defn- remember! [req q] (swap! queries assoc (buzz/connection req) q))

(defui todo-app []
  (let [todos (server (matching (my-query (buzz/request))))]
    [:div
     [:input {:on-input (fn [e] (server! (remember! (buzz/request)
                                                    (client (.. e -target -value)))))}]
     ...]))
```

A reconnect gets a new connection ID. Use `:on-close` to remove
connection-scoped state. Buzz passes it the request that opened the connection:

```clojure
(buzz/handler {:on-close (fn [req] (swap! queries dissoc (buzz/connection req))) ...})
```


## The page

Set `:title` to name the page and `:head` to add HTML such as stylesheet
links. Buzz creates the page with an element for each mount, its initial
content, and the scripts needed to run it.

Set `:index` to use your own HTML file:

```clojure
(buzz/handler {:index "public/index.html" :mounts [{:el "app" :ui #'todo-app}]})
```

Add the mount element and scripts shown below. Put `<!--app-->` inside the
element to include its initial content in the HTML response. Use `NONCE` on
the inline script so Buzz can authorize it under the page's content security
policy:

```html
<div id="app"><!--app--></div>
<script type="importmap" nonce="NONCE">
  {"imports": {"squint-cljs/core.js": "https://esm.sh/squint-cljs@0.14.208/core.js"}}
</script>
<script type="module" src="/client.mjs"></script>
```

Omit `<!--app-->` to render that component only after the browser connects.

## Examples

- [examples/observe](examples/observe) shows two pages that observe separate keys in one atom.
- [examples/auth](examples/auth) signs two users in and gives each of them
  their own data.
- [examples/tap-viewer](examples/tap-viewer) displays `tap>` values in an
  expandable tree.
- [examples/whiteboard](examples/whiteboard) is a shared whiteboard with live
  cursors, one color per connection.
- [examples/datalevin](examples/datalevin) is a Datalevin browser over a
  MusicBrainz sample, with a query log shared between viewers. It uses a database source.

## Development

    bb dev    # the demo, plus an nrepl on 1667

Re-evaluate a `defui` or `buzz/defn` to update open pages. Local state survives
updates and reconnects when the number of `local-state` forms stays the same.
