# Buzz

> ⚠️ **WARNING**: This project is highly experimental and the API will surely change. Use only for non-serious projects.

Use Buzz to write a browser component and its server code in one Clojure
definition. Write Hiccup and event handlers as browser code, with `server`
expressions for server values and `server!` calls for server actions. Local
interactions run in the browser, while changes to observed server state push
new values into the same component. The browser renders the UI from those
values and its own local state.

[Squint](https://github.com/squint-cljs/squint) compiles the browser half and
[Reagami](https://github.com/borkdude/reagami) renders it.

Buzz runs on Babashka and on the JVM. You do not need other tooling like ClojureScript or Node.js.

Each connection renders on its own virtual thread, so the JVM needs 21 or
later. Babashka carries its own runtime and needs nothing.

In this project, you can run:

    bb serve    # a demo on http://localhost:1341
    bb bench    # a benchmark, on http://localhost:1342

Also take a look at [tube-pod](https://github.com/borkdude/tube-pod), a real application I wrote using Buzz.

## Quickstart

Create a project with two files. `deps.edn`:

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

The count is a server value, so it is the same for all browsers. The step is a browser value, so each browser has a different one.

The body of a component is client side code. In the body you can use four marks to communicate with the server or to make local state.

- `(server expr)` is a value from the server. The server runs the expression again
when observed state changes and sends the result to the browser. See
[Sources](#sources).

- `(server! expr)` is way to make the server do something. It is a side effect, not a value. The return value is a promise. Using the special `reply` form, you can send a value back to the browser. Give `reply` a second argument to add to the http response the value arrives in, which is how a handler sets a cookie.

```clojure
(server! (reply :ok {:headers {"Set-Cookie" "session=abc; HttpOnly; Path=/"}}))
```

- `(client expr)` is a client value that crosses into a `server!` form.

- `(local-state init)` is an atom that the client can read and write. It is not sent to the server. This state survives a re-render of the app and is only created once per mount. It is not shared between browsers or tabs. The initial value can read a `server` expression, so a client atom can start from what the server sent.

## Parts

You can define a part of a component with `defpart`. A part is like a component, but it does not have its own root element. You can use a `defpart` inside a `defui` to break it into smaller pieces.

```clojure
(defpart row [item]
  [:li (:title item)])
```

Parts compile to browser functions and can call themselves. Define
`(server ...)` and `(local-state ...)` in `defui`, then pass their results to
the part. Parts can contain `(server! ...)`. See [doc/parts.md](doc/parts.md).

## Mounting

The `buzz/handler` function returns a Ring handler. Its event stream requires a
`buzz.stream` adapter. Buzz uses the bundled http-kit adapter unless the
handler spec supplies `:adapter`.

To compose the handler with other routes, you can use `or` since the handler returns `nil` for unknown routes. For example:

```clojure
(defn app [req]
  (or (ui req) (my-other-routes req)))
```

One mount can hold one component at one element. A page can have more than one mount.

Rendering is asynchronous. Each connection renders independently, at most
once per `:render-interval-ms` (default 20). The first change triggers a
render immediately. Changes within the interval are combined into one render
with the latest state, so a counter can step from 3 to 7. Set
`:render-interval-ms` to 0 to wait for affected connections to render before
a write returns.

A mount names its component by var, so re-evaluating the component reaches
the open pages:

```clojure
:mounts [{:el "app" :ui #'todo-app}]
```

The page belongs to the handler, so one application can serve more than one of them. Give a handler a `:path` and it answers under that path, stream and modules included.

```clojure
(def admin (buzz/handler {:path "/admin" :mounts [...]}))   ; the page is /admin
(def home  (buzz/handler {:mounts [...]}))                  ; the page is /

(defn app [req] (or (admin req) (home req) {:status 404 :body "not found"}))
```

## Sources

Use `buzz/atom-source` to create a source and `buzz/observe` inside
`server` to read a path from it. Changes to that path re-render the
connections that read it.

```clojure
(defonce todos (atom {"alice" [] "bob" []}))

(def by-user (buzz/atom-source todos))

(defui board []
  [:ul (for [t (server (buzz/observe by-user [(whoami (request))]))]
         [:li t])])
```

Adding a todo for alice re-renders alice's connections. Bob's connections
keep their current values. Each affected connection runs all its server
expressions again.

Use `[]` to observe the whole atom:

```clojure
(server (buzz/observe by-user []))
```

Changes to any user's todos now re-render every connection reading the map.

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
current Ring request. In `(server ...)`, this is the request that opened the
event stream. In `(server! ...)`, this is the RPC request.

Keep state in application atoms. Use `(buzz/token (buzz/request))` as a key for
browser-scoped state and `(buzz/connection (buzz/request))` for
connection-scoped state. See [examples/auth](examples/auth) for per-user state
and authentication.

```clojure
(defonce queries (atom {}))   ; connection id -> search text

(defn- my-query  [req]   (get @queries (buzz/connection req) ""))
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

Without an `:index`, Buzz writes the page: a title from `:title`, a div per
mount holding its first render, and the two script tags. `:head` adds anything
else that belongs in the head, such as a stylesheet.

Give `:index` a file to write the page yourself:

```clojure
(buzz/handler {:index "public/index.html" …})
```

Two things in that file are then yours to place. Buzz replaces `<!--el-->` with
the first render of the mount at that element, and every `NONCE` with the one in
the Content-Security-Policy header:

```html
<div id="app"><!--app--></div>
<script type="importmap" nonce="NONCE">
  {"imports": {"squint-cljs/core.js": "https://esm.sh/squint-cljs@0.14.208/core.js"}}
</script>
<script type="module" src="/client.mjs"></script>
```

Leave out the comment and the page still works. It arrives empty and the browser
fills it in.

## Examples

- [examples/observe](examples/observe) shows two pages that observe separate keys in one atom.
- [examples/auth](examples/auth) signs two users in and gives each of them
  their own data.
- [examples/tap-viewer](examples/tap-viewer) shows everything the process taps, with a tree
  the browser folds by itself.
- [examples/whiteboard](examples/whiteboard) is a shared whiteboard with live
  cursors, one color per connection.
- [examples/datalevin](examples/datalevin) is a Datalevin browser over a
  MusicBrainz sample, with a query log shared between viewers. It uses a database source.

## Development

    bb dev    # the demo, plus an nrepl on 1667

Evaluate a `defui` or a `defpart` again and the open page updates. Browser state
survives the update, and also a reconnect after a restart.
