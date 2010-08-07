(ns clj-http.client-test
  (:use clojure.test)
  (:require [clj-http.client :as client]))

(def base-req
  {:scheme "http"
   :server-name "localhost"
   :server-port 8080})

(deftest rountrip
  (let [resp (client/request (merge base-req {:uri "/get" :method :get}))]
    (is (= 200 (:status resp)))
    (is (= "4" (get-in resp [:headers "content-length"])))
    (is (= "get\n" (:body resp)))))

(def echo-client identity)

(def method-client
  (client/wrap-method echo-client))

(deftest method-pass
  (let [echo (method-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest method-apply
  (let [echo (method-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))

