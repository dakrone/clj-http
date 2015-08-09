(ns clj-http.test.multipart-test
  (:require [clj-http.multipart :refer :all]
            [clojure.test :refer :all])
  (:import (java.io File)
           (org.apache.http.entity.mime.content FileBody)))

(deftest test-make-file-body
  (testing "no content throws exception"
    (is (thrown? Exception (make-file-body {}))))

  (testing "can create FileBody with content only"
    (let [testfile (File. "testfile")
          file-body (make-file-body {:content (File. "testfile")})]
      (is (= (.getFile ^FileBody file-body) testfile))))

  (testing "can create FileBody with content and mime-type"
    (let [testfile (File. "testfile")
          file-body (make-file-body {:content (File. "testfile")
                                     :mime-type "application/octet-stream"})]
      (is (= (.getFile ^FileBody file-body) testfile))))

  (testing "can create FileBody with content and mime-type and name"
    (let [testfile (File. "testfile")
          file-body (make-file-body {:content (File. "testfile")
                                     :mime-type "application/octet-stream"
                                     :name "testname"})]
      (is (= (.getFile ^FileBody file-body) testfile))
      (is (= (.getFilename ^FileBody file-body) "testname"))))

  (testing "can create FileBody with content and mime-type and encoding"
    (let [testfile (File. "testfile")
          file-body (make-file-body {:content (File. "testfile")
                                     :mime-type "application/octet-stream"
                                     :encoding "utf-8"})]
      (is (= (.getFile ^FileBody file-body) testfile))))

  (testing "can create FileBody with content, mime-type, encoding, and name"
    (let [testfile (File. "testfile")
          file-body (make-file-body {:content (File. "testfile")
                                     :mime-type "application/octet-stream"
                                     :encoding "utf-8"
                                     :name "testname"})]
      (is (= (.getFile ^FileBody file-body) testfile))
      (is (= (.getFilename ^FileBody file-body) "testname")))))
