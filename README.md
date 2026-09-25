# Buzz

> **Warning:** Buzz is experimental. Expect API changes. Use it for experiments, not production applications.

Use Buzz to build interactive web applications in Clojure. Define a component's
HTML, browser interactions, and server calls together. Read server values with
`server` and change them with `server!`. Pages update automatically when the
server state they observe changes.

[Squint](https://github.com/squint-cljs/squint) compiles your browser code to
JavaScript. [Reagami](https://github.com/borkdude/reagami) renders your Hiccup.

Run Buzz with Babashka or Java 21 or later. You do not need a ClojureScript
build or Node.js.

Try the demo from this repository:

```shell
bb serve
```

Open http://localhost:1341.

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

Create `src/counter.clj`:

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

Run the application:

```shell
clojure -M -m counter
```

Open http://localhost:1350 in two tabs. Click **add** to update the count in
both tabs. Click **step** to change how much the current tab adds.

## Server calls and local state

Use `defui` to define a component with Hiccup and browser event handlers.
Use these forms inside it:

- `(server expr)` reads a server value. Buzz evaluates the expression again
  when observed state changes and sends the result to the browser.
- `(server! expr)` runs a server action, such as saving a form. Call it from
  an event handler. It returns a JavaScript promise.
- `(client expr)` passes a browser value to a `server!` action.
- `(local-state init)` keeps state for one instance of a component in the
  browser. Use `deref`, `reset!`, and `swap!` to read and change the atom.
  Its value persists when the component updates.

Use a `server` expression to initialize local state from a server value.
For an initial value that requires browser code, use `host`:

```clojure
(local-state (host :cljs (js/Date.)))
```

This starts as `nil` in the HTML served by the server, then uses the current
date when the component starts in the browser.

Use `reply` inside `server!` to return a value to the browser. Supply a Ring
response map as the second argument to set a cookie or other response headers:

```clojure
(server! (reply :ok {:headers {"Set-Cookie" "session=abc; HttpOnly; Path=/"}}))
```

## Reuse component code

Use `buzz/defn` to define a function for the browser and the server:

```clojure
(buzz/defn row [item]
  [:li (:title item)])
```

Call `(row item)` inside `defui` or another `buzz/defn`. These functions can
call themselves recursively and use `server!` for actions. Define `server` and
`local-state` in `defui`, then pass their values as arguments. Use `host`
where the browser and the server need different code. See
[doc/defn.md](doc/defn.md).

## Serve components

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

Set `:render-interval-ms` to control the time between server updates. The
default is 20 milliseconds. The first change triggers an immediate update.
Further changes within the interval are combined, so pages receive the latest
state and may skip intermediate values. Set the interval to 0 if a write
should wait until updates have been sent to affected pages.

Set `:path` to serve a page at another URL. Its event stream and JavaScript
modules use the same prefix:

```clojure
(def admin (buzz/handler {:path "/admin" :mounts [...]}))   ; the page is /admin
(def home  (buzz/handler {:mounts [...]}))                  ; the page is /

(defn app [req] (or (admin req) (home req) {:status 404 :body "not found"}))
```

## Update pages when data changes

Wrap an atom with `buzz/atom-source` to let pages subscribe to its changes.
Use `buzz/observe` inside `server` to read a value at a path. Changes at that
path update the pages that read it.

```clojure
(defonce todos (atom {"alice" [] "bob" []}))

(def by-user (buzz/atom-source todos))

(defui board []
  [:ul (for [t (server (buzz/observe by-user [(whoami (request))]))]
         [:li t])])
```

Adding a todo for Alice updates Alice's open pages. Bob's pages keep their
current values. Each affected page runs all its `server` expressions again.

Use `[]` to observe the whole atom:

```clojure
(server (buzz/observe by-user []))
```

Changes to any user's todos now update every page reading the map.

Use `observe` when changes should update the page. Reading `@todos` directly
does not subscribe to changes. That value refreshes only when another change
causes the page to update.

Implement [buzz.source/Source](src/buzz/source.clj) to observe other data
sources. Return a dereferenceable handle from `-subscribe` and release it
in `-unsubscribe`. See [examples/datalevin](examples/datalevin) for a
database source.

See [examples/observe](examples/observe) for two counters that update
independently.

## Access requests and keep user state

Use `(buzz/request)` inside `(server ...)` and `(server! ...)` to read the
current Ring request. During the initial HTML render, this is the page
request. Later `server` evaluations use the request that opened the event
stream. A `server!` action uses the request that called it. All three carry
the query string of the page, so `/les?n=3` has `n=3` in each:

```clojure
(server (get-in (buzz/request) [:query-params "n"]))   ; with Ring's wrap-params
```

Keep state in application atoms. Use `(buzz/token (buzz/request))` as a key
to store state for a browser. Use `(buzz/connection (buzz/request))` to keep
state for one connection, as in the search field below. See
[examples/auth](examples/auth) for user authentication and state.

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

A reconnect gets a new connection ID. Use `:on-close` to remove state when
a connection closes. The callback receives the request that opened it:

```clojure
(buzz/handler {:on-close (fn [req] (swap! queries dissoc (buzz/connection req))) ...})
```


## Customize the page

Set `:title` to name the page and `:head` to add HTML such as stylesheet
links. Buzz creates the HTML for your components and includes the scripts
needed to run them.

Give a `<style>` or stylesheet `<link>` in `:head` the attribute
`nonce="NONCE"`. The content security policy blocks styles without it:

```clojure
(buzz/handler {:head "<style nonce=\"NONCE\">body {margin: 2rem}</style>"
               :mounts [{:el "app" :ui #'todo-app}]})
```

The policy allows `:style` on an element in a component.

Set `:index` to use your own HTML file:

```clojure
(buzz/handler {:index "public/index.html" :mounts [{:el "app" :ui #'todo-app}]})
```

Add an element with the ID from `:mounts` and the scripts shown below. Put
`<!--app-->` inside the element to show the component before the browser
connects. Keep the `NONCE` attribute so the inline script can run under the
page's content security policy. Buzz replaces `SQUINT_CORE` with the URL of the
squint runtime it serves, which carries a version so the browser keeps it:

```html
<div id="app"><!--app--></div>
<script type="importmap" nonce="NONCE">
  {"imports": {"squint-cljs/core.js": "SQUINT_CORE"}}
</script>
<script type="module" src="/client.mjs"></script>
```

Omit `<!--app-->` to render that component only after the browser connects.

## Test a component

Call the handler with a request map to test a component without a browser.
The `:body` holds the first render of each mount:

```clojure
(defui les []
  [:h1 "Les " (server (get-in (buzz/request) [:query-params "n"] "1"))])

(def page (buzz/handler {:mounts [{:el "app" :ui #'les}]}))

(deftest the-page-shows-the-lesson
  (let [body (:body (page {:uri "/" :query-params {"n" "3"}}))]
    (is (str/includes? body "<h1>Les 3</h1>"))))
```

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

Run the demo with an nREPL server on port 1667:

```shell
bb dev
```

Re-evaluate a `defui` or `buzz/defn` in your REPL to update open pages.
Components keep their local state across code updates and reconnects when
the number of `local-state` forms stays the same.
