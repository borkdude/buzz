# 0006: The road from :render-interval-ms to async rendering by default

Date: 2026-08-18

Status: Accepted as a plan. The opt-in is on the `render-coalescing` branch;
the items below gate the default flip.

## Context

[0001](0001-render-scheduling.md) records the problem: a watched write renders
every slot for every connection synchronously, on the writing thread.
`:render-interval-ms` implements the proposed fix as an opt-in: writes
coalesce into at most one render per interval, leading edge immediate,
trailing edge for writes landing inside the window, rendering on a scheduler
thread. Idle costs nothing.

Measured on the whiteboard example with 20 open connections:

| load                     | off      | 15ms    |
|--------------------------|----------|---------|
| burst, 1000 writes: writer wall | 2048ms | 4.2ms |
| burst: patches sent      | 20000    | 38      |
| burst: SSE bytes         | 77MiB    | 191KiB  |
| paced 60Hz, 5s: writer wall | 6.8s (falls behind) | 5.6s (keeps up) |

The paced row is the deployed failure reproduced: on the VPS one drawing user
cost about 70% of a core with the heap pegged against a 256M container limit,
because the writer paid every connection's render per pointer move.

Prior art agrees on the direction. hyperlith batches writes and renders on a
50ms tick and never renders on the write path. Colyseus patches state at a
50ms default. jolt's datastar middleware rate-limits its SSE re-renders at
15ms. No comparable system renders on the writing thread by default.

The contract change: with an interval set, patches are sampled state, not
every state. A counter can step 3 to 7. Sync code can no longer assume that
patches were written when a `swap!` returns.

## Decision

Async rendering becomes the default, interval 20ms, once the items below are
done. 20ms sits under the display rate floor (~16ms is the most a browser can
show) where 50ms gives cursor-style pages a visibly stuttering 20fps.
`:render-interval-ms 0` stays as the synchronous escape hatch. The flip is a
breaking release and ships last, smallest and alone.

## What gates the flip

1. **Serialize frame producers per connection.** Three writers can touch a
   connection's stream and `sent` state: the render scheduler, `reload-all!`
   (eval thread) and `open-stream` (adapter thread). A scheduled render can
   beat the mount frame of a connection that just opened. A connection must
   only become visible to broadcasts after its mount frames are written, and
   reloads must route through the same serial lane the renders use. This is
   the item that has to be airtight; the rest is trim.
2. **Per-connection error isolation.** One throwing slot currently aborts the
   whole broadcast doseq, so later connections silently miss that render.
   One bad render should cost one connection one frame.
3. **`:render-error-fn`.** Async demotes slot exceptions from surfacing at the
   `swap!` site to a println on a background thread. A pluggable hook, with
   the print as default.
4. **Flush affordance.** `flush-renders!` (submit a barrier task, wait for it
   and a clear dirty flag) so tests and REPL sessions can assert
   deterministically against the real async config. Documented next to
   `0` = synchronous.
5. **Executor per handler.** One shared scheduler thread lets a slow slot in
   one handler delay another. Handlers are few, threads are cheap, and
   per-handler serialization is what item 1 wants anyway. A pool like
   hyperlith's is not warranted yet.
6. **Adapter contract.** `buzz.stream/send!` may be called from several
   threads and must be frame-atomic. http-kit is; capra needs a look. The
   heartbeat already relied on this implicitly, async makes it load-bearing.
7. **Dogfood.** Whiteboard (including the VPS deploy), multi-snake and
   tube-pod at 20ms, for calendar time. Missing affordances should surface
   here, not after the flip.
8. **Paperwork at the flip.** Default 20, `0` for sync, README paragraph on
   the rendering model ("patches are sampled state"), breaking CHANGELOG
   line, and 0001 marked resolved.

## Left out on purpose

Frame dropping to slow readers (backpressure) is on the
`render-coalescing-poc` branch and waits for http-kit to expose its write
queue depth; the reflection it uses now is too brittle to ship and does not
exist on babashka. Patch size is untouched by all of this: a changed slot
resends whole, which is [0002](0002-work-after-the-scheduler.md)'s territory.

## References

- [0001](0001-render-scheduling.md), the problem and the proposal
- [0005](0005-rpc-batching-instead-of-websockets.md), the client-side half
- branch `render-coalescing` (the opt-in), branch `render-coalescing-poc`
  (adds the backpressure experiment)
- [hyperlith](https://github.com/andersmurphy/hyperlith), batch tick and
  frame dropping via manifold
- [Colyseus patchRate](https://docs.colyseus.io/state)
