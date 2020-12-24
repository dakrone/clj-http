(ns clj-http.test.cookies-test
  (:require [clj-http.cookies :refer :all]
            [clojure.test :refer :all])
  (:import org.apache.hc.client5.http.impl.cookie.BasicClientCookie))

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
    ["example-cookie" {:path "/" :secure false :value "example-value"}]
    "example-cookie=example-value;Domain=.example.com;Path=/"
    ["example-cookie"
     {:domain "example.com" :secure false :path "/" :value "example-value"}]))

(deftest test-decode-cookies-with-seq
  (let [cookies (decode-cookies [(str "ring-session=" session)])]
    (is (map? cookies))
    (is (= 1 (count cookies)))
    (let [cookie (get cookies "ring-session")]
      (is (nil? (:domain cookie)))
      (is (= "/" (:path cookie)))
      (is (= session (:value cookie))))))

(deftest test-decode-cookies-with-string
  (let [cookies (decode-cookies
                 (str "ring-session=" session ";Path=/"))]
    (is (map? cookies))
    (is (= 1 (count cookies)))
    (let [cookie (get cookies "ring-session")]
      (is (nil? (:domain cookie)))
      (is (= "/" (:path cookie)))
      (is (= session (:value cookie))))))

(deftest test-decode-cookie-header
  (are [response expected]
    (is (= expected (decode-cookie-header response)))
    {:headers {"set-cookie" "a=1"}}
    {:cookies {"a" {:path "/" :secure false :value "1"}} :headers {}}
    {:headers {"set-cookie" (str "ring-session=" session ";Path=/")}}
    {:cookies {"ring-session"
               {:path "/" :secure false :value session}} :headers {}}))

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
    (is (nil? (.getExpiryDate cookie)))
    (is (not (.isSecure cookie)))))

(deftest test-to-basic-client-cookie-with-full-cookie
  (let [cookie (to-basic-client-cookie
                ["ring-session"
                 {:value session
                  :path "/"
                  :domain "example.com"
                  :expires (java.util.Date. (long 0))
                  :secure true}])]
    (is (= "ring-session" (.getName cookie)))
    (is (= session (.getValue cookie)))
    (is (= "/" (.getPath cookie)))
    (is (= "example.com" (.getDomain cookie)))
    (is (= (java.util.Date. (long 0)) (.getExpiryDate cookie)))
    (is (.isSecure cookie))))

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
    (is (= "example.com" (:domain content)))
    (is (nil? (:expires content)))
    (is (not (:secure content)))
    (is (= "example-value" (:value content)))))

(deftest test-to-cookie-with-full-cookie
  (let [[name content]
        (to-cookie
         (doto (BasicClientCookie. "example-cookie" "example-value")
           (.setDomain "example.com")
           (.setExpiryDate (java.util.Date. (long 0)))
           (.setPath "/")
           (.setSecure true)))]
    (is (= "example-cookie" name))
    (is (= "example.com" (:domain content)))
    (is (= (java.util.Date. (long 0)) (:expires content)))
    (is (= true (:secure content)))
    (is (= "example-value" (:value content)))))

(deftest test-wrap-cookies
  (is (= {:cookies {"example-cookie" {:domain "example.com"
                                      :path "/" :value "example-value"
                                      :secure false}} :headers {}}
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
