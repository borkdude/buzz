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

  Seven rules. `sources-hold-the-contract` in `test/buzz/handler_test.clj` runs
  five of them against `atom-source` and against a source with no store behind
  it. Rules one and seven are matters of construction: their interleavings
  cannot be forced from outside an implementation, so they are enforced by
  reading it.

  1. The subscription is in place before the first value is read, and the first
     value is stored so that it cannot land on top of a newer one the
     subscription has already delivered. Reading and storing are two steps.
  2. The handle holds the new value before `notify` is called. Buzz raises a
     version and marks a topic inside `notify`, and the render that follows
     reads the handle.
  3. Nothing calls `notify` after `-unsubscribe` returns.
  4. `-unsubscribe` closes only the handle it is given. Two subscriptions to
     one key overlap while an old one is being released, so a source that keys
     its own bookkeeping by `k` has one of them close the other. Key it by the
     handle.
  5. Key equality is the source's business. Two keys that are `=` are one
     subscription.
  6. Notifying more often than necessary is allowed. It costs a render and no
     frame, since unchanged values are compared away before anything is sent.
  7. Callbacks can run concurrently and finish in any order. The handle has to
     end holding the latest value, so storing the snapshot a callback was
     handed is not enough. Read the current value under a per handle lock, or
     carry a revision and refuse an older write.")

(defprotocol Source
  (-subscribe [source k notify]
    "Calls `notify`, a function of no arguments, whenever `k` changes. Returns
     a handle that `deref` gives the current value of.")
  (-unsubscribe [source k handle]
    "Releases what `-subscribe` set up for `k`."))
