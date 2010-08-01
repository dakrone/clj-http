(use 'ring.adapter.jetty)
(use 'clj-http.core-test)

(run-jetty #'handler {:port 8080})
