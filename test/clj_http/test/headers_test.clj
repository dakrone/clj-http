(ns clj-http.test.headers-test
  (:require [clj-http.client :as client]
            [clj-http.headers :refer :all]
            [clj-http.util :refer [lower-case-keys]]
            [clojure.test :refer :all])
  (:import (javax.servlet.http HttpServletRequest
                               HttpServletResponse)
           (org.eclipse.jetty.server Request Server)
           (org.eclipse.jetty.server.handler AbstractHandler)))

(deftest test-special-case
  (are [expected given]
    (is (= expected (special-case given)))
    nil nil
    "" ""
    "foo" "foo"
    "DNT" "dnt"
    "P3P" "P3P"
    "Content-MD5" "content-md5"))

(deftest test-canonicalize
  (are [expected given]
    (is (= expected (canonicalize given)))
    nil nil
    "" ""
    "Date" :date
    "Date" :DATE
    "Foo-Bar-Baz" :foo-bar-baz
    "Content-MD5" :content-md5))

(deftest test-normalize
  (are [expected given]
    (is (= expected (normalize given)))
    nil nil
    "" ""
    "foo" "foo")
  (is (= "foo"
         (normalize "foo")
         (normalize :foo)
         (normalize "Foo")
         (normalize :FOO))))

(deftest test-assoc-join
  (is (= {:foo "1"} (assoc-join {} :foo "1")))
  (is (= {:foo "1"} (assoc-join {:foo nil} :foo "1")))
  (is (= {:foo ["1" "2"]} (assoc-join {:foo "1"} :foo "2")))
  (is (= {:foo ["1" "2" "3"]} (assoc-join {:foo ["1" "2"]} :foo "3"))))

(deftest test-header-map
  (let [m (header-map :foo "bar" "baz" "quux")
        m2 (assoc m :ham "eggs")]
    (is (= "bar"
           (:foo m)
           (:FOO m)
           (m :foo)
           (m "foo")
           (m "FOO")
           (get m "foo")))
    (is (= {"baz" "quux"}
           (dissoc m :foo)
           (dissoc m "foo")))
    (is (= #{"Foo" "baz"} (set (keys m))))
    (is (= #{"Foo" "Ham" "baz"} (set (keys m2))))
    (is (= "eggs" (m2 "ham")))
    (is (= "nope" (get m2 "absent" "nope")))
    (is (= "baz" (:foo (merge (header-map :foo "bar")
                              {"Foo" "baz"}))))
    (let [m-with-meta (with-meta m {:withmeta-test true})]
      (is (= (:withmeta-test (meta m-with-meta)) true)))))

(deftest test-empty
  (testing "an empty header-map is a header-map"
    (let [m (header-map :foo :bar)]
      (is (= (class m)
             (class (empty m)))))))

(defn ^Server header-server
  "fixture server that copies all request headers into the response as
  response headers"
  []
  ;; argh, we can't use ring for this, because it lowercases headers
  ;; on the server side, and we explicitly want to get back the
  ;; headers as they are. so we'll just use jetty directly, nbd.
  (doto (Server. 18181)
    (.setHandler (proxy [AbstractHandler] []
                   (handle [target
                            ^Request base-request
                            ^HttpServletRequest request
                            ^HttpServletResponse response]
                     (.setHandled base-request true)
                     (.setStatus response 200)
                     ;; copy over request headers verbatim
                     (doseq [n (enumeration-seq (.getHeaderNames request))]
                       (doseq [v (enumeration-seq (.getHeaders request n))]
                         ;; (println n v) ;; useful for debugging
                         (.addHeader response n v)))
                     ;; add a response header of our own in known case
                     (.addHeader response "Echo-Server" "Says Hi!")
                     (.. response getWriter (print "Echo!")))))
    (.start)))

(deftest ^:integration test-wrap-header-map
  (let [server (header-server)]
    (try
      (let [headers {:foo "bar"
                     :etag "some etag"
                     :content-md5 "some md5"
                     :multi ["value1" "value2"]
                     "MySpecialCase" "something"}
            resp (client/get "http://localhost:18181" {:headers headers})
            resp-headers (:headers resp)]
        (testing "basic sanity checks"
          (is (= "Echo!" (:body resp)))
          (is (= "Says Hi!" (:echo-server resp-headers)))
          ;; was everything copied over correctly
          (doseq [[k v] headers]
            (is (= v (resp-headers k)))))
        (testing "foo is available as a variety of names"
          (is (= "bar"
                 (:foo resp-headers)
                 (resp-headers "foo")
                 (resp-headers "Foo"))))
        (testing "header case is preserved"
          (let [resp-headers (into {} resp-headers)] ;; no more magic
            (testing "keyword request headers are canonicalized"
              (is (= "bar" (resp-headers "Foo")))
              (is (= "some etag" (resp-headers "ETag")))
              (is (= "some md5" (resp-headers "Content-MD5")))
              (is (= ["value1" "value2"] (resp-headers "Multi"))))
            (testing "strings are as written"
              (is (= "something" (resp-headers "MySpecialCase")))))))
      (finally
        (.stop server)))))

(defmacro without-header-map [& body]
  `(client/with-middleware '~(->> client/default-middleware
                                  (list* client/wrap-lower-case-headers)
                                  (remove #(= wrap-header-map %))
                                  (vec))
     ~@body))

(deftest ^:integration test-dont-wrap-header-map
  (let [server (header-server)]
    (try
      (let [headers {"foo" "bar"
                     "etag" "some etag"
                     "content-md5" "some md5"
                     "multi" ["value1" "value2"]
                     "MySpecialCase" "something"}
            resp (without-header-map
                  (client/get "http://localhost:18181" {:headers headers}))
            resp-headers (:headers resp)]
        (testing "basic sanity checks"
          (is (= "Echo!" (:body resp)))
          ;; was everything copied over correctly
          (doseq [[k v] (lower-case-keys headers)]
            (is (= v (resp-headers k)))))
        (testing "header names are all lowercase"
          (is (= "bar" (resp-headers "foo")))
          (is (= "some etag" (resp-headers "etag")))
          (is (= "some md5" (resp-headers "content-md5")))
          (is (= ["value1" "value2"] (resp-headers "multi")))
          (is (= "something" (resp-headers "myspecialcase")))
          (is (= "Says Hi!" (resp-headers "echo-server")))))
      (finally
        (.stop server)))))
