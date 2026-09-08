# datalevin

A [Datalevin](https://github.com/juji-io/datalevin) browser over a MusicBrainz
sample: a query editor with canned queries, results as a table, and a query log
shared live between every viewer.

Run it:

    clojure -M:run                      # http://localhost:1395

Run this example on the JVM.

## Observe database queries

Use a Datalog query as the key for the source in
[src/buzz/dlv/source.clj](src/buzz/dlv/source.clj):

```clojure
(server (observe db log-q))
```

The source re-runs a subscribed query when a transaction changes an
attribute it reads. It updates connected pages when the query result changes.
Only transactions through the supplied Datalevin connection are observed.

Running a query adds a database entry to the shared query log. The query
re-run counts above the log show the log query updating while the artist,
album and track queries remain unchanged.

The first start seeds `db/` from `resources/seed.edn`: 8 artists, their studio
albums, and the tracks of each artist's first album, fetched once from the
MusicBrainz API. Delete `db/` to reseed.

Click a canned query to put it in the editor, edit it, run it. Every run
lands in the shared log, so open two browsers and steal each other's queries.

The query field evaluates datalog against the database, including calls to
fully qualified functions, so treat this like a database console: run it
locally, do not expose it to the internet.
