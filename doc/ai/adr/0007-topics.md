# 0007: Topics

Date: 2026-08-19

Status: Open. Proposed, not implemented.

## Context

A watched write renders every connection of the handler. [0001](0001-render-scheduling.md)
bounds when that fan out happens and [0006](0006-async-rendering-by-default.md)
made the bound the default, but the fan out is still the whole registry:

```clojure
(defn- broadcast-patch! [registry]
  (fn [_ _ _ _]
    (doseq [[session {:keys [ch mounted]}] @registry, m mounted]
      (patch! ch m))))
```

The `sent` comparison suppresses the write, not the work. In `examples/auth`,
alice adding a note runs bob's slots. 0001 measured about 92 us per connection
where the slot was a map lookup, so an application whose slots query a database
pays far more than that, per connection, on every write.

That shape does not survive contact with the case buzz is now being asked to
serve: per user data in a database, thousands of connections, one user acting.
Every other signed in session pays for it.

Two further things break in that case. A database has no atom to watch, so
`:watch` needs a stand in that the application writes by hand anyway. And the
signal an atom carries is "something changed somewhere", which is the least
information a fan out could act on.

## Decision

Add topics: a value that names what changed, a per connection subscription, and
an index from one to the other. A write invalidates a topic, and only the
connections holding that topic render.

A topic is any EDN value compared with `=`. Nothing about it is tied to an atom,
a var or a namespace. `:watch` stays, as sugar.

## The API

### Declaring what a connection cares about

`:topics` on the handler spec is a function of the request. It runs when the
stream opens and again after each render of that connection.

```clojure
(buzz/handler
 {:topics (fn [req] [[:user (whoami req)] :announcements])
  :mounts [{:el "app" :ui #'todo-app}]})
```

Dynamic subscription needs no separate call. Connection scoped state already
lives in application atoms keyed by `(buzz/connection req)`, which the function
can read:

```clojure
(defn- topics [req]
  (into [[:user (whoami req)]]
        (map (fn [id] [:doc id]) (open-docs req))))
```

Opening a document is a `server!` that writes that state and invalidates the
connection. The connection renders, the function runs again, and the new topic
is live.

Topics come from the request. Never accept one from the browser. A client chosen
topic reveals names and is a wake up vector, even though the slots still run
against the caller's own identity.

### Invalidating

```clojure
(buzz/invalidate! [:user "alice"])
(buzz/invalidate! [:user "alice"] [:team 3])
```

A plain function, callable from an RPC handler, a background job, a scheduled
task, a database transaction listener or a webhook. Invalidating a topic nobody
holds is a no-op.

The todo case in full:

```clojure
(server! (do (toggle! (whoami (request)) (client @id))
             (invalidate! [:user (whoami (request))])))
```

Every tab and device of that user refreshes. Nobody else's slots run.

### Built in topics

`::buzz/all` reaches every connection of the handler.

Every connection also holds its own session id as a topic, so
`(buzz/invalidate! (buzz/connection req))` renders exactly one connection.

### :watch keeps working

A watched atom invalidates `::buzz/all`. Existing applications are unchanged and
the two compose, so a handler can move one page to topics at a time.

### Cross process

`:bus` on the handler spec takes a `buzz.bus/Bus` with `publish!` and
`subscribe!`. The default is in process. Redis and Postgres implementations live
outside core, the way `:adapter` and `buzz.stream` already do.

```clojure
(buzz/handler {:bus (redis-bus conn) ...})
```

`invalidate!` publishes once per distinct bus across handlers. An arriving
message marks the topic dirty in every handler of the receiving process.

## What this costs

[0002](0002-work-after-the-scheduler.md) section 1 rejected declared
dependencies and stated the reason:

> A watched atom says something changed somewhere, not that this mount cares.
> Rather than have each mount declare what it reads, which can drift from what
> its slots actually do, run the slots and send nothing when the values are the
> same as last time.

This ADR accepts that risk, at the connection level rather than the slot level.
A forgotten `invalidate!` leaves a browser showing a value that is no longer
true, with nothing to notice it by. Running everything cannot fail that way.

Three things make the trade different from the one 0002 declined:

The declaration is small and it is in one place. A `:topics` function names a
handful of values. 0002 section 1 asked each slot to declare what it reads,
which is many more declarations and many more chances to drift.

The failure has a blunt fix. `::buzz/all` restores today's behaviour for one
write, so an application that is unsure can invalidate broadly and narrow later.

The price of not doing it is now measured rather than suspected. 0001 has the
numbers and 0006 has the deployment that fell over.

The mitigation 0002 proposed applies unchanged: a development mode that renders
every connection anyway and reports the ones whose values changed without their
topics firing. That turns silent staleness into a warning, and it is the item to
build alongside this rather than after it.

## Implementation sketch

Two maps per handler registry:

```
{topic   #{session}}   ; who to wake
{session #{topic}}     ; for O(1) removal on close
```

`dirty` in `coalesced` becomes a set of topics rather than a boolean. A tick
swaps the set out atomically, resolves the topics to sessions, unions them and
renders each session once. Swap rather than clear after rendering, or a topic
arriving mid render is lost. Leading edge immediate and trailing edge on the
interval both stay as they are.

`broadcast-patch!` takes a session selection instead of the whole registry. The
`sent` comparison and the per connection error containment are untouched.

`:on-close` removes the session from both maps. With a bus, the last local
subscriber leaving a topic unsubscribes from it if the implementation subscribes
per topic.

Rendering stays single threaded per handler. Parallel rendering across topics
needs the per connection serialisation that 0006 item 1 wants first, or it
reintroduces the ordering race 0001 closed.

## Cross process detail

Invalidation is a hint and carries no data, so nothing on the bus needs
authorising and the payload stays small. Slots run in the process that holds the
connection.

Start with one channel and a local set lookup per message. Splitting into a
channel per topic trades wake up noise for subscribe churn on every connect and
disconnect, and a process with ten thousand connections is still only doing a
set lookup per message.

Redis pub/sub is fire and forget, so a message lost during a reconnect leaves a
connection that never refreshes. Invalidate `::buzz/all` when the bus
reconnects. That is enough because a patch carries whole slot values rather than
a delta, so one render restores any connection to correct. Redis Streams would
give at least once delivery and replay from a last id, which that recovery makes
unnecessary.

Postgres `LISTEN` and `NOTIFY` is one moving part fewer where Postgres is
already present, and `NOTIFY` fires on commit, which is the consistency point
this wants.

Publish after commit. A reader on a replica can still see a pre commit snapshot,
which needs a transaction watermark in the payload for the slot to wait on, or
reads from the primary. Leave room in the payload and do not build it yet.

**This does not remove the need for sticky routing.** 0002 section 6 states why:
a connection's server closures cannot migrate, so a stream and its RPCs still
have to reach the same node. Topics remove the need for stickiness in
invalidation only.

## Alternatives

**A. Derive topics from the read set.** Record which database reads each
connection's slots performed and invalidate the connections whose read set
intersects a write. Stale UI becomes impossible. Convex does this and Meteor's
mergebox is the cautionary version, where the per connection bookkeeping was
what stopped it scaling. It also requires the storage layer to cooperate, which
rules out treating the database as opaque.

**B. Derive topics from the write.** A Datalevin transaction report names the
entities and attributes touched, which maps to topics without the application
saying anything. Attractive and additive: it is a producer of topic values, so
it sits on top of this rather than instead of it. Build it after, against a real
schema.

**C. Mailbox per connection instead of a registry loop.** Phoenix LiveView gives
each connection its own process. A broadcast is a mailbox send per subscriber
and the runtime schedules them across cores, so ordering per connection,
parallelism and crash isolation come from the structure rather than from
discipline in a scheduler. On the JVM this is a mailbox per connection drained
by a bounded pool, not a thread per connection, since buzz also runs on
babashka. Coalescing then has to move into the mailbox as a dirty set, or 0006's
win is given back.

This is a different axis and not a competing decision. LiveView pairs process
per connection with manual PubSub topics, which is this ADR's model on the other
axis. Worth its own ADR after this one.

**D. Render once and send the same frame to everyone.** Turbo Streams broadcasts
one rendered fragment to a topic. Fan out becomes almost free and personalisation
becomes impossible. 0002 section 2 has the version of this that keeps
personalisation, by sharing only the slots that do not depend on the connection,
and it composes with topics: connections on the same topic are exactly the set
worth sharing a computation across.

**E. Leave it.** Correct for a handful of connections. The cost is invisible
until an application has both real slots and real connection counts, which is
when it is hardest to change.

## What this does not change

`server`, `server!`, `client`, `local-state` and `defui` mean what they meant.
The wire protocol is untouched, and so is the shape of `["patch" id vals]`.
Applications that use `:watch` and no topics behave exactly as they do today.

## Build order

1. Index, `invalidate!`, topic set in `coalesced`, `:topics` on the handler
   spec, `:watch` as sugar for `::buzz/all`. In process. This is the whole win
   for a single node.
2. The development mode that catches a missing `invalidate!`.
3. Counters per topic: ticks, sessions woken, render duration. 0002 section 7
   wanted one place for this and the tick is it.
4. `buzz.bus` protocol, with the Redis implementation outside core.

## References

- `broadcast-patch!`, `coalesced`, `open-stream`, `handler` in `src/buzz/impl/page.clj`
- [0001](0001-render-scheduling.md) for the measurements and for option C
- [0002](0002-work-after-the-scheduler.md) sections 1, 2, 6 and 7
- [0004](0004-the-request-is-the-only-ambient-thing.md) for the registry per handler this indexes
- [0006](0006-async-rendering-by-default.md) for the scheduler this extends
- Phoenix PubSub and LiveView, for manual topics paired with a process per connection
- Convex, for invalidation derived from the read set
