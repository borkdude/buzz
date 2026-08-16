# Notes

This example demonstrates how to use Buzz to build a simple note-taking application with user authentication. It shows how to manage user sessions, handle requests, and maintain state across different users.

To run the example, use the following commands:

    bb dev     # http://localhost:1360

Sign in as alice with the password wonderland, or as bob with builder. Open a second (or igcognito) browser, sign in as the other one, and add a note in each.

## Reading identity

Read the current identity from `(request)`:

```clojure
[:h1 "notes for " (server (whoami (request)))]
```

In `(server ...)`, this is the request that opened the stream. In
`(server! ...)`, this is the RPC request, so each action checks the current
identity.

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

Read the current role from the request in the same way:

```clojure
(role-of (whoami (request)))
```

alice is an admin and bob is not.

An admin only page gets its own handler and its own `:path`, checked in `app`:

```clojure
(when (= :admin (role-of (whoami req))) (admin-ui req))
```

The route check protects the admin page, stream and RPC endpoint. `clear!` also
checks the role because permissions can change while the page is open:

```clojure
(defn- clear! [role who]
  (admin! role)
  (when (contains? users who)
    (swap! notes assoc who [])))
```

The browser supplies `who`, so `clear!` also checks that the user exists.

## Hiding a control is not guarding it

Conditional rendering does not authorize an action. Check the role inside the
handler:

```clojure
(when (server (= :admin (role-of (whoami (request)))))
  [:button {:on-click (fn [_] (server! (do (admin! (role-of (whoami (request)))) ...)))}
   "remind everyone"])
```

## Signing out reaches open pages

`sessions` is in `:watch`, so signing out redraws open pages. The session cookie
no longer resolves to a user, and protected content disappears.

## Signing in

The sign in page is a component too, with its own `:path` so that its stream and its modules do not collide with the ones behind the gate.

```clojure
(await (server! (reply :ok (sign-in! (client @who) (client @pw)))))
(set! js/window.location "/")
```

`sign-in!` returns `{:headers {"Set-Cookie" ...}}`, and the second argument to `reply` adds that to the http response. The cookie is `HttpOnly`, which a cookie set from JavaScript can never be. A wrong password throws on the server, so the browser sees a rejected promise and the page says so.
