(ns clj-http.test.core
  (:use [clojure.test]
        [clojure.java.io :only [file]])
  (:require [clojure.pprint :as pp]
            [clj-http.client :as client]
            [clj-http.core :as core]
            [clj-http.util :as util]
            [cheshire.core :as json]
            [ring.adapter.jetty :as ring])
  (:import (java.io ByteArrayInputStream)
           (org.apache.http.message BasicHeader BasicHeaderIterator)
           (org.apache.http.client.methods HttpPost)
           (org.apache.http HttpResponse HttpConnection)
           (org.apache.http.protocol HttpContext ExecutionContext)))

(defn handler [req]
  ;;(pp/pprint req)
  ;;(println) (println)
  (condp = [(:request-method req) (:uri req)]
    [:get "/get"]
    {:status 200 :body "get"}
    [:get "/clojure"]
    {:status 200 :body "{:foo \"bar\" :baz 7M :eggplant {:quux #{1 2 3}}}"
     :headers {"content-type" "application/clojure"}}
    [:get "/json"]
    {:status 200 :body "{\"foo\":\"bar\"}"}
    [:get "/json-bad"]
    {:status 400 :body "{\"foo\":\"bar\"}"}
    [:get "/redirect"]
    {:status 302 :headers
     {"location" "http://localhost:18080/redirect"}}
    [:get "/redirect-to-get"]
    {:status 302 :headers
     {"location" "http://localhost:18080/get"}}
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
    {:status 200 :body "patch"}))

(defn run-server
  []
  (defonce server
    (future (ring/run-jetty handler {:port 18080}))))

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

(deftest ^{:integration true} makes-get-request
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (= 200 (:status resp)))
    (is (= "get" (slurp-body resp)))))

(deftest ^{:integration true} makes-head-request
  (run-server)
  (let [resp (request {:request-method :head :uri "/head"})]
    (is (= 200 (:status resp)))
    (is (nil? (:body resp)))))

(deftest ^{:integration true} sets-content-type-with-charset
  (run-server)
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain" :character-encoding "UTF-8"})]
    (is (= "text/plain; charset=UTF-8" (slurp-body resp)))))

(deftest ^{:integration true} sets-content-type-without-charset
  (run-server)
  (let [resp (request {:request-method :get :uri "/content-type"
                       :content-type "text/plain"})]
    (is (= "text/plain" (slurp-body resp)))))

(deftest ^{:integration true} sets-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/header"
                       :headers {"X-My-Header" "header-val"}})]
    (is (= "header-val" (slurp-body resp)))))

(deftest ^{:integration true} sends-and-returns-byte-array-body
  (run-server)
  (let [resp (request {:request-method :post :uri "/post"
                       :body (util/utf8-bytes "contents")})]
    (is (= 200 (:status resp)))
    (is (= "contents" (slurp-body resp)))))

(deftest ^{:integration true} returns-arbitrary-headers
  (run-server)
  (let [resp (request {:request-method :get :uri "/get"})]
    (is (string? (get-in resp [:headers "date"])))))

(deftest ^{:integration true} returns-status-on-exceptional-responses
  (run-server)
  (let [resp (request {:request-method :get :uri "/error"})]
    (is (= 500 (:status resp)))))

(deftest ^{:integration true} sets-socket-timeout
  (run-server)
  (try
    (request {:request-method :get :uri "/timeout" :socket-timeout 1})
    (throw (Exception. "Shouldn't get here."))
    (catch Exception e
      (is (= java.net.SocketTimeoutException (class e))))))

(deftest ^{:integration true} delete-with-body
  (run-server)
  (let [resp (request {:request-method :delete :uri "/delete-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))))

(deftest ^{:integration true} self-signed-ssl-get
  (let [t (doto (Thread. #(ring/run-jetty handler
                                          {:port 8081 :ssl-port 18082 :ssl? true
                                           :keystore "test-resources/keystore"
                                           :key-password "keykey"})) .start)]
    ;; wait for jetty to start up completely
    (Thread/sleep 3000)
    (try
      (is (thrown? javax.net.ssl.SSLPeerUnverifiedException
                   (request {:request-method :get :uri "/get"
                             :server-port 18082 :scheme :https})))
      (let [resp (request {:request-method :get :uri "/get" :server-port 18082
                           :scheme :https :insecure? true})]
        (is (= 200 (:status resp)))
        (is (= "get" (slurp-body resp))))
      (finally
        (.stop t)))))

(deftest ^{:integration true} multipart-form-uploads
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
                                    :mime-type "application/json"}]})
        resp-body (apply str (map #(try (char %) (catch Exception _ ""))
                                  (:body resp)))]
    (is (= 200 (:status resp)))
    (is (re-find #"testFINDMEtest" resp-body))
    (is (re-find #"application/json" resp-body))
    (is (re-find #"application/text" resp-body))
    (is (re-find #"UTF-8" resp-body))
    (is (re-find #"byte-test" resp-body))
    (is (re-find #"name=\"c\"" resp-body))
    (is (re-find #"name=\"d\"" resp-body))))

(deftest ^{:integration true} t-save-request-obj
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

(deftest ^{:integration true} t-streaming-response
  (run-server)
  (let [stream (:body (request {:request-method :get :uri "/get" :as :stream}))
        body (slurp stream)]
    (is (= "get" body))))

(deftest throw-on-invalid-body
  (is (thrown-with-msg? IllegalArgumentException #"Invalid request method :bad"
        (client/request {:method :bad :url "http://example.org"}))))

(deftest ^{:integration true} throw-on-too-many-redirects
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

(deftest ^{:integration true} get-with-body
  (run-server)
  (let [resp (request {:request-method :get :uri "/get-with-body"
                       :body (.getBytes "foo bar")})]
    (is (= 200 (:status resp)))
    (is (= "foo bar" (String. (:body resp))))))

(deftest ^{:integration true} head-with-body
  (run-server)
  (let [resp (request {:request-method :head :uri "/head" :body "foo"})]
    (is (= 200 (:status resp)))))

(deftest ^{:integration true} t-clojure-output-coercion
  (run-server)
  (let [resp (client/get (localhost "/clojure") {:as :clojure})]
    (is (= 200 (:status resp)))
    (is (= {:foo "bar" :baz 7M :eggplant {:quux #{1 2 3}}} (:body resp))))
  (let [resp (client/get (localhost "/clojure") {:as :auto})]
    (is (= 200 (:status resp)))
    (is (= {:foo "bar" :baz 7M :eggplant {:quux #{1 2 3}}} (:body resp)))))

(deftest ^{:integration true} t-json-output-coercion
  (run-server)
  (let [resp (client/get (localhost "/json") {:as :json})
        bad-resp (client/get (localhost "/json-bad")
                             {:throw-exceptions false :as :json})]
    (is (= 200 (:status resp)))
    (is (= {:foo "bar"} (:body resp)))
    (is (= 400 (:status bad-resp)))
    (is (= "{\"foo\":\"bar\"}" (:body bad-resp))
        "don't coerce on bad response status")))

(deftest ^{:integration true} t-ipv6
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
(deftest ^{:integration true} t-copy-options-move
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

(deftest ^{:integration true} t-json-encoded-form-params
  (run-server)
  (let [params {:param1 "value1" :param2 {:foo "bar"}}
        resp (client/post (localhost "/post") {:content-type :json
                                               :form-params params})]
    (is (= 200 (:status resp)))
    (is (= (json/encode params) (:body resp)))))


(deftest ^{:integration true} t-response-interceptor
  (run-server)
  (let [saved-ctx (atom [])
        {:keys [status trace-redirects] :as resp}
        (client/get (localhost "/redirect-to-get")
                    {:response-interceptor
                     (fn [^HttpResponse resp ^HttpContext ctx]
                       (let [http-conn (.getAttribute ctx ExecutionContext/HTTP_CONNECTION)]
                         (swap! saved-ctx conj {:remote-port (.getRemotePort http-conn)
                                                :http-conn http-conn})))})]
    (is (= 200 status))
    (is (= 2 (count @saved-ctx)))
    (is (count trace-redirects) (count @saved-ctx))
    (is (every? #(= 18080 (:remote-port %)) @saved-ctx))
    (is (every? #(instance? HttpConnection (:http-conn %)) @saved-ctx))))



