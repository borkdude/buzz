# 0007: Sources and topics

Date: 2026-08-19

Status: Layers 0, 1 and 2 are implemented on the `sources-and-topics` branch,
with two sources: `atom-source` in core and a Datalevin source keyed by a
datalog query in `examples/datalevin`, which derives its notifications from
the transaction report. Layer 0 is internal, so the public API is `observe`,
`atom-source` and the `Source` protocol. `:watch` is gone, so a slot reads
server state through a source or not at all. The subscription lifecycle was
reviewed and three leaks and races were fixed: an untracked read left a
subscription nothing would release, `atom-source` read before it subscribed,
and a delayed release could close a subscription taken since. The five contract
rules are on `buzz.source/Source` and tested against two implementations. Still
open: the per topic
counters, indexing `atom-source` by the first key of a path, and per slot
skipping, which is [0002](0002-work-after-the-scheduler.md) section 1.

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

The atom in `:watch` is the reason. It carries three jobs at once:

1. the place a slot reads from
2. the signal that something changed
3. the grain of that signal, which is the whole atom

Job 3 cannot be narrowed while the atom is the abstraction, so every write is a
global one. That was a fair trade for a demo and it does not survive per user
data in a database, thousands of connections and one user acting.

The same three jobs appear elsewhere with a better grain. Rama's `ProxyState` is
read with `deref`, maintains itself from diffs the server pushes, and is scoped
to a path rather than to a whole store. Postgres has `LISTEN` on a channel.
Datalevin has a transaction report. None of them is an atom and all of them are
the same shape once the three jobs are separated.

So the question is not how to make `:watch` cheaper. It is what the smallest
thing is that an atom, a Rama PState, a Datalevin database and a Postgres table
can all be.

## Measured

`bb bench-topics`, babashka, `:render-interval-ms 0` so the write pays for the
render the way 0001 measured it. N connections, one per user, and the timed
operation is one write to user-0's data. Median of 201 samples. The wide key is
`[]`, the whole map, which is what `:watch` used to do. The narrow key is one
user.

Slot is a map lookup:

| connections | wide us/write | narrow us/write | wide slot runs | narrow slot runs |
|---|---|---|---|---|
| 1   |  18.0 | 47.0 |   1 | 1 |
| 10  |  71.5 | 51.0 |  10 | 1 |
| 25  | 140.0 | 54.8 |  25 | 1 |
| 50  | 257.1 | 27.2 |  50 | 1 |
| 100 | 503.8 | 38.4 | 100 | 1 |

Slot does about 67 us of work, standing in for a query:

| connections | wide us/write | narrow us/write | wide slot runs | narrow slot runs |
|---|---|---|---|---|
| 1   |   77.3 | 77.8 |   1 | 1 |
| 10  |  446.6 | 78.1 |  10 | 1 |
| 25  | 1074.7 | 85.8 |  25 | 1 |
| 50  | 2200.4 | 80.5 |  50 | 1 |
| 100 | 4299.4 | 82.3 | 100 | 1 |

The slot runs columns are the mechanism: N against 1, whatever the slot costs.
The clock only makes it visible once a slot costs something, which is why the
first table barely moves and the second is 52 times apart at 100 connections.
An application whose slots query a database is the second table.

Both columns use the same mechanism and differ only in the width of the key.
That is the point: the fan out is a property of what a slot reads, not of a
setting on the handler.

## Decision

Three layers. Each is useful on its own and each is a strict addition to the one
below it.

**Layer 0, topics.** A topic is a value naming what changed. Connections hold
topics, and marking a topic renders the connections holding it and nobody else.
Internal, for the reason under its own heading below.

**Layer 1, sources.** A `Source` turns changes in an external system into marked
topics, with a subscription whose lifetime follows the topic index. This is the
integration seam, and the whole public API together with layer 2.

**Layer 2, `observe`.** A slot's reads through a source become its topics, so
subscriptions are derived rather than declared.

`:watch` is removed. It was a declared dependency at handler granularity, and
so carried the drift it looked like it was protecting against: leave an atom
out of the vector and the page goes quietly stale. Reading the whole atom
through a source says the same thing, per connection rather than per handler,
and says it where the value is read.

## Layer 0: topics

A topic is any EDN value compared with `=`. Nothing about it is tied to an atom,
a var or a namespace. A connection holds a set of them, and marking a topic
renders the connections holding it and nobody else.

**This layer is internal.** An earlier draft made it public, as `invalidate!`
and a `:topics` function on the handler spec, so an application could name its
own topics and mark them by hand. That pair carries the exact defect
[0002](0002-work-after-the-scheduler.md) section 1 rejected: a forgotten mark
leaves a browser on a value that is no longer true, with nothing to notice it
by. `observe` does not, because the declaration and the read are the same
expression.

Keeping both would put two mechanisms in the public API, one safe and one not,
and the unsafe one only covered cases a small source covers better. So the
public API is `observe`, `atom-source` and the `Source` protocol, and nothing
else.

What this gives up is an escape hatch for a change that arrives through no
source at all, a webhook being the example. The answer is to write the source:
whatever the webhook carries has to be readable for a slot to render it, so
there is a source, it just has not been written yet. A source that is told its
new value is about fifteen lines.

Layer 0 alone is the whole win for a single process, and it is what the other
two layers are built out of.

## Layer 1: sources

```clojure
(defprotocol Source
  (-subscribe [source k notify]
    "Calls notify, a function of no arguments, whenever k changes.
     Returns a handle that deref gives the current value of.")
  (-unsubscribe [source k handle]))
```

Two methods. The handle is derefable, which is the point: the subscription and
the read cache are the same object.

Buzz keys the subscription by `[source k]` and uses that pair as the topic, so
the `notify` it hands to a source raises that subscription's version and marks
that topic, and nothing else has to be wired. The lifetime follows the topic
index. A topic gaining its first subscriber opens the subscription, and losing
its last closes it after a grace period.

| source | key | handle |
|---|---|---|
| atom | path into it | cursor, notifies when the value at that path changes |
| Rama PState | Specter path | the `ProxyState`, unchanged |
| Datalevin | query or entity id | cached result, notified from the transaction report |
| Postgres | channel name | cached result, notified on `NOTIFY` |
| Redis | channel name | cached result, notified from pub sub |
| HTTP API | endpoint | cached result, notified from a poller or a webhook |

One handle per `[source k]` per process, shared by every connection holding that
topic. Alice with three tabs has one subscription and one materialised value.
That is a piece of [0002](0002-work-after-the-scheduler.md) section 2 falling
out rather than being built.

After this layer, buzz core knows nothing about atoms. An atom is a source
like any other, and the whole of it is the key `[]`.

## Layer 2: observe

```clojure
(server (observe todos [:user (whoami (request))]))
```

`observe` resolves the handle for `[source k]`, records the pair in the read set
of the running slot, and derefs. Local, no remote call.

After the render, the union of the read sets is the connection's topic set.
Subscriptions are then exact and there is nothing to declare.

**This answers the objection 0002 section 1 raised against declared
dependencies:**

> A watched atom says something changed somewhere, not that this mount cares.
> Rather than have each mount declare what it reads, which can drift from what
> its slots actually do, run the slots and send nothing when the values are the
> same as last time.

The drift is real, and it applies to any topic named apart from the read. It
does not apply to `observe`, because the declaration and the read are the same
expression. A slot cannot subscribe to the wrong thing without also reading the
wrong thing, which is a bug the browser shows rather than hides.

`observe` is therefore the only way an application names a topic, and layer 0
stays behind it.

`read` was the first name and shadows `clojure.core/read`.

## Working example

```clojure
(defonce state (atom {"alice" [] "bob" []}))

(def todos (buzz/atom-source state))

(defui todo-app []
  (let [items (server (observe todos [(whoami (request))]))]
    [:ul
     (for [{:keys [id title]} items]
       [:li {:on-click (fn [_] (server! (toggle! (whoami (request)) (client id))))}
        title])]))
```

The handler writes the atom and says nothing else. The source notices the write,
marks the key, and every tab and device of that user refreshes. Nobody else's
slots run.

## The hazards

**Subscribe before reading.** Read first and subscribe second and a change
landing in between is lost, leaving that connection on a stale value with
nothing to notice it by. This is the classic bug in systems of this shape, and
it appears at two levels.

Inside a source it is ordered away. `-subscribe` puts the subscription in place
before it caches the first value, so no change falls between them.

Between the read set and the index it cannot be ordered away, because the read
set is only known once the slots have run. A change landing between the read
and the index write is marked while nothing holds the topic, so the mark is
dropped where it is made and no later render corrects it. It only bites when no
other connection holds that key, which is exactly the first connection to read
it.

The fix is a version on each subscription, raised before each notification.
`observe` records the version it read at, the version first and the value
second, so a change between the two reports a stale read rather than a current
one. After the index write the versions are compared, and a connection that
read a version that has moved renders again. The follow-up pass patches rather
than mounts, since the first pass already sent the frame the browser starts
from. Two passes are enough in practice, because every change after the first
index write marks this connection through the normal path.

`a-change-during-the-first-render-is-not-lost` in `test/buzz/handler_test.clj`
holds this. Its slot writes the atom it just read, which puts a change inside
exactly that window. Without the version check the browser never receives the
new value.

**Read sets change between renders.** `(if admin? (observe a k) (observe b k))`
subscribes to different things on different renders, so each render diffs the
set against the previous one. Do that with a grace period, or a toggled
condition opens and closes the same Rama proxy on every click.

**The first render has no subscription yet.** The mount render is what
establishes the set, so a slot that reads conditionally is only fully subscribed
after the second render. Normal for reactive systems, worth stating.

**A read that leaves the render thread is not recorded.** `observe` writes into
a dynamic binding, so whether a read counts depends on where it runs. Measured,
not guessed:

| how the slot reads the key | recorded | why |
|---|---|---|
| `(observe src k)` | yes | |
| inside a `future`, awaited | yes | Clojure conveys bindings into `future` |
| inside a lazy sequence | yes | the render realises it, when comparing and encoding |
| on a `Thread` or executor of our own | **no** | it starts from the root bindings |
| `@some-atom`, no source at all | **no** | nothing to record |

The lazy case holds by where the work sits rather than by design. Move the
encoding to another thread and it stops being true, which is what
`a-lost-read-never-reaches-the-browser` in `test/buzz/handler_test.clj` is
there to catch.

The two that are not recorded fail the same way: the value is right at mount
and never changes again. And a page hides it, because a slot that is not
subscribed still runs whenever some other slot wakes the connection, so a
broken value catches up at a rate that depends on what else is happening. The
test asserts that too, since it is the reason this survives in production.

None of the four is a reason not to build this. All of them are a reason to
build layers 1 and 2 separately, with their own tests.

## Implementation sketch

Two maps per handler registry:

```
{topic   #{session}}   ; who to wake, and the refcount sources hang on
{session #{topic}}     ; for O(1) removal on close
```

`dirty` in `coalesced` becomes a set of topics rather than a boolean. A tick
swaps the set out atomically, resolves the topics to sessions, unions them and
renders each session once. Swap rather than clear after rendering, or a topic
arriving mid render is lost. Leading edge immediate and trailing edge on the
interval both stay as they are.

`broadcast-patch!` takes a session selection instead of the whole registry. The
`sent` comparison and the per connection error containment are untouched.

`:on-close` removes the session from both maps, which is also what closes the
last subscription on a topic.

`observe` needs a dynamic read set bound around a connection's slots. Binding it
around the whole session render, rather than around each slot, needs no change
to `defui` or `split-body` at all: `observe` is an ordinary function call inside
a slot expression, so runtime tracking is enough and the topics come out per
connection, which is the grain the index wants.

Rendering stays single threaded per handler. Parallel rendering across topics
needs the per connection serialisation that 0006 item 1 wants first, or it
reintroduces the ordering race 0001 closed.

## Rama as a data layer

Rama and buzz occupy disjoint layers. Rama has a JVM client API and no UI story.
Buzz has no storage, clustering or scaling story. A buzz process is a Rama
client holding the HTTP and SSE connections and no state of its own.

Reads are a source whose handle is the `ProxyState` itself, so the table entry
above is the whole integration.

Writes go to a depot. `foreign-append!` takes an ack level of `nil`,
`:append-ack` or `:ack`, defaulting to `:ack`, which blocks until colocated
stream topologies have processed the record. So when the append returns, the
streaming PStates are current and the point to invalidate is unambiguous.
Trading that for latency means `:append-ack` or `foreign-append-async!`, with
the invalidation moved into the callback.

Several buzz processes each hold their own subscriptions for the connections
they serve, and Rama pushes to each of them. **Rama is the backplane**, so this
topology needs no pub sub of its own.

**Sticky routing is still required.** 0002 section 6 states why: a connection's
server closures cannot migrate, so a stream and its RPCs have to reach the same
node. Sources remove the need for stickiness in change propagation only.

The cost is memory in the web process. Each handle holds a materialised copy of
its key's value in the buzz heap, so the rule is to observe the narrowest key
the slot actually renders rather than the structure around it. Rama paths are
built for that. The same discipline applies to every source in the table, and
the failure when it is ignored is the same.

## Alternatives

**A. Leave `:watch` as the only mechanism.** Correct for a handful of
connections. The cost is invisible until an application has both real slots and
real connection counts, which is when it is hardest to change. It also declares
dependencies, at the coarsest granularity there is, so it never had the safety
its blunt behaviour suggested.

**B. Topics without sources.** The previous draft of this ADR. Works, and
integrating anything means writing the same subscribe, cache and refcount code
per application. The protocol is two methods, so there is little to save by
leaving it out.

**C. Sources without `observe`.** Layers 0 and 1 only, with an application that
names its own topics and marks them by hand. Built first and then withdrawn, for
the reason under layer 0: it carries the drift risk 0002 section 1 named, and it
covered nothing a small source does not cover better.

**D. Track reads without a protocol.** Intercept `deref` or instrument the
storage layer to derive the read set from ordinary code. Convex does the
storage-layer version and Meteor's mergebox is the cautionary one, where per
connection bookkeeping was what stopped it scaling. It also requires the store
to cooperate, which rules out treating a database as opaque. Requiring reads to
go through `observe` is what makes the tracking tractable.

**E. Render once and send the same frame to everyone.** Turbo Streams broadcasts
one rendered fragment to a topic. Fan out becomes almost free and
personalisation becomes impossible. 0002 section 2 keeps personalisation by
sharing only what does not depend on the connection, and composes with this:
connections holding the same topic are exactly the set worth sharing across.

**F. Mailbox per connection instead of a registry loop.** Phoenix LiveView gives
each connection its own process, so ordering per connection, parallelism and
crash isolation come from the structure rather than from discipline in a
scheduler. On the JVM this is a mailbox per connection drained by a bounded
pool, not a thread per connection, since buzz also runs on babashka. A different
axis and not a competing decision. LiveView pairs process per connection with
manual PubSub topics, which is layer 0 on the other axis. Worth its own ADR.

## What this does not change

`server`, `server!`, `client`, `local-state` and `defui` mean what they meant.
The wire protocol is untouched, and so is the shape of `["patch" id vals]`.
`:watch` is the one thing that goes. Every use of it becomes a source read of
the whole atom, which is one line and a narrower fan out.

## Build order

1. Layer 0. Index, marking, topic set in `coalesced`. In process, no protocol.
   This is the whole win for a single node.
2. Layer 1. The `Source` protocol, the refcounted handle registry, and
   `atom-source` as the first implementation.
3. A Rama-backed example. It is the second implementation and the one that
   proves the protocol is not shaped around atoms.
4. Layer 2. `observe` and read set tracking, with the subscribe-before-read
   hazard tested first.
5. Counters per topic: ticks, sessions woken, render duration, subscriptions
   open. 0002 section 7 wanted one place for this and the tick is it.

Redis is a source like any other and does not need its own step.

## References

- `src/buzz/source.clj`, the protocol an integration implements
- `src/buzz/impl/hub.clj`, the topic index, the subscription registry and `observe`
- `render-session!`, `broadcast-patch!`, `coalesced`, `handler` in `src/buzz/impl/page.clj`
- [0001](0001-render-scheduling.md) for the measurements and for option C
- [0002](0002-work-after-the-scheduler.md) sections 1, 2, 6 and 7
- [0004](0004-the-request-is-the-only-ambient-thing.md) for the registry per handler this indexes
- [0006](0006-async-rendering-by-default.md) for the scheduler this extends
- Rama PStates, paths and depot ack levels, for the source this is shaped to fit
- Phoenix PubSub and LiveView, for manual topics paired with a process per connection
