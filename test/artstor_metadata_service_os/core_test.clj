(ns artstor-metadata-service-os.core-test
  (:require [artstor-metadata-service-os.tokens :as tokens]
            [artstor-metadata-service-os.user :as user]
            [artstor-metadata-service-os.schema :refer :all]
            [artstor-metadata-service-os.util :as util]
            [artstor-metadata-service-os.core :as core]
            [artstor-metadata-service-os.schema :as data]
            [artstor-metadata-service-os.auth :as auth]
            [artstor-metadata-service-os.repository :as repo]
            [clj-sequoia.logging :as cptlog]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test :refer :all]
            [clojure.string :as string]
            [clojure.tools.logging :as logger]
            [environ.core :refer [env]]
            [schema.core :as s]
            [ring.mock.request :as mock]
            [ring.util.codec :as codec]
            [ragtime.jdbc :as rag]
            [ragtime.repl :refer [migrate rollback]]))

;;"cookie" header
(def web-token (auth/generate-web-token 123456 1000 false "qa@artstor.org"))

;code borrowed from asynchronizer
(defn- match? [expected-log actual-log]
  (= expected-log
     (select-keys actual-log
                  (keys expected-log))))

;code borrowed from asynchronizer
(defn included? [expected logs]
  (some (partial match? expected) logs))

(def config {:datastore (rag/sql-database {:connection-uri (env :artstor-metadata-db-url)})
             :migrations (rag/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(deftest using-ip-for-auth
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  user/extract-user (fn [request] {:profile_id 100})
                  org.ithaka.clj-iacauth.core/get-session-id (fn [session-text] "session-text")
                  tokens/get-eme-tokens (fn [request] "12345678901")
                  repo/get-allowed-assets (fn [object-id session-id] ["obj1"])
                  auth/with-legacy (fn [h] (h {}))
                  repo/sql-retrieve-items (fn [_] [{:cfobjectid "",
                                                    :thumbnail2 "",
                                                    :collectionid "103",
                                                    :thumbnailimgurl "imgstor/size0/thumb_image_url.jpg",
                                                    :largeimgurl "imgstor/size1/large_image_url.jpg",
                                                    :objecttypeid 10,
                                                    :thumbnail3 "thumbnail3",
                                                    :thumbnail1 "thumbnail1"
                                                    :downloadsize "1024,1024",
                                                    :objectid "obj1",
                                                    :collectiontype 1,
                                                    :clustered 0 }])]
      (with-db config
               (testing "Test api/v1/items with internal=true"
                 (let [response (app (-> (mock/request :get "/api/v1/items?object_id=obj1&internal=true")
                                         (mock/header "web-token" web-token)))
                       {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get "/api/v1/items?object_id=obj1&internal=true")
                                                                             (mock/header "web-token" web-token))))
                       raw-body (slurp (:body response))
                       body (cheshire.core/parse-string raw-body true)]
                   (is (= (:status response) 200))
                   (is (= true (:success body)))
                   (is (= 1 (:total body)))
                   (is (= "obj1" (:objectId(first (:items body)))))
                   (is (included? {:eventtype  "artstor_get_items_with_GET_internal" :dests ["captains-log"] :profileid "100"} logs)))))
      (with-db config
               (testing "Test api/v1/items with internal=false"
                 (let [response (app (-> (mock/request :get "/api/v1/items?object_id=obj1&internal=false")
                                         (mock/header "web-token" web-token)))
                       {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get "/api/v1/items?object_id=obj1&internal=false")
                                                                             (mock/header "web-token" web-token))))
                       raw-body (slurp (:body response))
                       body (cheshire.core/parse-string raw-body true)]
                   (is (= (:status response) 200))
                   (is (= true (:success body)))
                   (is (= 1 (:total body)))
                   (is (= "obj1" (:objectId(first (:items body)))))
                   (is (included? {:eventtype  "artstor_get_items_with_GET" :dests ["captains-log"] :profileid "100"} logs))))))))

(deftest decryption-testing-please-stand-back
  (let [app core/app
        item {:collectionId "10",
              :objectId "obj1",
              :downloadSize "1024,1024",
              :tombstone [],
              :collectionType 1,
              :thumbnailImgUrl "thumbnailImageUrl",
              :objectTypeId 10,
              :clustered 0,
              :largeImgUrl "largeImageUrl",
              :cfObjectId ""},
        obj-data-from-solr {:object_id "obj1" :SSID "SA-MPLE" :contributinginstitutionid 1000 :collections []},
        metadata  [{:object_id "obj1"
                    :object_type_id 10
                    :download_size "1024,1024"
                    :collection_id "123"
                    :collection_name "Collection"
                    :category_id "1234567890"
                    :category_name "cat-test"
                    :collection_type 1
                    :SSID "SA-MPLE"
                    :thumbnail_url "/thumb/hitchiker.jpg"
                    :title "Dinner Dress better than the rest"
                    :metadata_json [{:count 1, :fieldName "Creator", :fieldValue "Reutlinger Studio, 1850-1937", :index 1},
                                    {:count 1, :fieldName "Title", :fieldValue "Dinner Dress by Panem, pub. 'Les Modes Magazine' Halfton Reproduction", :index 1}]
                    :fileProperties [{:fileName "asdfg.fpx"}]
                    :icc_profile_loc "1"
                    :image_url "image-url"
                    :width 3030
                    :height 3030
                    :resolution_x 600
                    :resolution_y 600
                    :collections []
                    :contributinginstitutionid 1000}]]
    (with-redefs [repo/get-metadata-for-object-ids (fn [objectId xml] obj-data-from-solr)
                  repo/get-legacy-metadata-for-object-ids (fn [objectId xml obj-data-from-solr] metadata)
                  repo/find-item (fn [objectId] item)
                  auth/with-legacy (fn [h] ((fn [req] (h req))))]
      (testing "Test decrypting a valid token using valid web-token"
        (let [encrypted-id (util/encrypt "obj1")
              response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id=" encrypted-id))
                                (mock/header "web-token" web-token)))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (true? (body :success)))
          (is (nil? (s/check data/Item (body :item))))
          (is (nil? (s/check [data/Metadata] (body :metadata))))
          (is (= (body :metadata) metadata))
          (is (= (body :imageUrl) "largeImageUrl"))
          (is (= (body :downloadSize) "1024,1024")))))
    (with-redefs [org.ithaka.clj-iacauth.ring.middleware/has-valid-web-token? (fn [_] false)
                  org.ithaka.clj-iacauth.ring.middleware/extract-user-from-session (fn [_ _] nil)]
      (testing "Test decrypting a valid token without web-token or valid clientip or cookie"
        (let [encrypted-id (util/encrypt "obj1")
              response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id=" encrypted-id))))
              raw-body (:body response)]
          (is (= (:status response) 401)))))
    (with-redefs [auth/with-legacy (fn [h] ((fn [req] (h req))))]
      (with-db config
               (testing "Test decrypting a non-existent object_id with valid web-token"
                 (let [encrypted-id (util/encrypt "i-am-not-valid")
                       response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id=" encrypted-id))
                                         (mock/header "web-token" web-token)))]
                   (is (= (:status response) 404))))))))

(deftest test-decrypting-invalid-values
  (with-redefs [auth/with-legacy (fn [h] ((fn [req] (h req))))]
    (let [app core/app]
      (with-db config
               (testing "Test decrypting a null parameter with valid web-token"
                 (let [response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id="))
                                         (mock/header "web-token" web-token)))]
                   (is (= (:status response) 404)))))
      (with-db config
               (testing "Test decrypting a empty string with valid web-token"
                 (let [response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id=''"))
                                         (mock/header "web-token" web-token)))]
                   (is (= (:status response) 404))))))))

(deftest decoding-decryption-testing-ok-hold-your-breath
  (let [app core/app
        item {:collectionId "103",
              :objectId "SS33731_33731_1094662",
              :downloadSize "1024,1024",
              :tombstone [],
              :collectionType 1,
              :thumbnailImgUrl "thumbnailImageUrl",
              :objectTypeId 10,
              :clustered 0,
              :largeImgUrl "largeImageUrl",
              :cfObjectId "SS33731_33731_1094662"},
        metadata  [{:SSID "SA-MPLE"
                    :thumbnail_url "/thumb/hitchiker.jpg"
                    :title "Dinner Dress better than the rest"
                    :object_id "SS33731_33731_1094662"
                    :object_type_id 10
                    :collection_id "123"
                    :collection_type 1
                    :collection_name "Collection"
                    :category_id "1234567890"
                    :category_name "cat-test"
                    :metadata_json [{:count 1, :fieldName "Creator", :fieldValue "Reutlinger Studio, 1850-1937", :index 1},
                                    {:count 1, :fieldName "Title", :fieldValue "Dinner Dress by Panem, pub. 'Les Modes Magazine' Halfton Reproduction", :index 1}]
                    :fileProperties [{:fileName "asdfg.fpx"}]
                    :icc_profile_loc "1"
                    :download_size "1024,1024",
                    :image_url "image-url"
                    :width 3030
                    :height 3030
                    :resolution_x 600
                    :resolution_y 600
                    :collections []
                    :contributinginstitutionid 1000}]]
    (with-redefs [repo/get-legacy-metadata-for-object-ids (fn [objectId xml session-id] metadata)
                  repo/find-item (fn [objectId] item)]
      (with-db config
               (testing "Test decrypting a url-encoded funky token using valid web-token issue seen in PROD-1243"
                 (let [encrypted-id (codec/form-encode  (util/encrypt "SS33731_33731_1094662"))
                       response (app (-> (mock/request :get (str "/api/v1/items/resolve?encrypted_id=" encrypted-id))
                                         (mock/header "web-token" web-token)))
                       raw-body (slurp (:body response))
                       body (cheshire.core/parse-string raw-body true)]
                   (is (= (:status response) 200))
                   (is (true? (body :success)))
                   (is (nil? (s/check data/Item (body :item))))
                   (is (nil? (s/check [data/Metadata] (body :metadata))))
                   (is (= (body :imageUrl) "largeImageUrl"))
                   (is (= (body :downloadSize) "1024,1024"))))))))

(deftest using-id-for-flush
  (let [app core/app]
    (with-db config
             (testing "Test for deleting single item using web-token"
               (let [response (app (-> (mock/request :get "/api/v1/metadata/flush?object_ids=obj1")
                                       (mock/header "web-token" web-token)))
                     raw-body (slurp (:body response))
                     body (cheshire.core/parse-string raw-body true)]
                 (is (= (:status response) 200))
                 (is (= (body :total) 1)))))
    (with-db config
             (testing "Test for deleting multiple item using web-token"
               (let [response (app (-> (mock/request :get "/api/v1/metadata/flush?object_ids=flush_obj1&object_ids=flush_obj2")
                                       (mock/header "web-token" web-token)))
                     raw-body (slurp (:body response))
                     body (cheshire.core/parse-string raw-body true)]
                 (is (= (:status response) 200))
                 (is (= (body :total) 2)))))
    (with-db config
             (testing "Test for invalid item using web-token"
               (let [response (app (-> (mock/request :get "/api/v1/metadata/flush?object_ids=invalid_id")
                                       (mock/header "web-token" web-token)))]
                 (is (= (:status response) 404)))))
    (with-db config
             (with-redefs [org.ithaka.clj-iacauth.ring.middleware/has-valid-web-token? (fn [_] false)
                           org.ithaka.clj-iacauth.ring.middleware/extract-user-from-session (fn [_ _] nil)]
               (testing "Test 401 without cookie or web-token"
                 (let [response (app (-> (mock/request :get "/api/v1/metadata/flush?object_ids=obj1")))]
                   (is (= (:status response) 401))))))))

(deftest testing-metadata-legacy-object-id
  (let [app core/app
        metadata '({:mdString "",
                    :SSID "10707836",
                    :editable false,
                    :objectId "AWSS35953_35953_35410117",
                    :fileProperties [{:fileName "asdfg.fpx"}]
                    :title "Horse",
                    :imageUrl "/thumb/imgstor/size0/sslps/c35953/10707836.jpg",
                    :metaData ({:celltype "",
                                :count 1,
                                :fieldName "Creator",
                                :fieldValue "Sara Woster",
                                :index 1,
                                :link "",
                                :textsize 1,
                                :tooltip "" },
                                {:celltype "",
                                 :count 1,
                                 :fieldName "Title",
                                 :fieldValue "Horse",
                                 :index 0,
                                 :link "",
                                 :textsize 1,
                                 :tooltip ""},
                                {:celltype "",
                                 :count 1,
                                 :fieldName "Work Type",
                                 :fieldValue "painting",
                                 :index 0,
                                 :link "",
                                 :textsize 1,
                                 :tooltip ""})})]
    (with-redefs [tokens/get-eme-tokens (fn [_] ["12345678901"])
                  repo/get-allowed-assets (fn [_ _] ["AWSS35953_35953_35410117"])
                  repo/get-legacy-formatted-metadata (fn [_ _] metadata)]
      (with-db config
               (testing "Test call to replace /secure/metadata from artstor-earth-library "
                 (let [object-id "AWSS35953_35953_35410117"
                       response (app (-> (mock/request :get (str "/api/v1/metadata/legacy/" object-id))
                                         (mock/header "web-token" web-token)))
                       raw-body (slurp (:body response))
                       body (cheshire.core/parse-string raw-body true)]
                   (println "....body=" body)
                   (is (= (:status response) 200))
                   (is (= "10707836" (body :SSID)))
                   (is (= (body :fileProperties) [{:fileName "asdfg.fpx"}]))
                   (is (= "/thumb/imgstor/size0/sslps/c35953/10707836.jpg" (body :imageUrl)))
                   (is (= "Horse" (body :title)))
                   (is (= "AWSS35953_35953_35410117" (body :objectId)))))))))

(deftest testing-metadata-legacy-object-id-no-ssid
  (let [app core/app
        metadata '({:mdString "",
                    :SSID "",
                    :editable false,
                    :objectId "LESSING_ART_10310752347",
                    :fileProperties [{:fileName "asdfg.fpx"}]
                    :title "Lady with an Ermine",
                    :imageUrl "/thumb/imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg",
                    :metaData ({:celltype "",
                                :count 1,
                                :fieldName "Creator",
                                :fieldValue "Leonardo De Caprio?",
                                :index 1,
                                :link "",
                                :textsize 1,
                                :tooltip "" },
                                {:celltype "",
                                 :count 1,
                                 :fieldName "Title",
                                 :fieldValue "Lady with an Ermine",
                                 :index 0,
                                 :link "",
                                 :textsize 1,
                                 :tooltip ""},
                                {:celltype "",
                                 :count 1,
                                 :fieldName "Work Type",
                                 :fieldValue "movie",
                                 :index 0,
                                 :link "",
                                 :textsize 1,
                                 :tooltip ""})})]
    (with-redefs [tokens/get-eme-tokens (fn [request] ["12345678901"])
                  repo/get-allowed-assets (fn [object_ids eme-tokens] ["LESSING_ART_10310752347"])
                  repo/get-legacy-formatted-metadata (fn [objectId xml] metadata)]
      (with-db config
               (testing "Test call to replace /secure/metadata from artstor-earth-library no ssid"
                 (let [object-id "LESSING_ART_10310752347"
                       response (app (-> (mock/request :get (str "/api/v1/metadata/legacy/" object-id))
                                         (mock/header "web-token" web-token)))
                       raw-body (slurp (:body response))
                       body (cheshire.core/parse-string raw-body true)]
                   (is (= (:status response) 200))
                   (is (= "" (body :SSID)))
                   (is (= (body :fileProperties) [{:fileName "asdfg.fpx"}]))
                   (is (= "/thumb/imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg" (body :imageUrl)))
                   (is (= "Lady with an Ermine" (body :title)))
                   (is (= "LESSING_ART_10310752347" (body :objectId)))))))))

(deftest testing-metadata-legacy-object-id-not-logged-in
  (let [app core/app]
    (with-redefs [tokens/get-eme-tokens (fn [request] "")
                  repo/get-allowed-assets (fn [object_ids eme-tokens] [])
                  repo/get-legacy-formatted-metadata (fn [objectId xml] {})
                  repo/build-legacy-metadata-using-tokens (fn [object_ids xml eme-tokens] [])
                  repo/populate-metadata-cache (fn [ids] nil)]
      (with-db config
               (testing "Test call to replace /secure/metadata from artstor-earth-library "
                 (let [object-id "SS35495_35495_22720454"
                       response (app (-> (mock/request :get (str "/api/v1/metadata/legacy/" object-id))
                                         (mock/header "web-token" web-token)))]
                   (is (= (:status response) 403)))))
      (with-db config
               (testing "Test call to internal/v1/metadata from internal users"
                 (let [object-id "SS35495_35495_22720454"
                       response (app (-> (mock/request :get (str "/internal/v1/metadata?object_ids=" object-id))
                                         ))]
                   (is (= (:status response) 200))))))))

(deftest test-legacy-search
  (let [app core/app]
    (with-db config
             (testing "Test mock response for legacy search"
               (let [response (app (-> (mock/request :get (str "/legacy_search"))
                                       (mock/header "web-token" web-token)))
                     raw-body (slurp (:body response))
                     body (cheshire.core/parse-string raw-body true)]
                 (println (string/starts-with? (body :altKey) "This call is no longer supported"))
                 (is (string/starts-with? (body :altKey) "This call is no longer supported"))
                 (is (= (:status response) 200)))))))

(defn generate-one-metadata [object-id]
  {
   :resolution_x 600,
   :object_id object-id,
   :collection_id "103",
   :object_type_id 10,
   :collection_type 1
   :category_id "1234567890"
   :category_name "cat-test"
   :width 4096,
   :SSID "SA-MPLE"
   :fileProperties [{:fileName "asdfg.fpx"}]
   :thumbnail_url "/thumb/hitchiker.jpg"
   :title "Dinner Dress better than the rest"
   :metadata_json [{:count 1, :fieldName "Creator", :fieldValue "Reutlinger Studio, 1850-1937", :index 1},
                   {:count 1, :fieldName "Title", :fieldValue "Dinner Dress by Panem, pub. 'Les Modes Magazine' Halfton Reproduction", :index 1}]
   :collection_name "Artstor Collections",
   :image_url "artonfile/db/abudhabi-25-4_8b_srgb.fpx/KDUH8bqgZl_9sFByv4GyQQ/1509720778/",
   :icc_profile_loc nil,
   :resolution_y 600,
   :height 6144,
   :download_size "1024,1024",
   :collections [],
   :contributinginstitutionid 1000
   }
  )
(deftest test-metadata-legacy
  (let [app core/app]
    (with-redefs [org.ithaka.clj-iacauth.token/get-eme-tokens (fn [_] ["123"])
                  repo/build-legacy-metadata-using-tokens (fn [_ _ _] [(generate-one-metadata "object1")])
                  auth/with-legacy (fn [h] (h {}))
                  org.ithaka.clj-iacauth.ring.middleware/has-valid-web-token? (fn [_] false)
                  org.ithaka.clj-iacauth.ring.middleware/extract-user-from-session (fn [_ _] [{} nil])]

      (testing "Test call to legacy metadata with public tokens only"
        (let [object-id "object1"
              response (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=" object-id))))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)
              mo (first (:metadata body))]
          (is (= (:status response) 200))
          (is (= "SA-MPLE" (mo :SSID)))
          (is (= (mo :fileProperties) [{:fileName "asdfg.fpx"}]))
          (is (= "artonfile/db/abudhabi-25-4_8b_srgb.fpx/KDUH8bqgZl_9sFByv4GyQQ/1509720778/" (mo :image_url)))
          (is (= "Dinner Dress better than the rest" (mo :title)))
          (is (= "object1" (mo :object_id))))))))

(deftest testing-group-maximum-flag
  (let [app core/app
        megagroup '{:status 200
                    :id "689140",
                    :name "01. Architecture and the Built Environment",
                    :description "<div align=\"right\"><div align=\"left\"><font face=\"Arial\"><font size=\"3\"><b>Architecture and the Built Environment </b></font></font><p></p><font face=\"Arial\"><font size=\"3\">This selection provides a survey of the great works of architecture across the continents and through time from the pyramids of ancient Egypt to the towers of contemporary Dubai. In addition to the iconic architectural sites, the group includes phenomena of engineering and technology:  a Roman aqueduct, Stonehenge and a wind farm, for example.  <p></p>Photographic views of monuments as diverse as the Dome of the Rock, a Palladian villa, St. Petersburg’s Palace Square, the Great Wall of China and the Brooklyn Bridge are supplemented by visionary drawings, prints and QTVR (QuickTime Virtual Reality) panoramas that offer virtual access to <font size=\"2\">celebrated spaces.<p></p>See: Working with QTVR Files: <p style=\"margin:0\"></p><a href=\"http://support.artstor.org/?article=thumbnail-icons#QuickTime_Virtual_Reality_file_icon\" target=\"_blank\">http://support.artstor.org/?article=thumbnail icons#QuickTime_Virtual_Reality_file_icon</a></font><p style=\"margin:0\"></p> </font></font><p style=\"margin:0\"></p></div><font face=\"Arial\"><font size=\"3\">     <p style=\"margin:0\"></p></font></font><p style=\"margin:0\"></p></div>",
                    :sequence_number 1,
                    :access [{:entity_type 200,
                              :entity_identifier "1000",
                              :access_type 100}],
                    :public true,
                    :items ["ARTONFILE_DB_10313202936", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "HUNT_50007", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "AMAGNUMIG_10311508227", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "HARTILL_12322514", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "ARTONFILE_DB_10313203903", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "ARTONFILE_DB_10313203903", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30",
                            "ARTONFILE_DB_10310485363", "1","2","3","4","5","6","7","8","9","10","11","12","13","14","15","16","17","18","19","20","21","22","23","24","25","26","27","28","29","30"],
                    :tags ["Teaching Resources",
                           "Art and Architecture Overviews",
                           "Art and Architecture, Themes"]}]
    (with-redefs [repo/build-legacy-metadata-using-tokens (fn [object_ids xml eme-tokens] (map #(generate-one-metadata %) object_ids))
                  repo/get-group-by-id (fn [_ _ _] megagroup)
                  tokens/get-eme-tokens (fn [_] "12345678901")]
      (testing "Test call to get group using maximum flag"
        (let [group-id "689140"
              response (app (-> (mock/request :get (str "/api/v1/metadata/group/" group-id "?maximize=true"))
                                (mock/header "web-token" web-token)))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (= (count (:metadata body)) 217))))
      (testing "Test call to get group using default 150 maximum"
        (let [group-id "689140"
              response (app (-> (mock/request :get (str "/api/v1/metadata/group/" group-id))
                                (mock/header "web-token" web-token)))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (= (count (:metadata body)) 150))))
      (testing "Test logging for call to get group metadata without internal flag"
        (let [group-id "689140"
              {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get (str "/api/v1/metadata/group/" group-id))
                                                                    (mock/header "web-token" web-token))))]
          (is (included? {:eventtype  "artstor_get_metadata_for_group" :dests ["captains-log"] :profileid "123456"} logs))))
      (testing "Test logging call to get metadata with internal true"
        (let [group-id "689140"
              {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get (str "/api/v1/metadata/group/" group-id "?internal=true"))
                                                                    (mock/header "web-token" web-token))))]
          (is (included? {:eventtype  "artstor_get_metadata_for_group_internal" :dests ["captains-log"] :profileid "123456"} logs)))))))

(deftest test-get-metadata-with-internal
  (let [app core/app]
    (with-redefs [logger/log* (fn [_ _ _ message] (println "Test Log Message=" message))
                  tokens/get-eme-tokens (fn [_] "12345678901")
                  repo/build-legacy-metadata-using-tokens (fn [object_ids _ _] (map #(generate-one-metadata %) object_ids))
                  auth/with-legacy (fn [h] ((fn [req] (h req))))]
      (testing "Test call to get metadata without internal flag"
        (let [response (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1"))
                                (mock/header "web-token" web-token)))
              {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1"))
                                                                    (mock/header "web-token" web-token))))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (= (count (:metadata body)) 1))
          (is (included? {:eventtype  "artstor_get_metadata" :dests ["captains-log"] :profileid "123456"} logs))))
      (testing "Test call to get metadata with internal true"
        (let [response (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1&object_ids=obj2&internal=true"))
                                (mock/header "web-token" web-token)))
              {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1&object_ids=obj2&internal=true"))
                                                                    (mock/header "web-token" web-token))))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (= (count (:metadata body)) 2))
          (is (included? {:eventtype  "artstor_get_metadata_internal" :dests ["captains-log"] :profileid "123456"} logs))))
      (testing "Test call to get metadata with internal false"
        (let [response (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1&object_ids=obj2&internal=false"))
                                (mock/header "web-token" web-token)))
              {:keys [logs _]} (cptlog/collect-parsed-logs (app (-> (mock/request :get (str "/api/v1/metadata?object_ids=obj1&object_ids=obj2&internal=false"))
                                                                    (mock/header "web-token" web-token))))
              raw-body (slurp (:body response))
              body (cheshire.core/parse-string raw-body true)]
          (is (= (:status response) 200))
          (is (= (count (:metadata body)) 2))
          (is (included? {:eventtype  "artstor_get_metadata" :dests ["captains-log"] :profileid "123456"} logs)))))))