# 0005: Batch rpc calls instead of adding websockets

Date: 2026-08-18

Status: Open. Idea only, waiting for a repro that shows the need.

## Context

A user building games on Buzz asked for a websocket duplex channel, with an
inbound hook like `:on-message (fn [ch msg])` on the stream protocol.

Buzz is already duplex. Every `server!` compiles to `fetch POST /rpc`
(`resources/buzz/rpc.cljs`), and patches come back over the SSE stream. A
websocket would not add a capability, only change the per message cost:

- Every `server!` is a full HTTP request: headers and cookies each time,
  against a few bytes per websocket frame. Measurable at 30-60Hz input per
  player, rarely fatal.
- http-kit speaks HTTP/1.1. Browsers cap around 6 connections per host, the
  SSE stream holds one open, and rapid POSTs contend for the rest. An
  HTTP/2 terminating proxy in front makes this mostly vanish.
- Separate POSTs may arrive out of order. Websocket frames are ordered.
  Matters when a game sends deltas rather than absolute state.
- Latency is not an argument: warm connections, same RTT both ways.

Websockets cost auto-reconnect, HTTP semantics, load balancer stickiness and
buffering. Datastar takes the same position (SSE down, fetch up) and demos a
multiplayer game on it.

## Idea

Coalesce on the client: collect the `server!` calls of one animation frame and
send them as one POST. The server unpacks and runs them in order.

- Buzz internal, no protocol change, no new user API. `server!` stays the one
  inbound frame.
- Restores ordering within a batch, since one POST arrives as one body.
- Amortizes header and cookie overhead over the batch.
- `reply` needs care: a batch response must carry the per call replies so each
  returned promise resolves with its own value.

Batching by animation frame delays a lone call by up to one frame. If that
matters, flush immediately when the queue was empty and only start batching
under pressure.

**Ordering across batches**: separate POSTs can arrive out of order, the one
guarantee websockets have that this design lacks. Fixed client side by
chaining the flushes: keep buffering while a POST is in flight and send the
next batch when the previous one resolves. At most one request in flight
means total order with no server cooperation, and batch size adapts to the
actual round trip for free: slow network, bigger batches. `server!` returns a
promise, so this is expressible today.

## Status

The whiteboard example batches at the application level: pointer moves buffer
in a client atom and flush once per animation frame as a point array.
Measured with 20 connections, this and render coalescing together took a
1000-write burst from 20000 patches and 77MiB to under 40 patches and 191KiB.
Message overhead after batching, behind the HTTP/2 proxy: a few KB/s, so the
per-message cost argument for websockets is settled here. Chained flushes
(ordering) are not implemented anywhere yet. A generic batching layer inside
`server!` itself stays not-needed until an app cannot do what the whiteboard
does.

## References

- `resources/buzz/rpc.cljs`, the `server!` transport
- [0003](0003-revocation-and-open-connections.md), the stream side of the
  protocol
