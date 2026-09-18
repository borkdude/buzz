# Tap viewer

View any `tap>` value as a live, expandable tree. Every open browser updates,
and large or infinite values stay bounded.

```shell
bb dev
```

Open http://localhost:1370 and press a sample button, or connect to the nREPL
on port 1670:

```clojure
(tap> {:user "alice" :roles #{:admin} :seen (range)})
```

If port 1370 is in use, the viewer chooses an available port and prints it.
If port 1670 is in use, it skips nREPL startup.

Long values split into range buckets like the devtools console. Click a
clipped range to fetch it, for this browser only.

Click `select` on an entry or on any row to put that value in
`@buzz.tap-viewer/selected`. The selected value also appears as a new entry
at the top. Click the copy icon beside it to copy the value as EDN. For
datafied values the clipboard gets the data, and `selected` gets the
original object.

Selected values are highlighted in every viewer.

Enter an expression such as `(count %)` and press Enter to evaluate it.
Use `%` for the selected value, or the newest tap when the selection is nil.
Results appear as new entries unless identical to the newest entry.

Qualify names from other namespaces. Expressions run on the server in
`buzz.tap-viewer`.

## Datafy

Use `clojure.core.protocols/Datafiable` or
`:clojure.core.protocols/datafy` metadata to customize how values appear.
Classes show reflection maps, exceptions show `Throwable->map` data, and
refs show their values.

Click a datafied value to expand its children. The viewer calls `datafy` on
each redraw and `nav` when it renders children. Click an object unchanged
by `datafy` to inspect its bean properties.

Click `table` to view a collection of maps as a table. Exceptions show their
type, message and stack frames. Click `data` to view the exception map.

## In a running REPL

Clojure 1.12 or later:

```clojure
(require '[clojure.repl.deps :refer [add-libs]])
(add-libs '{io.github.borkdude/buzz-tap
            {:git/url "https://github.com/borkdude/buzz"
             :git/sha "<sha>"
             :deps/root "examples/tap-viewer"}})
(require '[buzz.tap-viewer :as viewer])
(viewer/serve! {})
```

The `serve!` function takes an optional map, whose values default `{:port 1370 :host "127.0.0.1"}`.

Babashka:

```clojure
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {io.github.borkdude/buzz-tap
                        {:git/url "https://github.com/borkdude/buzz"
                         :git/sha "<sha>"
                         :deps/root "examples/tap-viewer"}}})
(require '[buzz.tap-viewer :as viewer])
(viewer/serve! {})
```
