(ns artstor-metadata-service-os.core
  (:require [artstor-metadata-service-os.schema :as data]
            [artstor-metadata-service-os.repository :as repo]
            [artstor-metadata-service-os.auth :as auth]
            [artstor-metadata-service-os.logging :as log]
            [artstor-metadata-service-os.user :as user]
            [artstor-metadata-service-os.util :as util]
            [artstor-metadata-service-os.tokens :as tokens]
            [org.ithaka.clj-iacauth.core :refer [get-session-id]]
            [org.ithaka.clj-iacauth.artstor :refer [valid-user?]]
            [org.ithaka.clj-iacauth.ring.middleware :refer [with-auth]]
            [ring.util.http-response :refer :all]
            [ring.middleware.cookies :as rcookie]
            [ring.logger :as logger]
            [ring.util.codec :as codec]
            [environ.core :refer [env]]
            [compojure.api.sweet :refer :all]
            [schema.core :as s]))

(def service
  (->
    (api
      {:swagger
       {:ui   "/"
        :spec "/swagger.json"
        :data {:info  {:title "Artstor Item Resolution Service API"
                       :description "This service exposes CRUD methods for retrieving item information."}
               :tags  [{:name "items" :description "Services for retrieving thumbnail data about an item"}
                       {:name "metadata" :description "Services for retrieving legacy metadata about an item"}]}}}
      (context "/api/v1/items" []
               :tags ["items"]
               (GET "/" request
                    :auth-rules auth/artstor-user?
                    :return {:success s/Bool :total s/Int :items [data/Item]}
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :query-params [{object_id :- [s/Str] []} {internal :- s/Bool false}]
                    :summary "Gets all specified items the user has access to."
                    :current-user user
                    :responses {200 {:schema      {:success s/Bool :total s/Int :items [data/Item]}
                                     :description "Find items by object_id and return matches"}
                                400 {:description "Invalid item identifiers supplied"}
                                401 {:description "Not Authorised"}
                                500 {:description "Server Error handling the request"}}
                    (let [object_id (filter #(>(count %) 0) object_id)]
                      (if (> (count object_id) 0)
                        (if-let [sorted-items (repo/build-items-using-tokens object_id request)]
                          (ok {:success true :total (count sorted-items) :items sorted-items})
                          (not-found "Couldn't find any of the requested items."))
                        (bad-request))))
               (GET "/resolve" request
                    :auth-rules auth/artstor-user?
                    :return {:success s/Bool :item data/Item :metadata [data/Metadata] :imageUrl s/Str :imageServer s/Str :downloadSize s/Str}
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :query-params [{encrypted_id :- s/Str ""}]
                    :summary "Decrypts the supplied object_id and returns the metadata for same."
                    :responses {200 {:schema      {:success s/Bool :item data/Item :metadata [data/Metadata] :imageUrl s/Str :imageServer s/Str :downloadSize s/Str}
                                     :description "Decrypt object_id and return metadata"}
                                400 {:description "Could not decrypt identifier"}
                                401 {:description "Not Authorised"}
                                403 {:description "Forbidden"}
                                404 {:description "Invalid item identifiers supplied"}
                                500 {:description "Server Error handling the request"}}
                    (if-let [item (repo/find-item (util/decrypt (codec/url-decode encrypted_id)))]
                      (let [objects-data-from-solr (into [] (repo/get-metadata-for-object-ids [(item :objectId)] false))
                            metadata (if item (repo/get-legacy-metadata-for-object-ids [(item :objectId)] false objects-data-from-solr))]
                        (if (and item (not (empty? metadata)))
                          (ok {:success true :item item :metadata metadata :imageUrl (item :largeImgUrl) :imageServer (env :artstor-image-server) :downloadSize (item :downloadSize)})
                          (not-found "Couldn't find the requested item.")))
                      (not-found "Couldn't find the requested item.")))
               (POST "/" request
                     :auth-rules auth/artstor-user?
                     :current-user user
                     :header-params [{web-token :- (s/maybe s/Str) ""}]
                     :return {:success s/Bool :total s/Int :items [data/Item]}
                     :body [items [s/Str]]
                     :summary "Gets all specified items the user has access too.  (100 max)"
                     :responses {200 {:schema      {:success s/Bool :total s/Int :items [data/Item]}
                                      :description "Find items by object_id and return matches"}
                                 400 {:description "Invalid form data supplied"}
                                 401 {:description "Not Authorised"}
                                 500 {:description "Server Error handling the request"}}
                     (let [object_id (filter #(>(count %) 0) items)]
                       (if (> (count object_id) 0)
                         (if-let [sorted-items (repo/build-items-using-tokens object_id request)]
                           (ok {:success true :total (count sorted-items) :items sorted-items})
                           (not-found "Couldn't find any of the requested items."))
                         (bad-request)))))
      (context "/api/v1/metadata" []
               :tags ["metadata"]
               (GET "/" request
                    :auth-rules auth/artstor-user-or-public-tokens?
                    :current-user user
                    :return {:success s/Bool :total s/Int :metadata [data/Metadata]}
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :query-params [{object_ids :- [s/Str] []} {legacy :- s/Bool true} {xml :- s/Bool false} {internal :- s/Bool false}]
                    :summary "Gets the metadata for all the specified items the user has access to.  (150 max).
                              Legacy flag determines datasource. (default) true=Oracle database"
                    :responses {200 {:schema      {:success s/Bool :total s/Int :metadata [data/Metadata]}
                                     :description "Find items by object_id and return metadata matches"}
                                400 {:description "Invalid item identifiers supplied"}
                                401 {:description "Not Authorised"}
                                500 {:description "Server Error handling the request"}}
                    (let [eme-tokens (if (valid-user? (request :artstor-user-info)) (tokens/get-eme-tokens request)
                                                                                    (request :artstor-public-tokens))
                          metadata-retriever (if legacy repo/build-legacy-metadata-using-tokens
                                                        repo/get-metadata-for-object-ids)]
                      (if-let [metadata (metadata-retriever (take 150 object_ids) xml eme-tokens)]
                        (ok {:success true :total (count metadata) :metadata metadata})
                        (not-found "Couldn't find any of the requested items"))))
               (GET "/legacy/:object-id" [id :as request]
                    :auth-rules auth/artstor-user?
                    :current-user user
                    :return data/Legacy-Metadata
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :path-params [object-id :- s/Str]
                    :summary "Accepts object-id in url and returns the metadata in legacy json format for the specified item."
                    :responses {200 {:schema data/Legacy-Metadata
                                     :description "Find items by object_id and return metadata matches"}
                                400 {:description "Invalid item identifier supplied"}
                                401 {:description "Not Authorised"}
                                404 {:description "Invalid object id supplied"}
                                500 {:description "Server Error handling the request"}}
                    (let [allowed-objects (repo/get-allowed-assets (list object-id) (tokens/get-eme-tokens request))]
                      (if (not (empty? allowed-objects))
                        (ok (first (repo/get-legacy-formatted-metadata object-id false)))
                        (forbidden "Access Denied"))))
               (GET "/group/:group-id" [id :as request]
                    :auth-rules auth/artstor-user?
                    :current-user user
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :return {:success s/Bool :total s/Int :metadata [data/Metadata]}
                    :path-params [group-id :- s/Str]
                    :query-params [{legacy :- s/Bool true} {xml :- s/Bool false} {maximize :- s/Bool false} {internal :- s/Bool false}]
                    :summary "Gets the metadata for all items in an image group (150 max, or use maximize param)
                              Legacy flag determines datasource. (default) true=Oracle database.
                              This call is used by the Offline Image Viewer service"
                    :responses {200 {:schema      {:success s/Bool :total s/Int :metadata [data/Metadata]}
                                     :description "Find items by object_id and return metadata matches"}
                                401 {:description "Not Authorised"}
                                403 {:description "Access denied"}
                                404 {:description "Invalid group id supplied"}
                                500 {:description "Server Error handling the request"}}
                    (let [artstor-cookie (get ((request :cookies) "ARTSTOR_HASHED_REM_ME") :value "")
                          eme-tokens (tokens/get-eme-tokens request)
                          group_response (repo/get-group-by-id artstor-cookie (request :web-token) group-id)
                          object-ids (if maximize (take 500 (:items group_response)) (take 150 (:items group_response)))
                          metadata-retriever (if legacy repo/build-legacy-metadata-using-tokens
                                                        repo/get-metadata-for-object-ids)]
                      (if (= 200 (:status group_response))
                        (if (empty? object-ids)
                          (ok {:success true :total 0 :metadata []})
                          (if-let [metadata (metadata-retriever object-ids xml eme-tokens)]
                            (ok {:success true :total (count metadata) :metadata metadata})
                            (not-found "Couldn't find any of the requested items"))))))
               (GET "/flush" []
                    :auth-rules auth/artstor-user?
                    :current-user user
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    :return {:success s/Bool :total s/Int :message s/Str}
                    :query-params [{object_ids :- [s/Str] []}]
                    :summary "Flush the record against object id from the cache, Internal use only"
                    :responses {200 {:schema      {:success s/Bool :total s/Int :message s/Str}
                                     :description "Flush the record against object id from the cache, Internal use only"}
                                400 {:description "Invalid object identifiers supplied"}
                                401 {:description "Not Authorised"}
                                500 {:description "Server Error handling the request"}}
                    (let [delete_count (repo/flush-cache! object_ids)]
                      (if (not (zero? delete_count))
                        (ok {:success true :total delete_count :message "Cache successfully cleared"})
                        (not-found "Couldn't find any of the request items in the cache")))))
      (context "/legacy_search" []
               :tags ["Legacy Search"]
               (GET "/" []
                    :summary "Static response for Legacy Search"
                    :current-user user
                    :header-params [{web-token :- (s/maybe s/Str) ""}]
                    (ok (repo/legacy-search-response))))
      (context "/internal/generate" []
               :tags ["web-token"]
               (POST "/" []
                     :return  [{:success s/Bool :tags [s/Str]}]
                     :body [data data/ArtstorUser]
                     :summary "Returns a web token"
                     :responses {200 {:schema    {:success s/Bool :token s/Str}
                                      :description "Generate a Web token"}
                                 400 {:description "Invalid form data supplied"}
                                 401 {:description "Not Authorised"}
                                 403 {:description "Access denied"}
                                 500 {:description "Server Error handling the request"}}
                     (let [wt (auth/generate-web-token data)]
                       (if (nil? wt)
                         (bad-request)
                         (ok {:success true :token wt})))))
      (context "/internal/v1/metadata" []
               :tags ["InternalOnly Metadata"]
               (GET "/" []
                    :return {:success s/Bool :total s/Int :metadata [data/Metadata]}
                    :query-params [{object_ids :- [s/Str] []} {legacy :- s/Bool true} {xml :- s/Bool false}]
                    :summary "Gets the metadata similar to GET /api/v1/metadata but this is internal only.
                                Legacy flag determines datasource. (default) true=Oracle database"
                    :responses {200 {:schema      {:success s/Bool :total s/Int :metadata [data/Metadata]}
                                     :description "Find items by object_id and return metadata matches"}
                                400 {:description "Invalid item identifiers supplied"}
                                401 {:description "Not Authorised"}
                                500 {:description "Server Error handling the request"}}
                    (let [metadata-retriever (if legacy repo/build-legacy-metadata-using-tokens
                                                        repo/get-metadata-for-object-ids)]
                      (if-let [metadata (metadata-retriever (take 150 object_ids) xml [])]
                        (ok {:success true :total (count metadata) :metadata metadata})
                        (not-found "Couldn't find any of the requested items") )))
               )
      (ANY "/*" []
           :responses {404 {:schema data/RequestStatus}}
           (not-found {:success false :message "My human masters didn't plan for this eventuality.  Pity."})))))

(def app (->> service
              log/wrap-web-logging
              user/wrap-user-or-ip
              (with-auth {:public-allowd true
                          :exclude-paths [#"/index.html"
                                          #"/swagger.json"
                                          #".\.js"
                                          #".*.js"
                                          #"/images/.*"
                                          #"/lib/.*"
                                          #"/css/.*"
                                          #"/conf/.*"
                                          #"/internal/.*"
                                          #"/"
                                          #"/watchable"]})
              auth/with-legacy
              rcookie/wrap-cookies
              logger/wrap-with-logger))