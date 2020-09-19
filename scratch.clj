(in-ns 'clj-http.core)



(comment
  ;; Stop the test server
  (.stop clj-http.test.core-test/server)
  (ns-unmap (find-ns 'clj-http.test.core-test) 'server)

  ;; scratch for client testing
  (let [client (-> (HttpClients/custom)
                   (.setConnectionManager
                    (org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager.))
                   (.build))
        ;; client (build-http-client {} false (get-conn-mgr false {}))
        get (HttpGet. "http://httpbin.org/get")
        response (.execute client get)]
    (println (.getCode response) " " (.getReasonPhrase response))
    (println (slurp (.getContent (.getEntity response))))
    (.close client)
    (.close response))

  (let [client (HttpClients/custom)
        ;; client (build-http-client {} false (get-conn-mgr false {}))
        get (HttpGet. "http://httpbin.org/get")
        response (.execute client get)
        _ (println (.getCode response) " " (.getReasonPhrase response))])

  (let [client (HttpClients/createDefault)
        ;; client (build-http-client {} false (get-conn-mgr false {}))
        get (HttpGet. "http://httpbin.org/get")
        response (with-open [response (.execute client get)]
                   (println (.getCode response) " " (.getReasonPhrase response)))])

  (let [client (-> (HttpClients/custom)
                   (.setConnectionManager
                    (org.apache.hc.client5.http.impl.io.BasicHttpClientConnectionManager.))
                   (.build))
        ;; client (build-http-client {} false (get-conn-mgr false {}))
        get (HttpGet. "http://httpbin.org/get")
        response (with-open [response (.execute client get)]
                   (println (.getCode response) " " (.getReasonPhrase response)))])


  (let [client (build-http-client {} false (get-conn-mgr false {}))
        get (HttpGet. "http://httpbin.org/get")]
    (with-open [response (.execute client get)]
      ;; if you don't consume the body, it complains!
      (println (.getCode response) " " (.getReasonPhrase response))))

  ;; something is fishy about the client or conn-mgr, it seems to be buffering the response
  (let [client (build-http-client {} false (org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager.))
        get (HttpGet. "http://httpbin.org/get")
        response (with-open [response (.execute client get)]
                   (println (.getCode response) " " (.getReasonPhrase response))
                   #_(println (slurp (.getContent (.getEntity response))))
                   )])

  (def response (clj-http.core/request {:scheme "https" :request-method :get :uri "httpbin.org/get" :socket-timeout 50000 :connection-request-timeout 50000 :connection-timeout 50000}))
  (slurp (:body response))



  )
