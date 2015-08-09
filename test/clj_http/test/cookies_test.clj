(ns clj-http.test.cookies-test
  (:require [clj-http.cookies :refer :all]
            [clj-http.util :refer :all]
            [clojure.test :refer :all])
  (:import (org.apache.http.impl.cookie BasicClientCookie BasicClientCookie2)))

(defn refer-private [ns]
  (doseq [[symbol var] (ns-interns ns)]
    (when (:private (meta var))
      (intern *ns* symbol var))))

(refer-private 'clj-http.cookies)

(def session (str "ltQGXSNp7cgNeFG6rPE06qzriaI+R8W7zJKFu4UOlX4=-"
                  "-lWgojFmZlDqSBnYJlUmwhqXL4OgBTkra5WXzi74v+nE="))

(deftest test-compact-map
  (are [map expected]
    (is (= expected (compact-map map)))
    {:a nil :b 2 :c 3 :d nil}
    {:b 2 :c 3}
    {:comment nil :domain "example.com" :path "/" :ports [80 8080] :value 1}
    {:domain "example.com" :path "/" :ports [80 8080] :value 1}))

(deftest test-decode-cookie
  (are [set-cookie-str expected]
    (is (= expected (decode-cookie set-cookie-str)))
    nil nil
    "" nil
    "example-cookie=example-value;Path=/"
    ["example-cookie"
     {:discard true :path "/" :secure false
      :value "example-value" :version 0}]
    "example-cookie=example-value;Domain=.example.com;Path=/"
    ["example-cookie"
     {:discard true :domain "example.com" :secure false :path "/"
      :value "example-value" :version 0}]))

(deftest test-decode-cookies-with-seq
  (let [cookies (decode-cookies [(str "ring-session=" session)])]
    (is (map? cookies))
    (is (= 1 (count cookies)))
    (let [cookie (get cookies "ring-session")]
      (is (= true (:discard cookie)))
      (is (nil? (:domain cookie)))
      (is (= "/" (:path cookie)))
      (is (= session (:value cookie)))
      (is (= 0 (:version cookie))))))

(deftest test-decode-cookies-with-string
  (let [cookies (decode-cookies
                 (str "ring-session=" session ";Path=/"))]
    (is (map? cookies))
    (is (= 1 (count cookies)))
    (let [cookie (get cookies "ring-session")]
      (is (= true (:discard cookie)))
      (is (nil? (:domain cookie)))
      (is (= "/" (:path cookie)))
      (is (= session (:value cookie)))
      (is (= 0 (:version cookie))))))

(deftest test-decode-cookie-header
  (are [response expected]
    (is (= expected (decode-cookie-header response)))
    {:headers {"set-cookie" "a=1"}}
    {:cookies {"a" {:discard true :path "/" :secure false
                    :value "1" :version 0}} :headers {}}
    {:headers {"set-cookie"
               (str "ring-session=" session ";Path=/")}}
    {:cookies {"ring-session"
               {:discard true :path "/" :secure false
                :value session :version 0}} :headers {}}))

(deftest test-encode-cookie
  (are [cookie expected]
    (is (= expected (encode-cookie cookie)))
    [:a {:value "b"}] "a=b"
    ["a" {:value "b"}] "a=b"
    ["example-cookie"
     {:domain ".example.com" :path "/" :value "example-value"}]
    "example-cookie=example-value"
    ["ring-session" {:value session}]
    (str "ring-session=" session)))

(deftest test-encode-cookies
  (are [cookie expected]
    (is (= expected (encode-cookies cookie)))
    (sorted-map :a {:value "b"} :c {:value "d"} :e {:value "f"})
    "a=b;c=d;e=f"
    (sorted-map "a" {:value "b"} "c" {:value "d"} "e" {:value "f"})
    "a=b;c=d;e=f"
    {"example-cookie"
     {:domain ".example.com" :path "/" :value "example-value"}}
    "example-cookie=example-value"
    {"example-cookie"
     {:domain ".example.com" :path "/" :value "example-value"
      :discard true :version 0}}
    "example-cookie=example-value"
    {"ring-session" {:value session}}
    (str "ring-session=" session)))

(deftest test-encode-cookie-header
  (are [request expected]
    (is (= expected (encode-cookie-header request)))
    {:cookies {"a" {:value "1"}}}
    {:headers {"Cookie" "a=1"}}
    {:cookies
     {"example-cookie" {:domain ".example.com" :path "/"
                        :value "example-value"}}}
    {:headers {"Cookie" "example-cookie=example-value"}}))

(deftest test-to-basic-client-cookie-with-simple-cookie
  (let [cookie (to-basic-client-cookie
                ["ring-session"
                 {:value session
                  :path "/"
                  :domain "example.com"}])]
    (is (= "ring-session" (.getName cookie)))
    (is (= session (.getValue cookie)))
    (is (= "/" (.getPath cookie)))
    (is (= "example.com" (.getDomain cookie)))
    (is (nil? (.getComment cookie)))
    (is (nil? (.getCommentURL cookie)))
    (is (not (.isPersistent cookie)))
    (is (nil? (.getExpiryDate cookie)))
    (is (nil? (seq (.getPorts cookie))))
    (is (not (.isSecure cookie)))
    (is (= 0 (.getVersion cookie)))))

(deftest test-to-basic-client-cookie-with-full-cookie
  (let [cookie (to-basic-client-cookie
                ["ring-session"
                 {:value session
                  :path "/"
                  :domain "example.com"
                  :comment "Example Comment"
                  :comment-url "http://example.com/cookies"
                  :discard true
                  :expires (java.util.Date. (long 0))
                  :ports [80 8080]
                  :secure true
                  :version 0}])]
    (is (= "ring-session" (.getName cookie)))
    (is (= session (.getValue cookie)))
    (is (= "/" (.getPath cookie)))
    (is (= "example.com" (.getDomain cookie)))
    (is (= "Example Comment" (.getComment cookie)))
    (is (= "http://example.com/cookies" (.getCommentURL cookie)))
    (is (not (.isPersistent cookie)))
    (is (= (java.util.Date. (long 0)) (.getExpiryDate cookie)))
    (is (= [80 8080] (seq (.getPorts cookie))))
    (is (.isSecure cookie))
    (is (= 0 (.getVersion cookie)))))

(deftest test-to-basic-client-cookie-with-symbol-as-name
  (let [cookie (to-basic-client-cookie
                [:ring-session {:value session :path "/"
                                :domain "example.com"}])]
    (is (= "ring-session" (.getName cookie)))))

(deftest test-to-cookie-with-simple-cookie
  (let [[name content]
        (to-cookie
         (doto (BasicClientCookie. "example-cookie" "example-value")
           (.setDomain "example.com")
           (.setPath "/")))]
    (is (= "example-cookie" name))
    (is (nil? (:comment content)))
    (is (nil? (:comment-url content)))
    (is (:discard content))
    (is (= "example.com" (:domain content)))
    (is (nil? (:expires content)))
    (is (nil? (:ports content)))
    (is (not (:secure content)))
    (is (= 0 (:version content)))
    (is (= "example-value" (:value content)))))

(deftest test-to-cookie-with-full-cookie
  (let [[name content]
        (to-cookie
         (doto (BasicClientCookie2. "example-cookie" "example-value")
           (.setComment "Example Comment")
           (.setCommentURL "http://example.com/cookies")
           (.setDiscard true)
           (.setDomain "example.com")
           (.setExpiryDate (java.util.Date. (long 0)))
           (.setPath "/")
           (.setPorts (int-array [80 8080]))
           (.setSecure true)
           (.setVersion 1)))]
    (is (= "example-cookie" name))
    (is (= "Example Comment" (:comment content)))
    (is (= "http://example.com/cookies" (:comment-url content)))
    (is (= true (:discard content)))
    (is (= "example.com" (:domain content)))
    (is (= (java.util.Date. (long 0)) (:expires content)))
    (is (= [80 8080] (:ports content)))
    (is (= true (:secure content)))
    (is (= 1 (:version content)))
    (is (= "example-value" (:value content)))))

(deftest test-wrap-cookies
  (is (= {:cookies {"example-cookie" {:discard true :domain "example.com"
                                      :path "/" :value "example-value"
                                      :version 0 :secure false}} :headers {}}
         ((wrap-cookies
           (fn [request]
             (is (= (get (:headers request) "Cookie") "a=1;b=2"))
             {:headers
              {"set-cookie"
               "example-cookie=example-value;Domain=.example.com;Path=/"}}))
          {:cookies (sorted-map :a {:value "1"} :b {:value "2"})})))
  (is (= {:headers {"set-cookie"
                    "example-cookie=example-value;Domain=.example.com;Path=/"}}
         ((wrap-cookies
           (fn [request]
             (is (= (get (:headers request) "Cookie") "a=1;b=2"))
             {:headers
              {"set-cookie"
               "example-cookie=example-value;Domain=.example.com;Path=/"}}))
          {:cookies (sorted-map :a {:value "1"} :b {:value "2"})
           :decode-cookies false}))))
