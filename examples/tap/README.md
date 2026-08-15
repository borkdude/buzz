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
