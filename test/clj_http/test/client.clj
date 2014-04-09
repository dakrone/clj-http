(ns clj-http.test.client
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.conn-mgr :as conn]
            [clj-http.test.core :refer [run-server]]
            [clj-http.util :as util]
            [clojure.java.io :refer [resource]]
            [clojure.test :refer :all])
  (:import (java.net UnknownHostException)
           (java.util Arrays)
           (java.io ByteArrayInputStream)
           (org.apache.http HttpEntity)))

(def base-req
  {:scheme :http
   :server-name "localhost"
   :server-port 18080})

(defn request [req]
  (client/request (merge base-req req)))

(deftest ^:integration roundtrip
  (run-server)
  ;; roundtrip with scheme as a keyword
  (let [resp (request {:uri "/get" :method :get})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp))))
  ;; roundtrip with scheme as a string
  (let [resp (request {:uri "/get" :method :get
                       :scheme "http"})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp)))))

(deftest ^:integration nil-input
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/get nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/post nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/put nil)))
  (is (thrown-with-msg? Exception #"Host URL cannot be nil"
                        (client/delete nil))))

(defn is-passed [middleware req]
  (let [client (middleware identity)]
    (is (= req (client req)))))

(defn is-applied [middleware req-in req-out]
  (let [client (middleware identity)]
    (is (= req-out (client req-in)))))

(deftest redirect-on-get
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com"
                        :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://bar.com/bat"] (:trace-redirects resp)))
    (is (= "/bat" (:uri (:req resp))))))

(deftest relative-redirect-on-get
  (let [client (fn [req]
                 (if (:redirects-count req)
                   {:status 200
                    :req req}
                   {:status 302
                    :headers {"location" "/bat"}}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com"
                        :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://foo.com/bat"] (:trace-redirects resp)))
    (is (= "/bat" (:uri (:req resp))))))

(deftest trace-redirects-using-uri
  (let [client (fn [req] {:status 200 :req req})
        r-client (-> client client/wrap-redirects)
        resp (r-client {:scheme :http :server-name "foo.com" :uri "/"
                        :request-method :get})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= [] (:trace-redirects resp)))))

(deftest redirect-without-location-header
  (let [client (fn [req]
                 {:status 302 :body "no redirection here"})
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com"
                        :request-method :get})]
    (is (= 302 (:status resp)))
    (is (= ["http://foo.com"] (:trace-redirects resp)))
    (is (= "no redirection here" (:body resp)))))

(deftest redirect-with-query-string
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat?x=y"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com"
                        :request-method :get :query-params {:x "z"}})]
    (is (= 200 (:status resp)))
    (is (= :get (:request-method (:req resp))))
    (is (= :http (:scheme (:req resp))))
    (is (= ["http://foo.com" "http://bar.com/bat?x=y"] (:trace-redirects resp)))
    (is (= "/bat" (:uri (:req resp))))
    (is (= "x=y" (:query-string (:req resp))))
    (is (nil? (:query-params (:req resp))))))

(deftest max-redirects
  (let [client (fn [req]
                 (if (= "foo.com" (:server-name req))
                   {:status 302
                    :headers {"location" "http://bar.com/bat"}}
                   {:status 200
                    :req req}))
        r-client (-> client client/wrap-url client/wrap-redirects)
        resp (r-client {:server-name "foo.com" :url "http://foo.com"
                        :request-method :get :max-redirects 0})]
    (is (= 302 (:status resp)))
    (is (= ["http://foo.com"] (:trace-redirects resp)))
    (is (= "http://bar.com/bat" (get (:headers resp) "location")))))

(deftest redirect-303-to-get-on-any-method
  (doseq [method [:get :head :post :delete :put :option]]
    (let [client (fn [req]
                   (if (= "foo.com" (:server-name req))
                     {:status 303
                      :headers {"location" "http://bar.com/bat"}}
                     {:status 200
                      :req req}))
          r-client (-> client client/wrap-url client/wrap-redirects)
          resp (r-client {:server-name "foo.com" :url "http://foo.com"
                          :request-method method})]
      (is (= 200 (:status resp)))
      (is (= :get (:request-method (:req resp))))
      (is (= :http (:scheme (:req resp))))
      (is (= ["http://foo.com" "http://bar.com/bat"] (:trace-redirects resp)))
      (is (= "/bat" (:uri (:req resp)))))))

(deftest pass-on-non-redirect
  (let [client (fn [req] {:status 200 :body (:body req)})
        r-client (client/wrap-redirects client)
        resp (r-client {:body "ok" :url "http://foo.com"})]
    (is (= 200 (:status resp)))
    (is (= ["http://foo.com"] (:trace-redirects resp)))
    (is (= "ok" (:body resp)))))

(deftest pass-on-non-redirectable-methods
  (doseq [method [:put :post :delete]
          status [301 302 307]]
    (let [client (fn [req] {:status status :body (:body req)
                            :headers {"location" "http://foo.com/bat"}})
          r-client (client/wrap-redirects client)
          resp (r-client {:body "ok" :url "http://foo.com"
                          :request-method method})]
      (is (= status (:status resp)))
      (is (= ["http://foo.com"] (:trace-redirects resp)))
      (is (= {"location" "http://foo.com/bat"} (:headers resp)))
      (is (= "ok" (:body resp))))))

(deftest force-redirects-on-non-redirectable-methods
  (doseq [method [:put :post :delete]
          [status expected-method] [[301 :get] [302 :get] [307 method]]]
    (let [client (fn [{:keys [trace-redirects body] :as req}]
                   (if trace-redirects
                     {:status 200 :body body :trace-redirects trace-redirects
                      :req req}
                     {:status status :body body :req req
                      :headers {"location" "http://foo.com/bat"}}))
          r-client (client/wrap-redirects client)
          resp (r-client {:body "ok" :url "http://foo.com"
                          :request-method method
                          :force-redirects true})]
      (is (= 200 (:status resp)))
      (is (= ["http://foo.com" "http://foo.com/bat"] (:trace-redirects resp)))
      (is (= "ok" (:body resp)))
      (is (= expected-method (:request-method (:req resp)))))))

(deftest pass-on-follow-redirects-false
  (let [client (fn [req] {:status 302 :body (:body req)})
        r-client (client/wrap-redirects client)
        resp (r-client {:body "ok" :follow-redirects false})]
    (is (= 302 (:status resp)))
    (is (= "ok" (:body resp)))
    (is (nil? (:trace-redirects resp)))))

(deftest throw-on-exceptional
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #"500"
                          (e-client {}))))
  (let [client (fn [req] {:status 500 :body "foo"})
        e-client (client/wrap-exceptions client)]
    (is (thrown-with-msg? Exception #":body"
                          (e-client {:throw-entire-message? true})))))

(deftest pass-on-non-exceptional
  (let [client (fn [req] {:status 200})
        e-client (client/wrap-exceptions client)
        resp (e-client {})]
    (is (= 200 (:status resp)))))

(deftest pass-on-exceptional-when-surpressed
  (let [client (fn [req] {:status 500})
        e-client (client/wrap-exceptions client)
        resp (e-client {:throw-exceptions false})]
    (is (= 500 (:status resp)))))

(deftest apply-on-compressed
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/gzip (util/utf8-bytes "foofoofoo"))
                  :headers {"content-encoding" "gzip"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "foofoofoo" (util/utf8-string (:body resp))))
    (is (= "gzip" (:orig-content-encoding resp)))
    (is (= nil (get-in resp [:headers "content-encoding"])))))

(deftest apply-on-deflated
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/deflate (util/utf8-bytes "barbarbar"))
                  :headers {"content-encoding" "deflate"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "barbarbar" (-> resp :body util/force-byte-array util/utf8-string))
        "string correctly inflated")
    (is (= "deflate" (:orig-content-encoding resp)))
    (is (= nil (get-in resp [:headers "content-encoding"])))))

(deftest t-disabled-body-decompression
  (let [client (fn [req]
                 (is (not= "gzip, deflate"
                           (get-in req [:headers "accept-encoding"])))
                 {:body (util/deflate (util/utf8-bytes "barbarbar"))
                  :headers {"content-encoding" "deflate"}})
        c-client (client/wrap-decompression client)
        resp (c-client {:decompress-body false})]
    (is (= (slurp (util/inflate (util/deflate (util/utf8-bytes "barbarbar"))))
           (slurp (util/inflate (-> resp :body util/force-byte-array))))
        "string not inflated")
    (is (= nil (:orig-content-encoding resp)))
    (is (= "deflate" (get-in resp [:headers "content-encoding"])))))

(deftest t-weird-non-known-compression
  (let [client (fn [req]
                 (is (= "gzip, deflate"
                        (get-in req [:headers "accept-encoding"])))
                 {:body (util/utf8-bytes "foofoofoo")
                  :headers {"content-encoding" "pig-latin"}})
        c-client (client/wrap-decompression client)
        resp (c-client {})]
    (is (= "foofoofoo" (util/utf8-string (:body resp))))
    (is (= "pig-latin" (:orig-content-encoding resp)))
    (is (= "pig-latin" (get-in resp [:headers "content-encoding"])))))

(deftest pass-on-non-compressed
  (let [c-client (client/wrap-decompression (fn [req] {:body "foo"}))
        resp (c-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest apply-on-accept
  (is-applied client/wrap-accept
              {:accept :json}
              {:headers {"accept" "application/json"}}))

(deftest pass-on-no-accept
  (is-passed client/wrap-accept
             {:uri "/foo"}))

(deftest apply-on-accept-encoding
  (is-applied client/wrap-accept-encoding
              {:accept-encoding [:identity :gzip]}
              {:headers {"accept-encoding" "identity, gzip"}}))

(deftest pass-on-no-accept-encoding
  (is-passed client/wrap-accept-encoding
             {:uri "/foo"}))

(deftest apply-on-output-coercion
  (let [client (fn [req] {:body (util/utf8-bytes "foo")})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (= "foo" (:body resp)))))

(deftest pass-on-no-output-coercion
  (let [client (fn [req] {:body nil})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo"})]
    (is (nil? (:body resp))))
  (let [the-stream (ByteArrayInputStream. (byte-array []))
        client (fn [req] {:body the-stream})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :stream})]
    (is (= the-stream (:body resp))))
  (let [client (fn [req] {:body :thebytes})
        o-client (client/wrap-output-coercion client)
        resp (o-client {:uri "/foo" :as :byte-array})]
    (is (= :thebytes (:body resp)))))

(deftest apply-on-input-coercion
  (let [i-client (client/wrap-input-coercion identity)
        resp (i-client {:body "foo"})
        resp2 (i-client {:body "foo2" :body-encoding "ASCII"})
        data (slurp (.getContent ^HttpEntity (:body resp)))
        data2 (slurp (.getContent ^HttpEntity (:body resp2)))]
    (is (= "UTF-8" (:character-encoding resp)))
    (is (= "foo" data))
    (is (= "ASCII" (:character-encoding resp2)))
    (is (= "foo2" data2))))

(deftest pass-on-no-input-coercion
  (is-passed client/wrap-input-coercion
             {:body nil}))

(deftest no-length-for-input-stream
  (let [i-client (client/wrap-input-coercion identity)
        resp1 (i-client {:body (ByteArrayInputStream. (util/utf8-bytes "foo"))})
        resp2 (i-client {:body (ByteArrayInputStream. (util/utf8-bytes "foo"))
                         :length 3})
        ^HttpEntity body1 (:body resp1)
        ^HttpEntity body2 (:body resp2)]
    (is (= -1 (.getContentLength body1)))
    (is (= 3 (.getContentLength body2)))))

(deftest apply-on-content-type
  (is-applied client/wrap-content-type
              {:content-type :json}
              {:headers {"content-type" "application/json"}
               :content-type :json})
  (is-applied client/wrap-content-type
              {:content-type :json :character-encoding "UTF-8"}
              {:headers {"content-type" "application/json; charset=UTF-8"}
               :content-type :json :character-encoding "UTF-8"}))

(deftest pass-on-no-content-type
  (is-passed client/wrap-content-type
             {:uri "/foo"}))

(deftest apply-on-query-params
  (is-applied client/wrap-query-params
              {:query-params {"foo" "bar" "dir" "<<"}}
              {:query-string "foo=bar&dir=%3C%3C"})
  (is-applied client/wrap-query-params
              {:query-string "foo=1"
               :query-params {"foo" ["2" "3"]}}
              {:query-string "foo=1&foo=2&foo=3"}))

(deftest pass-on-no-query-params
  (is-passed client/wrap-query-params
             {:uri "/foo"}))

(deftest apply-on-basic-auth
  (is-applied client/wrap-basic-auth
              {:basic-auth ["Aladdin" "open sesame"]}
              {:headers {"authorization"
                         "Basic QWxhZGRpbjpvcGVuIHNlc2FtZQ=="}}))

(deftest pass-on-no-basic-auth
  (is-passed client/wrap-basic-auth
             {:uri "/foo"}))

(deftest apply-on-oauth
  (is-applied client/wrap-oauth
              {:oauth-token "my-token"}
              {:headers {"authorization"
                         "Bearer my-token"}}))

(deftest pass-on-no-oauth
  (is-passed client/wrap-oauth
             {:uri "/foo"}))

(deftest apply-on-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val :method :post})]
    (is (= :val (:key echo)))
    (is (= :post (:request-method echo)))
    (is (not (:method echo)))))

(deftest pass-on-no-method
  (let [m-client (client/wrap-method identity)
        echo (m-client {:key :val})]
    (is (= :val (:key echo)))
    (is (not (:request-method echo)))))

(deftest apply-on-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:url "http://google.com:8080/baz foo?bar=bat bit?"})]
    (is (= :http (:scheme resp)))
    (is (= "google.com" (:server-name resp)))
    (is (= 8080 (:server-port resp)))
    (is (= "/baz%20foo" (:uri resp)))
    (is (= "bar=bat%20bit?" (:query-string resp)))))

(deftest pass-on-no-url
  (let [u-client (client/wrap-url identity)
        resp (u-client {:uri "/foo"})]
    (is (= "/foo" (:uri resp)))))

(deftest provide-default-port
  (is (= nil  (-> "http://example.com/" client/parse-url :server-port)))
  (is (= 8080 (-> "http://example.com:8080/" client/parse-url :server-port)))
  (is (= nil  (-> "https://example.com/" client/parse-url :server-port)))
  (is (= 8443 (-> "https://example.com:8443/" client/parse-url :server-port))))

(deftest apply-on-form-params
  (testing "With form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :post
                              :form-params (sorted-map :param1 "value1"
                                                       :param2 "value2")})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :put
                              :form-params (sorted-map :param1 "value1"
                                                       :param2 "value2")})]
      (is (= "param1=value1&param2=value2" (:body resp)))
      (is (= "application/x-www-form-urlencoded" (:content-type resp)))
      (is (not (contains? resp :form-params)))))
  (testing "With json form params"
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :post
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :put
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 "value1" :param2 "value2"}
          resp (param-client {:request-method :patch
                              :content-type :json
                              :form-params params})]
      (is (= (json/encode params) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params))))
    (let [param-client (client/wrap-form-params identity)
          params {:param1 (java.util.Date. (long 0))}
          resp (param-client {:request-method :put
                              :content-type :json
                              :form-params params
                              :json-opts {:date-format "yyyy-MM-dd"}})]
      (is (= (json/encode params {:date-format "yyyy-MM-dd"}) (:body resp)))
      (is (= "application/json" (:content-type resp)))
      (is (not (contains? resp :form-params)))))
  (testing "Ensure it does not affect GET requests"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:request-method :get
                              :body "untouched"
                              :form-params {:param1 "value1"
                                            :param2 "value2"}})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type)))))
  (testing "with no form params"
    (let [param-client (client/wrap-form-params identity)
          resp (param-client {:body "untouched"})]
      (is (= "untouched" (:body resp)))
      (is (not (contains? resp :content-type))))))

(deftest apply-on-nested-params
  (testing "nested parameter maps"
    (are [in out] (is-applied client/wrap-nested-params
                              {:query-params in :form-params in}
                              {:query-params out :form-params out})
         {"foo" "bar"} {"foo" "bar"}
         {"x" {"y" "z"}} {"x[y]" "z"}
         {"a" {"b" {"c" "d"}}} {"a[b][c]" "d"}
         {"a" "b", "c" "d"} {"a" "b", "c" "d"}))

  (testing "not creating empty param maps"
    (is-applied client/wrap-query-params {} {})))

(deftest t-ignore-unknown-host
  (is (thrown? UnknownHostException (client/get "http://aorecuf892983a.com")))
  (is (nil? (client/get "http://aorecuf892983a.com"
                        {:ignore-unknown-host? true}))))

(deftest test-status-predicates
  (testing "2xx statuses"
    (doseq [s (range 200 299)]
      (is (client/success? {:status s}))
      (is (not (client/redirect? {:status s})))
      (is (not (client/client-error? {:status s})))
      (is (not (client/server-error? {:status s})))))
  (testing "3xx statuses"
    (doseq [s (range 300 399)]
      (is (not (client/success? {:status s})))
      (is (client/redirect? {:status s}))
      (is (not (client/client-error? {:status s})))
      (is (not (client/server-error? {:status s})))))
  (testing "4xx statuses"
    (doseq [s (range 400 499)]
      (is (not (client/success? {:status s})))
      (is (not (client/redirect? {:status s})))
      (is (client/client-error? {:status s}))
      (is (not (client/server-error? {:status s})))))
  (testing "5xx statuses"
    (doseq [s (range 500 599)]
      (is (not (client/success? {:status s})))
      (is (not (client/redirect? {:status s})))
      (is (not (client/client-error? {:status s})))
      (is (client/server-error? {:status s}))))
  (testing "409 Conflict"
    (is (client/conflict? {:status 409}))
    (is (not (client/conflict? {:status 201})))
    (is (not (client/conflict? {:status 404})))))

(deftest test-wrap-lower-case-headers
  (is (= {:status 404} ((client/wrap-lower-case-headers
                         (fn [r] r)) {:status 404})))
  (is (= {:headers {"content-type" "application/json"}}
         ((client/wrap-lower-case-headers
           #(do (is (= {:headers {"accept" "application/json"}} %1))
                {:headers {"Content-Type" "application/json"}}))
          {:headers {"Accept" "application/json"}}))))

(deftest t-request-timing
  (is (pos? (:request-time ((client/wrap-request-timing
                             (fn [r] (Thread/sleep 15) r)) {})))))

(deftest t-wrap-additional-header-parsing
  (let [^String text (slurp (resource "header-test.html"))
        client (fn [req] {:body (.getBytes text)})
        new-client (client/wrap-additional-header-parsing client)
        resp (new-client {:decode-body-headers true})
        resp2 (new-client {:decode-body-headers false})]
    (is (= {"content-type" "text/html; charset=Shift_JIS"
            "content-style-type" "text/css"
            "content-script-type" "text/javascript"}
           (:headers resp)))
    (is (nil? (:headers resp2)))))

(deftest t-wrap-additional-header-parsing-html5
  (let [^String text (slurp (resource "header-html5-test.html"))
        client (fn [req] {:body (.getBytes text)})
        new-client (client/wrap-additional-header-parsing client)
        resp (new-client {:decode-body-headers true})]
    (is (= {"content-type" "text/html; charset=UTF-8"}
           (:headers resp)))))

(deftest ^:integration t-request-without-url-set
  (run-server)
  ;; roundtrip with scheme as a keyword
  (let [resp (request {:uri "/redirect-to-get"
                       :method :get})]
    (is (= 200 (:status resp)))
    (is (= "close" (get-in resp [:headers "connection"])))
    (is (= "get" (:body resp)))))

(deftest ^:integration t-reusable-conn-mgrs
  (run-server)
  (let [cm (conn/make-reusable-conn-manager {:timeout 10 :insecure? false})
        resp1 (request {:uri "/redirect-to-get"
                        :method :get
                        :connection-manager cm})
        resp2 (request {:uri "/redirect-to-get"
                        :method :get})]
    (is (= 200 (:status resp1) (:status resp2)))
    (is (nil? (get-in resp1 [:headers "connection"]))
        "connection should remain open")
    (is (= "close" (get-in resp2 [:headers "connection"]))
        "connection should be closed")
    (.shutdown cm)))

(deftest test-url-encode-path
  (is (= (client/url-encode-illegal-characters "?foo bar+baz[]75")
         "?foo%20bar+baz%5B%5D75"))
  (is (= {:uri (str "/:@-._~!$&'()*+,="
                    ";"
                    ":@-._~!$&'()*+,"
                    "="
                    ":@-._~!$&'()*+,==")
          :query-string (str "/?:@-._~!$'()*+,;"
                             "="
                             "/?:@-._~!$'()*+,;==")}
         ;; This URL sucks, yes, it's actually a valid URL
         (select-keys (client/parse-url
                       (str "http://example.com/:@-._~!$&'()*+,=;:@-._~!$&'()*+"
                            ",=:@-._~!$&'()*+,==?/?:@-._~!$'()*+,;=/?:@-._~!$'("
                            ")*+,;==#/?:@-._~!$&'()*+,;="))
                      [:uri :query-string])))
  (let [all-chars (apply str (map char (range 256)))
        all-legal (client/url-encode-illegal-characters all-chars)]
    (is (= all-legal
           (client/url-encode-illegal-characters all-legal)))))

(deftest t-coercion-methods
  (let [json-body (ByteArrayInputStream. (.getBytes "{\"foo\":\"bar\"}"))
        auto-body (ByteArrayInputStream. (.getBytes "{\"foo\":\"bar\"}"))
        edn-body (ByteArrayInputStream. (.getBytes "{:foo \"bar\"}"))
        json-resp {:body json-body :status 200
                   :headers {"content-type" "application/json"}}
        auto-resp {:body auto-body :status 200
                   :headers {"content-type" "application/json"}}
        edn-resp {:body edn-body :status 200
                  :headers {"content-type" "application/edn"}}]
    (is (= {:foo "bar"}
           (:body (client/coerce-response-body {:as :json} json-resp))
           (:body (client/coerce-response-body {:as :clojure} edn-resp))
           (:body (client/coerce-response-body {:as :auto} auto-resp))))))

(deftest ^:integration t-with-middleware
  (run-server)
  (is (:request-time (request {:uri "/get" :method :get})))
  (is (= client/*current-middleware* client/default-middleware))
  (client/with-middleware [client/wrap-url
                           client/wrap-method
                           #'client/wrap-request-timing]
    (is (:request-time (request {:uri "/get" :method :get})))
    (is (= client/*current-middleware* [client/wrap-url
                                        client/wrap-method
                                        #'client/wrap-request-timing])))
  (client/with-middleware (->> client/default-middleware
                               (remove #{client/wrap-request-timing}))
    (is (not (:request-time (request {:uri "/get" :method :get}))))
    (is (not (contains? (set client/*current-middleware*)
                        client/wrap-request-timing)))
    (is (contains? (set client/default-middleware)
                   client/wrap-request-timing))))
