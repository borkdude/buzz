# Notes

Two users, a note list each, and one page that knows which of them is looking at it.

    bb run     # http://localhost:1360

Sign in as alice or bob. Open a second browser, sign in as the other one, and add a note in each.

## What it shows

Buzz authenticates nobody. An application has to do two things itself.

**Decide who someone is.** This example asks and believes the answer. A real one checks a password or an email link or an OAuth token. That part is your application's business and [Biff](https://biffweb.com) already does it well.

**Keep unauthenticated requests away from the handler.** Buzz returns nil for requests it does not own, so the application decides what reaches it:

```clojure
(defn app [req]
  (case (:uri req)
    "/signin"  (if (= :post (:request-method req)) (sign-in req) (signin-page))
    "/signout" (sign-out req)
    (if (whoami req)
      (or (ui req) {:status 404 :body "not found"})
      {:status 303 :headers {"Location" "/signin"}})))
```

The check covers the page, the event stream and the rpc endpoint alike. Guarding only the page leaves the handlers open, and a `server!` handler is an endpoint.

## Where the identity lives

A mount's `:state` is called with the request that opened the connection:

```clojure
{:el "app"
 :state (fn [req] {:user (whoami req)})
 :component (fn [{:keys [user]}] (board user))}
```

A handler is a closure over that map and never sees a request, so it acts as whoever opened the connection. Both browsers run the same compiled component and call the same handler id, and each one writes to its own list.
