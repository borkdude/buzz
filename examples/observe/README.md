# observe

Two counter pages observe separate keys in one atom. Changing a counter
updates only the page that observes it.

Run it:

    bb dev     # http://localhost:1370/a and http://localhost:1370/b

Open both pages, then click the buttons and watch the terminal.

## What it shows

Create a source for the shared atom:

```clojure
(defonce state (atom {:a 0 :b 0}))

(def counts (buzz/atom-source state))
```

Read the page's key with `observe` and print each render:

```clojure
(let [v (buzz/observe counts [k])]
  (prn :slot-ran k :value v)
  v)
```

Click `b + 1` on page a three times. Page b updates and the terminal prints:

```clojure
:slot-ran :b :value 1
:slot-ran :b :value 2
:slot-ran :b :value 3
```

Page a keeps its current value because `:a` did not change.

Use `[]` as the observed path to read the whole atom. Changes to either
counter then re-render both pages.

## Rendering

Each affected connection runs all its server expressions again, including
expressions that read unchanged values.
