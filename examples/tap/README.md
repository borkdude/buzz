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

Click `copy` to copy the EDN and put the original value in `@taps/copied`.

The tree is [@alenaksu/json-viewer](https://github.com/alenaksu/json-viewer),
loaded from esm.sh. It takes the value as a JSON string in one attribute, so a
set arrives as an array. Keywords keep their colon.
