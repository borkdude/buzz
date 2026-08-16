(ns buzz.stream
  "What Buzz asks of a server: keep one response open and let chunks be
  written to it over time. An adapter is a function of a Ring request and

    {:status .. :headers .. :on-open (fn [ch]) :on-close (fn [])}

  that answers the request with those, keeps it open, calls `:on-open` with
  something satisfying `Channel`, and calls `:on-close` when the client goes
  away. Everything else Buzz serves is plain Ring, so the adapter is the whole
  of what a server has to provide.

  The contract, learned the hard way from a server whose writes block:

  - `send!` never blocks and never throws. Buzz calls it from watch threads,
    which are the application's own `swap!` callers, so an adapter over a
    blocking writer puts a queue and a pump thread in between and treats a
    queue that stays full as a dead connection.
  - Killing a connection never blocks either: a close can park in the same
    backpressure a wedged write does.
  - `:on-close` fires at most once. The application's own close hook is
    downstream of it.
  - The channel works inside `:on-open`, which is where the first frames are
    sent, and every chunk reaches the wire promptly: a buffering adapter
    breaks the stream invisibly.")

(defprotocol Channel
  (send! [ch s] "Writes one chunk, keeping the stream open.")
  (close! [ch] "Closes the stream."))
