(ns artstor-metadata-service-os.repository-test
  (:require [artstor-metadata-service-os.schema :refer :all]
            [artstor-metadata-service-os.repository :as repo]
            [artstor-metadata-service-os.tokens :as tokens]
            [artstor-metadata-service-os.user :as user]
            [artstor-metadata-service-os.util :as util]
            [artstor-metadata-service-os.auth :as auth]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [clojure.string :as string]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clj-time.coerce :as tc]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [ragtime.jdbc :as rag]
            [ragtime.repl :refer [migrate rollback]]
            [environ.core :refer [env]]
            [schema.core :as s])
  (:import (java.sql Clob)
           (java.io InputStream InputStreamReader ByteArrayInputStream)))

(def config {:datastore (rag/sql-database {:connection-uri (env :artstor-metadata-db-url)})
             :migrations (rag/load-resources "test-migrations")})

(defmacro with-db [conf & body]
  `(do
     (migrate ~conf)
     (try
       ~@body
       (finally
         (rollback ~conf "001")))))

(def web-token (auth/generate-web-token 123456 1000 false "qa@artstor.org"))

(deftest test-finding-an-item
  (with-db config
           (testing "Finding an existing item returns the item"
             (let [rec (repo/find-item "obj1")]
               (is (nil? (s/check Item rec))))))
  (with-db config
           (testing "Finding an existing item returns the item"
             (let [rec (repo/find-item "SS33731_33731_1094662")]
               (is (nil? (s/check Item rec))))))
  (with-db config
           (testing "Trying to find a non-existent item returns nil"
             (let [rec (repo/find-item "not-an-item")]
               (is (nil? rec))))))

(deftest test-resolving-thumbnails
  (with-redefs [repo/sql-retrieve-items (fn [_] [{:cfobjectid "",
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
                                                  :clustered 0
                                                  }])]
    (testing "Resolving a valid thumbnail request"
      (let [recs (repo/find-items ["obj1"])]
        (is (= 1 (count recs)))
        (is (nil? (s/check Item (first recs)))))))
  (with-redefs [repo/sql-retrieve-items (fn [_] [{:cfobjectid "",
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
                                                  :clustered 0
                                                  }
                                                 {:cfobjectid "",
                                                  :thumbnail2 "",
                                                  :collectionid "103",
                                                  :thumbnailimgurl "imgstor/size0/thumb_image_url.jpg",
                                                  :largeimgurl "imgstor/size1/large_image_url.jpg",
                                                  :objecttypeid 10,
                                                  :thumbnail3 "thumbnail3",
                                                  :thumbnail1 "thumbnail1"
                                                  :downloadsize "1024,1024",
                                                  :objectid "obj2",
                                                  :collectiontype 1,
                                                  :clustered 0
                                                  }])]
    (testing "Resolving a valid thumbnail request using two obj-ids"
      (let [recs (repo/find-items ["obj1" "obj2"])]
        (is (= 2 (count recs)))
        (is (= "obj2" ((second recs) :objectId)))
        (is (= "available" ((first recs) :status)))
        (is (= "available" ((last recs) :status)))
        (is (nil? (s/check Item (first recs))))))
    (testing "Resolving a valid thumbnail request using two available obj-ids and one invalid object-id"
      (let [recs (repo/find-items ["obj3" "obj2" "obj1"])]
        (is (= 3 (count recs)))
        (is (= "not-available" ((first recs) :status)))
        (is (= "available" ((second recs) :status)))
        (is (= "available" ((last recs) :status)))))))

(defn make-clob [str-value]
  (reify Clob (getCharacterStream [this]
                (InputStreamReader. (ByteArrayInputStream. (.getBytes str-value))))))

(deftest test-building-legacy-metadata
  (with-redefs [repo/populate-metadata-cache (fn [_] _)]
    (testing "Get metadata for QTVR asset"
      (with-redefs [repo/sql-get-qtvr-asset-image-url (fn [_] [{:image_url "qtvr-stor-url"}])
                    repo/sql-get-metadata (fn [_] [{:object_id     "obj1"
                                                    :object_type_id 11
                                                    :download_size "1024,1024"
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :collection_name "Collection"
                                                    :icc_profile_loc nil
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}])
                    repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1"] false [{:object_id "obj1" :SSID "12345" :contributinginstitutionid 1000}])]
          (is (= 1 (count recs)))
          (is (= {:base_asset_url "qtvr-stor-url", :panorama_xml "qtvr-stor-url/viewer/xml"} ((first recs) :viewer_data)))
          (is (nil? (s/check Metadata (first recs))))
          (is (false? (contains? (first recs) :xml_data))))))
    (testing "Get metadata with without XML"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "obj1"
                                                    :object_type_id 10
                                                    :download_size "1024,1024"
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :collection_name "Collection"
                                                    :icc_profile_loc nil
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600 }])
                    repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1"] false [{:object_id "obj1" :SSID "12345" :contributinginstitutionid 1000}])]
          (is (= 1 (count recs)))
          (is (nil? (s/check Metadata (first recs))))
          (is (false? (contains? (first recs) :xml_data))))))
    (testing "Get metadata with XML"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "obj1"
                                                    :object_type_id 10
                                                    :download_size "1024,1024"
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :collection_name "Collection"
                                                    :icc_profile_loc nil
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}])
                    repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1"] true [{:object_id "obj1" :SSID "12345" :contributinginstitutionid 0}])]
          (is (= 1 (count recs)))
          (is (nil? (s/check Metadata (first recs))))
          (is (true? (contains? (first recs) :xml_data))))))
    (testing "Get metadata with XML and icc_profile_loc"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "obj1"
                                                    :object_type_id 10
                                                    :download_size "1024,1024"
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :collection_name "Collection"
                                                    :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}])
                    repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1"] true [{:object_id "obj1" :SSID "12345" :contributinginstitutionid 0}])]
          (is (= 1 (count recs)))
          (is (nil? (s/check Metadata (first recs))))
          (is (true? (contains? (first recs) :xml_data))))))
    (testing "Ensure multiples items are returned in the order requested"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "obj2" :object_type_id 10 :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}
                                                   {:object_id     "obj1" :object_type_id 10 :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}
                                                   {:object_id     "obj3" :object_type_id 10 :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                    :collection_id "103"
                                                    :collection_type 1
                                                    :xml_data (make-clob "<name>will</name>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :resolution_x  600 :resolution_y 600}])
                    repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1" "obj2" "obj3"] false [{:object_id "obj1" :SSID "12345" :contributinginstitutionid 0} {:object_id "obj2" :SSID "67890" :contributingInstitutionId 0} {:object_id "obj3" :SSID "109283" :contributingInstitutionId 0}])]
          (is (= 3 (count recs)))
          (is (= "obj1" ((first recs) :object_id)))
          (is (= "obj2" ((second recs) :object_id)))
          (is (= "obj3" ((nth recs 2) :object_id))))))
    (testing "Empty SQL result returns no records"
      (with-redefs [repo/sql-get-metadata (fn [_] [])]
        (let [recs (repo/get-legacy-metadata-for-object-ids ["obj1"] false {:object_id "obj1" :SSID "12345" :contributinginstitutionid 0})]
          (is (= 0 (count recs))))))))


(defspec generates-correct-legacy-data-entries
         25
         (prop/for-all [field-name gen/string
                        list-o-strings (gen/list (gen/such-that   #(> 0 (.indexOf % "|" ))  gen/string))]
                       (let [res (repo/generate-legacy-entry field-name list-o-strings)]
                         (is (= (get (first res) :count 0) (count list-o-strings)))
                         (if (not (empty? res))
                           (do
                             (is (= (get (first res) :index) 1))
                             (is (= (get (first res) :fieldValue) (string/trim (first list-o-strings))))
                             (is (= (get (first res) :fieldName) field-name))
                             (is (= (get (last res) :index) (count list-o-strings)))
                             (is (= (get (last res) :fieldValue) (string/trim (last list-o-strings)))))
                           true))))

(deftest builds-fpx-urls
  (testing "Building fpx links from the media record"
    (let [media {"lps" "my-lps" "thumbnailSizeOnePath" "/weird/path/earth.jpg"}
          res (repo/build-fpx-url media)]
      (is (string/starts-with? res "my-lps/earth.fpx")))
    (let [media {"lps" "lps" "thumbnailSizeOnePath" "/path/to/earth.jpg.jpg"}
          res (repo/build-fpx-url media)]
      (is (string/starts-with? res "lps/earth.jpg.fpx")))
    (let [media {}
          res (repo/build-fpx-url media)]
      (is (string/starts-with? res "/")))))

(deftest convert-to-schema
  (with-redefs [env {:secret "swiss cheese"}
                tc/to-long (fn [_] 1507501300112)]
    (testing "Handles blank media and rights"
      (let [ang-record {:artstorid 123 :media "{}" :rights "{}"}
            converted-record (repo/convert-to-schema ang-record false)]
        (is (= {:SSID "" :fileProperties [{:fileName nil}] :object_id 123 :object_type_id 10 :icc_profile_loc nil :width 1024 :height 1024
                :resolution_x 600 :resolution_y 600 :download_size "1024,1024" :contributinginstitutionid 1000 :updated_on "" :collections []
                :image_url    "/AlHuEoLBqnB1V1UamOMeRw/1507504900/" :thumbnail_url "/thumb/" :title "" :category_id "":category_name ""
                :collection_id  "" :collection_name "" :collection_type 0} (dissoc converted-record :metadata_json)))))
    (testing "Pulls width and height from media"
      (let [ang-record {:artstorid 456 :media (json/encode {:width 9999 :height 9999 :lps "lps"
                                                            :thumbnailSizeOnePath "/path/file.jpg"})}
            converted-record (repo/convert-to-schema ang-record false)]
        (is (= {:SSID "" :fileProperties [{:fileName "file.fpx"}] :object_id 456 :object_type_id 10 :icc_profile_loc nil :width 9999 :height 9999
                :resolution_x 600 :resolution_y 600 :download_size "1024,1024" :contributinginstitutionid 1000 :updated_on "" :collections []
                :image_url    "lps/file.fpx/oBkKM01Y9W8nXSdD0Wa1lw/1507504900/" :thumbnail_url "/thumb/" :title "" :category_id "":category_name ""
                :collection_id  "" :collection_name "" :collection_type 0} (dissoc converted-record :metadata_json)))))
    (testing "Extracts rights correctly from encoded rights block"
      (let [ang-record {:artstorid 123 :additional_Fields {:rights (json/encode {:record ["One" "Two" "Three"]})}}
            converted-record (repo/convert-to-schema ang-record false)
            metadata (get converted-record :metadata_json)]
        (is (= (count metadata) 3))
        (is (= (get (first metadata) :fieldValue) "One"))
        (is (= (get (last metadata) :fieldValue) "Three"))))
    (testing "Extracts rights in the presence of invalid data"
      (let [ang-record {:artstorid 123  :additional_Fields {:rights (json/encode {:record ["One" nil "Three"]})}}
            converted-record (repo/convert-to-schema ang-record false)
            metadata (get converted-record :metadata_json)]
        (is (= (count metadata) 2))
        (is (= (get (first metadata) :fieldValue) "One"))
        (is (= (get (last metadata) :fieldValue) "Three"))))
    (testing "Extracts personalcollectionowner for valid pc asset"
      (let [ang-record {:artstorid 123 :additional_Fields {:personalcollectionowner 123456}}
            converted-record (repo/convert-to-schema ang-record false)
            pc-owner (get converted-record :personalCollectionOwner)]
        (is (= pc-owner 123456))))))

(deftest generate-xml-from-ang-record
  (testing "Generates XML from good data"
    (let [data {:agent ["agent"] :artculture ["culture"] :arttitle ["title"] :artworktype ["work type"] :artdate ["date"]
                :artlocation ["location"] :artmaterial ["material"] :artstyleperiod ["style"]
                :artmeasurements ["measurements"] :description ["description"] :artcurrentrepository ["repository"]
                :artrelation ["relation"] :artsubject ["subject"] :artcollectiontitle ["collection title"]
                :artidnumber ["id num"] :artsource ["source"] :artcurrentrepositoryidnumber ["accession"]}
          mapped-data (repo/remap-data data)
          xml (repo/build-xml mapped-data)]
      (is (= (str "<Image><MetaData><Creator>agent</Creator><Culture>culture</Culture>"
                  "<Title>title</Title><Work_Type>work type</Work_Type><Date>date</Date>"
                  "<Location>location</Location><Material>material</Material><Period>style</Period>"
                  "<Measurements>measurements</Measurements><Description>description</Description>"
                  "<Repository>repository</Repository><Accession_Number>accession</Accession_Number><Related_Item>relation</Related_Item>"
                  "<Subject>subject</Subject><Collection>collection title</Collection>"
                  "<ID_Number>id num</ID_Number><Source>source</Source></MetaData></Image>") xml))))
  (testing "Generates mostly empty XML in the absence of data"
    (let [data {}
          mapped-data (repo/remap-data data)
          xml (repo/build-xml mapped-data)]
      (is (= (str "<Image><MetaData></MetaData></Image>") xml)))))

(deftest remap-data
  (testing "Remaps data correctly in the presence of good data"
    (let [data {:agent ["agent"] :artculture ["culture"] :arttitle ["title"] :artworktype ["work type"] :artdate ["date"]
                :artlocation ["location"] :artmaterial ["material"] :artstyleperiod ["style"]
                :artmeasurements ["measurements"] :description ["description"] :artcurrentrepository ["repository"]
                :artrelation ["relation"] :artsubject ["subject"] :artcollectiontitle ["collection title"]
                :artidnumber ["id num"] :artsource ["source"] :artcurrentrepositoryidnumber ["accession"]}
          mapped-data (repo/remap-data data)]
      (is (= {:count 1 :fieldName "Creator" :fieldValue "agent" :index 1} (first mapped-data)))
      (is (= {:count 1 :fieldName "Culture" :fieldValue "culture" :index 1} (second mapped-data)))
      (is (= {:count 1 :fieldName "Title" :fieldValue "title" :index 1} (nth mapped-data 2)))
      (is (= {:count 1 :fieldName "Work Type" :fieldValue "work type" :index 1} (nth mapped-data 3)))
      (is (= {:count 1 :fieldName "Date" :fieldValue "date" :index 1} (nth mapped-data 4)))
      (is (= {:count 1 :fieldName "Location" :fieldValue "location" :index 1} (nth mapped-data 5)))
      (is (= {:count 1 :fieldName "Material" :fieldValue "material" :index 1} (nth mapped-data 6)))
      (is (= {:count 1 :fieldName "Period" :fieldValue "style" :index 1} (nth mapped-data 7)))
      (is (= {:count 1 :fieldName "Measurements" :fieldValue "measurements" :index 1} (nth mapped-data 8)))
      (is (= {:count 1 :fieldName "Description" :fieldValue "description" :index 1} (nth mapped-data 9)))
      (is (= {:count 1 :fieldName "Repository" :fieldValue "repository" :index 1} (nth mapped-data 10)))
      (is (= {:count 1 :fieldName "Accession Number" :fieldValue "accession" :index 1} (nth mapped-data 11)))
      (is (= {:count 1 :fieldName "Related Item" :fieldValue "relation" :index 1} (nth mapped-data 12)))
      (is (= {:count 1 :fieldName "Subject" :fieldValue "subject" :index 1} (nth mapped-data 13)))
      (is (= {:count 1 :fieldName "Collection" :fieldValue "collection title" :index 1} (nth mapped-data 14)))
      (is (= {:count 1 :fieldName "ID Number" :fieldValue "id num" :index 1} (nth mapped-data 15)))
      (is (= {:count 1 :fieldName "Source" :fieldValue "source" :index 1} (last mapped-data)))))
  (testing "Remaps data in the presence of sparse data"
    (let [data {:agent ["agent"] :artsource ["source"]}
          mapped-data (repo/remap-data data)]
      (is (= {:count 1 :fieldName "Creator" :fieldValue "agent" :index 1} (first mapped-data)))
      (is (= {:count 1 :fieldName "Source" :fieldValue "source" :index 1} (last mapped-data))))))

(deftest test-create-fields-for-artadditionalfields
  (testing "artadditionalfields from solr are tested for kv pairs"
    (let [r1 (repo/create-fields-for-artadditionalfields "{}")
          r2 (repo/create-fields-for-artadditionalfields "{\"Keywords\":[\"carving\"],\"Digital Collection\":[\"The Yale Indo-Pacific collection\"],\"Notes\":[\"Original format and photographic equipment: Slide. Nikon FM. Roll 20, Frame 23. August 29, 1996\"]}")
          r3 (repo/create-fields-for-artadditionalfields "{\"Provenance\":[\"Album compiled and maintained as part of the collection of Charles William Wason.\"]}")]
      (is (empty? r1))
      (is (= r2 '({:count 1, :fieldName "Keywords", :fieldValue "carving", :index 1}
                   {:count 1, :fieldName "Digital Collection", :fieldValue "The Yale Indo-Pacific collection", :index 1}
                   {:count 1, :fieldName "Notes", :fieldValue "Original format and photographic equipment: Slide. Nikon FM. Roll 20, Frame 23. August 29, 1996", :index 1}))
          (is (= r3 '({:count 1, :fieldName "Provenance", :fieldValue "Album compiled and maintained as part of the collection of Charles William Wason.", :index 1})))))))

(defspec calls-search-service-with-object-ids 25
         (prop/for-all [list-o-ids (gen/list gen/string-ascii)]
                       (with-redefs [util/build-service-url (fn [_ _] "search-service")
                                     http/post (fn [_ opts]
                                                 (is (= ((opts :form-params) :limit) (count list-o-ids)))
                                                 (is (= ((opts :form-params) :filter_query)
                                                        [(str "artstorid:(" (string/join " OR " list-o-ids) ")")])))
                                     http/get (fn [_,_])]
                         (repo/get-metadata-for-object-ids list-o-ids false "12345678901"))))

(deftest test-getting-allowable-assets
  (testing "call search with object-ids and valid tokens"
    (let [allowed-object-ids ["obj1"]]
      (with-redefs [repo/get-allowed-assets (fn [object-ids session-id] allowed-object-ids)]
        (is (= ["obj1"] (repo/get-allowed-assets ["obj1" "obj2"] "12345678901")))))))

(deftest test-get-group-by-id
  (testing "Call group service with group-id using token"
    (let [test-data {:total 1
                     :groups [{:id "group-123" :name "Another group" :description "A tasty new group" :sequence_number 1
                               :access []
                               :items ["objectid1" "objectid2"]
                               :tags ["tag1" "tag2" "tag3"]}]}]
      (with-redefs [util/build-service-url (fn [nm path] (str "http://" nm "/" path))
                    http/get (fn [url options] {:body (json/encode test-data)
                                                :status 200})]
        (let [group-json (repo/get-group-by-id "" web-token "group-123")]
          (is (string/includes? group-json "objectid1"))
          (is (string/includes? group-json "tag1"))))))
  (testing "Call group service with group-id without permissions"
    (let [test-data {:total 0}]
      (with-redefs [util/build-service-url (fn [nm path] (str "http://" nm "/" path))
                    http/get (fn [url options] {:body (json/encode test-data)
                                                :status 403})]
        (let [response (repo/get-group-by-id "qa" "" "group-123")]
          (is (= 403 (:status response))))))))

(deftest test-get-allowed-assets
  (testing "get allowed assets from search service"
    (let [post-response {:body {:results [{:artstorid "obj1"} {:artstorid "obj2"}]}}]
      (with-redefs [util/build-service-url (fn [nm path] (str "http://" nm "/" path))
                    http/post (fn[url form] post-response)]
        (let [allowed-assets (repo/get-allowed-assets ["obj1" "obj2"] "12345678901")]
          (is (= ["obj1" "obj2"] allowed-assets)))
        (let [allowed-assets (repo/get-allowed-assets ["obj1" "obj2" "obj3"] "12345678901")]
          (is (= ["obj1" "obj2"] allowed-assets)))))))

(deftest test-build-items-using-tokens
  (testing "building items using eme tokens and generating missing items"
    (let [object-ids ["obj1" "obj2" "obj3"]
          allowed-objs ["obj1" "obj3"]]
      (with-redefs [org.ithaka.clj-iacauth.core/get-session-id (fn [session-txt] "session-id")
                    user/extract-user (fn [request] {:profile_id 100})
                    tokens/get-eme-tokens (fn [request] "12345678901")
                    repo/get-allowed-assets (fn [object-ids eme-tokens] allowed-objs)
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
                                                      :clustered 0
                                                      }
                                                     {:cfobjectid "",
                                                      :thumbnail2 "",
                                                      :collectionid "103",
                                                      :thumbnailimgurl "imgstor/size0/thumb_image_url.jpg",
                                                      :largeimgurl "imgstor/size1/large_image_url.jpg",
                                                      :objecttypeid 10,
                                                      :thumbnail3 "thumbnail3",
                                                      :thumbnail1 "thumbnail1"
                                                      :downloadsize "1024,1024",
                                                      :objectid "obj3",
                                                      :collectiontype 1,
                                                      :clustered 0
                                                      }])]
        (with-db config
                 (let [recs (repo/build-items-using-tokens object-ids "blah-blah-req")]
                   (is (= 3 (count recs)))
                   (is (nil? (s/check Item (first recs))))
                   (is (nil? (s/check Item (second recs))))
                   (is (nil? (s/check Item (last recs))))))))))

(deftest test-extract-legacy-style-xml-metadata
  (testing "test the legacy xml extractor with SSID"
    (let [xml-string "<Image><Name>\"Bananas\"</Name><SSID>1234567</SSID></Image>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-string)))
          zipped-data (zip/xml-zip xml-parsed)
          found-ssid (repo/extract-ssid-from-xml zipped-data)]
      (is (= "1234567" found-ssid))))
  (testing "test the legacy xml extractor without SSID"
    (let [xml-string "<Image><Name>\"No Bananas\"</Name><SSID></SSID></Image>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-string)))
          zipped-data (zip/xml-zip xml-parsed)
          found-ssid (repo/extract-ssid-from-xml zipped-data)]
      (is (= "" found-ssid))))
  (testing "test the legacy xml extractor with invalid metadata xml"
    (let [xml-string "<Junk><Bunch>\"No Bananas\"</Bunch><Hiss></Hiss></Junk>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-string)))
          zipped-data (zip/xml-zip xml-parsed)
          found-ssid (repo/extract-ssid-from-xml zipped-data)]
      (is (= "" found-ssid)))))

(deftest test-get-legacy-formatted-metadata
  (with-redefs [repo/populate-metadata-cache (fn [_] _)]
    (testing "test get-legacy-formatted-metadata with SSID"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "AWSS35953_35953_35410117"
                                                    :object_type_id 10
                                                    :download_size "1024,1024"
                                                    :collection_id "123"
                                                    :collection_name "Collection"
                                                    :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"count\":1,\"fieldName\":\"Creator\",\"fieldValue\":\"Sara Woster\",\"index\":1},{\"count\":1,\"fieldName\":\"Title\",\"fieldValue\":\"Horse\"},{\"count\":1,\"fieldName\":\"Work Type\",\"fieldValue\":\"painting\"}, {\"count\":1, \"fieldName\":\"Collection\", \"fieldValue\":\"collection-name-update-test\", \"index\":1}, {\"count\":2,\"fieldName\":\"Source\",\"fieldValue\":\"Some Editor\"}, {\"count\":2,\"fieldName\":\"Source\",\"fieldValue\":\"someditor@hismail.com\"}]")
                                                    :xml_data (make-clob "<Image><Name>\"Goofy\"</Name><SSID>10707836</SSID></Image>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :thumbnailimgurl "imgstor/size0/sslps/c35953/10707836.jpg"
                                                    :resolution_x  600 :resolution_y 600}])]
        (let [object-id "AWSS35953_35953_35410117"
              expected-metadata '({:mdString "",
                                   :SSID "10707836",
                                   :editable false,
                                   :objectId "AWSS35953_35953_35410117",
                                   :fileProperties [{:fileName "image"}]
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
                                                :tooltip ""},
                                               {:celltype "",
                                                :count 1,
                                                :fieldName "Collection",
                                                :fieldValue "collection-name-update-test",
                                                :index 1,
                                                :link "",
                                                :textsize 1,
                                                :tooltip "" },
                                               {:celltype "",
                                                :count 2,
                                                :fieldName "Source",
                                                :fieldValue "Some Editor",
                                                :index 0,
                                                :link "",
                                                :textsize 1,
                                                :tooltip "" })})
              found-metadata (repo/get-legacy-formatted-metadata object-id false)]
          (is (= expected-metadata found-metadata)))))
    (testing "test get-legacy-formatted-metadata without SSID"
      (with-redefs [repo/sql-get-metadata (fn [_] [{:object_id     "LESSING_ART_10310752347"
                                                    :object_type_id 10
                                                    :download_size "1024,1024"
                                                    :collection_id "123"
                                                    :collection_name "Collection"
                                                    :icc_profile_loc "/path/to/data"
                                                    :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}, {\"count\":1,\"fieldName\":\"Source\",\"fieldValue\":\"someditor@hismail.com\"}]")
                                                    :xml_data (make-clob "<Image><Name>NoSSID</Name><SSID></SSID></Image>")
                                                    :image_url     "/path/to/image" :width 1024 :height 768
                                                    :thumbnailimgurl "imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg"
                                                    :resolution_x  600 :resolution_y 600}])]
        (let [object-id "LESSING_ART_10310752347"
              expected-metadata '({:mdString "",
                                   :SSID "",
                                   :editable false,
                                   :objectId "LESSING_ART_10310752347",
                                   :fileProperties [{:fileName "image"}]
                                   :title "Lady with an Ermine",
                                   :imageUrl "/thumb/imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg",
                                   :metaData ( {:celltype "",
                                                :count 1,
                                                :fieldName "Creator",
                                                :fieldValue "Leonardo da Vinci (1452-1519)",
                                                :index 1,
                                                :link "",
                                                :textsize 1,
                                                :tooltip ""},
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
                                                :fieldValue "painting",
                                                :index 0,
                                                :link "",
                                                :textsize 1,
                                                :tooltip ""})})
              found-metadata (repo/get-legacy-formatted-metadata object-id false)]
          (is (= expected-metadata found-metadata)))))
    (testing "test get-legacy-formatted-metadata with no db results"
      (with-redefs [repo/sql-get-metadata (fn [_] {})]
        (let [object-id "A_GARBAGE_ID"
              found-metadata (repo/get-legacy-formatted-metadata object-id false)]
          (is (= nil found-metadata)))))))

(deftest test-get-parsed-json
  (testing "parsing metadata strings"
    (let [metadata (make-clob "{\"this\": \"that\"}")
          object-id "GOOD_DATA_OBJECT"]
      (is (= [[:this "that"]] (repo/get-parsed-json metadata object-id ))))))

(deftest test-get-clean-metadata
  (testing "testing good data"
    (with-redefs [repo/sql-get-metadata-without-backslashes (fn [_] [{:object_id "LESSING_ART_10310752347"
                                                                      :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}]")}])]
      (let [clean-parsed-json (repo/get-clean-metadata "LESSING_ART_10310752347")]
        (is (= "Leonardo da Vinci (1452-1519)" (first (map #(get-in % [:fieldValue]) clean-parsed-json))))))))

(deftest test-massage-legacy-metadata
  (with-redefs [repo/sql-get-metadata-without-backslashes (fn [_] [{:object_id "LESSING_ART_10310752347"
                                                                    :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}, {\"count\":1, \"fieldName\":\"Collection\", \"fieldValue\":\"Collection-name-update-test\"}]")}])
                repo/get-adl-category-id (fn [object-id collection-id] "10345678")]
    (testing "testing good data"
      (let [rec {:object_id "LESSING_ART_10310752347"
                 :object_type_id 10
                 :download_size "1024,1024"
                 :collection_id "123"
                 :collection_name "Collection"
                 :collection_type 1
                 :icc_profile_loc "/path/to/data"
                 :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}, {\"count\":1, \"fieldName\":\"Collection\", \"fieldValue\":\"collection-name-update-test\", \"index\":1}]")
                 :xml_data (make-clob "<Image><Name>NoSSID</Name><SSID></SSID></Image>")
                 :image_url     "/path/to/image" :width 1024 :height 768
                 :thumbnailimgurl "imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg"
                 :resolution_x  600 :resolution_y 600}
            metadata (repo/massage-legacy-metadata rec [{:object_id "LESSING_ART_10310752347" :SSID "12345" :contributingInstitutionId 1000}])]
        (is (= "LESSING_ART_10310752347" (metadata :object_id)))
        (is (= "Collection" (metadata :collection_name)))))
    (testing "testing good data with personalCollectionOwner"
      (let [rec {:object_id "PC_OBJECT"
                 :object_type_id 10
                 :download_size "1024,1024"
                 :collection_id "123"
                 :collection_name "Global Personal Collection"
                 :collection_type 6
                 :icc_profile_loc "/path/to/data"
                 :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}, {\"count\":1, \"fieldName\":\"Collection\", \"fieldValue\":\"collection-name-update-test\", \"index\":1}]")
                 :xml_data (make-clob "<Image><Name>NoSSID</Name><SSID></SSID></Image>")
                 :image_url     "/path/to/image" :width 1024 :height 768
                 :thumbnailimgurl "path/to/file.jpg"
                 :resolution_x  600 :resolution_y 600}
            metadata (repo/massage-legacy-metadata rec [{:object_id "PC_OBJECT" :SSID "12345" :contributingInstitutionId 1000 :personalCollectionOwner 123456}])]
        (is (= "PC_OBJECT" (metadata :object_id)))
        (is (= 123456 (metadata :personalCollectionOwner)))
        (is (= "Global Personal Collection" (metadata :collection_name)))))
    (testing "testing bad data"
      (let [metadata_json "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo \\da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}]"
            rec {:object_id "LESSING_ART_10310752347"
                 :object_type_id 10
                 :download_size "1024,1024"
                 :collection_id "123"
                 :collection_name "Collection"
                 :collection_type 2
                 :icc_profile_loc "/path/to/data"
                 :metadata_json (make-clob "[{\"count\":1, \"fieldName\":\"Creator\", \"fieldValue\":\"Leonardo \\da Vinci (1452-1519)\", \"index\":1}, {\"count\":1, \"fieldName\":\"Title\",\"fieldValue\":\"Lady with an Ermine\"}, {\"count\":1, \"fieldName\":\"Work Type\", \"fieldValue\":\"painting\"}]")
                 :xml_data (make-clob "<Image><Name>NoSSID</Name><SSID></SSID></Image>")
                 :image_url     "/path/to/image" :width 1024 :height 768
                 :thumbnailimgurl "imgstor/size0/lessing/art/lessing_40070854_8b_srgb.jpg"
                 :resolution_x  600 :resolution_y 600}
            metadata (repo/massage-legacy-metadata rec [{:object_id "LESSING_ART_10310752347" :SSID "12345" :contributinginstitutionid 1000}])]
        (is (= "LESSING_ART_10310752347" (metadata :object_id)))
        (is (not (= (metadata :metadata_json) metadata_json)))))))

(deftest test-get-legacy-metadata-for-object-ids
  (with-redefs [repo/populate-metadata-cache (fn [_] _)
                repo/get-adl-category-id (fn [_ _] _)
                repo/sql-get-qtvr-asset-image-url (fn [_] [])
                repo/sql-get-metadata (fn [_] [{:object_id     "SS34216_34216_38872125"
                                                :object_type_id 11
                                                :download_size "1024,1024"
                                                :collection_id "103"
                                                :collection_type 1
                                                :collection_name "Collection"
                                                :icc_profile_loc nil
                                                :metadata_json (make-clob "[{\"hello\":\"world\"}]")
                                                :xml_data (make-clob "<name>test</name>")
                                                :image_url     "/path/to/image" :width 1024 :height 768
                                                :resolution_x  600 :resolution_y 600}])]
    (testing "Simple testing test-get-legacy-metadata-for-object-ids"
      (let [list (repo/get-legacy-metadata-for-object-ids ["SS34216_34216_38872125"] false [])]
        (is (= "SS34216_34216_38872125" (:object_id (first list))))
        (is (= "" (:updated_on (first list))))))
    (testing "test-get-legacy-metadata-for-object-ids for personalCollectionOwner"
      (let [list (repo/get-legacy-metadata-for-object-ids ["SS34216_34216_38872125"] false [{:object_id "SS34216_34216_38872125" :SSID "12345" :personalCollectionOwner 123456}])]
        (is (= "SS34216_34216_38872125" (:object_id (first list))))
        (is (= 123456 (:personalCollectionOwner (first list))))))
    (testing "Testing test-get-legacy-metadata for updated_on retrieved from solr"
      (let [list (repo/get-legacy-metadata-for-object-ids ["SS34216_34216_38872125"] false [{:object_id "SS34216_34216_38872125" :SSID "12345" :updated_on "2017-05-01T23:51:50Z"}])]
        (is (= "SS34216_34216_38872125" (:object_id (first list))))
        (is (= 11 (:object_type_id (first list))))
        (is (= "2017-05-01T23:51:50Z" (:updated_on (first list))))))))

(deftest build-collections-from-collectiontypenameid-tests
  (testing "testing good collectiontypenameid-"
    (let [c (repo/build-collections-from-collectiontypenameid "2|SAHARA Members' Collection|1113")]
      (is (= {:type "2" :name "SAHARA Members' Collection" :id "1113"} c))))
  (testing "testing adl collectiontypenameid-"
    (let [c (repo/build-collections-from-collectiontypenameid "1|Artstor Digital Library|35953")]
      (is (= {:type "1" :name "Artstor Collections" :id "103"} c))))
  (testing "testing bad collectiontypenameid-"
    (let [c (repo/build-collections-from-collectiontypenameid nil)]
      (is (= {} c)))))

(deftest get-doi-tests
  (testing "testing good doi"
    (let [good-doi (repo/get-ssid-from-doi "10.2307/artstor.13743021")]
      (is (= "13743021" good-doi))))
  (testing "testing nil doi"
    (let [nil-doi (repo/get-ssid-from-doi nil)]
      (is (= "" nil-doi))))
  (testing "testing empty string doi"
    (let [empty-doi (repo/get-ssid-from-doi nil)]
      (is (= "" empty-doi)))))

(deftest test-extract-mediafiles-from-zipped-data
  (testing "testing extract-mediafiles-from-xml"
    (let [xml-data "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Image><MediaFiles><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"96\" resolution=\"-3\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size0/sslps/c35953/18072368.jpg\" height=\"96\" serverurl=\"imgstor/size0\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"196\" resolution=\"-1\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size1/sslps/c35953/18072368.jpg\" height=\"196\" serverurl=\"imgstor/size1\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"400\" resolution=\"2\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size2/sslps/c35953/18072368.jpg\" height=\"400\" serverurl=\"imgstor/size2\"></MediaFile><MediaFile format=\"FPX\" ldsid=\"1\" width=\"4839\" resolution=\"5\" filename=\"18072368.fpx\" lps=\"sslps/c35953\" url=\"imgstor/fpx/sslps/c35953/18072368.fpx\" height=\"6028\" serverurl=\"imgstor/fpx\"></MediaFile></MediaFiles></Image>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-data)))
          zipped-data (zip/xml-zip xml-parsed)
          all-mediafiles (repo/extract-mediafiles-from-zipped-data zipped-data)]
      (is (= (first all-mediafiles) {:serverurl "imgstor/size0", :height "96", :url "imgstor/size0/sslps/c35953/18072368.jpg", :lps "sslps/c35953", :filename "18072368.jpg", :resolution "-3", :width "96", :ldsid "1", :format "JPEG"}))
      (is (= (second all-mediafiles) {:serverurl "imgstor/size1", :height "196", :url "imgstor/size1/sslps/c35953/18072368.jpg", :lps "sslps/c35953", :filename "18072368.jpg", :resolution "-1", :width "196", :ldsid "1", :format "JPEG"}))
      (is (= (last all-mediafiles) {:serverurl "imgstor/fpx", :height "6028", :url "imgstor/fpx/sslps/c35953/18072368.fpx", :lps "sslps/c35953", :filename "18072368.fpx", :resolution "5", :width "4839", :ldsid "1", :format "FPX"}))
      (is (= (nth all-mediafiles 2) {:serverurl "imgstor/size2", :height "400", :url "imgstor/size2/sslps/c35953/18072368.jpg", :lps "sslps/c35953", :filename "18072368.jpg", :resolution "2", :width "400", :ldsid "1", :format "JPEG"})))))

(deftest test-filename-from-xml
  (testing "testing get-filenames-from-xml"
    (let [xml-data "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Image><MediaFiles><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"96\" resolution=\"-3\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size0/sslps/c35953/18072368.jpg\" height=\"96\" serverurl=\"imgstor/size0\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"196\" resolution=\"-1\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size1/sslps/c35953/18072368.jpg\" height=\"196\" serverurl=\"imgstor/size1\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"400\" resolution=\"2\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size2/sslps/c35953/18072368.jpg\" height=\"400\" serverurl=\"imgstor/size2\"></MediaFile><MediaFile format=\"FPX\" ldsid=\"1\" width=\"4839\" resolution=\"5\" filename=\"18072368.fpx\" lps=\"sslps/c35953\" url=\"imgstor/fpx/sslps/c35953/18072368.fpx\" height=\"6028\" serverurl=\"imgstor/fpx\"></MediaFile></MediaFiles></Image>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-data)))
          zipped-data (zip/xml-zip xml-parsed)
          fpx-filename (repo/get-filename-from-zipped-data zipped-data)]
      (is (= fpx-filename "18072368.fpx"))
      (is (not (= fpx-filename "18072368.jpg"))))))

(deftest test-get-filename
  (testing "test get-file-name"
    (let [xml-data "<?xml version=\"1.0\" encoding=\"UTF-8\"?><Image><MediaFiles><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"96\" resolution=\"-3\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size0/sslps/c35953/18072368.jpg\" height=\"96\" serverurl=\"imgstor/size0\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"196\" resolution=\"-1\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size1/sslps/c35953/18072368.jpg\" height=\"196\" serverurl=\"imgstor/size1\"></MediaFile><MediaFile format=\"JPEG\" ldsid=\"1\" width=\"400\" resolution=\"2\" filename=\"18072368.jpg\" lps=\"sslps/c35953\" url=\"imgstor/size2/sslps/c35953/18072368.jpg\" height=\"400\" serverurl=\"imgstor/size2\"></MediaFile><MediaFile format=\"FPX\" ldsid=\"1\" width=\"4839\" resolution=\"5\" filename=\"18072368.fpx\" lps=\"sslps/c35953\" url=\"imgstor/fpx/sslps/c35953/18072368.fpx\" height=\"6028\" serverurl=\"imgstor/fpx\"></MediaFile></MediaFiles></Image>"
          xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-data)))
          zipped-data (zip/xml-zip xml-parsed)
          object-type-id-1 10M
          object-type-id-2 12M
          image-url "imgstor/fpx/sslps/c35953/asdfg.fpx"]
      (is (= (repo/get-file-name zipped-data object-type-id-1 image-url) "asdfg.fpx"))
      (is (= (repo/get-file-name zipped-data object-type-id-2 image-url) "18072368.fpx")))))

(deftest test-get-stor-url-for-qtvr-assets
  (testing "Get stor url for valid object type - qtvr asset"
    (with-redefs [repo/sql-get-qtvr-asset-image-url (fn[_] [{:image_url "http://valid-qtvr-asset-stor-url"}])]
      (let [url (repo/get-stor-url-for-qtvr-assets "obj1")]
        (is (= "http://valid-qtvr-asset-stor-url" url)))))
  (testing "Get empty stor url for invalid object type qtvr asset"
    (with-redefs [repo/sql-get-qtvr-asset-image-url (fn[_] [{:image_url ""}])]
      (let [url (repo/get-stor-url-for-qtvr-assets "obj1")]
        (is (= "" url))
        (is (= true (empty? url)))))))

(deftest test-validate-email
  (testing "validate email"
    (let [valid-email "asdfg@asd.com"
          invalid-email "asd@asd"]
      (is (= valid-email (repo/validate-email valid-email)))
      (is (= nil (repo/validate-email invalid-email))))))

(deftest test-remove-source-having-valid-email
  (testing "remove source having valid email"
    (let [data-without-email {:count 1, :fieldName "Source", :fieldValue "asd@asd", :index 1}
          data-with-email {:count 1, :fieldName "Source", :fieldValue "asd@asd.com", :index 1}]
      (is (= data-without-email (repo/remove-source-having-valid-email data-without-email)))
      (is (= nil (repo/remove-source-having-valid-email data-with-email))))))