(ns notes
  "Two users, a note list each, and one page that knows which of them is
  looking at it.

  Buzz authenticates nobody. An application has to decide who someone is, and
  keep everyone else away from the handler. What Buzz gives it is the request
  that opened the connection, so the answer has somewhere to live."
  (:require [buzz.core :refer [client defui local-state reply server server!]]
            [buzz.handler :as buzz]
            [clojure.string :as str]
            [org.httpkit.server :as http]))

;; alice signs in with wonderland, bob with builder. Only the salt and the hash
;; are here, never the password. `password-hash` makes a new pair.
(def ^:private users
  {"alice" {:role :admin
            :password "6IjuyNF3OzL2emdkA486AA==$dUpGnuLro04li0/jAnZW2tPtPyQdEFGDjvGeDSa6fnk="}
   "bob"   {:role :user
            :password "6WTtNcPfhUki/5jxwLgUkg==$ilGAY2ddxwfRGMngcVy7RpFhVWs2k3a7IhxTquZrwxc="}})

(defn- role-of [user] (:role (get users user)))

;; Refuses by throwing, so the browser sees a rejected promise. Every guarded
;; handler calls this itself. A component that draws no admin control still
;; registers its handler, because the handler map is built once for the
;; component rather than once per render.
(defn- admin! [role]
  (when-not (= :admin role)
    (throw (ex-info "not allowed" {:role role}))))

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

(defn- cookie [value]
  (str "notes-session=" value "; Path=/; HttpOnly; SameSite=Strict"))

;; Answers with the response that signs someone in, and refuses by throwing.
;; The browser's promise rejects, which is all it needs to know.
(defn sign-in! [user password]
  (let [stored (:password (get users user))]
    (when-not (and stored (password-ok? password stored))
      (throw (ex-info "no such name and password" {:user user})))
    (let [t (encode (random-bytes 24))]
      (swap! sessions assoc t user)
      {:headers {"Set-Cookie" (cookie t)}})))

(defn sign-out! [t]
  (swap! sessions dissoc t)
  {:headers {"Set-Cookie" (str (cookie "") " Max-Age=0")}})

;; `user` is a plain string the connection was built with, so it reaches the
;; browser through a slot like any other server value.
(defui board [user role token]
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
     ;; Drawn for an admin only. The slot decides what the page shows. It does
     ;; not decide what the handler does, so the handler checks the role itself.
     ;; A boolean rather than the role, because a keyword arrives as a string.
     (when (server (= :admin role))
       [:p [:a {:href "/admin"} "everyone's notes"] " "
        [:button {:on-click (fn [_]
                              (server! (do (admin! role)
                                           (swap! notes update-vals
                                                  #(conj % "remember the milk")))))}
         "remind everyone"]])
     [:p [:button {:on-click (^:async fn [_]
                              (await (server! (reply :ok (sign-out! token))))
                              (set! js/window.location "/signin"))}
          "sign out"]]]))

(def ^:private notes-ui
  (buzz/handler
   {:title "notes"
    :watch [notes]
    :mounts [{:el "app"
              ;; The one place an identity can enter. A handler is a closure over
              ;; this map and never sees a request, so it acts as whoever opened
              ;; the connection.
              :state (fn [req] (let [user (whoami req)]
                                 {:user user :role (role-of user) :token (token req)}))
              :component (fn [{:keys [user role token]}] (board user role token))}]}))

;; Requests Buzz does not own return nil, so the application decides what
;; reaches it. Everything Buzz serves sits behind the same check: the page, the
;; event stream and the rpc endpoint alike. Guarding only the page would leave
;; the handlers open, and a `server!` handler is an endpoint.
;; The login page is a component too, on its own path so that its stream and its
;; modules do not collide with the ones behind the gate.
(defui doorbell []
  (let [form (local-state {:who "" :pw "" :err nil})]
    [:div
     [:h1 "sign in"]
     [:p [:input {:value (:who @form) :placeholder "alice or bob" :autofocus true
                  :on-input (fn [e] (swap! form assoc :who (.. e -target -value)))}]]
     [:p [:input {:type "password" :value (:pw @form) :placeholder "password"
                  :on-input (fn [e] (swap! form assoc :pw (.. e -target -value)))}]]
     ;; The reply carries the Set-Cookie, so the browser is signed in by the
     ;; time this resolves. A wrong password throws on the server, which the
     ;; browser sees as a rejected promise.
     [:button {:on-click (^:async fn [_]
                          (try
                            (await (server! (reply :ok (sign-in! (client (:who @form))
                                                                 (client (:pw @form))))))
                            (set! js/window.location "/")
                            (catch :default _
                              (swap! form assoc :err "that is not a name and password I know"))))}
      "sign in"]
     (when (:err @form) [:p (:err @form)])]))

(def ^:private signin-ui
  (buzz/handler {:title "sign in"
                 :path "/signin"
                 :mounts [{:el "signin" :component (fn [_] (doorbell))}]}))

;; Only an admin gets past the check in `app`, so only an admin opens this
;; stream. That is worth having, but it is not what makes `clear!` safe: the
;; handler checks the role itself, the way every handler that does something
;; only some people may do has to.
;;
;; The role is read when the stream opens. Taking it away reaches an open
;; connection when it next reconnects, so the gate alone would go on trusting a
;; role that has been withdrawn.
(defn- clear! [role who]
  (admin! role)
  ;; the browser says which list to empty, so only a name that exists may be one
  (when (contains? users who)
    (swap! notes assoc who [])))

(defui console [role]
  [:div
   [:h1 "everyone's notes"]
   [:ul
    ;; joined here rather than in the browser, so the body stays free of
    ;; anything the page would have to import
    (for [row (server (mapv (fn [[who ns]] {:who who :notes (str/join ", " ns)})
                            (sort @notes)))]
      [:li {:key (:who row)}
       [:strong (:who row)] " " (:notes row) " "
       [:button {:on-click (fn [_] (server! (clear! role (client (:who row)))))}
        "clear"]])]
   [:p [:a {:href "/"} "back"]]])

(def ^:private admin-ui
  (buzz/handler {:title "everyone's notes"
                 :path "/admin"
                 :watch [notes]
                 :mounts [{:el "admin"
                           :state (fn [req] {:role (role-of (whoami req))})
                           :component (fn [{:keys [role]}] (console role))}]}))

(defn app [req]
  (or (signin-ui req)                       ; /signin and its stream, open to all
      (when (= :admin (role-of (whoami req))) (admin-ui req))
      (when (whoami req) (notes-ui req))
      ;; Signed in but not allowed here, rather than not signed in at all.
      (if (whoami req)
        {:status 303 :headers {"Location" "/"}}
        {:status 303 :headers {"Location" "/signin"}})))

(defn -main [& _]
  (http/run-server app {:port 1360 :ip "127.0.0.1"})
  (println "http://localhost:1360")
  (println "alice/wonderland or bob/builder")
  @(promise))
