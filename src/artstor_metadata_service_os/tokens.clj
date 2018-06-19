(ns artstor-metadata-service-os.tokens
  (:require [artstor-metadata-service-os.util :as util]
            [org.ithaka.clj-iacauth.core :refer [get-session-id]]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [environ.core :refer [env]]))

(def external-account-id (env :artstor-external-account-id))

(defn get-artstor-licenses [all-licenses]
  (if (some #(>  (.indexOf ["Artstor" "ArtstorTrial" "Artstor_Institution_Collection" "Artstor_User_Collection"] (% :licSubType)) -1) (all-licenses :licenses))
    (all-licenses :entExtId)))

(defn get-all-licenses-from-session-id [session-id]
  (let [url (util/build-service-url "iac-service" (str "v2/session/" session-id "?sessionType=true"))
        response (http/get url {})]
    (if (empty? response)
      {:status false :message "Unknown error"}
      ((json/parse-string (response :body) true) :authzs))))

(defn get-eme-tokens-from-session [session-id]
  (let [all-licenses (get-all-licenses-from-session-id session-id)
        artstor-licenses (filter get-artstor-licenses all-licenses)]
    (into [] (map #(get % :entExtId) artstor-licenses))))

(defn legacy? [id]
  (< (count id) 12))

(defn get-user-ext-accountid-from-profileid [profile-id]
  (let [url (util/build-service-url "iac-service" (str "profile/" profile-id))
        response (http/get url {})]
    (if (empty? response)
      {:status false :message "Unknown error"}
      ((json/parse-string (response :body) true) :userId))))

(defn get-inst-ext-accountid-from-legacyid [inst-id]
  (let [institution-id (if (legacy? inst-id) (+ 1100000000 (read-string inst-id)) inst-id)
        url (util/build-service-url "iac-service" (str "account/byLegacy/" institution-id))
        response (http/get url {})]
    (if (empty? response)
      {:status false :message "Unknown error"}
      ((json/parse-string (response :body) true) :id))))

(defn build-query-params-to-get-licenses [artstor-user-info]
  (let [inst-id (artstor-user-info :institution-id)
        inst-ext-id (get-inst-ext-accountid-from-legacyid inst-id)
        prof-id (artstor-user-info :profile-id)
        user-acct-id (get-user-ext-accountid-from-profileid prof-id)]
    (if (artstor-user-info :default-user)
      {:accountIds (str inst-ext-id "," external-account-id) :idType "externalId" :includeFlag false}
      {:accountIds (str inst-ext-id "," user-acct-id "," external-account-id) :idType "externalId" :includeFlag false})))

(defn get-all-licenses-from-artstor-user-info [artstor-user-info]
  (let [params (build-query-params-to-get-licenses artstor-user-info)
        url (util/build-service-url "iac-service" "license/byAccount/")
        response (http/get url {:query-params params})]
    (if (empty? response)
      {:status false :message "Unknown error"}
      ((json/parse-string (response :body) true) :results))))

(defn get-eme-tokens-from-artstor-user-info [artstor-user-info]
  (let [all-licenses (get-all-licenses-from-artstor-user-info artstor-user-info)
        artstor-licenses (filter get-artstor-licenses all-licenses)]
    (into [] (map #(get % :entExtId) artstor-licenses))))

(defn get-eme-tokens [request]
  (let [access-session (get ((request :cookies) "AccessSession") :value "")
        session-id (if (empty? access-session) "" (get-session-id access-session))
        artstor-user-info (request :artstor-user-info)]
    (if (empty? session-id)
      (get-eme-tokens-from-artstor-user-info artstor-user-info)
      (get-eme-tokens-from-session session-id))))