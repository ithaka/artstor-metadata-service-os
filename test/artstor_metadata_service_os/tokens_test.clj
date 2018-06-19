(ns artstor-metadata-service-os.tokens-test
  (:require [artstor-metadata-service-os.tokens :as tokens]
            [artstor-metadata-service-os.util :as util]
            [clojure.test :refer :all]
            [cheshire.core :as json]
            [clj-http.client :as http]
            [clojure.string :as string]))

(deftest test-build-query-params-to-get-licenses
  (with-redefs [tokens/external-account-id "asd123-asd123-123-asd"
                tokens/get-inst-ext-accountid-from-legacyid (fn [_] "asdfg123-asdfg-123")
                tokens/get-user-ext-accountid-from-profileid (fn [_] "qwerty123-qwerty-123")]
    (testing "build url if default user"
      (let [user-info {:profile-id "12345" :institution-id 1000 :default-user true :username "qa@artstor.org"}]
        (let [response (tokens/build-query-params-to-get-licenses user-info)]
          (is (= response {:accountIds "asdfg123-asdfg-123,asd123-asd123-123-asd" :idType "externalId" :includeFlag false})))))
    (testing "build url if not default user"
      (let [user-info {:profile-id "12345" :institution-id 1000 :default-user false :username "qa@artstor.org"}]
        (let [response (tokens/build-query-params-to-get-licenses user-info)]
          (is (= response {:accountIds "asdfg123-asdfg-123,qwerty123-qwerty-123,asd123-asd123-123-asd" :idType "externalId" :includeFlag false})))))))

(deftest test-get-all-licenses-from-artstor-user-info
  (testing "get all licenses from artstor-user-info"
    (let [user-info {:profile-id "12345" :institution-id "1000" :default-user false :username "qa@artstor.org"}
          get-data {:body "{
        \"results\" : [ {
          \"entExtId\" : \"12345678901234\",
          \"licenses\" : [ {
            \"acctExtId\" : \"abcd1234-1234-12ab-1234-123456a123a1\",
            \"acctType\" : \"Institution\",
            \"acctIntId\" : \"1234567\",
            \"acctLegacyId\" : \"1234567890\",
            \"licExtId\" : \"qwer1234-1234-12qw-1234-123456q123q1\",
            \"licType\" : \"Standard - Artstor\",
            \"licSubType\" : \"Artstor\"
          } ],
          \"updateTime\" : \"2017-10-16T21:19:18.475+0000\"}]}"}]
      (with-redefs [tokens/build-query-params-to-get-licenses (fn [_] {:accountIds "1100001000,1000012345,2" :idType "legacyId" :includeFlag false})
                    util/build-service-url (fn [nm path] (str "http://" nm "/" path))
                    http/get (fn [_ _] get-data)]
        (let [all-licenses (tokens/get-all-licenses-from-artstor-user-info user-info)]
          (is (string/includes? all-licenses "12345678901234"))
          (is (string/includes? all-licenses "1234567890"))
          (is (string/includes? all-licenses "Standard - Artstor"))
          (is (= all-licenses ((json/parse-string (get-data :body) true) :results))))))))

(deftest test-get-eme-tokens-from-artstor-user-info
  (testing "get eme tokens from artstor-user-info"
    (let [user-info {:profile-id "12345" :institution-id "1000" :default-user false :username "qa@artstor.org"}
          test-data [{:entExtId "12345678901",
                      :licenses [{:acctLegacyId "1100001000",
                                  :licSubType "Artstor"}]}
                     {:entExtId "12345987601",
                      :licenses [{:acctLegacyId "1100001000",
                                  :licSubType "Portal",
                                  :licType "Portal - Portal"}
                                 {:acctLegacyId "1100001001",
                                  :licSubType "Portal"}]}
                     {:entExtId "98765432101",
                      :licenses [{:acctLegacyId "1100001000",
                                  :licSubType "Artstor",
                                  :licType "Standard - Artstor"}]}]]
      (with-redefs [tokens/get-all-licenses-from-artstor-user-info (fn[_] test-data)]
        (let [artstor-eme-tokens (tokens/get-eme-tokens-from-artstor-user-info user-info)]
          (is (= ["12345678901" "98765432101"] artstor-eme-tokens)))))))

(deftest test-get-accountid-from-profileid
  (with-redefs [http/get (fn [_ _] {:body "{\"userId\" : \"asdfg-12345-asdfg-12345\"}"})]
    (testing "get user external account id from profileid"
      (let [response (tokens/get-user-ext-accountid-from-profileid "12345")]
        (is (= {response "asdfg-12345-asdfg-12345"}))))))

(deftest test-get-ext-accountid-from-legacyid
  (with-redefs [http/get (fn [_ _] {:body "{\"id\" : \"asdfg-12345-asdfg-12345\"}"})]
    (testing "get external account id from legacyid"
      (let [response (tokens/get-inst-ext-accountid-from-legacyid 1000)]
        (is (= {response "asdfg-12345-asdfg-12345"}))))))