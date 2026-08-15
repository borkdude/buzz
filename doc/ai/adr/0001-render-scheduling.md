# 0001: Rendering is synchronous on the writing thread

Date: 2026-08-15

Status: Open. Proposed, not implemented.

## Context

A watched atom renders every connection immediately, on whichever thread wrote
it. `handler` installs `broadcast-patch!` on each `:watch` atom, and
`watch-session!` installs a per connection watch on each atom in a mount's
`:state`:

```clojure
(defn- broadcast-patch! [_ _ _ _]
  (doseq [{:keys [ch mounted]} (vals @conns)
          m mounted]
    (patch! ch m)))

(defn- patch! [ch {:keys [instance sent]}]
  (let [vals ((:slots instance))]
    (when (not= vals @sent)
      (reset! sent vals)
      (event! ch ["patch" (:id instance) vals]))))
```

So a `(swap! db ...)` inside a `server!` handler runs the slots of every
connection, encodes each result as JSON and writes it to each channel, before
the handler returns and before the browser that asked gets its 204.

## Measured

The auth example, one warm socket, latency of one rpc that writes a watched
atom, against the number of connections open at the time:

```
connections   us/rpc
          1     86.9
          9    447.0
         17   1200.6
         25   2304.8
```

About 92 us per connection, paid by the caller. Twenty five idle browsers make
one person's button press twenty six times slower.

This example's slots are a map lookup, so that 92 us is almost all JSON encoding
and channel writes. A component with real slots adds its own cost on top:
multi-snake recomputes `[(rows g) (me g pid) (scoreboard g)]` in 59 us per
connection, so the same fan out there is roughly 150 us per connection.

## Problems

**Latency scales with connections and is charged to the writer.** The numbers
above. An application thread that writes an atom is billed for every browser
currently looking.

**No coalescing.** Ten writes in a burst are ten full fan outs. Only the last
one can still be on screen, so the first nine are wasted, and each of them was
paid for at the rate above.

**One slow or throwing slot stops everything.** A slot that throws propagates
out of `patch!`, out of the watch, and into the `swap!` that the application
made, which is somewhere that knows nothing about browsers. A slot that is
merely slow blocks every other connection behind it, since the loop is serial.

**`patch!` is not atomic.** It reads `@sent`, then writes it, with the slots run
in between. Two threads writing two different watched atoms both reach `patch!`
for the same connection, and nothing orders the read against the write or one
`http/send!` against the other. A stale patch can land after a fresh one. This
is a correctness bug today, not only a performance one.

## Proposal

Per handler, a render scheduler with coalescing and backpressure:

```
atom changes
  -> mark handler dirty
  -> enqueue once
  -> bounded worker computes the latest values
  -> compare with last sent values
  -> send one patch per connection
```

A write while a render is in flight only marks dirty again. Intermediate states
are skipped rather than queued.

This gives one render per burst, application writes that do not block on
connected browsers, bounded concurrency for slow slots, backpressure when a
client or a computation is slow, isolation between handlers, and one place to
put timing, queue depth and dropped update counters.

It also serialises `patch!` per connection, which removes the race above.

## What this changes

The programming model is untouched. `server`, `server!`, `local-state` and
`defui` mean what they meant, and no application code changes.

The wire protocol is untouched. The same `["patch" id vals]` frames in the same
order per connection.

The observable timing does change, in three ways worth stating plainly.

**Intermediate states are skipped.** Safe here, but only because a slot frame
carries whole values rather than deltas: `vals` is the complete slot vector, so
the newest frame is sufficient on its own and a skipped one leaves nothing out.
A protocol that sent increments could not coalesce this way.

**An rpc response can arrive before the patch it caused.** Today the fan out
finishes inside the handler, so the patch is already written when the 204 goes
out. Afterwards it need not be. `rpc.cljs` already documents this as
unspecified:

> Resolving means the server accepted the call, not that the page shows it. Any
> change to what is rendered arrives separately, as a patch.

So the contract survives, but any application that quietly depended on the
current ordering stops being right.

**The tests become timing dependent.** `handler_test` asserts arrival with
`next-event` and absence with `silent?` on a 300 ms timeout. Both are written
against synchronous rendering. They would need a way to wait for the scheduler
to be idle rather than a sleep, or the suite gets flaky under load.

## Alternatives

**A. Leave it.** Correct for one browser and for a handful. The fan out cost is
invisible until an application has both real slots and real connection counts,
which is exactly when it is hardest to change.

**B. Schedule with coalescing.** The proposal.

**C. Dependency tracking first.** Record which atoms a slot read, and patch only
the connections whose slots read the atom that changed. Attacks a different
axis: it makes each fan out smaller rather than making fan outs rarer or
asynchronous. It does not stop a burst from causing a burst, and it does not get
the writer off the hook for the connections that do care.

**D. Send asynchronously without coalescing.** Hand each `patch!` to an
executor. Gets the writer off the thread, keeps every wasted render, and makes
the ordering problem worse rather than better.

## Recommendation

B, before C. Scheduling bounds the cost of the recomputation that already
happens. Dependency tracking reduces how much of it is needed, which is worth
having, but on top of a scheduler rather than instead of one. Doing C first
leaves the writer thread on the hook and leaves the `sent` race in place.

Both are additive. Neither is a reason to change anything an application wrote.

## Prior art

reagami faced the same question one layer down, in `doc/dev/adr/0003-render-re-entrancy.md`.
Its option D is this proposal in the browser: defer every render to a microtask,
coalesce the ones in a task, and accept that `render` no longer updates the DOM
before returning. It was rejected there on measurement, since stream pushes
arrive in separate tasks and so do not coalesce. That reasoning does not carry
over. Buzz's writes are server side and a burst of `swap!` calls in one handler
lands in one window, which is the case coalescing helps.

Vue, Preact and React all schedule renders rather than running them where state
changed. They own their state, so this is natural for them and a choice for
Buzz.

## References

- `broadcast-patch!`, `patch!`, `watch-session!`, `handler` in `src/buzz/handler.clj`
- `rpc.cljs` on what resolving an rpc promise means, in `resources/buzz/rpc.cljs`
- reagami `doc/dev/adr/0003-render-re-entrancy.md`
