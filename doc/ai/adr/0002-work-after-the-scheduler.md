# 0002: Work after the render scheduler

Date: 2026-08-15

Status: Open. Recorded in order, none implemented.

Follows [0001](0001-render-scheduling.md), which bounds the cost of the fan out
that already happens. The items here reduce how much of it is needed at all, and
then cover what a deployment needs beyond one process. The order is deliberate:
each one is easier once the one before it exists.

## 1. Per slot dependency tracking

Recompute only the slots the changed state can affect. Today one watched atom
runs every slot of every mount.

Explicit rather than deref interception:

```clojure
(server {:watch [db]} (expensive-report @db))
```

**This reverses a decision the code states.** `patch!` carries the reason it was
not done this way:

> A watched atom says something changed somewhere, not that this mount cares.
> Rather than have each mount declare what it reads, which can drift from what
> its slots actually do, run the slots and send nothing when the values are the
> same as last time.

The drift is real and the failure is quiet. Under declare a dependency and the
slot never recomputes, so the browser keeps showing a value that is no longer
true, with nothing to notice it by. Running everything and comparing cannot go
wrong that way.

What changed is the price. The equality check only avoids sending. It does not
avoid computing, and 0001 measures what computing costs once connections are
real. Taking the risk becomes worth it at a size where the current approach does
not fit.

The risk is worth mitigating rather than accepting: a development mode that
recomputes everything anyway and reports slots whose value changed while their
declared dependencies did not would turn silent staleness into a warning.

Needs the scheduler's dirty marking to be per slot rather than per handler, so
it is genuinely after 0001 rather than beside it.

## 2. Shared computation across connections

A slot that does not depend on the connection should be computed once,
serialised once, and reused for every browser. Per connection slots still run
per connection. A thousand identical reports become one.

**This need not be declared.** `split-body` already has the slot expressions and
the component's parameters, and a mount's `:state` is the only way a connection
enters a component. So a slot is connection independent exactly when its
expression references none of the component's argv symbols, which is decidable
where the slots are already being walked:

```clojure
(server (count @notes))          ; shared, mentions no parameter
(server (get @notes user))       ; per connection, mentions user
```

Serialising once may matter more than computing once. 0001 measures about 92 us
per connection in an example whose slots are a map lookup, so most of that is
JSON encoding and the channel write. The encoding is shareable, the write is
not. Building one `data:` frame and writing it to many channels is the cheaper
half and the easier one.

Composes with 3: without it, a shared slot can only be sent as part of a whole
vector that also holds per connection slots, so nothing is identical between
browsers and there is nothing to share.

## 3. Send changed slots only

One slot changing sends the whole vector. Give slots stable indexes and send the
difference:

```
["patch", "panel", [[2, "new value"]]]
```

The browser merges into its stored values before rendering. This is what makes a
component with one large unchanged slot affordable.

**This changes the wire protocol**, unlike 0001. `client.cljs` gains a merge
step and both ends have to agree, so it cannot ship half way.

Coalescing stays correct, but for a reason worth writing down. 0001 leans on
frames being whole snapshots, and this makes them deltas. It survives because
the delta is computed against `sent`, which is what the browser has, rather than
against the previously computed value. Skipping intermediate states therefore
still produces one correct delta from what the browser holds to what is current.

Two constraints get sharper. `sent` has to track what was actually delivered, so
a failed write must not advance it. And a browser that reconnects needs a whole
vector, which `mount` and `reload` already send.

## 4. Handler local connection registries

Done, with [0004](0004-the-request-is-the-only-ambient-thing.md). Each handler
owns a registry of its connections and keys its watches by it, `broadcast-patch!`
walks that registry alone, and `rpc` finds only its own sessions, so the `:page`
check fell away. The replacement this item asked for is `:on-close`, and
multi-snake uses it. The original text follows.

Each handler owns its connections and its watches.

**This is not only tidiness.** `broadcast-patch!` walks every connection in the
process:

```clojure
(doseq [{:keys [ch mounted]} (vals @conns) m mounted] (patch! ch m))
```

The watch is per atom, but the walk is global, so writing an atom watched by one
page runs the slots of every connection belonging to every other page. An
application with a busy page and a quiet one pays for both on every write. The
per connection cost in 0001 was measured with a single handler and is therefore
an underestimate for anything larger.

It would also make the ownership boundary from the connection security work
structural. `rpc` compares a `:page` field against the handler serving the
request. With a registry per handler there is no map to find the wrong
connection in.

**It breaks a public interface.** `conns` is public and multi-snake watches it
to notice a player whose browser went away:

```clojure
(add-watch buzz/conns ::leave (fn [_ _ old new] ...))
```

That is a legitimate need, so a registry per handler has to come with a
replacement, most likely a per handler close hook. Without one this is a
breaking change for the only real application built on Buzz.

## 5. Production connection limits

Configurable, with cleanup:

- maximum connections globally and per client
- idle and maximum connection lifetimes
- maximum rpc body and argument sizes
- rpc rate limits
- slow client detection
- queue limits with a stated disconnect policy

Independent of 1 to 4 and implementable at any point, though the queues in 0001
are where several of them naturally live. A review of the auth example found the
same gaps from the other direction: an open stream endpoint with no cap, and no
rate limit on the one endpoint that runs a key derivation per attempt.

Two cookie attributes belong here too, on the browser token. `Secure` needs no
setting, since `X-Forwarded-Proto` says whether the request arrived over https.
`__Host-` would stop a neighbouring subdomain overwriting the token, which
denies service to whoever holds the real one rather than granting anything: the
token unlocks nothing on its own, since acting on a connection also needs a
session id from that browser's own stream and whatever the application
authenticates with. Both are close to free, and neither is urgent for the same
reason.

Disconnecting a connection whose authority has been withdrawn is a related but
separate question, in [0003](0003-revocation-and-open-connections.md).

## 6. A clustering story

Server closures cannot migrate between processes, so the honest answer is sticky
routing rather than a shared store. A stream and its rpcs have to reach the same
node. Application wide changes reach other nodes through a pluggable pub sub
backplane, since `:watch` atoms are process local and a write on one node
patches nothing on another.

Document this rather than implying `conns` could move into Redis. The map is
serialisable in the sense that its keys are, but every value holds a closure
over the state its component was built with, and that is the thing that cannot
be moved.

The browser token from the connection security work is already a per browser
cookie, so it is a candidate to route on rather than adding a second one.

## 7. Observability

Render duration, slot duration, connection count, queued renders, patches
suppressed by equality, bytes sent, rpc latency, disconnect reasons.

A slow `(server ...)` is otherwise very hard to find: it shows up as latency on
whichever thread wrote an atom, which is not where it was written. 0001 puts a
scheduler in the path, which is the one place all of this can be counted.

## The combination that matters

Scheduled, dependency scoped, shared computation. 1 and 2 remove redundant work,
0001 bounds and coalesces what remains, and 3 shrinks what goes on the wire.
Together they attack the work itself rather than the browser runtime, which is
small.

## References

- `patch!`, `broadcast-patch!`, `conns` in `src/buzz/handler.clj`
- `split-body` in `src/buzz/core.clj`, for what 2 could decide statically
- `handle` in `resources/buzz/client.cljs`, which 3 changes
- multi-snake `src/snake/main.clj`, for the `conns` watch that 4 breaks
