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

## Signing in

The sign in page is a component too, with its own `:path` so that its stream and its modules do not collide with the ones behind the gate.

```clojure
(await (server! (reply :ok (sign-in! (client @who) (client @pw)))))
(set! js/window.location "/")
```

`sign-in!` returns `{:headers {"Set-Cookie" ...}}`, and the second argument to `reply` adds that to the http response. The cookie is `HttpOnly`, which a cookie set from JavaScript can never be. A wrong password throws on the server, so the browser sees a rejected promise and the page says so.
