# datalevin

A [Datalevin](https://github.com/juji-io/datalevin) browser over a MusicBrainz
sample: a query editor with canned queries, results as a table, and a query log
shared live between every viewer.

Run it:

    clojure -M:run                      # http://localhost:1395

JVM only. The page updates from `datalevin.core/listen!`, and the babashka pod
exports that var but cannot take a callback across the pod boundary.

## A source over the database

`src/buzz/dlv/source.clj` implements `buzz.source/Source` over a Datalevin
connection, keyed by a datalog query. Subscribing runs the query and keeps the
result. One listener on the connection turns each transaction into
notifications: the attributes the transaction wrote are intersected with the
attributes each subscribed query reads, and only the overlapping queries run
again.

The page reads through it, and holds no atom of its own:

```clojure
(server (observe db log-q))
```

The query log is in the database rather than in an atom, so running a query is
a transaction on `:query/*`. The three count queries read `:artist/name`,
`:release/title` and `:track/title`, which no run ever writes. The "re-run"
line above the log shows it: the log count climbs and the other three stay at
zero.

The first start seeds `db/` from `resources/seed.edn`: 8 artists, their studio
albums, and the tracks of each artist's first album, fetched once from the
MusicBrainz API. Delete `db/` to reseed.

Click a canned query to put it in the editor, edit it, run it. Every run
lands in the shared log, so open two browsers and steal each other's queries.

The query field evaluates datalog against the database, including calls to
fully qualified functions, so treat this like a database console: run it
locally, do not expose it to the internet.
