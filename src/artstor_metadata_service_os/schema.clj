(ns artstor-metadata-service-os.schema
  (:require [schema.core :as s]))

(s/defschema Item
             {:objectId s/Str
              :objectTypeId s/Num
              :collectionId s/Str
              :collectionType s/Num
              :cfObjectId (s/maybe s/Str)
              :downloadSize (s/maybe s/Str)
              :clustered (s/maybe s/Num)
              :thumbnailImgUrl (s/maybe s/Str)
              :largeImgUrl (s/maybe s/Str)
              :tombstone [(s/maybe s/Str)]
              (s/optional-key :status) s/Str})

(s/defschema RequestStatus
             {:success s/Bool :message s/Str})

(s/defschema ArtstorUser
             {:profile-id s/Str
              :institution-id s/Str
              :default-user s/Bool
              :username  s/Str})

;format of metaData field returned in the legacy /secure/metadata call
(s/defschema Legacy-Metadata-Cell
             {(s/optional-key :celltype) s/Str
              :count s/Num
              :fieldName s/Str
              :fieldValue s/Str
              :index s/Num
              (s/optional-key :link) s/Str
              (s/optional-key :textsize) s/Num
              (s/optional-key :tooltip) s/Str})

(s/defschema File-Properties
             {:fileName s/Str})

;mimics the old /secure/metadata call.
(s/defschema Legacy-Metadata
             {:SSID s/Str
              :editable s/Bool
              :fileProperties [File-Properties]
              (s/optional-key :viewer_data) {:base_asset_url s/Str :panorama_xml s/Str}
              :imageUrl s/Str
              :mdString s/Str
              :metaData [Legacy-Metadata-Cell]
              (s/optional-key :collection_type) s/Num
              :objectId s/Str
              :title s/Str})

(s/defschema Collection-type-name
             {:type s/Str :id s/Str :name s/Str})

(s/defschema Metadata
             {:object_id s/Str
              :object_type_id s/Num
              (s/optional-key :collection_id) s/Str
              (s/optional-key :collection_name) s/Str
              (s/optional-key :category_id) s/Str
              (s/optional-key :category_name) s/Str
              (s/optional-key :collection_type) s/Num
              (s/optional-key :updated_on) s/Str
              (s/optional-key :personalCollectionOwner) s/Num
              :title s/Str
              :SSID s/Str
              :contributinginstitutionid s/Num
              :fileProperties [File-Properties]
              (s/optional-key :viewer_data) {:base_asset_url s/Str :panorama_xml s/Str}
              :thumbnail_url s/Str
              :download_size (s/maybe s/Str)
              :metadata_json [Legacy-Metadata-Cell]
              :icc_profile_loc (s/maybe s/Str)
              (s/optional-key :xml_data) s/Str
              :collections [Collection-type-name]
              :image_url s/Str
              :width s/Num
              :height s/Num
              :resolution_x s/Num
              :resolution_y s/Num})