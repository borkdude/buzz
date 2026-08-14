(ns notes
  "Two users, a note list each, and one page that knows which of them is
  looking at it.

  Buzz authenticates nobody. An application has to decide who someone is, and
  keep everyone else away from the handler. What Buzz gives it is the request
  that opened the connection, so the answer has somewhere to live."
  (:require [buzz.core :refer [client defui local-state server server!]]
            [buzz.handler :as buzz]
            [clojure.string :as str]
            [org.httpkit.server :as http]
            [reagami.ssr :as ssr]))

;; alice signs in with wonderland, bob with builder. Only the salt and the hash
;; are here, never the password. `password-hash` makes a new pair.
(def ^:private users
  {"alice" "6IjuyNF3OzL2emdkA486AA==$dUpGnuLro04li0/jAnZW2tPtPyQdEFGDjvGeDSa6fnk="
   "bob"   "6WTtNcPfhUki/5jxwLgUkg==$ilGAY2ddxwfRGMngcVy7RpFhVWs2k3a7IhxTquZrwxc="})

;; State the server owns, per user.
(defonce notes (atom {"alice" ["water the plants"]
                      "bob"   ["renew the domain"]}))

;; A session is a random token in a map, so signing out forgets it and a
;; restart signs everyone out.
(defonce sessions (atom {}))

(defn- pbkdf2 [password salt]
  (-> (javax.crypto.SecretKeyFactory/getInstance "PBKDF2WithHmacSHA256")
      (.generateSecret (javax.crypto.spec.PBEKeySpec. (.toCharArray password) salt 100000 256))
      (.getEncoded)))

(defn- encode [bs] (.encodeToString (java.util.Base64/getEncoder) bs))
(defn- decode [s] (.decode (java.util.Base64/getDecoder) s))

(defn- random-bytes [n]
  (let [bs (byte-array n)]
    (.nextBytes (java.security.SecureRandom.) bs)
    bs))

(defn password-hash
  "A salt and a hash, for the `users` map. Run this to add someone."
  [password]
  (let [salt (random-bytes 16)]
    (str (encode salt) "$" (encode (pbkdf2 password salt)))))

;; The comparison takes the same time whether one byte differs or all of them,
;; so it says nothing about how close a guess was. Whether the name exists is
;; still visible in how long the answer takes, which a real application hides by
;; hashing anyway.
(defn- password-ok? [password stored]
  (let [[salt hash] (str/split stored #"\$")]
    (java.security.MessageDigest/isEqual (decode hash) (pbkdf2 password (decode salt)))))

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

(defn- page [hiccup]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str "<!DOCTYPE html>\n<html>\n<head><meta charset=\"utf-8\">"
              "<title>sign in</title></head>\n<body>\n"
              (ssr/render hiccup)
              "\n</body>\n</html>\n")})

(defn- signin-page [& [error]]
  (page [:div
         [:h1 "sign in"]
         (when error [:p error])
         [:form {:method "post" :action "/signin"}
          [:p [:input {:name "user" :placeholder "alice or bob" :autofocus true}]]
          [:p [:input {:name "password" :type "password" :placeholder "password"}]]
          [:button "sign in"]]]))

(defn- form-params [req]
  (into {} (for [pair (some-> (:body req) slurp (str/split #"&"))
                 :let [[k v] (str/split pair #"=" 2)]]
             [k (java.net.URLDecoder/decode (or v "") "UTF-8")])))

(defn- sign-in [req]
  (let [{:strs [user password]} (form-params req)
        stored (get users user)]
    (if (and stored (password-ok? password stored))
      (let [t (encode (random-bytes 24))]
        (swap! sessions assoc t user)
        {:status 303
         :headers {"Location" "/"
                   "Set-Cookie" (str "notes-session=" t "; Path=/; HttpOnly; SameSite=Strict")}})
      (signin-page "that is not a name and password I know"))))

(defn- sign-out [req]
  (swap! sessions dissoc (token req))
  {:status 303
   :headers {"Location" "/signin"
             "Set-Cookie" "notes-session=; Path=/; Max-Age=0"}})

;; Requests Buzz does not own return nil, so the application decides what
;; reaches it. Everything Buzz serves sits behind the same check: the page, the
;; event stream and the rpc endpoint alike. Guarding only the page would leave
;; the handlers open, and a `server!` handler is an endpoint.
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
  (println "alice/wonderland or bob/builder")
  @(promise))
