(ns clj-http.core-test
  (:use clojure.test)
  (:require [clojure.contrib.pprint :as pp])
  (:require [clojure.contrib.io :as io])
  (:require [clj-http.core :as core]))

(defn handler [req]
  (pp/pprint req)
  (println) (println)
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
      {:status 200 :body "get"}
    [:head "/head"]
      {:status 200}
    [:get "/content-type"]
      {:status 200 :body (:content-type req)}))

(def base-req
  {:scheme "http"
   :server-name "localhost"
   :server-port 8080})

(defn request [req]
  (core/request (merge base-req req)))

(deftest makes-get-request
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get\n" (io/slurp* (:body resp))))))

(deftest makes-head-request
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))))

(deftest sets-content-type-with-charset
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain" :character-encoding "UTF-8"})]
    (is (= 200 (:status resp)))
    (is (= "text/plain; charset=UTF-8\n" (io/slurp* (:body resp))))))
