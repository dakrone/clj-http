(ns clj-http.test.multipart-test
  (:require [clj-http.multipart :refer :all]
            [clojure.test :refer :all])
  (:import (java.io File ByteArrayOutputStream ByteArrayInputStream)
           (org.apache.http.entity.mime.content FileBody StringBody ContentBody
                                                ByteArrayBody InputStreamBody)
           (java.nio.charset Charset)))

(defn body-str [^StringBody body]
  (-> body .getReader slurp))

(defn body-bytes [^ContentBody body]
  (let [buf (ByteArrayOutputStream.)]
    (.writeTo body buf)
    (.toByteArray buf)))

(defn body-charset [^ContentBody body]
  (-> body .getContentType .getCharset))

(defn body-mime-type [^ContentBody body]
  (-> body .getContentType .getMimeType))

(defn make-input-stream [& bytes]
  (ByteArrayInputStream. (byte-array bytes)))

(deftest test-multipart-body
  (testing "nil content throws exception"
    (is (thrown-with-msg? Exception #"Multipart content cannot be nil"
                          (make-multipart-body {:content nil}))))

  (testing "unsupported content type throws exception"
    (is (thrown-with-msg?
         Exception
         #"Unsupported type for multipart content: class java.lang.Object"
         (make-multipart-body {:content (Object.)}))))

  (testing "ContentBody content direct usage"
    (let [contentBody (StringBody. "abc")]
      (is (identical? contentBody
                      (make-multipart-body {:content contentBody})))))

  (testing "StringBody"

    (testing "can create StringBody with content only"
      (let [body (make-multipart-body {:content "abc"})]
        (is (instance? StringBody body))
        (is (= "abc" (body-str body)))))

    (testing "can create StringBody with content and encoding"
      (let [body (make-multipart-body {:content "abc" :encoding "ascii"})]
        (is (instance? StringBody body))
        (is (= "abc" (body-str body)))
        (is (= (Charset/forName "ascii") (body-charset body)))))

    (testing "can create StringBody with content and mime-type and encoding"
      (let [body (make-multipart-body {:content "abc"
                                       :mime-type "stream-body"
                                       :encoding "ascii"})]
        (is (instance? StringBody body))
        (is (= "abc" (body-str body)))
        (is (= (Charset/forName "ascii") (body-charset body)))
        (is (= "stream-body" (body-mime-type body))))))

  (testing "ByteArrayBody"

    (testing "exception thrown on missing name"
      (is (thrown-with-msg?
           Exception
           #"Multipart byte array body must contain at least :content and :name"
           (make-multipart-body {:content (byte-array [0 1 2])}))))

    (testing "can create ByteArrayBody with name only"
      (let [body (make-multipart-body {:content (byte-array [0 1 2])
                                       :name "testname"})]
        (is (instance? ByteArrayBody body))
        (is (= "testname" (.getFilename body)))
        (is (= [0 1 2] (vec (body-bytes body))))))

    (testing "can create ByteArrayBody with name and mime-type"
      (let [body (make-multipart-body {:content   (byte-array [0 1 2])
                                       :name      "testname"
                                       :mime-type "byte-body"})]
        (is (instance? ByteArrayBody body))
        (is (= "testname" (.getFilename body)))
        (is (= "byte-body" (body-mime-type body)))
        (is (= [0 1 2] (vec (body-bytes body)))))))

  (testing "InputStreamBody"

    (testing "exception thrown on missing name"
      (is
       (thrown-with-msg?
        Exception
        #"Multipart input stream body must contain at least :content and :name"
        (make-multipart-body
         {:content (ByteArrayInputStream. (byte-array [0 1 2]))}))))

    (testing "can create InputStreamBody with name and content"
      (let [input-stream (make-input-stream 1 2 3)
            body (make-multipart-body {:content input-stream
                                       :name    "testname"})]
        (is (instance? InputStreamBody body))
        (is (= "testname" (.getFilename body)))
        (is (identical? input-stream (.getInputStream body)))))

    (testing "can create InputStreamBody with name, content and mime-type"
      (let [input-stream (make-input-stream 1 2 3)
            body (make-multipart-body {:content   input-stream
                                       :name      "testname"
                                       :mime-type "input-stream-body"})]
        (is (instance? InputStreamBody body))
        (is (= "testname" (.getFilename body)))
        (is (= "input-stream-body" (body-mime-type body)))
        (is (identical? input-stream (.getInputStream body)))))

    (testing
        "can create input InputStreamBody name, content, mime-type and length"
      (let [input-stream (make-input-stream 1 2 3)
            body (make-multipart-body {:content   input-stream
                                       :name      "testname"
                                       :mime-type "input-stream-body"
                                       :length    42})]
        (is (instance? InputStreamBody body))
        (is (= "testname" (.getFilename body)))
        (is (= "input-stream-body" (body-mime-type body)))
        (is (identical? input-stream (.getInputStream body)))
        (is (= 42 (.getContentLength body))))))

  (testing "FileBody"

    (testing "can create FileBody with content only"
      (let [test-file (File. "testfile")
            body (make-multipart-body {:content test-file})]
        (is (instance? FileBody body))
        (is (= test-file (.getFile body)))))

    (testing "can create FileBody with content and mime-type"
      (let [test-file (File. "testfile")
            body (make-multipart-body {:content   test-file
                                       :mime-type "file-body"})]
        (is (instance? FileBody body))
        (is (= "file-body" (body-mime-type body)))
        (is (= test-file (.getFile body)))))

    (testing "can create FileBody with content, mime-type and name"
      (let [test-file (File. "testfile")
            body (make-multipart-body {:content   test-file
                                       :mime-type "file-body"
                                       :name      "testname"})]
        (is (instance? FileBody body))
        (is (= "file-body" (body-mime-type body)))
        (is (= test-file (.getFile body)))
        (is (= "testname" (.getFilename body)))))

    (testing "can create FileBody with content and mime-type and encoding"
      (let [test-file (File. "testfile")
            body (make-multipart-body {:content   test-file
                                       :mime-type "file-body"
                                       :encoding  "ascii"})]
        (is (instance? FileBody body))
        (is (= "file-body" (body-mime-type body)))
        (is (= (Charset/forName "ascii") (body-charset body)))
        (is (= test-file (.getFile body)))))

    (testing "can create FileBody with content, mime-type, encoding and name"
      (let [test-file (File. "testfile")
            body (make-multipart-body {:content   test-file
                                       :mime-type "file-body"
                                       :encoding  "ascii"
                                       :name      "testname"})]
        (is (instance? FileBody body))
        (is (= "file-body" (body-mime-type body)))
        (is (= (Charset/forName "ascii") (body-charset body)))
        (is (= test-file (.getFile body) ))
        (is (= "testname" (.getFilename body)))))))
