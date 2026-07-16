(ns clj-http.test.ring-multipart-test
  (:require [clojure.test :refer :all]
            [ring.mock.request :as request]
            [ring.middleware.multipart-params :refer [wrap-multipart-params]]))

(defn mock-handler [req]
  {:status 200
   :body req})

(def mock-app (-> mock-handler wrap-multipart-params))

(def mock-req
  (-> (request/request :get "/test")
      (request/multipart-body {:value (byte-array (range 0 128))})))

(deftest test-ring-multipart-request
  (testing "Completes without throwing due to old (< 2.18) commons-io version" 
    (is (= 200 (:status (mock-app mock-req))))))

(test-ring-multipart-request)