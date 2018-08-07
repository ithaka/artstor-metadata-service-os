(ns artstor-metadata-service-os.auth
  (:require [artstor-metadata-service-os.user :as user]
            [org.ithaka.clj-iacauth.ring.middleware :refer [valid-public-tokens?]]
            [org.ithaka.clj-iacauth.artstor :refer [valid-user?]]
            [org.ithaka.clj-iacauth.token :refer [generate]]
            [compojure.api.sweet :refer :all]
            [compojure.api.meta :refer [restructure-param]]
            [ring.util.http-response :refer :all]
            [buddy.auth.accessrules :as baa]))

(defn generate-web-token
  "Generate web token given profile id and ins id"
  ([user] (generate-web-token (user :profile-id) (user :institution-id) (user :default-user) (user :username)))
  ([profile-id institution-id] (generate-web-token profile-id institution-id true "default-user"))
  ([profile-id institution-id default-user user-name]
   (generate {:profile-id profile-id :institution-id institution-id :default-user default-user :username user-name})))

(defn logged-in? [req]
  (user/valid-cookie? (get (get (get req :cookies) "ARTSTOR_HASHED_REM_ME") :value)))

(defn authenticated? [req]
  (logged-in? req))

(defn with-legacy [handler]
  (fn [req]
    (if (authenticated? req)
      (let [user-info (user/extract-user req)
            web-token (generate-web-token (user-info :profile_id) (first (get user-info :institution_ids)) true (user-info :username))
            new-req req]
        (handler (assoc-in new-req [:headers "web-token"] web-token)))
      (handler req))))

(defn artstor-user? [req]
  (valid-user? (req :artstor-user-info)))

(defn artstor-user-or-public-tokens? [req]
  (or (valid-user? (req :artstor-user-info)) (valid-public-tokens? (req :artstor-public-tokens))))

(defn access-error [req val]
  (forbidden val))

(defn wrap-rule [handler rule]
  (-> handler
      (baa/wrap-access-rules {:rules [{:pattern #".*"
                                       :handler rule}]
                              :on-error access-error})))

(defmethod restructure-param :auth-rules
  [_ rule acc]
  (update-in acc [:middleware] conj [wrap-rule rule]))

(defmethod restructure-param :current-user
  [_ binding acc]
  (update-in acc [:letks] into [binding `(user/extract-user ~'+compojure-api-request+)]))


