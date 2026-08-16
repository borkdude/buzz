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
  (:require [buzz.core :as buzz :refer [client defui local-state server server!]]
            [org.httpkit.server :as http]))

(defonce clicks (atom 0))

(defui counter []
  (let [n    (server @clicks)
        step (local-state 1)]
    [:div
     [:p "clicked " n " times"]
     [:button {:on-click (fn [_] (server! (swap! clicks + (client @step))))} "add"]
     [:button {:on-click (fn [_] (swap! step inc))} (str "step " @step)]]))

(def ui
  (buzz/handler {:title "counter"
                 :watch [clicks]
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
after each change to an observed atom and the result is sent to the browser.

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

`defpart` creates a browser function and supports recursion. Pass server
values, local state and per-connection handlers from `defui` as arguments. A
`server!` that uses global state can stay in the part. See
[doc/parts.md](doc/parts.md).

## Mounting

The `buzz/handler` function returns a Ring handler and is server agnostic. In babashka, we typically use `org.httpkit.server/run-server` to run it. The one thing a plain Ring handler cannot do is hold the event stream open, so that goes through a `buzz.stream` adapter: http-kit's ships with Buzz and is used when a handler gets no `:adapter` of its own.

To compose the handler with other routes, you can use `or` since the handler returns `nil` for unknown routes. For example:

```clojure
(defn app [req]
  (or (ui req) (my-other-routes req)))
```

Buzz watches each atom in `:watch`. When one of them changes, it re-renders the component and sends a patch to each browser. One mount can hold one component at one element. A page can have more than one mount.

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

## The request

Inside a `(server ...)` or a `(server! ...)`, `(request)` is the request that
caused that code to run: the one that opened the stream for a slot, the rpc
itself for a handler. Identity and scope derive from it, and state lives in
your own atoms:

```clojure
(defonce queries (atom {}))   ; connection id -> search text, so one per tab

(defn- my-query  [req]   (get @queries (buzz/connection req) ""))
(defn- remember! [req q] (swap! queries assoc (buzz/connection req) q))

(defui todo-app []
  (let [todos (server (matching (my-query (request))))]
    [:div
     [:input {:on-input (fn [e] (server! (remember! (request)
                                                    (client (.. e -target -value)))))}]
     ...]))
```

A helper is ordinary server code, so it takes the request as an argument. The
marks themselves stay in the body: `(request)` and `(client ...)` are
rewritten at compile time, so a helper cannot call them itself.

Key by `(whoami (request))` for state a user owns, `(buzz/token (request))`
for state a browser owns, and `(buzz/connection (request))` for state a tab
owns. Connections end, so a handler takes `:on-close`, called with the
connection id, to let go of what a tab held:

```clojure
(buzz/handler {:on-close (fn [conn] (swap! queries dissoc conn)) ...})
```

See [examples/auth](examples/auth) for a page that signs two users in and
gives each of them their own data, checked again on every action.

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

## Development

    bb dev    # the demo, plus an nrepl on 1667

Evaluate a `defui` or a `defpart` again and the open page updates. Browser state
survives the update, and also a reconnect after a restart.
