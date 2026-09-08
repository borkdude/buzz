(ns buzz.source
  "Implement `Source` to use external state in `buzz.core/observe`.

  Buzz shares one subscription per source and key across connections and
  releases it after the last connection stops observing that key.

  Implementations must satisfy these requirements:

  1. Subscribe before reading the initial value. An initial read must not
     overwrite a newer value delivered by a concurrent change.
  2. Update the handle's value before calling `notify`.
  3. After `-unsubscribe` returns, later changes must not start new calls to
     `notify`. A callback already running may finish.
  4. Release only the supplied handle. Subscriptions for the same key can
     overlap, and each must remain usable until it is released.
  5. Treat keys that are `=` as the same key.
  6. Extra notifications are allowed. Buzz suppresses unchanged patches.
  7. Keep the latest value in the handle when callbacks run concurrently
     or finish out of order.")

(defprotocol Source
  (-subscribe [source k notify]
    "Subscribes to changes at `k` in `source`. Returns a dereferenceable handle
     containing the current value. Calls `notify` with no arguments after
     updating the handle.")
  (-unsubscribe [source k handle]
    "Releases `handle`, returned by `-subscribe` for `source` and `k`."))
