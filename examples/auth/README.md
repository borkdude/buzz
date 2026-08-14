# Notes

Two users, a note list each, and one page that knows which of them is looking at it.

    bb dev     # http://localhost:1360

Sign in as alice with the password wonderland, or as bob with builder. Open a second browser, sign in as the other one, and add a note in each.

## Where the identity lives

A mount's `:state` is called with the request that opened the connection:

```clojure
{:el "app"
 :state (fn [req] {:user (whoami req)})
 :component (fn [{:keys [user]}] (board user))}
```

A handler is a closure over that map and never sees a request, so it acts as whoever opened the connection.

## Keeping everyone else out

Buzz authenticates nobody, and a `server!` handler is an endpoint. Buzz returns nil for requests it does not own, so the application says what reaches it:

```clojure
(defn app [req]
  (case (:uri req)
    "/signin"  (if (= :post (:request-method req)) (sign-in req) (signin-page))
    "/signout" (sign-out req)
    (if (whoami req)
      (or (ui req) {:status 404 :body "not found"})
      {:status 303 :headers {"Location" "/signin"}})))
```

The check covers the page, the event stream and the rpc endpoint alike. Guarding only the page leaves the handlers open.
