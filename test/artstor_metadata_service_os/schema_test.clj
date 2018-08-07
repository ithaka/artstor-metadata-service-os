(ns artstor-metadata-service-os.schema-test
  (:require [artstor-metadata-service-os.schema :as schema]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [schema.core :as s]
            [cheshire.core :as json]
            [clojure.walk :as walk]))

(def legacy-dataset
  (walk/keywordize-keys (json/parse-string "{
      \"success\": true,
      \"total\": 1,
      \"metadata\": [
        {
          \"SSID\":\"SS_1234_TEST\",
          \"fileProperties\": [{\"fileName\": \"image\"}],
          \"thumbnail_url\":\"/thumb/thumbnail.jpg\",
          \"title\":\"Gloucester Mansion\",
          \"resolution_x\": 600,
          \"object_type_id\": 12345,
          \"collection_type\": 123,
          \"download_size\": \"a size\",
          \"collection_id\" : \"1\",
          \"collection_name\" : \"name\",
          \"category_id\" : \"1234567890\",
          \"category_name\" : \"cat-name\",
          \"object_id\": \"AMICO_BOSTON_103837212\",
          \"width\": 1600,
          \"metadata_json\": [\n        {\n          \"count\": 2,\n          \"fieldName\": \"Creator\",\n          \"fieldValue\": \"Edward Hopper\",\n          \"index\": 1\n        },\n        {\n          \"count\": 2,\n          \"fieldName\": \"Creator\",\n          \"fieldValue\": \"American, 1882-1967, North American; American\",\n          \"index\": 2\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Title\",\n          \"fieldValue\": \"Gloucester Mansion\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Work Type\",\n          \"fieldValue\": \"Drawings and Watercolors\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Date\",\n          \"fieldValue\": \"1924\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Material\",\n          \"fieldValue\": \"Watercolor\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Measurements\",\n          \"fieldValue\": \"Sheet: 34 x 49.6 cm (13 3/8 x 19 1/2 in. )\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Description\",\n          \"fieldValue\": \"Full View\",\n          \"index\": 1\n        },\n        {\n          \"count\": 5,\n          \"fieldName\": \"Repository\",\n          \"fieldValue\": \"Museum of Fine Arts, Boston\",\n          \"index\": 1\n        },\n        {\n          \"count\": 5,\n          \"fieldName\": \"Repository\",\n          \"fieldValue\": \"Boston, Massachusetts, USA\",\n          \"index\": 2\n        },\n        {\n          \"count\": 5,\n          \"fieldName\": \"Repository\",\n          \"fieldValue\": \"Bequest of John T. Spaulding\",\n          \"index\": 3\n        },\n        {\n          \"count\": 5,\n          \"fieldName\": \"Repository\",\n          \"fieldValue\": \"48.717\",\n          \"index\": 4\n        },\n        {\n          \"count\": 5,\n          \"fieldName\": \"Repository\",\n          \"fieldValue\": \"http://www.mfa.org/\",\n          \"index\": 5\n        },\n        {\n          \"count\": 2,\n          \"fieldName\": \"Collection\",\n          \"fieldValue\": \"Artstor Collections\",\n          \"index\": 1\n        },\n        {\n          \"count\": 2,\n          \"fieldName\": \"Collection\",\n          \"fieldValue\": \"Artstor Collections\",\n          \"index\": 2\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"ID Number\",\n          \"fieldValue\": \"BMFA.48.717\",\n          \"index\": 1\n        },\n        {\n          \"count\": 1,\n          \"fieldName\": \"Source\",\n          \"fieldValue\": \"Data From: Museum of Fine Arts, Boston\",\n          \"index\": 1\n        },\n        {\n          \"count\": 2,\n          \"fieldName\": \"Rights\",\n          \"fieldValue\": \"This image was provided by Museum of Fine Arts, Boston. Contact information: Debra LaKind, Head of Rights & Licensing, Museum of Fine Arts, Boston, 465 Huntington Avenue, Boston, MA 02115, (617) 369-4386 (ph), (617) 369-4340 (fax), dlakind@mfa.org.\",\n          \"index\": 1\n        },\n        {\n          \"count\": 2,\n          \"fieldName\": \"Rights\",\n          \"fieldValue\": \"Please note that if this image is under copyright, you may need to contact one or more copyright owners for any use that is not permitted under the ARTstor Terms and Conditions of Use or not otherwise permitted by law. While ARTstor tries to update contact information, it cannot guarantee that such information is always accurate. Determining whether those permissions are necessary, and obtaining such permissions, is your sole responsibility.\",\n          \"index\": 2\n        }\n      ],
          \"image_url\": \"amico/boston/bmfa.164-13.fpx\",
          \"icc_profile_loc\": null,
          \"resolution_y\": 600,
          \"height\": 1094,
          \"contributinginstitutionid\": 1000
        }
      ]
    }")))

(deftest check-schema
  (testing "test schema with legacy data"
    (is (= nil (s/check schema/Metadata (assoc (first (get legacy-dataset :metadata)) :collections []))))))

