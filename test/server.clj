(use 'ring.adapter.jetty)
(use 'ring.middleware.reload)
(use 'clj-http.core-test)

(run-jetty
  (-> #'handler
    (wrap-reload ['clj-http.core-test]))
  {:port 8080})
