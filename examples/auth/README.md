# Notes

This example demonstrates how to use Buzz to build a simple note-taking application with user authentication. It shows how to manage user sessions, handle requests, and maintain state across different users.

To run the example, use the following commands:

    bb dev     # http://localhost:1360

Sign in as alice with the password wonderland, or as bob with builder. Open a second (or igcognito) browser, sign in as the other one, and add a note in each.

## Where the identity lives

A mount's `:state` is called with the request that opened the connection:

```clojure
{:el "app"
 :state (fn [req] {:user (whoami req)})
 :component (fn [{:keys [user]}] (board user))}
```

Buzz builds the component with this map, so `board` is called with `"alice"` or with `"bob"`. A `server!` in the body of `board` uses the name it was built with.

## Guarding the handlers

Buzz does not have a built-in authentication mechanism. It is up to the application to check the identity and return a 303 redirect to the sign-in page if the user is not signed in.

```clojure
(defn app [req]
  (or (signin-ui req)                       ; /signin and its stream, open to all
      (when (whoami req) (notes-ui req))
      {:status 303 :headers {"Location" "/signin"}}))
```

The check covers the page, the event stream and the rpc endpoint alike. Guarding only the page leaves the handlers open.

## Roles

A role reaches a component the way an identity does, through the mount's `:state`:

```clojure
:state (fn [req] (let [user (whoami req)]
                   {:user user :role (role-of user) :token (token req)}))
```

alice is an admin and bob is not.

An admin only page gets its own handler and its own `:path`, checked in `app`:

```clojure
(when (= :admin (role-of (whoami req))) (admin-ui req))
```

That handler owns its page, stream, modules and rpc endpoint, so a browser that does not pass the check never opens the stream. Signed in as bob, a POST to `/admin/rpc` is turned away with a 303 before the page sees it.

That is worth having, but do not treat it as the boundary. The role is read once, when the stream opens, so taking a role away only reaches an open connection when it next reconnects, and until then the gate goes on admitting a role that has been withdrawn. Every handler that does something only some people may do checks the role itself:

```clojure
(defn- clear! [role who]
  (admin! role)
  (when (contains? users who)
    (swap! notes assoc who [])))
```

The second line matters as much as the first. The browser says which list to empty, so a name it made up has to be refused.

## Hiding a control is not guarding it

The same rule from the other direction. `:handlers` is built once for the component rather than once per render, so a control drawn for an admin alone is still registered for everyone. The handler checks the role itself:

```clojure
(when (server (= :admin role))
  [:button {:on-click (fn [_] (server! (do (admin! role) ...)))} "remind everyone"])
```

The slot decides what the page draws. Signed in as bob, `board/2` is registered and a POST to `/rpc` reaches it:

    {"error":"handler failed"}   HTTP 500
    buzz: board/2 failed on [] - not allowed

The slot sends a boolean rather than the role, because a keyword arrives in the browser as a string.

## Signing in

The sign in page is a component too, with its own `:path` so that its stream and its modules do not collide with the ones behind the gate.

```clojure
(await (server! (reply :ok (sign-in! (client @who) (client @pw)))))
(set! js/window.location "/")
```

`sign-in!` returns `{:headers {"Set-Cookie" ...}}`, and the second argument to `reply` adds that to the http response. The cookie is `HttpOnly`, which a cookie set from JavaScript can never be. A wrong password throws on the server, so the browser sees a rejected promise and the page says so.
