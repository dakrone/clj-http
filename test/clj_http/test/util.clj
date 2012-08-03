(ns clj-http.test.util
  (:require [clj-http.util :refer :all]
            [clojure.test :refer :all]))

(deftest test-lower-case-keys
  (are [map expected]
    (is (= expected (lower-case-keys map)))
    nil nil
    {} {}
    {"Accept" "application/json"} {"accept" "application/json"}
    {"X" {"Y" "Z"}} {"x" {"y" "Z"}}))
