(ns artstor-metadata-service-os.logging-test
  (:require [artstor-metadata-service-os.auth :as auth]
            [artstor-metadata-service-os.core :as core]
            [artstor-metadata-service-os.repository :as repo]
            [artstor-metadata-service-os.logging :as log]
            [clj-sequoia.logging :as cptlog]
            [clojure.tools.logging :as logger]
            [clojure.test :refer :all]
            [ragtime.jdbc :as rag]
            [ragtime.repl :refer [migrate rollback]]
            [ring.mock.request :as mock]
            [environ.core :refer [env]]))

;code borrowed from asynchronizer
(defn- match? [expected-log actual-log]
  (= expected-log
     (select-keys actual-log
                  (keys expected-log))))

;code borrowed from asynchronizer
(defn included? [expected logs]
  (some (partial match? expected) logs))


(def web-token (auth/generate-web-token 123456 1000 false "qa@artstor.org"))

(def config {:datastore (rag/sql-database {:connection-uri (env :artstor-metadata-db-url)})
             :migrations (rag/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(deftest test-determining-eventtype
  (testing "test determining event type mulitple requests"
    (is (= "artstor_get_items_with_POST" (log/determine-event-type {:uri "/api/v1/items" :request-method :post })))
    (is (= "artstor_get_items_with_GET" (log/determine-event-type {:uri "/api/v1/items" :request-method :get })))
    (is (= "artstor_get_metadata" (log/determine-event-type {:uri "/api/v1/metadata" :request-method :get })))
    (is (= "artstor_get_metadata_legacy_format" (log/determine-event-type {:uri "/api/v1/metadata/legacy/1234567" :request-method :get })))
    (is (= "artstor_get_item_from_encrypted_objectid" (log/determine-event-type {:uri "/api/v1/items/resolve" :request-method :get })))
    (is (= "artstor_get_metadata_for_group" (log/determine-event-type {:uri "/api/v1/metadata/group/asdf-456787" :request-method :get })))
    (is (= "artstor_flush_metadata_from_cache" (log/determine-event-type {:uri "/api/v1/metadata/flush" :request-method :get }))))
  (testing "test not yet used api"
    (is (= "artstor_general_metadata_api_event" (log/determine-event-type {:uri "/api/v1/somenewapi" :request-method :get }))))
  (testing "test not an api call"
    (is (= :no-op (log/determine-event-type {:uri "/swagger.json" :request-method :get })))))


(deftest test-get-sessionid-from-cookies
  (testing "test getting good session id from cookies"
    (let [cookies { "AccessSession" {:value "H4sIAAAAAAAAAJ2TS4-bMBSF9_MrEOtxxk-ws2oUdTGLtqM8FlU1iowfCRUDqe1Eikb57-UVmDRpF0UIxDnX18ef8ftDFMW5jqNpFHPEKeUEWQQTiqXKEmYVT6nVBkJsVfzYFKu-OpcKeOOOuTKQdda-txSBkCMmDCaUMiiFsRhSkmEDMTU26ap_9tVM8gxZaIHUxgKKTQKExBngCbQ8hURxQbsRrh9hU1nHY1ZIoZDk0GYpEYxQxCGCmIuuWh7CrikP7mBa4SiLrgFiiCAEUYITDlsr37eNCZ8k9Z1OkOhDSv2Wl41nZeG7Nt5X10JQ19_NxDOlgm_kH9F7rY2QEUJU1Bja9rVcDDqsr-ZxccyFJtNGkTQFiaAYEGEkEBxzwAWGhJpEpHroFk570w56Ln3IwyHkVTnMNJj5remqwvR54_Xy8yKOXntHOaOvVjKuJTvtpfcbL99836ejfOim2YWwnz49FZWSxa7yYVqYrVQnfyrVWD6EWhhrnDNutD5uYKudo9f6fX68QYoxgSy5QVrDxELgNP0TqeYpQSSRAAlMAUH1z5dZZAEWUOuMZYhpeAepzo-5PsjiluiLcb4q136I_39AOWMCcnoP5i9Z_x2fpAs-VG5Sue0dhM38L_WOjNaYsKi2-ceA_-DbJY19GM4K5KRGLNJWL-SVMR6i4vAXQ4bg2hX3-zaft5G-zi6wlqtWmC1Wy9W3xUWd92pZbmYuq9zmy_PFWnQd1kswavPvvRY3i3g4_wa1lbj_2wQAAA"}}
          session-id (log/get-sessionid-from-cookies cookies)]
      (is (= false (nil? session-id)))
      (is (= "9093531f10642acb65fc874fde002dx" session-id))))
  (testing "test getting good session id from cookies"
    (let [cookies { "x-mas-cookie" {:value "TheseAreNotTheCookiesYouareLookingFor"}}
          session-id (log/get-sessionid-from-cookies cookies)]
      (is (= "" session-id)))))


(deftest test-captains-logging-get-items
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  repo/build-items-using-tokens (fn [_ _] [{:collectionId "103" :objectId "AIC_940034" :downloadSize "1024,1024"
                                                            :tombstone ["Stonehenge","ca. 2750-1400 B.C.E."],
                                                            :collectionType 1, :thumbnailImgUrl "imgstor/size0/aic/d0094/00940034.jpg",
                                                            :status "available", :objectTypeId 10, :clustered 0,
                                                            :largeImgUrl "imgstor/size1/aic/d0094/00945034.jpg",
                                                            :cfObjectId "AIC_940034"}])]
      (with-db config
               (testing "Test captains log GET items"
                 (let [
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/request :get "/api/v1/items?object_id=ARTSTOR_103_41822001316544")
                                                   (mock/header "web-token" web-token))))]
                   (is (included? {:eventtype  "artstor_get_items_with_GET"
                                   :dests ["captains-log"]
                                   :profileid "123456"
                                   :status 200
                                   :uri "/api/v1/items"
                                   :query-string "object_id=ARTSTOR_103_41822001316544"}
                                  logs))))

               (testing "Test captains log POST items bad input"
                 (let [
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/request :post "/api/v1/items?object_id=ARTSTOR_103_41822001316544")
                                                   (mock/header "web-token" web-token))))]
                   (is (included? {:eventtype  "artstor_get_items_with_POST"
                                   :dests ["captains-log"]
                                   :profileid "123456"
                                   :status 400
                                   :uri "/api/v1/items"
                                   :query-string "object_id=ARTSTOR_103_41822001316544"}
                                  logs))))))))

(deftest test-captains-logging-items-not-found
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  repo/build-items-using-tokens (fn [_ _] nil)]
      (with-db config
               (testing "Test captains log GET items not found"
                 (let [
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/request :get "/api/v1/items?object_id=ARTSTOR_103_41822001316544")
                                                   (mock/header "web-token" web-token))))]
                   (is (included? {:eventtype  "artstor_get_items_with_GET"
                                   :dests ["captains-log"]
                                   :profileid "123456"
                                   :status 404
                                   :uri "/api/v1/items"
                                   :query-string "object_id=ARTSTOR_103_41822001316544"}
                                  logs))))))))

(deftest test-captains-logging-api-without-eventtype
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
      (with-db config
               (testing "Test captains log api not found"
                 (let [
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/request :get "/api/v1/unkownapi?object_id=ARTSTOR_103_41822001316544")
                                                   (mock/header "web-token" web-token))))]
                   (is (included? {:eventtype  "artstor_general_metadata_api_event"
                                   :dests ["captains-log"]
                                   :profileid "123456"
                                   :status 404
                                   :uri "/api/v1/unkownapi"
                                   :query-string "object_id=ARTSTOR_103_41822001316544"}
                                  logs))))))))

(deftest test-captains-logging-dont-log-swaggers
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))]
      (with-db config
               (testing "Test captains log dont write captains log for non api calls"
                 (let [
                       {:keys [logs _]} (cptlog/collect-parsed-logs
                                          (app (-> (mock/request :get "/swagger.json")
                                                   (mock/header "web-token" web-token))))]
                   (is (empty? logs))))))))