(ns buzz.stream
  "Server adapter contract for event streams. An adapter receives a Ring
  request and

    {:status .. :headers .. :on-open (fn [ch]) :on-close (fn [])}

  The adapter returns the server response, calls `:on-open` with a `Channel`,
  and calls `:on-close` once when the connection closes.

  `send!` and `close!` must not block or throw. `send!` returns true while the
  connection accepts writes. Adapters must flush chunks promptly.

  `send!` is called from more than one thread: the heartbeat, watch threads,
  and the render scheduler all write to the same channel. An adapter must
  accept concurrent calls and keep each chunk whole on the wire, never
  interleaved with another. http-kit serializes writes internally; the capra
  adapter funnels them through one queue and pump thread.")

(defprotocol Channel
  (send! [ch s] "Writes one chunk. Returns true while writes are accepted.")
  (close! [ch] "Closes the stream without blocking."))
