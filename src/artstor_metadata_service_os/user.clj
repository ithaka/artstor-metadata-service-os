(ns artstor-metadata-service-os.user
  (:require [artstor-metadata-service-os.util :as util]
            [org.ithaka.clj-iacauth.artstor :refer [valid-user?]]
            [clojure.core.cache :as cache]
            [clojure.string :as string]
            [environ.core :refer [env]]
            [yesql.core :refer [defqueries]])
  (:import (com.mchange.v2.c3p0 DataSources)))

(def db-spec {:datasource (DataSources/pooledDataSource
                            (DataSources/unpooledDataSource (env :artstor-metadata-db-url)))})

;; This is a macro that reads the Oracle calls in the specified adl.sql file and generates functions based on the
;; comments in the adl.sql file.
(defqueries "artstor_metadata_service_os/sql/user.sql"
            {:connection db-spec})

;milliseconds 1000 -> seconds 60 --> 15 minutes
(def timeout_in_milliseconds (* 1000 60 15))

(def users-cache (atom (cache/ttl-cache-factory {} :ttl timeout_in_milliseconds)))

(defn get-user-institution*
  "Makes the real call to remote/db"
  [profile-id]
  (str (get (first (get-user-institution-id {:profile_id profile-id})) :institution_id 0)))

(defn get-user-institution
  "Looks in cache and makes remote/db call to load user profile as needed."
  [profile-id]
  (if (cache/has? @users-cache profile-id)
    (get (cache/hit @users-cache profile-id) profile-id)
    (let [updated-cache (swap! users-cache #(cache/miss % profile-id (get-user-institution* profile-id)))]
      (get updated-cache profile-id))))

(defn extract-user-from-cookie [cookie]
  (try (let [user (util/build-user-from-artstor-cookie cookie)
             inst_id (get-user-institution (user :profile_id))]
         (assoc user :institution_ids [inst_id]))
       (catch Exception e {:status false :message "Caught exception extracting user from cookie"})))

(defn valid-cookie? [cookie]
  (if (not= 0 (count cookie))
    (let [decoded-value (util/decode-hash-rem-me cookie)]
      (= 6 (count (string/split decoded-value #":"))))
    false))

(defn extract-user [req]
  (if (valid-user? (req :artstor-user-info))
    {:profile_id ((req :artstor-user-info) :profile-id), :username ((req :artstor-user-info) :username), :institution_ids [((req :artstor-user-info) :institution-id)]}
    (if-let [artstor-cookie (:value ((req :cookies) "ARTSTOR_HASHED_REM_ME"))]
      (extract-user-from-cookie artstor-cookie))))

(defn extract-user-from-req
  "Attempts to extract user from clj-IAC library."
  [req]
  (if (not (empty? (req :artstor-user-info)))
    (let [profile_id (str (get-in  req [:artstor-user-info :profile-id]))
          ins_id (str (get-in req [ :artstor-user-info :institution-id]))]
      {:profile_id  profile_id :institution_ids [ins_id]})))

(defn wrap-user-or-ip
  "Middleware version of extract-user-from-req"
  [client-func]
  (fn [req]
    (let [user (extract-user-from-req req)]
      (client-func (assoc req :user user)))))


