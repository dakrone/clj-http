(ns clj-http.test.util-test
  (:require [clj-http.util :refer :all]
            [clojure.java.io :as io]
            [clojure.test :refer :all])
  (:import org.apache.commons.io.input.NullInputStream
           org.apache.commons.io.IOUtils))

(deftest test-lower-case-keys
  (are [map expected]
    (is (= expected (lower-case-keys map)))
    nil nil
    {} {}
    {"Accept" "application/json"} {"accept" "application/json"}
    {"X" {"Y" "Z"}} {"x" {"y" "Z"}}))

(deftest t-option-retrieval
  (is (= (opt {:thing? true :thing true} :thing) true))
  (is (= (opt {:thing? false :thing true} :thing) false))
  (is (= (opt {:thing? false :thing false} :thing) false))
  (is (= (opt {:thing? true :thing nil} :thing) true))
  (is (= (opt {:thing? nil :thing true} :thing) true))
  (is (= (opt {:thing? false :thing nil} :thing) false))
  (is (= (opt {:thing? nil :thing false} :thing) false))
  (is (= (opt {:thing? nil :thing nil} :thing) nil))
  (is (= (opt {:thing? :a :thing nil} :thing) :a)))

(deftest test-parse-content-type
  (are [s expected]
    (is (= expected (parse-content-type s)))
    nil nil
    "" nil
    "application/json"
    {:content-type :application/json
     :content-type-params {}}
    " application/json "
    {:content-type :application/json
     :content-type-params {}}
    "application/json; charset=UTF-8"
    {:content-type :application/json
     :content-type-params {:charset "UTF-8"}}
    " application/json;  charset=UTF-8 "
    {:content-type :application/json
     :content-type-params {:charset "UTF-8"}}
    " application/json;  charset=\"utf-8\" "
    {:content-type :application/json
     :content-type-params {:charset "utf-8"}}
    "text/html; charset=ISO-8859-4"
    {:content-type :text/html
     :content-type-params {:charset "ISO-8859-4"}}
    "text/html; charset="
    {:content-type :text/html
     :content-type-params {:charset nil}}))

(deftest test-force-byte-array
  (testing "empty InputStream returns empty byte-array"
    (is (= 0 (alength (force-byte-array (NullInputStream. 0))))))
  (testing "InputStream contain bytes for JPEG file is coereced properly"
    (let [jpg-path "test-resources/small.jpg"]
      ;; coerce to seq to force byte-by-byte comparison
      (is (= (seq (IOUtils/toByteArray (io/input-stream jpg-path)))
             (seq (force-byte-array (io/input-stream jpg-path))))))))

(deftest test-gunzip
  (testing "with input streams"
    (testing "with empty stream, does not apply gunzip stream"
      (is (= "" (slurp (gunzip (force-stream (byte-array 0)))))))
    (testing "with non-empty stream, gunzip decompresses data"
      (let [data "hello world"]
        (is (= data
               (slurp (gunzip (force-stream (gzip (.getBytes data)))))))))))
