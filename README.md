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

## Quick start

Three files. `deps.edn`:

```clojure
{:paths ["src"]
 :deps {io.github.borkdude/buzz
        {:git/sha "533cc5d03fc525ea1bdaba4d84091ffde55b8c79"}}}
```

`public/index.html`:

```html
<!DOCTYPE html>
<html>
  <head><meta charset="utf-8"><title>counter</title></head>
  <body>
    <div id="app"><!--app--></div>
    <script type="importmap" nonce="NONCE">
      {"imports": {"squint-cljs/core.js": "https://esm.sh/squint-cljs@0.14.208/core.js"}}
    </script>
    <script type="module" src="/client.mjs"></script>
  </body>
</html>
```

Buzz puts the first render in place of the `<!--app-->` comment, and gives each
`NONCE` the value from its Content-Security-Policy header.

`src/counter.clj`:

```clojure
(ns counter
  (:require [buzz.core :refer [client defui local-state server server!]]
            [buzz.handler :as buzz]
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
  (buzz/handler {:index "public/index.html"
                 :watch [clicks]
                 :mounts [{:el "app" :component (fn [_] (counter))}]}))

(defn -main [& _]
  (http/run-server (fn [req] (or (ui req) {:status 404 :body "not found"}))
                   {:port 1350})
  (println "http://localhost:1350")
  @(promise))
```

Then run it:

    clojure -M -m counter

The count comes from the server, so a second browser shows the same number. The
step is browser state, so each browser has its own.

The body of a component is browser code. The four marks below say what is not.

## The four marks

`(server expr)` is a value from the server. The server runs the expression again
after each change and sends the result. Value position only.

`(server! expr)` is work for the server. It goes in an event handler and returns
a promise.

`(client expr)` is a browser value that crosses into a `server!` form. A plain
symbol inside `server!` means the server, so a browser value says so.

`(local-state init)` is an atom that the browser owns. Buzz makes it once, when
the component mounts, and redraws when it changes. The server never sees it.

Each mark has one place. Somewhere else is an error, not a different meaning.

## Answers and errors

The promise from `server!` is empty. To send a value back, put `reply` last:

```clojure
(server! (delete! (client id)) (reply :ok))
```

The promise fails when the handler throws, and the message goes to the console.
To handle it, use `await`:

```clojure
(^:async fn [_]
  (try
    (await (server! (delete! (client id))))
    (catch :default e (reset! error (.-message e)))))
```

A reply is one answer and not a subscription. For a value that stays current,
use a `server` slot.

## Parts

```clojure
(defpart row [item]
  [:li (:title item)])
```

Buzz splices a part into the component that uses it, so a `server!` inside a
part belongs to that component. Parts take browser values. Mark a parameter
`^:server` to pass something that lives on the server.

## Mounting

`buzz/handler` returns a Ring handler. For a request that Buzz does not own, the
handler returns nil. You choose the web server and the other routes:

```clojure
(defn app [req]
  (or (ui req) (my-other-routes req)))
```

Buzz watches each atom in `:watch`. When one changes, each browser with a
changed value gets a patch.

One mount holds one component at one element. A page can have more than one.
Give each mount its own element and its own comment in the HTML.

## What crosses the network

The page loads Reagami and the Squint core from a CDN, and imports the
components from `/components.mjs`. There is no interpreter and no `eval`, so a
strict Content-Security-Policy works.

`curl -N localhost:1341/events` prints the stream:

```js
data: ["session","2fbe6cf7-..."]
data: ["mount","todos","app",[[{"title":"buy milk"}]]]
data: ["patch","todos",[[{"title":"buy bread"}]]]
```

## Development

    bb dev    # the demo, plus an nrepl on 1667

Evaluate a `defui` or a `defpart` again and the open page updates. Browser state
survives the update, and also a reconnect after a restart.

## Limits

A patch carries every value of a component, not a difference. One changed row in
a table of 5000 costs 191 KB and 318 ms. Keep a slot small.

A component cannot render another component. Use a part, or a second mount.
