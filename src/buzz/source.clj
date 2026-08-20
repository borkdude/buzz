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

  Five rules. `sources-hold-the-contract` in `test/buzz/handler_test.clj` runs
  the last four against `atom-source` and against a source with no store behind
  it. The first is a matter of construction: there is no way to force a write
  into the gap from outside, so it is enforced by reading the implementation.

  1. The subscription is in place before the first value is read. A source that
     reads first loses a change landing in between, and the connection stays on
     a stale value with nothing to notice it by.
  2. The handle holds the new value before `notify` is called. Buzz raises a
     version and marks a topic inside `notify`, and the render that follows
     reads the handle.
  3. Nothing calls `notify` after `-unsubscribe` returns.
  4. Key equality is the source's business. Two keys that are `=` are one
     subscription. Two keys that are not must not share whatever the source
     keys its own bookkeeping on, or one of them stops being notified.
  5. Notifying more often than necessary is allowed. It costs a render and no
     frame, since unchanged values are compared away before anything is sent.")

(defprotocol Source
  (-subscribe [source k notify]
    "Calls `notify`, a function of no arguments, whenever `k` changes. Returns
     a handle that `deref` gives the current value of.")
  (-unsubscribe [source k handle]
    "Releases what `-subscribe` set up for `k`."))
