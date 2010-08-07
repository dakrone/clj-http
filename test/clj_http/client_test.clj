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


(deftest redirect-on-get
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (client/wrap-redirects client)
        resp (r-client {:server-name "foo.com" :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= "http" (:scheme (:req resp))))
    (is (= "/bat" (:uri (:req resp))))))

(deftest redirect-to-get-on-head
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 303
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (client/wrap-redirects client)
        resp (r-client {:server-name "foo.com" :request-method :head})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= "http" (:scheme (:req resp))))
    (is (= "/bat" (:uri (:req resp))))))

(deftest pass-on-non-redirect
  (let [client (fn [req] {:status 200 :body (:body req)})
        r-client (client/wrap-redirects client)
        resp (r-client {:body "ok"})]
    (is (= 200 (:status resp)))
    (is (= "ok" (:body resp)))))


(deftest pass-on-no-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest apply-on-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))
