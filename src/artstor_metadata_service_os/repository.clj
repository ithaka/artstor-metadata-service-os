(ns artstor-metadata-service-os.repository
  (:require [artstor-metadata-service-os.util :as util]
            [artstor-metadata-service-os.tokens :as tokens]
            [org.ithaka.clj-iacauth.core :refer [extract-data]]
            [clj-sequoia.service :refer [get-env]]
            [clojure.java.jdbc :as jdbc]
            [clj-http.client :as http]
            [clojure.string :as string]
            [cheshire.core :as json]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.xml :as xml]
            [clojure.zip :as zip]
            [clojure.tools.logging :as logger]
            [clojure.data.zip.xml :refer [xml-> xml1-> text]]
            [yesql.core :refer [defqueries]]
            [hiccup.core :refer [html]]
            [environ.core :refer [env]])
  (:import (com.mchange.v2.c3p0 DataSources)
           (oracle.jdbc OracleTypes)
           (com.fasterxml.jackson.core JsonParseException)))


(def db-spec {:datasource (DataSources/pooledDataSource
                            (DataSources/unpooledDataSource (env :artstor-metadata-db-url)))})

;; This is a macro that reads the Oracle calls in the specified adl.sql file and generates functions based on the
;; comments in the adl.sql file.
(defqueries "artstor_metadata_service/sql/adl.sql"
            {:connection db-spec})

(defn get-ssid-from-doi [doi]
  "ssid is string after the last dot in the doi"
  (if (nil? doi)
    ""
    (last (str/split doi #"\."))))

(defn massage-db-recs [rec]
  (let [r (clojure.set/rename-keys rec {:objectid :objectId :objecttypeid :objectTypeId :downloadsize :downloadSize
                                        :collectionid :collectionId :collectiontype :collectionType
                                        :cfobjectid :cfObjectId :thumbnailimgurl :thumbnailImgUrl
                                        :largeimgurl :largeImgUrl})
        r (assoc r :status "available")]
    (dissoc (assoc r :tombstone (map #(if (r %) (str (r %))) [:thumbnail1 :thumbnail2 :thumbnail3]))
            :thumbnail1 :thumbnail2 :thumbnail3)))

(defn find-item [object-id]
  (if-let [rec (first (sql-retrieve-item {:object_id object-id}))]
    (massage-db-recs rec)))

(defn generate-missing-item [object-id]
  (-> {}
      (assoc :objectId object-id)
      (assoc :status "not-available")
      (assoc :objectTypeId 0)
      (assoc :collectionId "")
      (assoc :collectionType 0)
      (assoc :cfObjectId nil)
      (assoc :downloadSize nil)
      (assoc :clustered nil)
      (assoc :thumbnailImgUrl nil)
      (assoc :largeImgUrl nil)
      (assoc :tombstone nil)))

(defn find-items
  ([allowed-object-ids]
   "default of 100 maximum allowed object ids"
   (find-items allowed-object-ids 100))
  ([allowed-object-ids maximum-objects]
   (let [limited_object_ids (take maximum-objects allowed-object-ids)
         items (sql-retrieve-items {:object_ids limited_object_ids})
         items (map massage-db-recs items)
         missing-items-ids (set/difference (set allowed-object-ids) (set (map #(% :objectId) items)))
         missing-items (map #(generate-missing-item %) missing-items-ids)
         items (into items missing-items)]
     (sort-by #(.indexOf allowed-object-ids (% :objectId)) items))))

(defn get-parsed-json [metadata object_id]
  (let [slurped (slurp (.getCharacterStream  metadata))
        parsed-json (json/parse-string slurped true)]
    (try (vec parsed-json)
         (catch JsonParseException e
           (logger/error ">>>BAD DATA<<< in Metadata of object_id: " object_id)
           {:status 500}))))

(defn get-clean-metadata [object-id]
  (let [raw-data (sql-get-metadata-without-backslashes {:object_id object-id})
        parsed-json (get-parsed-json (first (map #(get-in % [:metadata_json]) raw-data)) object-id)]
    parsed-json))

(defn massage-legacy-metadata-one-cell [one-legacy-cell limited]
  "Limited flag is intended for use by the UI"
  (let [base_metadata {:count      (if-not (one-legacy-cell :count) 1 (one-legacy-cell :count))
                       :fieldName  (if-not (one-legacy-cell :fieldName) "" (str/trim (one-legacy-cell :fieldName)))
                       :fieldValue (if-not (one-legacy-cell :fieldValue) "" (str/trim (one-legacy-cell :fieldValue)))
                       :index      (if-not (one-legacy-cell :index) 0 (one-legacy-cell :index))}]
    (if limited
      base_metadata
      (assoc base_metadata
        :celltype   (if-not (one-legacy-cell :celltype) "" (one-legacy-cell :celltype))
        :link       (if-not (one-legacy-cell :link) "" (one-legacy-cell :link))
        :textsize   1
        :tooltip    (if-not (one-legacy-cell :tooltip) "" (one-legacy-cell :tooltip))))))

(defn massage-legacy-metadata-into-cells
  ([parsed-json]
   (massage-legacy-metadata-into-cells parsed-json false))
  ([parsed-json limited]
   (map #(massage-legacy-metadata-one-cell % limited) parsed-json)))

(defn pull-title-from-legacy-cells [metadata-cells]
  (if-let [title-cell (first (filter #(= "Title" (% :fieldName)) metadata-cells))]
    (title-cell :fieldValue) ""))

(defn extract-ssid-from-xml [zipped-data]
  (if-let [ssid (xml1-> zipped-data :Image :SSID text)]
    ssid ""))

(defn extract-mediafiles-from-zipped-data [zipped-data]
  (->> (xml-> zipped-data :Image :MediaFiles :MediaFile #(keep :attrs %))))

(defn get-filename-from-zipped-data [zipped-data]
  (let [all-mediafiles (into [] (extract-mediafiles-from-zipped-data zipped-data))
        valid-filenames (filter #(= (read-string (:resolution %)) 5) all-mediafiles)
        filename (map #(get % :filename) valid-filenames)]
    (apply str filename)))

(defn get-file-name [zipped-data object-type-id image-url]
  (if (== object-type-id 12)
    (get-filename-from-zipped-data zipped-data)
    (last (str/split image-url #"/"))))

(defn get-stor-url-for-qtvr-assets [object-id]
  (let [data (sql-get-qtvr-asset-image-url {:object_id object-id})]
    (apply str (map #(get % :image_url) data))))

(defn update-collection-name [metadata-cells]
  (let [collection-name (map #(if (= "Collection" (% :fieldName)) (% :fieldValue)) metadata-cells)]
    (apply str collection-name)))

(defn validate-email [email]
  (let [pattern #"[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*@(?:[a-z0-9](?:[a-z0-9-]*[a-z0-9])?\.)+[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"]
    (and (string? email) (re-matches pattern email))))

(defn remove-source-having-valid-email [source-data]
  (if (not (nil? source-data))
    (if (empty? (filter #(validate-email %) (str/split (source-data :fieldValue) #" ")))
      source-data)))

(defn get-adl-category-id [object-id collection-id]
  (let [data (sql-get-adl-category-id {:object_id object-id :collection_id collection-id})]
    (apply str (filter #(str/starts-with? % collection-id) (map #(get % :category_id) data)))))

(defn legacy-metadata-massage [rec]
  "Takes newly formatted record data and puts into the old format.  In with the old, out with the new."
  (let [raw-parsed-json (get-parsed-json (rec :metadata_json) (rec :object_id))
        parsed-json (if (= 500 (get raw-parsed-json :status))
                      (get-clean-metadata (rec :object_id))
                      raw-parsed-json)
        metaDataCells (massage-legacy-metadata-into-cells parsed-json)
        metadata-cells-without-email (filter #(if (= "Source" (% :fieldName))
                                                (remove-source-having-valid-email %)
                                                %) metaDataCells)
        xml-data-string (slurp (.getCharacterStream (rec :xml_data)))
        xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-data-string)))
        zipped-data (zip/xml-zip xml-parsed)
        qtvr-base-asset-url (if (== (rec :object_type_id) 11) (get-stor-url-for-qtvr-assets (rec :object_id)) "")
        rec-qtvr (if (not (empty? qtvr-base-asset-url))
                   (assoc rec :viewer_data {:base_asset_url qtvr-base-asset-url
                                            :panorama_xml (str qtvr-base-asset-url "/viewer/xml")})
                   rec)
        legacy-rec (assoc rec-qtvr
                     :SSID (extract-ssid-from-xml zipped-data)
                     :editable false
                     :fileProperties [{:fileName (get-file-name zipped-data (rec :object_type_id) (rec :image_url))}]
                     :imageUrl (str "/thumb/" (rec :thumbnailimgurl))
                     :mdString ""
                     :metaData metadata-cells-without-email
                     :objectId (rec :object_id)
                     :title (pull-title-from-legacy-cells metadata-cells-without-email)
                     :xml_data xml-data-string)]
    (dissoc legacy-rec
            :object_id :metadata_json :image_url :resolution_x :resolution_y :height :object_type_id
            :width :icc_profile_loc :download_size :thumbnailimgurl
            :collection_id :collection_name)))

(defn populate-metadata-cache [object_ids]
  "Populate the data_metadata_json table by calling the stored procedure that does the XSLT transformation"
  (jdbc/with-db-connection [conn db-spec]
                           (let [ps (.prepareCall (jdbc/get-connection conn) "{ ? = call pkg_imagegroups.get_pptd_jsons(?) }")]
                             (doto ps
                               (.setString 2 (string/join "," object_ids))
                               (.registerOutParameter 1 OracleTypes/CURSOR)
                               (.execute))
                             (into [] (resultset-seq (.getObject ps 1))))))

;; Gets data from legacy oracle and formats in the style of the old artstor-digital-library /secure/metadata call
(defn get-legacy-formatted-metadata [object-id xml]
  ; Secret sauce - data_metdata_json is a cache table, it may be cleared periodically.
  (do (populate-metadata-cache [object-id])
      (if-let [raw-data (sql-get-metadata {:object_ids [object-id]})]
        (if-not (empty? raw-data)
          (let [data (map legacy-metadata-massage raw-data)]
            (map #(dissoc % :xml_data) data))))))

(defn generate-legacy-entry [field data]
  (let [data (if (string? data) [data] (filter identity data))
        data (flatten (map #(clojure.string/split % #"\|") data))
        num (count data)]
    (map-indexed (fn [i d] {:count num :fieldName field :fieldValue (string/trim d) :index (+ i 1)}) data)))

(defn build-fpx-url [media]
  (let [lps (get media "lps")
        filename (last (string/split (get media "thumbnailSizeOnePath" "") #"/"))
        fpx-filename (string/replace filename #".jpg$" ".fpx")]
    (util/generate-auth-token (str lps "/" fpx-filename))))

(defn remap-data [ang-record]
  "Rewrite hashmap data into a list of legacy field definitions"
  (let [mapping [[:agent "Creator"] [:artculture "Culture"] [:arttitle "Title"] [:artworktype "Work Type"]
                 [:artdate "Date"] [:artlocation "Location"] [:artmaterial "Material"] [:artstyleperiod "Period"]
                 [:artmeasurements "Measurements"] [:description "Description"] [:artcurrentrepository "Repository"]
                 [:artcurrentrepositoryidnumber "Accession Number"] [:artrelation "Related Item"] [:artsubject "Subject"]
                 [:artcollectiontitle "Collection"] [:artidnumber "ID Number"] [:artsource "Source"]]]
    (flatten (map #(generate-legacy-entry (second %) (get ang-record (first %) [])) mapping))))

(defn create-fields-for-artadditionalfields [kvp]
  "artadditionalfields need to be extracted into fields"
  (let [m (json/decode kvp)]
    (flatten (for [[k v] m] (generate-legacy-entry k v)))))

(defn explode-additional_Fields [record]
  "Explode :additional_Fields from browse endpoint before calling remap-data"
  (into (dissoc record :additional_Fields) (:additional_Fields record)))

(defn build-xml [mapped-data]
  "Take the mapped data and create a simple XML document"
  (html [:Image [:MetaData
                 (map #(vector (symbol (string/replace (% :fieldName) #"\s" "_")) (% :fieldValue)) mapped-data)]]))

(defn build-collections-from-collectiontypenameid [collectiontypenameid]
  "Takes an collectiontypenameid and returns collection object"
  (if-some [a collectiontypenameid]
    (let [[type name id] (clojure.string/split collectiontypenameid #"\|")
          is-adl (try (.equals type "1") (catch Exception e false))]
      (if is-adl {:id "103" :type type :name "Artstor Collections"} {:id id :type type :name name})) {}))

;;This is used when retrieving metadata with legacy=false flag.  Uses Metadata from search-service, not Oracle.
(defn convert-to-schema [ang-record xml]
  (let [solr-rec {:object_id (get ang-record :artstorid)}
        media (json/decode (get ang-record :media "{}"))
        download-size (get media "downloadSize" 1024)
        rights (json/decode (get-in ang-record [:additional_Fields :rights] "{}"))
        image-url (build-fpx-url media)
        collections (map #(build-collections-from-collectiontypenameid %) (get ang-record :collectiontypenameid))
        adl-category-id (str (or (get-in ang-record [:additional_Fields :categoryid]) ""))
        ang-record (if (nil? (:artcollectiontitle ang-record))
                     (assoc ang-record :artcollectiontitle (map #(:name %) collections)) ang-record)
        rights-map (generate-legacy-entry "Rights" (flatten [(get rights "record") (get rights "portal")]))
        additionalfields  (create-fields-for-artadditionalfields (get-in ang-record [:additional_Fields :artadditionalfields]))
        mapped-data (flatten (conj additionalfields rights-map (remap-data (explode-additional_Fields ang-record))))
        pc-owner (get-in ang-record [:additional_Fields :personalcollectionowner] nil)
        updated-solr-rec (if (not (nil? pc-owner))
                           (assoc solr-rec :personalCollectionOwner pc-owner)
                           solr-rec)
        updated-solr-rec (-> updated-solr-rec
                             (assoc :title (get ang-record :name "")
                                    :SSID (get-ssid-from-doi (get ang-record :doi ""))
                                    :fileProperties [{:fileName (first (filter #(clojure.string/ends-with? % ".fpx") (clojure.string/split image-url #"/")))}]
                                    :thumbnail_url (str "/thumb/" (get media "thumbnailSizeZeroPath" ""))
                                    :metadata_json mapped-data
                                    :object_type_id (get media "adlObjectType" 10)
                                    :download_size (str download-size "," download-size)
                                    :icc_profile_loc (get media "icc_profile_location")
                                    :width (get media "width" 1024)
                                    :height (get media "height" 1024)
                                    :image_url image-url
                                    :resolution_x 600
                                    :resolution_y 600
                                    :updated_on (get ang-record :updatedon "")
                                    :collections (if (empty? collections) [] collections)
                                    :category_id adl-category-id
                                    :category_name (if (clojure.string/blank? adl-category-id) "" (update-collection-name mapped-data))
                                    :collection_id (or (:id (first collections)) "")
                                    :collection_name (or (:name (first collections)) "")
                                    :collection_type (Integer/parseInt (or (:type (first collections)) "0"))
                                    :contributinginstitutionid (get ang-record :contributinginstitutionid 1000)))]
    (if xml (assoc updated-solr-rec :xml_data (build-xml mapped-data))
            updated-solr-rec)))

;; retrieves metadata using the search service
(defn get-metadata-for-object-ids
  ([object-ids xml] (get-metadata-for-object-ids object-ids xml []))
  ([object-ids xml eme-tokens]
   "Get the metadata for the given object-ids"
   (let [url (util/build-service-url "search-service" "browse/")
         form {:form-params {:limit (count object-ids)
                             :content_types ["art"]
                             :filter_query [(str "artstorid:(" (string/join " OR " object-ids) ")")]
                             :additional_fields [:personalcollectionowner :artculture :artworktype :artstyleperiod :artdate :artlocation :artmaterial :artidnumber
                                                 :artmeasurements :description :artcurrentrepository :rights :artcollectiontitle :artadditionalfields
                                                 :artrelation :artsubject :artidnumber :artsource :arttitle :agent :categoryid]
                             :tokens eme-tokens}
               :content-type :json :as :json}
         response (http/post url form)
         data (-> response :body :results)
         data-xml (map #(convert-to-schema % xml) data)]
     (sort-by #(.indexOf object-ids (% :object_id)) data-xml))))

(defn get-allowed-assets [object-ids eme-tokens]
  "Get the metadata for the given object-ids"
  (let [url (util/build-service-url "search-service" "browse/")
        form {:form-params {:limit (count object-ids)
                            :content_types ["art"]
                            :filter_query [(str "artstorid:(" (string/join " OR " object-ids) ")")]
                            :tokens eme-tokens}
              :content-type :json :as :json}
        response (http/post url form)]
    (if (nil? response)
      []
      (let [data (-> response :body :results)]
        (into [] (sort-by #(.indexOf object-ids %) (map #(get % :artstorid) data)))))))

(defn get-group-by-id [artstor-cookie web-token group-id]
  "To get the data of a group from the group service given a group-id"
  (let [url (str (util/build-service-url "artstor-group-service" "api/v1/group") "/" group-id)
        response (http/get url {:cookies {"ARTSTOR_HASHED_REM_ME" {:value artstor-cookie}}
                                :headers {"web-token" web-token}
                                :throw-exceptions false})
        body (get response :body)]
    (if (= (response :status) 200)
      (try (assoc (json/parse-string body true) :status 200)
           (catch JsonParseException e {:status false :message "Server error"}))
      (if (= (response :status) 403)
        response))))

(defn flush-cache! [object_id]
  (jdbc/with-db-transaction
    [tx db-spec]
    (sql-flush-metadata! {:object_ids object_id})))

(defn build-items-using-tokens [object_ids request]
  (let [eme-tokens (tokens/get-eme-tokens request)
        allowed-objects (get-allowed-assets object_ids eme-tokens)
        na-object-ids (set/difference (set object_ids) (set allowed-objects))
        allowed-items (if (empty? allowed-objects) [] (find-items allowed-objects))
        na-objects (map #(generate-missing-item %) na-object-ids)
        items (into na-objects allowed-items)]
    (sort-by #(.indexOf object_ids (% :objectId)) items)))

(defn massage-legacy-metadata [rec allowed-objects-data]
  (let [raw-parsed-json (get-parsed-json (rec :metadata_json) (rec :object_id))
        parsed-json (if (= 500 (get raw-parsed-json :status))
                      (get-clean-metadata (rec :object_id))
                      raw-parsed-json)
        record-solr (first (filter #(= (rec :object_id) (get % :object_id)) allowed-objects-data))
        ssid (get  record-solr :SSID)
        contributinginstid (get record-solr :contributinginstitutionid)
        metaDataCells (massage-legacy-metadata-into-cells parsed-json true)
        metadata-cells-without-email (filter #(if (= "Source" (% :fieldName)) (remove-source-having-valid-email %) %) metaDataCells)
        xml-data-string (slurp (.getCharacterStream (rec :xml_data)))
        xml-parsed (xml/parse (java.io.ByteArrayInputStream. (.getBytes xml-data-string)))
        zipped-data (zip/xml-zip xml-parsed)
        qtvr-base-asset-url (if (== (rec :object_type_id) 11) (get-stor-url-for-qtvr-assets (rec :object_id)) "")
        rec-qtvr (if (not (empty? qtvr-base-asset-url))
                   (assoc rec :viewer_data {:base_asset_url qtvr-base-asset-url :panorama_xml (str qtvr-base-asset-url "/viewer/xml")})
                   rec)
        adl-category-id (if (== (rec :collection_type) 1) (get-adl-category-id (rec :object_id) (rec :collection_id)) "")
        adl-category-name (if (== (rec :collection_type) 1) (update-collection-name metadata-cells-without-email) "")
        pc-owner (get record-solr :personalCollectionOwner)
        updated-rec-with-pc-data (if (not (nil? pc-owner))
                                   (assoc rec-qtvr :personalCollectionOwner pc-owner)
                                   rec-qtvr)
        rec-with-mixins (assoc updated-rec-with-pc-data
                          :SSID ssid
                          :fileProperties [{:fileName (get-file-name zipped-data (rec :object_type_id) (rec :image_url))}]
                          :title (pull-title-from-legacy-cells metadata-cells-without-email)
                          :thumbnail_url (str "/thumb/" (rec :thumbnailimgurl))
                          :metadata_json metadata-cells-without-email
                          :category_id adl-category-id
                          :category_name adl-category-name
                          :image_url (util/generate-auth-token (:image_url rec))
                          :xml_data xml-data-string
                          :updated_on (get record-solr :updated_on "")
                          :collections (get record-solr :collections [])
                          :contributinginstitutionid contributinginstid)]
    (dissoc rec-with-mixins :thumbnailimgurl)))

;; "Legacy" here means it gets the data from the "legacy" oracle tables.
(defn get-legacy-metadata-for-object-ids [object-ids xml allowed-objects-data]
  ; Secret sauce - data_metdata_json is a cache table, it may be cleared periodically.
  (do (populate-metadata-cache object-ids))
  (if-let [raw-data (sql-get-metadata {:object_ids object-ids})]
    (let [data (map #(massage-legacy-metadata % allowed-objects-data) raw-data)
          data-xml (if (false? xml)
                     (map #(dissoc % :xml_data) data) data)]
      (sort-by #(.indexOf object-ids (% :object_id)) data-xml))))

(defn build-legacy-metadata-using-tokens [object_ids xml eme-tokens]
  (let [allowed-objects-data (into [] (get-metadata-for-object-ids object_ids false eme-tokens))
        allowed-objects (into [] (map #(get % :object_id) allowed-objects-data))]
    (if (empty? allowed-objects) [] (get-legacy-metadata-for-object-ids allowed-objects xml allowed-objects-data))))

(defn legacy-search-response []
  (let [response {:thumbnails [],
                  :count 0,
                  :altKey "This call is no longer supported, please use http://library.artstor.org/api/search/v1.0/search for more information please content support@artstor.org",
                  :classificationFacets [],
                  :geographyFacets [],
                  :minDate 0,
                  :maxDate 0,
                  :collTypeFacets [],
                  :dateFacets []}]
    response))

