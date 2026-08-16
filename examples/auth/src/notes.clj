(ns notes
  "Example note app with request-based authentication and per-user state."
  (:require [buzz.core :as buzz :refer [client defui local-state reply server server!]]
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

;; Conditional rendering does not authorize an RPC, so privileged handlers
;; call this directly.
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

;; Resolve identity from the stream request for slots and the RPC request for
;; handlers.
(defui board []
  (let [draft (local-state "")]
    [:div
     [:h1 "notes for " (server (whoami (buzz/request)))]
     [:ul
      (for [[i note] (map-indexed vector (server (get @notes (whoami (buzz/request)))))]
        [:li {:key i}
         note
         [:button {:on-click (fn [_] (server! (let [u (whoami (buzz/request))
                                                    n (client i)]
                                                (swap! notes update u
                                                       #(vec (concat (subvec % 0 n)
                                                                     (subvec % (inc n))))))))}
          "delete"]])]
     [:input {:value @draft
              :placeholder "a new note"
              :on-input (fn [e] (reset! draft (.. e -target -value)))}]
     [:button {:on-click (fn [_]
                           (server! (swap! notes update (whoami (buzz/request))
                                           conj (client @draft)))
                           (reset! draft ""))}
      "add"]
     ;; Check the current role in the handler as well as during rendering.
     (when (server (= :admin (role-of (whoami (buzz/request)))))
       [:p [:a {:href "/admin"} "everyone's notes"] " "
        [:button {:on-click (fn [_]
                              (server! (do (admin! (role-of (whoami (buzz/request))))
                                           (swap! notes update-vals
                                                  #(conj % "remember the milk")))))}
         "remind everyone"]])
     [:p [:button {:on-click (^:async fn [_]
                              (await (server! (reply :ok (sign-out! (token (buzz/request))))))
                              (set! js/window.location "/signin"))}
          "sign out"]]]))

;; Watching sessions redraws open pages after signout.
(def ^:private notes-ui
  (buzz/handler
   {:title "notes"
    :watch [notes sessions]
    :mounts [{:el "app" :ui #'board}]}))

;; Route checks protect the page, event stream, and RPC endpoint.
(defui doorbell []
  (let [form (local-state {:who "" :pw "" :err nil})]
    [:div
     [:h1 "sign in"]
     [:p [:input {:value (:who @form) :placeholder "alice or bob" :autofocus true
                  :on-input (fn [e] (swap! form assoc :who (.. e -target -value)))}]]
     [:p [:input {:type "password" :value (:pw @form) :placeholder "password"
                  :on-input (fn [e] (swap! form assoc :pw (.. e -target -value)))}]]
     ;; Wait for the Set-Cookie response before redirecting.
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
                 :mounts [{:el "signin" :ui #'doorbell}]}))

;; Check the current RPC role and the browser-supplied user.
(defn- clear! [role who]
  (admin! role)
  (when (contains? users who)
    (swap! notes assoc who [])))

(defui console []
  [:div
   [:h1 "everyone's notes"]
   [:ul
    (for [row (server (mapv (fn [[who ns]] {:who who :notes (str/join ", " ns)})
                            (sort @notes)))]
      [:li {:key (:who row)}
       [:strong (:who row)] " " (:notes row) " "
       [:button {:on-click (fn [_] (server! (clear! (role-of (whoami (buzz/request)))
                                                     (client (:who row)))))}
        "clear"]])]
   [:p [:a {:href "/"} "back"]]])

(def ^:private admin-ui
  (buzz/handler {:title "everyone's notes"
                 :path "/admin"
                 :watch [notes sessions]
                 :mounts [{:el "admin" :ui #'console}]}))

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
