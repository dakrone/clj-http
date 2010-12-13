(ns clj-http.core-test
  (:use clojure.test)
  (:require [clojure.contrib.pprint :as pp])
  (:require [clojure.contrib.io :as io])
  (:require [clj-http.core :as core])
  (:require [clj-http.util :as util]))

(defn handler [req]
  (pp/pprint req)
  (println) (println)
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
      {:status 200 :body "get"}
    [:head "/head"]
      {:status 200}
    [:get "/content-type"]
      {:status 200 :body (:content-type req)}
    [:get "/header"]
      {:status 200 :body (get-in req [:headers "x-my-header"])}
    [:post "/post"]
      {:status 200 :body (io/slurp* (:body req))}
    [:get "/error"]
      {:status 500 :body "o noes"}))

(def base-req
  {:scheme "http"
   :server-name "localhost"
   :server-port 8080})

(defn request [req]
  (core/request (merge base-req req)))

(defn slurp-body [req]
  (io/slurp* (:body req)))

(deftest makes-get-request
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest makes-head-request
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))))

(deftest sets-content-type-with-charset
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain" :character-encoding "UTF-8"})]
    (is (= "text/plain; charset=UTF-8" (slurp-body resp)))))

(deftest sets-content-type-without-charset
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain"})]
    (is (= "text/plain" (slurp-body resp)))))

(deftest sets-arbitrary-headers
  (let [resp (request {:request-method :get :uri "/header"
                       :headers {"X-My-Header" "header-val"}})]
    (is (= "header-val" (slurp-body resp)))))

(deftest sends-and-returns-byte-array-body
  (let [resp (request {:request-method :post :uri "/post"
                       :body (util/utf8-bytes "contents")})]
    (is (= 200 (:status resp)))
    (is (= "contents" (slurp-body resp)))))

(deftest returns-arbitrary-headers
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (string? (get-in resp [:headers "date"])))))

(deftest returns-status-on-exceptional-responses
  (let [resp (request {:request-method :get :uri "/error"})]
    (is (= 500 (:status resp)))))
