# 0003: Revocation does not reach an open connection

Date: 2026-08-15

Status: Open. Recorded after rejecting a narrower fix.

## Context

A handler is a closure over the state its component was built with, and a
mount's `:state` runs once, when the stream opens. So whatever an application
decided about identity is decided then and held for the life of the connection.

`examples/auth` says so in its README:

> The role is read once, when the stream opens, so taking a role away only
> reaches an open connection when it next reconnects.

Nothing forces a reconnect, and `EventSource` only reconnects when the
connection drops. A signed out or demoted user therefore keeps a connection that
was built with authority they no longer have, until they happen to close the
tab.

## The fix that was considered and is not enough

Give `handler` a key to recompute per rpc:

```clojure
:connection-key (fn [req] (token req))
```

Record it when the stream opens, recompute it on each rpc, refuse on a mismatch.
Keyed on the session token rather than the user name, so that a logout, a new
login or a revoked session all change it.

**It closes the write path only.** `broadcast-patch!` walks `(vals @conns)` and
consults no identity at all:

```clojure
(doseq [{:keys [ch mounted]} (vals @conns) m mounted] (patch! ch m))
```

So a revoked admin goes on receiving every patch that page produces. They cannot
act, and they can still watch. For a page whose whole content is the privilege,
which is what an admin page usually is, that is most of what was being protected.

Note this is read from the code rather than measured.

## What would be enough

A way to drop connections by an application chosen key. Record the key at stream
open, expose something like `(buzz/disconnect! k)`, close those channels, and let
`EventSource` reconnect into a fresh `:state` that sees the new identity.

That covers both paths, because a closed stream sends no patches and its
handlers leave `conns`. It also matches how the same problem is solved
elsewhere: Phoenix LiveView puts a `live_socket_id` in the session and
broadcasts on that topic to disconnect every socket belonging to a user, then
lets the failed re-authentication on reconnect redirect them.

It wants the handler local registries from
[0002](0002-work-after-the-scheduler.md), item 4. A global `conns` gives nothing
to scope a disconnect by, and a per handler registry is the thing that would
hold the key.

## What is already covered, and is not this

The connection ownership work binds an rpc to the browser and page that opened
the stream. That stops one signed in user driving another's connection. It says
nothing about a connection outliving the authority it was built with, which is a
different question and the one recorded here.

## Status

Open. `:connection-key` is explicitly not recommended: it is half of this at
close to the same cost, and shipping it would make the remaining half look
handled.

## References

- `broadcast-patch!`, `conns` in `src/buzz/handler.clj`
- `:state` and what a connection is built with, in the `handler` docstring
- `examples/auth/README.md`, on a role reaching an open connection
- Phoenix LiveView security model, on `live_socket_id` and disconnecting
