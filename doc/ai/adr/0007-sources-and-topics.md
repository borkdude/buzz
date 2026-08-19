# 0007: Sources and topics

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

## Decision

Three layers. Each is useful on its own and each is a strict addition to the one
below it.

**Layer 0, topics.** A topic is a value naming what changed. Connections hold
topics, `invalidate!` marks them, and only the connections holding a marked
topic render.

**Layer 1, sources.** A `Source` turns changes in an external system into
`invalidate!` calls, with a subscription whose lifetime follows the topic index.
This is the integration seam.

**Layer 2, `observe`.** A slot's reads through a source become its topics, so
subscriptions are derived rather than declared.

`:watch` stays, as one small built in source.

## Layer 0: topics

A topic is any EDN value compared with `=`. Nothing about it is tied to an atom,
a var or a namespace.

```clojure
(buzz/invalidate! [:todos "alice"])
(buzz/invalidate! [:todos "alice"] [:team 3])
```

A plain function, callable from an RPC handler, a background job, a scheduled
task or a webhook. Invalidating a topic nobody holds is a no-op.

`:topics` on the handler spec declares what a connection holds. It is a function
of the request, run when the stream opens and again after each render of that
connection.

```clojure
(buzz/handler
 {:topics (fn [req] [[:todos (whoami req)] :announcements])
  :mounts [{:el "app" :ui #'todo-app}]})
```

`::buzz/all` reaches every connection of the handler. Every connection also
holds its own session id, so `(buzz/invalidate! (buzz/connection req))` renders
exactly one connection.

Topics come from the request. Never accept one from the browser. A client chosen
topic reveals names and is a wake up vector, even though the slots still run
against the caller's own identity.

Layer 0 alone is the whole win for a single process, and it is what the other
two layers are built out of.

## Layer 1: sources

```clojure
(defprotocol Source
  (-subscribe [source k notify]
    "Calls notify, a function of no arguments, whenever k changes.
     Returns a handle supporting deref.")
  (-unsubscribe [source handle]))
```

Two methods. The handle is derefable, which is the point: the subscription and
the read cache are the same object.

Buzz keys the subscription by `[source k]` and uses that pair as the topic, so
`notify` is `#(invalidate! [source-id k])` and nothing else has to be wired. The
lifetime follows the topic index. A topic gaining its first subscriber opens the
subscription, and losing its last closes it.

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

After this layer, buzz core knows nothing about atoms. `:watch` is a source
whose key space has one member.

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

The drift is real, and it applies to `:topics`. It does not apply to `observe`,
because the declaration and the read are the same expression. A slot cannot
subscribe to the wrong thing without also reading the wrong thing, which is a
bug the browser shows rather than hides.

So `:topics` remains the escape hatch for changes that arrive through no source
at all, and `observe` is the normal path.

Naming is open. `read` shadows `clojure.core/read`.

## Working example

```clojure
(def todos (buzz/atom-source state))

(defui todo-app []
  (let [me    (server (whoami (request)))
        items (server (observe todos [:todos (whoami (request))]))]
    [:ul
     (for [{:keys [id title done]} items]
       [:li {:on-click (fn [_] (server! (do (toggle! (whoami (request)) (client id))
                                            (invalidate! [:todos (whoami (request))]))))}
        title])]))
```

Every tab and device of that user refreshes. Nobody else's slots run.

With a source that pushes its own changes, the `invalidate!` in the handler goes
away and the source does it.

## The three hazards

**Subscribe before reading.** Read first and subscribe second and a change
landing in between is lost, leaving that connection on a stale value with
nothing to notice it by. Subscribe first and read after, or read a version along
with the value and re-check it once subscribed. This is the classic bug in
systems of this shape and it is the one to write a test for first.

**Read sets change between renders.** `(if admin? (observe a k) (observe b k))`
subscribes to different things on different renders, so each render diffs the
set against the previous one. Do that with a grace period, or a toggled
condition opens and closes the same Rama proxy on every click.

**The first render has no subscription yet.** The mount render is what
establishes the set, so a slot that reads conditionally is only fully subscribed
after the second render. Normal for reactive systems, worth stating.

None of the three is a reason not to build this. All three are a reason to build
layers 1 and 2 separately, with their own tests.

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

`observe` needs a dynamic read set bound around each slot evaluation.
`split-body` already walks the slot expressions, so this is a binding at the
call site rather than analysis.

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
real connection counts, which is when it is hardest to change.

**B. Topics without sources.** The previous draft of this ADR. Works, and
integrating anything means writing the same subscribe, cache and refcount code
per application. The protocol is two methods, so there is little to save by
leaving it out.

**C. Sources without `observe`.** Layers 0 and 1 only. Every connection declares
`:topics` by hand and carries the drift risk 0002 section 1 named. This is a
real intermediate state rather than an alternative, since layer 2 is additive.

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
Applications using `:watch` and nothing else behave exactly as they do today.

## Build order

1. Layer 0. Index, `invalidate!`, topic set in `coalesced`, `:topics` on the
   handler spec, `:watch` as sugar for `::buzz/all`. In process, no protocol.
   This is the whole win for a single node.
2. Layer 1. The `Source` protocol, the refcounted handle registry, and
   `atom-source` as the first implementation, which reduces `:watch` to a
   special case of it.
3. A Rama-backed example. It is the second implementation and the one that
   proves the protocol is not shaped around atoms.
4. Layer 2. `observe` and read set tracking, with the subscribe-before-read
   hazard tested first.
5. Counters per topic: ticks, sessions woken, render duration, subscriptions
   open. 0002 section 7 wanted one place for this and the tick is it.

Redis is a source like any other and does not need its own step.

## References

- `broadcast-patch!`, `coalesced`, `open-stream`, `handler` in `src/buzz/impl/page.clj`
- `split-body` in `src/buzz/core.clj`, where the read set binding goes
- [0001](0001-render-scheduling.md) for the measurements and for option C
- [0002](0002-work-after-the-scheduler.md) sections 1, 2, 6 and 7
- [0004](0004-the-request-is-the-only-ambient-thing.md) for the registry per handler this indexes
- [0006](0006-async-rendering-by-default.md) for the scheduler this extends
- Rama PStates, paths and depot ack levels, for the source this is shaped to fit
- Phoenix PubSub and LiveView, for manual topics paired with a process per connection
