(ns buzz.stream
  "What Buzz asks of a server: keep one response open and let chunks be
  written to it over time. An adapter is a function of a Ring request and

    {:status .. :headers .. :on-open (fn [ch]) :on-close (fn [])}

  that answers the request with those, keeps it open, calls `:on-open` with
  something satisfying `Channel`, and calls `:on-close` when the client goes
  away. Everything else Buzz serves is plain Ring, so the adapter is the whole
  of what a server has to provide.")

(defprotocol Channel
  (send! [ch s] "Writes one chunk, keeping the stream open.")
  (close! [ch] "Closes the stream."))
