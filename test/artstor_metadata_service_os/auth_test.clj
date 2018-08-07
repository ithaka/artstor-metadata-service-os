(ns artstor-metadata-service-os.auth-test
  (:require [artstor-metadata-service-os.auth :as auth]
            [clojure.test :refer :all]))

(deftest testing-auth
  (testing "Test auth using valid artstor-user-info"
    (let [req {:artstor-user-info {:profile-id 123456, :institution-id 1000, :username "qa@artstor.org", :default-user false}}
          resp (auth/artstor-user? req)]
      (is (= true resp))))
  (testing "Test auth using invalid artstor-user-info"
    (let [req {:artstor-user-info {:profile-id nil, :institution-id 1000, :username "qa@artstor.org", :default-user false}}
          resp (auth/artstor-user? req)]
      (is (= false resp))))
  (testing "Test auth without artstor-user-info"
    (let [req {:headers {:fromkress true}}
          resp (auth/artstor-user? req)]
      (is (= false resp)))))