# Buzz

> ⚠️ **WARNING**: This project is highly experimental and the API will surely change. Use only for non-serious projects.

Buzz lets you write a web application using the JVM (or babashka) only. State
lives on the server and can be watched and updated from client code.

This project uses [Squint](https://github.com/squint-cljs/squint) to compile
the UI to JavaScript and [Reagami](https://github.com/borkdude/reagami)
to renders it.

Buzz runs on Babashka and on the JVM. You do not need other tooling like ClojureScript or Node.js.

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
after each change to something the expression read through `observe`, and the
result is sent to the browser. See [Sources](#sources).

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

Rendering is asynchronous: a write returns at once, and rendering happens at
most once per `:render-interval-ms` (default 20). The first write renders
immediately and writes inside the window collapse into one render carrying the
latest state, so patches are sampled state, not every state: a counter can
step from 3 to 7. Pass `:render-interval-ms 0` to render synchronously on the
writing thread, which makes tests deterministic.

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

A slot reads server state through a source, and reading a key subscribes the
connection to it. A write then reaches the connections that read the key it
changed, and no others.

```clojure
(defonce todos (atom {"alice" [] "bob" []}))

(def by-user (buzz/atom-source todos))

(defui board []
  [:ul (for [t (server (buzz/observe by-user [(whoami (request))]))]
         [:li t])])
```

`buzz/observe` reads a key and subscribes the connection to it. What a
connection holds is whatever its slots read, so there is nothing to declare and
nothing to keep in step. Adding a note for alice runs alice's slots. Bob's do
not run.

Buzz keeps one subscription per key per process, shared by every connection
reading it, and releases it once the last connection lets go.

A key decides which connections render, not which slots. A connection runs all
of its slots whenever any key it reads changes.

Read a wide key and you get a wide fan out. `(observe by-user [])` is the whole
map, so every connection reading it renders on every write. Narrow the key and
the fan out narrows with it.

State a slot reads any other way has nothing watching it, so nothing will ever
update that connection. Read it through a source, or accept that it is fixed
for the life of the page.

Implement `buzz.source/Source` to render from something other than an atom. It
takes a subscribe and an unsubscribe, and the handle it returns is what
`observe` derefs. `examples/datalevin` has one over a database, driven by the
transaction report.

See [examples/observe](examples/observe) for the smallest version of all of
this.

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

- [examples/observe](examples/observe) is two pages over one atom, each
  reading one key of it.
- [examples/auth](examples/auth) signs two users in and gives each of them
  their own data.
- [examples/tap-viewer](examples/tap-viewer) shows everything the process taps, with a tree
  the browser folds by itself.
- [examples/whiteboard](examples/whiteboard) is a shared whiteboard with live
  cursors, one color per connection.
- [examples/datalevin](examples/datalevin) is a Datalevin browser over a
  MusicBrainz sample, with a query log shared between viewers. It has a source
  over the database.

## Development

    bb dev    # the demo, plus an nrepl on 1667

Evaluate a `defui` or a `defpart` again and the open page updates. Browser state
survives the update, and also a reconnect after a restart.
