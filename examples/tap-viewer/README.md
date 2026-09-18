# Tap viewer

Inspect values sent with `tap>` in your browser. Expand collections, select
values, and evaluate Clojure expressions on them. New values appear in every
open viewer.

## Start the viewer

Run from `examples/tap-viewer`:

```shell
bb dev
```

Open http://localhost:1370 and click a sample button to try the viewer.
To send your own values, connect your editor to nREPL on port 1670 and run:

```clojure
(tap> {:user "alice" :roles #{:admin} :seen (range)})
```

If port 1370 is in use, the viewer chooses an available port and prints it.
If port 1670 is in use, it skips nREPL startup.

## Inspect values

Expand an entry to browse its contents. Long collections appear in smaller
ranges. Click a range to see more values. You can also browse infinite
sequences a portion at a time. Expanding a value affects only your browser.

Click `table` to view a collection of maps as a table. Exceptions show their
type, message and stack frames. Click `data` to view the exception as a map.

## Select and copy

Click `select` on an entry or row to use that value in expressions. Read it
from your REPL with `@buzz.tap-viewer/selected`. The selected value also
appears as a new entry at the top and is highlighted in every viewer.

Click the copy icon to copy a value as EDN. For objects displayed as data,
this copies the data. Selecting an object keeps the original object.

## Evaluate expressions

Enter an expression such as `(count %)` and press Enter to evaluate it.
Use `%` for the selected value. If the selection is `nil`, `%` refers to the
newest entry. Results appear at the top. Returning the same object as the
newest entry does not add a duplicate.

Expressions run in the Clojure process that hosts the viewer, in the
`buzz.tap-viewer` namespace. Use fully qualified names for functions from
other namespaces.

## Customize object display

Expand objects to inspect them as data. Classes show reflection information,
exceptions show `Throwable->map` data, and refs show their current values.
Other objects expose their bean properties when expanded.

Implement `clojure.core.protocols/Datafiable` for your types, or add
`:clojure.core.protocols/datafy` metadata to a value, to customize its display.

## Use an existing REPL

Replace `<sha>` with a Buzz commit SHA in the examples below.

For Clojure 1.12 or later, run:

```clojure
(require '[clojure.repl.deps :refer [add-libs]])
(add-libs '{io.github.borkdude/buzz-tap
            {:git/url "https://github.com/borkdude/buzz"
             :git/sha "<sha>"
             :deps/root "examples/tap-viewer"}})
(require '[buzz.tap-viewer :as viewer])
(viewer/serve! {})
```

Pass `:port` and `:host` to `serve!` to change the address. The defaults are
`{:port 1370 :host "127.0.0.1"}`. Open the URL printed in your REPL.

For Babashka, run:

```clojure
(require '[babashka.deps :as deps])
(deps/add-deps '{:deps {io.github.borkdude/buzz-tap
                        {:git/url "https://github.com/borkdude/buzz"
                         :git/sha "<sha>"
                         :deps/root "examples/tap-viewer"}}})
(require '[buzz.tap-viewer :as viewer])
(viewer/serve! {})
```
