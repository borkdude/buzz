(ns notes
  "Two users, a note list each, and one page that knows which of them is
  looking at it.

  Buzz authenticates nobody. This shows the two things an application has to do
  itself: decide who someone is, and keep unauthenticated requests away from the
  handler. What Buzz gives you is the request that opened the connection, so the
  answer has somewhere to live."
  (:require [buzz.core :refer [client defui local-state server server!]]
            [buzz.handler :as buzz]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

;; State the server owns, per user.
(defonce notes (atom {"alice" ["water the plants"]
                      "bob"   ["renew the domain"]}))

;; A session is a random token in a map. Deciding whether someone really is
;; alice, with a password or an email link or OAuth, is your application's
;; business and is not shown here: this example asks who you are and believes
;; you. Biff does the real thing well.
(defonce sessions (atom {}))

(defn- token [req]
  (some->> (get-in req [:headers "cookie"])
           (re-find #"notes-session=([^;]+)")
           second))

(defn- whoami [req]
  (get @sessions (token req)))

;; `user` is a plain string the connection was built with, so it reaches the
;; browser through a slot like any other server value.
(defui board [user]
  (let [draft (local-state "")]
    [:div
     [:h1 "notes for " (server user)]
     [:ul
      (for [[i note] (map-indexed vector (server (get @notes user)))]
        [:li {:key i}
         note
         [:button {:on-click (fn [_] (server! (let [n (client i)]
                                                (swap! notes update user
                                                       #(vec (concat (subvec % 0 n)
                                                                     (subvec % (inc n))))))))}
          "delete"]])]
     [:input {:value @draft
              :placeholder "a new note"
              :on-input (fn [e] (reset! draft (.. e -target -value)))}]
     [:button {:on-click (fn [_]
                           (server! (swap! notes update user conj (client @draft)))
                           (reset! draft ""))}
      "add"]
     [:p [:a {:href "/signout"} "sign out"]]]))

(def ^:private ui
  (buzz/handler
   {:title "notes"
    :watch [notes]
    :mounts [{:el "app"
              ;; The one place an identity can enter. A handler is a closure over
              ;; this map and never sees a request, so it acts as whoever opened
              ;; the connection.
              :state (fn [req] {:user (whoami req)})
              :component (fn [{:keys [user]}] (board user))}]}))

(defn- page [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\">"
              "<title>sign in</title></head>\n<body>\n" body "\n</body>\n</html>\n")})

(defn- signin-page []
  (page (str "<h1>who are you?</h1>\n"
             "<form method=\"post\" action=\"/signin\">\n"
             "<button name=\"user\" value=\"alice\">alice</button>\n"
             "<button name=\"user\" value=\"bob\">bob</button>\n"
             "</form>")))

(defn- form-value [req k]
  (some->> (some-> (:body req) slurp (str/split #"&"))
           (map #(str/split % #"=" 2))
           (some (fn [[a v]] (when (= a k) v)))))

(defn- sign-in [req]
  (let [user (form-value req "user")]
    (if (contains? @notes user)
      (let [t (str (random-uuid))]
        (swap! sessions assoc t user)
        {:status 303
         :headers {"Location" "/"
                   "Set-Cookie" (str "notes-session=" t "; Path=/; HttpOnly; SameSite=Strict")}})
      {:status 400 :body "no such user"})))

(defn- sign-out [req]
  (swap! sessions dissoc (token req))
  {:status 303
   :headers {"Location" "/signin"
             "Set-Cookie" "notes-session=; Path=/; Max-Age=0"}})

;; Requests Buzz does not own return nil, so the application decides what
;; reaches it. Everything Buzz serves sits behind the same check: the page, the
;; event stream and the rpc endpoint alike. Guarding only the page would leave
;; the handlers open, and a handler is an endpoint.
(defn app [req]
  (case (:uri req)
    "/signin"  (if (= :post (:request-method req)) (sign-in req) (signin-page))
    "/signout" (sign-out req)
    (if (whoami req)
      (or (ui req) {:status 404 :body "not found"})
      {:status 303 :headers {"Location" "/signin"}})))

(defn -main [& _]
  (http/run-server app {:port 1360 :ip "127.0.0.1"})
  (println "http://localhost:1360")
  @(promise))
