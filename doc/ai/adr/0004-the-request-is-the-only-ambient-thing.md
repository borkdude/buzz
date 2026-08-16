# 0004: The request is the only ambient thing

Date: 2026-08-16

Status: Prototyped on the `request-mark` branch.

## Context

A mount's `:state` fn ran once per connection and its result was captured in
closures: the component was called with it, and every handler closed over it.
One decision, four costs.

1. Identity froze at stream open, which is [0003](0003-revocation-and-open-connections.md).
2. Handlers had to be instantiated per connection, so `conns` held rebuilt
   instances and reload machinery to rebuild them.
3. A part is compiled once, so its handlers could not reach per connection
   state at all. First splicing compensated, which banned recursion. Then
   passing handlers down compensated, which put browser code where the first
   paint had to compile it.
4. Two tabs never shared, even when the state was the user's rather than the
   tab's, because the connection was the only scope on offer.

## Decision

State lives in the application's own atoms. Buzz supplies one ambient value,
the request, as a sixth mark beside `server`, `server!`, `client`, `reply`
and `local-state`:

```clojure
(server  (notes-for (whoami (request))))          ; the request that opened the stream
(server! (delete! (whoami (request)) (client id))) ; the rpc carrying this call
```

`(request)` means the request that caused this code to run. In a slot that is
the one that opened the stream, refreshed by a reconnect. In a handler it is
the rpc itself, so authority is checked per action, which closes the write
path of 0003. The read path closes when the application treats the cookie as
a key rather than a fact: delete the session it points to and the next patch
renders the signed out view.

Scope is a keying choice, not a framework concept:

| scope | key |
|-------|-----|
| everyone | none, a plain atom |
| per user | `(whoami (request))`, from the app's cookie |
| per browser | `(buzz/token (request))`, Buzz's cookie |
| per tab | `(buzz/connection (request))`, assoc'd by Buzz |

The connection id is the one enrichment: the stream's opening request and
every rpc that connection sends carry the same value, so state keyed by it
agrees on both sides. Keying by the request map itself would not, since a
handler's request is a fresh map per call.

Connections end and only Buzz knows when, so the handler spec takes one
lifecycle notification:

```clojure
(buzz/handler {:on-close (fn [conn-id] (swap! snakes dissoc conn-id)) ...})
```

What that means for the application's atoms is the application's business.
User and browser keyed maps outlive any connection and want app side TTLs.

## Costs

- A component that filters by per tab state runs its lookup on every patch of
  every connection, where a `:state` closure read it once. The lookups this
  design asks for are a cookie regex and a map get, well under the render and
  encode work around them.
- Marks that never call `(request)` compile exactly as before, and a handler
  that asks gets it as a prepended argument. Measured on `bb bench`, which
  never asks: four interleaved rounds against main, full browser loop per op,
  medians in ms. Every delta is inside one standard deviation, so the branch
  is at parity.

  | op | n | main | branch |
  |----|---|------|--------|
  | create-1000 | 6 | 58.6 | 56.8 |
  | create-5000 | 2 | 295.6 | 316.9 |
  | update-one | 24 | 36.5 | 30.0 |
  | update-10th | 24 | 34.8 | 28.6 |

## Consequences

`:state` and per connection instances are no longer load bearing. A `:ui`
mount names its component by var, and one instance serves every connection,
cached per revision. `:component` and `:state` still work, instantiated per
connection as before, and nothing uses them in the examples.

## References

- `request`, `lift-request` in `src/buzz/core.clj`
- `connection`, `token`, `:on-close` in `src/buzz/handler.clj`
- The multi-snake branch of the same name, a game whose whole state is one
  atom keyed by connection id.
