(ns clj-http.client-test
  (:use clojure.test)
  (:require [clj-http.client :as client]))

(def echo-client identity)

(def method-client
  (client/wrap-method echo-client))

(deftest method-pass
  (let [echo (method-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest method-pass
  (let [echo (method-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))
