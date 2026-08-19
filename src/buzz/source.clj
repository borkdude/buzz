(ns buzz.source
  "Contract for a source of change. A source is a keyed thing that can be
  subscribed to and read, which is what an atom, a Rama PState, a Datalevin
  database and a Postgres channel all are once the reading and the signalling
  are separated.

  Buzz keeps one subscription per key per process, shared by every connection
  that reads it, and closes it after the last connection lets go. Implement
  this to make an external system drive rendering:

    (defrecord PStateSource [pstate]
      buzz.source/Source
      (-subscribe [_ path notify] (foreign-proxy pstate path {:callback notify}))
      (-unsubscribe [_ _ proxy] (close! proxy)))

  Subscribe before reading. A source that reads first and subscribes second
  loses a change landing in between, and the connection stays on a stale value
  with nothing to notice it by.")

(defprotocol Source
  (-subscribe [source k notify]
    "Calls `notify`, a function of no arguments, whenever `k` changes. Returns
     a handle that `deref` gives the current value of.")
  (-unsubscribe [source k handle]
    "Releases what `-subscribe` set up for `k`."))
