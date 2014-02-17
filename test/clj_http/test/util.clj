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
