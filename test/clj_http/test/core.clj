(ns clj-http.test.core
  (:require [cheshire.core :as json]
            [clj-http.client :as client]
            [clj-http.core :as core]
            [clj-http.util :as util]
            [clojure.java.io :refer [file]]
            [clojure.pprint :as pp]
            [clojure.test :refer :all]
            [ring.adapter.jetty :as ring])
  (:import (java.io ByteArrayInputStream)
           (org.apache.http.params CoreConnectionPNames CoreProtocolPNames)
           (org.apache.http.conn.params ConnRoutePNames)
           (org.apache.http.message BasicHeader BasicHeaderIterator)
           (org.apache.http.client.methods HttpPost)
           (org.apache.http.client.params CookiePolicy ClientPNames)
           (org.apache.http HttpResponse HttpConnection HttpInetConnection
                            HttpVersion)
           (org.apache.http.protocol HttpContext ExecutionContext)
           (org.apache.http.impl.client DefaultHttpClient)
           (org.apache.http.cookie CookieSpecFactory)
           (org.apache.http.impl.cookie BrowserCompatSpec)
           (org.apache.http.client.params ClientPNames)
           (org.apache.http.cookie.params CookieSpecPNames)))

(defn handler [req]
  ;;(pp/pprint req)
  ;;(println) (println)
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
    {:status 200 :body "get"}
    [:get "/clojure"]
    {:status 200 :body "{:foo \"bar\" :baz 7M :eggplant {:quux #{1 2 3}}}"
     :headers {"content-type" "application/clojure"}}
    [:get "/edn"]
    {:status 200 :body "{:foo \"bar\" :baz 7M :eggplant {:quux #{1 2 3}}}"
     :headers {"content-type" "application/edn"}}
    [:get "/clojure-bad"]
    {:status 200 :body "{:foo \"bar\" :baz #=(+ 1 1)}"
     :headers {"content-type" "application/clojure"}}
    [:get "/json"]
    {:status 200 :body "{\"foo\":\"bar\"}"
     :headers {"content-type" "application/json"}}
    [:get "/json-array"]
    {:status 200 :body "[\"foo\", \"bar\"]"
     :headers {"content-type" "application/json"}}
    [:get "/json-bad"]
    {:status 400 :body "{\"foo\":\"bar\"}"}
    [:get "/redirect"]
    {:status 302
     :headers {"location" "http://localhost:18080/redirect"}}
    [:get "/redirect-to-get"]
    {:status 302
     :headers {"location" "http://localhost:18080/get"}}
    [:head "/head"]
    {:status 200}
    [:get "/content-type"]
    {:status 200 :body (:content-type req)}
    [:get "/header"]
    {:status 200 :body (get-in req [:headers "x-my-header"])}
    [:post "/post"]
    {:status 200 :body (slurp (:body req))}
    [:get "/error"]
    {:status 500 :body "o noes"}
    [:get "/timeout"]
    (do
      (Thread/sleep 10)
      {:status 200 :body "timeout"})
    [:delete "/delete-with-body"]
    {:status 200 :body "delete-with-body"}
    [:post "/multipart"]
    {:status 200 :body (:body req)}
    [:get "/get-with-body"]
    {:status 200 :body (:body req)}
    [:options "/options"]
    {:status 200 :body "options"}
    [:copy "/copy"]
    {:status 200 :body "copy"}
    [:move "/move"]
    {:status 200 :body "move"}
    [:patch "/patch"]
    {:status 200 :body "patch"}
    [:get "/headers"]
    {:status 200 :body (json/encode (:headers req))}))

(defn run-server
  []
  (defonce server
    (ring/run-jetty handler {:port 18080 :join? false})))

(defn localhost [path]
  (str "http://localhost:18080" path))

(def base-req
  {:scheme :http
   :server-name "localhost"
   :server-port 18080})

(defn request [req]
  (core/request (merge base-req req)))

(defn slurp-body [req]
  (slurp (:body req)))

(deftest ^:integration makes-get-request
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest ^:integration makes-head-request
  (run-server)
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))))

(deftest ^:integration sets-content-type-with-charset
  (run-server)
  (let [resp (client/request {:scheme :http
                              :server-name "localhost"
                              :server-port 18080
                              :request-method :get :uri "/content-type"
                              :content-type "text/plain"
                              :character-encoding "UTF-8"})]
    (is (= "text/plain; charset=UTF-8" (:body resp)))))

(deftest ^:integration sets-content-type-without-charset
  (run-server)
  (let [resp (client/request {:scheme :http
                              :server-name "localhost"
                              :server-port 18080
                              :request-method :get :uri "/content-type"
                              :content-type "text/plain"})]
    (is (= "text/plain" (:body resp)))))

(deftest ^:integration sets-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/header"
                       :headers {"x-my-header" "header-val"}})]
    (is (= "header-val" (slurp-body resp)))))

(deftest ^:integration sends-and-returns-byte-array-body
  (run-server)
  (let [resp (request {:request-method :post :uri "/post"
                       :body (util/utf8-bytes "contents")})]
    (is (= 200 (:status resp)))
    (is (= "contents" (slurp-body resp)))))

(deftest ^:integration returns-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (string? (get-in resp [:headers "date"])))
    (is (nil? (get-in resp [:headers "Date"])))))

(deftest ^:integration returns-status-on-exceptional-responses
  (run-server)
  (let [resp (request {:request-method :get :uri "/error"})]
    (is (= 500 (:status resp)))))

(deftest ^:integration sets-socket-timeout
  (run-server)
  (try
    (is (thrown? java.net.SocketTimeoutException
                 (client/request {:scheme :http
                                  :server-name "localhost"
                                  :server-port 18080
                                  :request-method :get :uri "/timeout"
                                  :socket-timeout 1})))))

(deftest ^:integration delete-with-body
  (run-server)
  (let [resp (request {:request-method :delete :uri "/delete-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))))

(deftest ^:integration self-signed-ssl-get
  (let [server (ring/run-jetty handler
                               {:port 8081 :ssl-port 18082
                                :ssl? true
                                :join? false
                                :keystore "test-resources/keystore"
                                :key-password "keykey"})]
    (try
      (is (thrown? sun.security.provider.certpath.SunCertPathBuilderException
                   (client/request {:scheme :https
                                    :server-name "localhost"
                                    :server-port 18082
                                    :request-method :get :uri "/get"})))
      (let [resp (request {:request-method :get :uri "/get" :server-port 18082
                           :scheme :https :insecure? true})]
        (is (= 200 (:status resp)))
        (is (= "get" (String. (util/force-byte-array (:body resp))))))
      (finally
        (.stop server)))))

(deftest ^:integration multipart-form-uploads
  (run-server)
  (let [bytes (util/utf8-bytes "byte-test")
        stream (ByteArrayInputStream. bytes)
        resp (request {:request-method :post :uri "/multipart"
                       :multipart [{:name "a" :content "testFINDMEtest"
                                    :encoding "UTF-8"
                                    :mime-type "application/text"}
                                   {:name "b" :content bytes
                                    :mime-type "application/json"}
                                   {:name "d"
                                    :content (file "test-resources/keystore")
                                    :encoding "UTF-8"
                                    :mime-type "application/binary"}
                                   {:name "c" :content stream
                                    :mime-type "application/json"}
                                   {:name "e" :part-name "eggplant"
                                    :content "content"
                                    :mime-type "application/text"}]})
        resp-body (apply str (map #(try (char %) (catch Exception _ ""))
                                  (util/force-byte-array (:body resp))))]
    (is (= 200 (:status resp)))
    (is (re-find #"testFINDMEtest" resp-body))
    (is (re-find #"application/json" resp-body))
    (is (re-find #"application/text" resp-body))
    (is (re-find #"UTF-8" resp-body))
    (is (re-find #"byte-test" resp-body))
    (is (re-find #"name=\"c\"" resp-body))
    (is (re-find #"name=\"d\"" resp-body))
    (is (re-find #"name=\"eggplant\"" resp-body))
    (is (re-find #"content" resp-body))))

(deftest ^:integration multipart-inputstream-length
  (run-server)
  (let [bytes (util/utf8-bytes "byte-test")
        stream (ByteArrayInputStream. bytes)
        resp (request {:request-method :post :uri "/multipart"
                       :multipart [{:name "c" :content stream :length 9
                                    :mime-type "application/json"}]})
        resp-body (apply str (map #(try (char %) (catch Exception _ ""))
                                  (util/force-byte-array (:body resp))))]
    (is (= 200 (:status resp)))
    (is (re-find #"byte-test" resp-body))))

(deftest ^:integration t-save-request-obj
  (run-server)
  (let [resp (request {:request-method :post :uri "/post"
                       :body "foo bar"
                       :save-request? true
                       :debug-body true})]
    (is (= 200 (:status resp)))
    (is (= {:scheme :http
            :http-url (localhost "/post")
            :request-method :post
            :save-request? true
            :debug-body true
            :uri "/post"
            :server-name "localhost"
            :server-port 18080
            :body-content "foo bar"
            :body-type String}
           (dissoc (:request resp) :body :http-req)))
    (is (instance? HttpPost (-> resp :request :http-req)))))

(deftest parse-headers
  (are [headers expected]
    (let [iterator (BasicHeaderIterator.
                    (into-array BasicHeader
                                (map (fn [[name value]]
                                       (BasicHeader. name value))
                                     headers)) nil)]
      (is (= (core/parse-headers iterator) expected)))

    [] {}

    [["Set-Cookie" "one"]] {"set-cookie" "one"}

    [["Set-Cookie" "one"] ["set-COOKIE" "two"]]
    {"set-cookie" ["one" "two"]}

    [["Set-Cookie" "one"] ["serVer" "some-server"] ["set-cookie" "two"]]
    {"set-cookie" ["one" "two"] "server" "some-server"}))

(deftest ^:integration t-streaming-response
  (run-server)
  (let [stream (:body (request {:request-method :get :uri "/get" :as :stream}))
        body (slurp stream)]
    (is (= "get" body))))

(deftest throw-on-invalid-body
  (is (thrown-with-msg? IllegalArgumentException #"Invalid request method :bad"
                        (client/request {:url "http://example.org"
                                         :method :bad}))))

(deftest ^:integration throw-on-too-many-redirects
  (run-server)
  (let [resp (client/get (localhost "/redirect")
                         {:max-redirects 2 :throw-exceptions false})]
    (is (= 302 (:status resp)))
    (is (= (apply vector (repeat 3 "http://localhost:18080/redirect"))
           (:trace-redirects resp))))
  (is (thrown-with-msg? Exception #"Too many redirects: 3"
                        (client/get (localhost "/redirect")
                                    {:max-redirects 2 :throw-exceptions true})))
  (is (thrown-with-msg? Exception #"Too many redirects: 21"
                        (client/get (localhost "/redirect")
                                    {:throw-exceptions true}))))

(deftest ^:integration get-with-body
  (run-server)
  (let [resp (request {:request-method :get :uri "/get-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))
    (is (= "foo bar" (String. (util/force-byte-array (:body resp)))))))

(deftest ^:integration head-with-body
  (run-server)
  (let [resp (request {:request-method :head :uri "/head" :body "foo"})]
    (is (= 200 (:status resp)))))

(deftest ^:integration t-clojure-output-coercion
  (run-server)
  (let [resp (client/get (localhost "/clojure") {:as :clojure})]
    (is (= 200 (:status resp)))
    (is (= {:foo "bar" :baz 7M :eggplant {:quux #{1 2 3}}} (:body resp))))
  (let [clj-resp (client/get (localhost "/clojure") {:as :auto})
        edn-resp (client/get (localhost "/edn") {:as :auto})]
    (is (= 200 (:status clj-resp) (:status edn-resp)))
    (is (= {:foo "bar" :baz 7M :eggplant {:quux #{1 2 3}}}
           (:body clj-resp)
           (:body edn-resp)))))

(deftest ^:integration t-json-output-coercion
  (run-server)
  (let [resp (client/get (localhost "/json") {:as :json})
        resp-array (client/get (localhost "/json-array") {:as :json-strict})
        resp-str (client/get (localhost "/json")
                             {:as :json :coerce :exceptional})
        resp-auto (client/get (localhost "/json") {:as :auto})
        bad-resp (client/get (localhost "/json-bad")
                             {:throw-exceptions false :as :json})
        bad-resp-json (client/get (localhost "/json-bad")
                                  {:throw-exceptions false :as :json
                                   :coerce :always})
        bad-resp-json2 (client/get (localhost "/json-bad")
                                   {:throw-exceptions false :as :json
                                    :coerce :unexceptional})]
    (is (= 200
           (:status resp)
           (:status resp-array)
           (:status resp-str)
           (:status resp-auto)))
    (is (= {:foo "bar"}
           (:body resp)
           (:body resp-auto)))
    (is (= ["foo", "bar"]
           (:body resp-array)))
    ;; '("foo" "bar") and ["foo" "bar"] compare as equal with =.
    (is (vector? (:body resp-array)))
    (is (= "{\"foo\":\"bar\"}" (:body resp-str)))
    (is (= 400
           (:status bad-resp)
           (:status bad-resp-json)
           (:status bad-resp-json2)))
    (is (= "{\"foo\":\"bar\"}" (:body bad-resp))
        "don't coerce on bad response status by default")
    (is (= {:foo "bar"} (:body bad-resp-json)))
    (is (= "{\"foo\":\"bar\"}" (:body bad-resp-json2)))))

(deftest ^:integration t-ipv6
  (run-server)
  (let [resp (client/get "http://[::1]:18080/get")]
    (is (= 200 (:status resp)))
    (is (= "get" (:body resp)))))

(deftest t-custom-retry-handler
  (let [called? (atom false)]
    (is (thrown? Exception
                 (client/post "http://localhost"
                              {:multipart [{:name "title" :content "Foo"}
                                           {:name "Content/type"
                                            :content "text/plain"}
                                           {:name "file"
                                            :content (file "/tmp/missingfile")}]
                               :retry-handler (fn [ex try-count http-context]
                                                (reset! called? true)
                                                false)})))
    (is @called?)))

;; super-basic test for methods that aren't used that often
(deftest ^:integration t-copy-options-move
  (run-server)
  (let [resp1 (client/options (localhost "/options"))
        resp2 (client/move (localhost "/move"))
        resp3 (client/copy (localhost "/copy"))
        resp4 (client/patch (localhost "/patch"))]
    (is (= #{200} (set (map :status [resp1 resp2 resp3 resp4]))))
    (is (= "options" (:body resp1)))
    (is (= "move" (:body resp2)))
    (is (= "copy" (:body resp3)))
    (is (= "patch" (:body resp4)))))

(deftest ^:integration t-json-encoded-form-params
  (run-server)
  (let [params {:param1 "value1" :param2 {:foo "bar"}}
        resp (client/post (localhost "/post") {:content-type :json
                                               :form-params params})]
    (is (= 200 (:status resp)))
    (is (= (json/encode params) (:body resp)))))

(deftest ^:integration t-response-interceptor
  (run-server)
  (let [saved-ctx (atom [])
        {:keys [status trace-redirects] :as resp}
        (client/get
         (localhost "/redirect-to-get")
         {:response-interceptor
          (fn [^HttpResponse resp ^HttpContext ctx]
            (let [^HttpInetConnection conn
                  (.getAttribute ctx ExecutionContext/HTTP_CONNECTION)]
              (swap! saved-ctx conj {:remote-port (.getRemotePort conn)
                                     :http-conn conn})))})]
    (is (= 200 status))
    (is (= 2 (count @saved-ctx)))
    (is (= (count trace-redirects) (count @saved-ctx)))
    (is (every? #(= 18080 (:remote-port %)) @saved-ctx))
    (is (every? #(instance? HttpConnection (:http-conn %)) @saved-ctx))))

(deftest ^:integration t-send-input-stream-body
  (run-server)
  (let [b1 (:body (client/post "http://localhost:18080/post"
                               {:body (ByteArrayInputStream. (.getBytes "foo"))
                                :length 3}))
        b2 (:body (client/post "http://localhost:18080/post"
                               {:body (ByteArrayInputStream.
                                       (.getBytes "foo"))}))
        b3 (:body (client/post "http://localhost:18080/post"
                               {:body (ByteArrayInputStream.
                                       (.getBytes "apple"))
                                :length 2}))]
    (is (= b1 "foo"))
    (is (= b2 "foo"))
    (is (= b3 "ap"))))

(deftest t-add-client-params
  (testing "Using add-client-params!"
    (let [ps {"http.conn-manager.timeout" 100
              "http.socket.timeout" 250
              "http.protocol.allow-circular-redirects" false
              "http.protocol.version" HttpVersion/HTTP_1_0
              "http.useragent" "clj-http"}
          setps (.getParams (doto (DefaultHttpClient.)
                              (core/add-client-params! ps)))]
      (doseq [[k v] ps]
        (is (= v (.getParameter setps k)))))))

;; Regression, get notified if something changes
(deftest ^:integration t-known-client-params-are-unchanged
  (let [params ["http.socket.timeout" CoreConnectionPNames/SO_TIMEOUT
                "http.connection.timeout"
                CoreConnectionPNames/CONNECTION_TIMEOUT
                "http.protocol.version" CoreProtocolPNames/PROTOCOL_VERSION
                "http.useragent" CoreProtocolPNames/USER_AGENT
                "http.conn-manager.timeout" ClientPNames/CONN_MANAGER_TIMEOUT
                "http.protocol.allow-circular-redirects"
                ClientPNames/ALLOW_CIRCULAR_REDIRECTS]]
    (doseq [[plaintext constant] (partition 2 params)]
      (is (= plaintext constant)))))

;; If you don't explicitly set a :cookie-policy, use
;; CookiePolicy/BROWSER_COMPATIBILITY
(deftest t-add-client-params-default-cookie-policy
  (testing "Using add-client-params! to get a default cookie policy"
    (let [setps (.getParams (doto (DefaultHttpClient.)
                              (core/add-client-params! {})))]
      (is (= CookiePolicy/BROWSER_COMPATIBILITY
             (.getParameter setps ClientPNames/COOKIE_POLICY))))))

;; If you set a :cookie-policy, the name of the policy is registered
;; as (str (type cookie-policy))
(deftest t-add-client-params-cookie-policy
  (testing "Using add-client-params! to get an explicitly set :cookie-policy"
    (let [setps (.getParams (doto (DefaultHttpClient.)
                              (core/add-client-params!
                               {:cookie-policy (constantly nil)})))]
      (is (.startsWith ^String (.getParameter setps ClientPNames/COOKIE_POLICY)
                       "class ")))))


;; This relies on connections to writequit.org being slower than 1ms, if this
;; fails, you must have very nice internet.
(deftest ^:integration sets-conn-timeout
  (run-server)
  (try
    (is (thrown? org.apache.http.conn.ConnectTimeoutException
                 (client/request {:scheme :http
                                  :server-name "www.writequit.org"
                                  :server-port 80
                                  :request-method :get :uri "/"
                                  :conn-timeout 1})))))

(deftest ^:integration connection-pool-timeout
  (run-server)
  (client/with-connection-pool {:timeout 1 :threads 1 :default-per-route 1}
    (let [async-request #(future (client/request {:scheme :http
                                                  :server-name "localhost"
                                                  :server-port 18080
                                                  :request-method :get
                                                  :conn-timeout 1
                                                  :uri "/timeout"}))
          is-pool-timeout-error?
          (fn [req-fut]
            (instance? org.apache.http.conn.ConnectionPoolTimeoutException
                       (try @req-fut (catch Exception e (.getCause e)))))
          req1 (async-request)
          req2 (async-request)
          timeout-error1 (is-pool-timeout-error? req1)
          timeout-error2 (is-pool-timeout-error? req2)]
      (is (or timeout-error1 timeout-error2)))))

(deftest ^:integration t-header-collections
  (run-server)
  (let [headers (-> (client/get "http://localhost:18080/headers"
                                {:headers {"foo" ["bar" "baz"]
                                           "eggplant" "quux"}})
                    :body
                    json/decode)]
    (is (= {"eggplant" "quux" "foo" "bar,baz"}
           (select-keys headers ["foo" "eggplant"])))))

(deftest ^:integration t-clojure-no-read-eval
  (run-server)
  (is (thrown? Exception (client/get (localhost "/clojure-bad") {:as :clojure}))
      "Should throw an exception when reading clojure eval components"))

(deftest ^:integration t-numeric-headers
  (run-server)
  (client/request {:method :get :url (localhost "/get") :headers {"foo" 2}}))
