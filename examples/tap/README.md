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

Long values split into range buckets like the devtools console. Click a
clipped range to fetch it, for this browser only.

Click `select` on an entry or on any row to copy that value as EDN and put
it in `@taps/selected`. The value also comes back as a fresh entry on top.
