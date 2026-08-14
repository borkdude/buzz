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
  (case (:uri req)
    "/signin"  (if (= :post (:request-method req)) (sign-in req) (signin-page))
    "/signout" (sign-out req)
    (if (whoami req)
      (or (ui req) {:status 404 :body "not found"})
      {:status 303 :headers {"Location" "/signin"}})))
```
