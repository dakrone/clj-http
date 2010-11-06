(ns clj-http.run-server
  (:use ring.adapter.jetty)
  (:use ring.middleware.reload)
  (:use clj-http.core-test))

(defn -main [& args]
  (println "booting test server")
  (run-jetty
    (-> #'handler (wrap-reload '(clj-http.core-test)))
    {:port 8080}))
