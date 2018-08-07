(ns artstor-metadata-service-os.util-test
  (:require [artstor-metadata-service-os.util :as util]
            [clojure.test :refer :all]
            [clojure.test.check.clojure-test :refer [defspec]]
            [clojure.test.check.properties :as prop]
            [clojure.test.check.generators :as gen]
            [environ.core :refer [env]]
            [clj-time.coerce :as tc]))


(defspec test-crappy-encrypt-functions 100
         (prop/for-all [plain-str gen/string-alpha-numeric]
                       (let [round-trip (util/decrypt (util/encrypt plain-str))]
                         (is (= round-trip plain-str)))))

(deftest test-generate-auth-token
  (testing "test generating an auth token"
    (with-redefs [env {:secret "swiss cheese"}
                  tc/to-long (fn [_] 1507501300112)]
      (is (= "sslps/c35269/2639599.fpx/8Sw7XoQxXC9awrXDs_DGlw/1507504900/"
             (util/generate-auth-token "sslps/c35269/2639599.fpx"))))))