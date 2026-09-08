# 0008: A render lane per connection

Date: 2026-08-20

Status: Implemented on the `sources-and-topics` branch.

## Context

[0006](0006-async-rendering-by-default.md) put one scheduler thread in front of
all handlers. Three writers could still touch a connection's stream: the
scheduler, `reload-all!` on the eval thread, and `open-stream` on the adapter
thread. Item 1 of that ADR asked for the frame producers to be serialized per
connection and called it the item that has to be airtight.

[0007](0007-sources-and-topics.md) then had to defend a window the shared
scheduler leaves open: a key can change between the moment a slot reads it and
the moment the connection is written into the topic index. The defence grew
into a version per subscription, a staleness check, and a bounded loop of
follow-up render passes, and a security review spent three of its six findings
on that machinery.

The register-during-the-read alternative closes the window by construction:
`observe` puts the topic in the index before it dereferences the handle, so any
write the deref does not see must postdate the index entry and its mark reaches
the session. It was rejected in review because at `:render-interval-ms 0` a
mark renders synchronously, so a write landing mid-render would re-enter the
running render on the writing thread.

Hyper runs a virtual thread per tab and parks it on a semaphore, which is
alternative F of 0007 live on the JVM. What kept buzz off that design was
babashka. Measured now rather than assumed: babashka supports virtual threads,
including parking on a `Semaphore` and ten thousand of them at once.

## Decision

Every connection gets a lane: a dirty set, a job queue, a semaphore, and a
virtual thread that parks on the semaphore, drains both, renders, sleeps the
coalescing interval, and parks again. Every frame of a connection is written by
its lane: the session frame, the mounts, patches, and reloads. `mark!` resolves
topics to sessions and releases each lane's semaphore, so a write stays as
cheap as it was.

With re-entrancy gone, `observe` registers the topic in the session's index
before dereferencing the handle. The versions, the staleness check and the
follow-up passes are deleted. The correctness argument is one sentence leaning
on contract rule 2: `notify` follows the store, so a value the deref did not
see implies a mark that arrives after the index entry, and the lane renders
again.

On the JVM this sets a floor of JDK 21. Babashka needs nothing.

## Semantics kept, stated precisely

`:render-interval-ms` keeps its meaning. The first mark renders immediately,
marks landing inside the interval collapse, the last state always goes out.
The interval is now a sleep between a lane's renders rather than a scheduled
follow-up.

`:render-interval-ms 0` keeps its meaning through a handshake: a writer that is
not a lane thread blocks until every lane it marked has rendered, so when a
`swap!` returns the patches are written, which is what the tests rely on. A
mark made from a lane thread never blocks, which is what makes a slot that
writes state safe: its own lane picks the mark up on the next iteration, and a
mark for another connection's lane is fire and forget rather than a deadlock.

Renders for different connections now run in parallel. Frames for one
connection are totally ordered by its lane. The heartbeat still writes from its
own thread, so the adapter contract on concurrent `send!` stands.

## What this deletes

- the shared render scheduler path: `coalesced`, `broadcast-patch!`
- the version on every subscription, `stale?`, `max-passes`, the follow-up loop
- the mount settle in the fan-out tests, whose cause was the follow-up pass

The scheduler thread itself stays for the subscription release grace period.

## What this does not change

The programming model, the wire protocol, the `Source` contract and the
subscription lifecycle from the review are untouched. A failed render still
keeps the topics a session holds rather than reconciling against a partial
read set, with one refinement: topics registered during the failed pass stand,
which errs toward extra renders rather than missed ones.

## Measured

`bb bench-topics` after the change, same method as 0007: N connections, one
write to user-0's data, median of 201 samples, `:render-interval-ms 0` so the
write waits for every render it caused.

Slot does about 60 us of work, standing in for a query:

| connections | wide us/write | narrow us/write | wide slot runs | narrow slot runs |
|---|---|---|---|---|
| 1   |  408.7 | 415.2 |   1 | 1 |
| 10  |  721.9 | 434.4 |  10 | 1 |
| 25  | 1453.1 | 430.5 |  25 | 1 |
| 50  | 2411.8 | 420.3 |  50 | 1 |
| 100 | 5317.4 | 418.2 | 100 | 1 |

The slot runs columns are unchanged from 0007: N against 1. The wall clock now
measures something different, and the comparison with 0007's tables has to say
so. Synchronous mode used to render inline on the writing thread, and now it
is a handshake: park a lane, render there, wake the writer. That round trip is
a few hundred microseconds under babashka and it is the price of synchronous
semantics only. At the default interval the writer pays a semaphore release
per affected connection and never waits. The wide column grows slower than
0007's because a hundred lanes render in parallel where the scheduler thread
rendered them one after another.

## References

- [0006](0006-async-rendering-by-default.md) items 1 and 4, which this closes
- [0007](0007-sources-and-topics.md), the hazards section this simplifies
- the review exchange, fifth round, where the simplification was recorded as
  blocked on exactly this
- hyper's per-tab loop, `~/dev/hyper/src/hyper/server.clj`
