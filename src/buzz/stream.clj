(ns buzz.stream
  "Server adapter contract for event streams. An adapter receives a Ring
  request and

    {:status .. :headers .. :on-open (fn [ch]) :on-close (fn [])}

  The adapter returns the server response, calls `:on-open` with a `Channel`,
  and calls `:on-close` once when the connection closes.

  `send!` and `close!` must not throw. `close!` must not block. `send!` returns
  true while the connection accepts writes. Adapters must flush chunks
  promptly.

  Buzz calls `send!` from one thread per connection. `send!` may block until
  the client accepts the chunk. While it blocks, changes for that connection
  collapse into one render of the latest state. An adapter bounds the wait and
  closes a connection that stops reading.")

(defprotocol Channel
  (send! [ch s] "Writes one chunk. Returns true while writes are accepted.")
  (close! [ch] "Closes the stream without blocking."))
