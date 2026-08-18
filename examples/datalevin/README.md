# datalevin

A [Datalevin](https://github.com/juji-io/datalevin) browser over a MusicBrainz
sample: a query editor with canned queries, results as a table, and a query
log shared live between every viewer. Datalevin runs as a pod on babashka and
as a library on the JVM; both use the same database directory.

Run it:

    bb dev                              # http://localhost:1395

or on the JVM:

    clojure -M:run

The first start seeds `db/` from `resources/seed.edn`: 8 artists, their studio
albums, and the tracks of each artist's first album, fetched once from the
MusicBrainz API. Delete `db/` to reseed.

Click a canned query to put it in the editor, edit it, run it. Every run
lands in the shared log, so open two browsers and steal each other's queries.

The query field evaluates datalog against the database, including calls to
fully qualified functions, so treat this like a database console: run it
locally, do not expose it to the internet.
