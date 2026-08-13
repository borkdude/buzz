# buzz

> ⚠️ **WARNING**: This project is highly experimental and the API will surely change. Use only for non-serious projects.

Buzz lets you write a web application using the JVM (or babashka) only. State
lives on the server and can be watched and updated from client code.

This project uses [Squint](https://github.com/squint-cljs/squint) to compile
the UI to JavaScript and [Reagami](https://github.com/borkdude/reagami)
to renders it.

Buzz runs on Babashka and on the JVM. You do not need other tooling like ClojureScript or Node.js.

    bb serve    # the demo, on http://localhost:1341
    bb bench    # a large table, on http://localhost:1342

[tube-pod](https://github.com/borkdude/tube-pod) is a real application that uses
buzz.

## A component

```clojure
(defui todos []
  (let [items (server (vals @db))
        draft (local-state "")]
    [:div
     [:input {:on-input (fn [e] (reset! draft (.. e -target -value)))}]
     [:button {:on-click (fn [_] (server! (add! (client @draft))))} "add"]
     [:ul (for [t items] [:li (:title t)])]]))
```

The body is browser code. The four marks below say what is not.

## The four marks

`(server expr)` is a value from the server. The server runs the expression again
after each change and sends the result. Value position only.

`(server! expr)` is work for the server. It goes in an event handler and returns
a promise.

`(client expr)` is a browser value that crosses into a `server!` form. A plain
symbol inside `server!` means the server, so a browser value says so.

`(local-state init)` is an atom that the browser owns. buzz makes it once, when
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

buzz splices a part into the component that uses it, so a `server!` inside a
part belongs to that component. Parts take browser values. Mark a parameter
`^:server` to pass something that lives on the server.

## Mounting

```clojure
(def ui
  (buzz/handler {:index "public/index.html"
                 :watch [db]
                 :mounts [{:el "app" :component (fn [_] (todos))}]}))

(defn app [req]
  (or (ui req) (files req)))
```

`buzz/handler` returns a Ring handler. For a request that buzz does not own, the
handler returns nil. You choose the web server and the other routes.

buzz watches each atom in `:watch`. When one changes, each browser with a
changed value gets a patch.

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
