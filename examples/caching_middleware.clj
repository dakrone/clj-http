(ns clj-http.examples.caching-middleware
  (:require
   [clj-http.client :as http]
   [clojure.core.cache :as cache]))

(def http-cache (atom (cache/ttl-cache-factory {} :ttl (* 60 60 1000))))

(defn- cached-response
  ([client req]
   (let [cache-key (str (:server-name req) (:uri req) "?" (:query-string req))]
     (if (cache/has? @http-cache cache-key)
       (do
         (println "CACHE HIT")
         (client req (reset! http-cache (cache/hit @http-cache cache-key)) nil ) )
       (do
         (println "CACHE MISS")
         (let [resp (client req)]
           (if (http/success? resp)
             (do
               (reset! http-cache (cache/miss @http-cache cache-key resp))
               (client req resp nil))
             (do
               (client req resp nil)))))))))

(defn wrap-caching-middleware
  [client]
  (fn
    ([req]
     (cached-response client req))))

(defn example [& uri]
  (http/with-additional-middleware [#'wrap-caching-middleware]
    (http/get (or uri "https://api.github.com")
              {
               :debug true
               :debug-body true
               :throw-entire-message? true
               })))

;; Try this out:
;;
;; user> (use '[clj-http.examples.caching-middleware :as mw])
;; nil
;; user> (mw/example)
;; CACHE MISS
