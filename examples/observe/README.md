# observe

Two pages over one atom. Each page reads one key of it, so a write to the other
key renders nothing.

Run it:

    bb dev     # http://localhost:1370/a and http://localhost:1370/b

Open both pages, then click the buttons and watch the terminal.

## What it shows

The whole example is one atom and one source:

```clojure
(defonce state (atom {:a 0 :b 0}))

(def counts (buzz/atom-source state))
```

A page reads one key through the source, and prints when its slot runs:

```clojure
(let [v (buzz/observe counts [k])]
  (prn :slot-ran k :value v)
  v)
```

Reading a key subscribes the connection to it. Nothing else is declared and
nothing is registered by hand.

Three clicks on `b + 1`, made from page a:

```
:slot-ran :b :value 1
:slot-ran :b :value 2
:slot-ran :b :value 3
```

The atom changed three times and page a never ran. Its key did not change, so
its connection was never woken. Page b went to 3 and page a stayed where it
was.

Swap `buzz/observe` for `(get @state k)` and add `:watch [state]` to both
handlers, and every line above appears twice.

## The grain

A topic decides which connection renders, not which slot. A connection with two
slots runs both of them whenever any key it reads changes. The saving is
between connections, which is why this example uses two pages.
