# 0004: The request is the only ambient thing

Date: 2026-08-16

Status: Prototyped on the `request-mark` branch.

## Context

A mount's `:state` function ran once per connection. Its result was passed to
the component and captured by its handlers. This had four costs:

1. Identity froze at stream open, which is [0003](0003-revocation-and-open-connections.md).
2. Handlers had to be instantiated per connection, so `conns` held rebuilt
   instances and reload machinery to rebuild them.
3. Parts could not access per-connection state. Inlining parts prevented
   recursion. Passing handlers to parts required browser code to compile during
   server rendering.
4. State was scoped to one connection even when it belonged to a user or
   browser.

## Decision

Applications store state in their own atoms. Buzz adds `request` as a sixth
compiler form beside `server`, `server!`, `client`, `reply` and `local-state`:

```clojure
(server  (notes-for (whoami (request))))          ; the request that opened the stream
(server! (delete! (whoami (request)) (client id))) ; the rpc carrying this call
```

In `(server ...)`, `(request)` returns the request that opened the stream. A
reconnect supplies a new request. In `(server! ...)`, it returns the RPC
request, so authorization can be checked for each action. Deleting a watched
session also removes protected data from later renders.

Scope is a keying choice, not a framework concept:

| scope | key |
|-------|-----|
| everyone | none, a plain atom |
| per user | `(whoami (request))`, from the app's cookie |
| per browser | `(buzz/token (request))`, Buzz's cookie |
| per connection | `(buzz/connection (request))`, added by Buzz |

Buzz adds the same connection ID to the stream request and its RPC requests.
Do not use the request map itself as a key because each RPC has a new map.

Use `:on-close` to remove connection-scoped state:

```clojure
(buzz/handler {:on-close (fn [req] (swap! snakes dissoc (buzz/connection req))) ...})
```

The hook receives the opening request with its connection ID. User-scoped and
browser-scoped state requires an application-defined retention policy.

## Costs

- Per-connection lookups now run on every patch instead of once when the
  connection opens. These lookups are a cookie match and a map lookup.
- Handlers that use `(request)` receive it as an extra argument. The benchmark
  below does not use `(request)`. Results varied without a consistent
  regression.

  | op | n | main | branch |
  |----|---|------|--------|
  | create-1000 | 6 | 58.6 | 56.8 |
  | create-5000 | 2 | 295.6 | 316.9 |
  | update-one | 24 | 36.5 | 30.0 |
  | update-10th | 24 | 34.8 | 28.6 |

## Consequences

Mounts use `:ui` instead of `:state` and `:component`. A component instance is
cached per revision and shared by all connections. Each handler owns its
connection registry, as proposed by item 4 of
[0002](0002-work-after-the-scheduler.md). Watched writes patch only that
handler's connections. RPC lookup is also limited to that registry.

## References

- `request`, `lift-request` in `src/buzz/core.clj`
- `connection`, `token`, `:on-close` in `src/buzz/core.clj`
- The multi-snake `request-mark` branch, which keys game state by connection ID.
