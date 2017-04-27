(ns clj-http.examples.caching-middleware
  "Example middleware that caches successful GET requests using core.cache."
  (:require
   [clj-http.client :as http]
   [clojure.core.cache :as cache])
  (:import
   (java.nio.charset StandardCharsets)))

(def http-cache (atom (cache/ttl-cache-factory {} :ttl (* 60 60 1000))))

(defn slurp-bytes
  "Read all bytes from the stream.
  Use for example when the bytes will be in demand after stream has been closed."
  [stream]
  (.getBytes (slurp stream) StandardCharsets/UTF_8))

(defn- cached-response
  "Look up the response in the cache using URL as the cache key.
  If the cache has the response, return the cached value.
  If the cache does not have the response, invoke the remaining middleware functions
  to perform the request and receive the response.
  If the response is successful (2xx) and is a GET, store the response in the cache.
  Return the response."
  ([client req]
   (let [cache-key (str (:server-name req) (:uri req) "?" (:query-string req))]
     (if (cache/has? @http-cache cache-key)
       (do
         (println "CACHE HIT")
         (reset! http-cache (cache/hit @http-cache cache-key)) ; update cache stats
         (cache/lookup @http-cache cache-key)) ; return cached value
         ; do not invoke further middleware
       (do
         (println "CACHE MISS")
         (let [resp (update (client req) :body slurp-bytes)] ; middleware chain invoked
           (when (and (http/success? resp) (= (:request-method req) :get))
             (reset! http-cache (cache/miss @http-cache cache-key resp)) ; update cache value
            resp)))))))

(defn wrap-caching-middleware
  "Middleware are functions that add functionality to handlers.
  The argument client is a handler.
  This wrapper function adds response caching to the client."
  [client]
  (fn
    ([req]
     (cached-response client req))))

(defn example
  "Add the caching middleware and perform a GET request using the URI argument.
  Subsequent invocations of this function using an identical URI argument
  before the Time To Live expires can be expected to hit the cache."
  [& uri]
  (-> (time (http/with-additional-middleware [#'wrap-caching-middleware]
              (http/get (or uri "https://api.github.com")
                        {
                         ;; :debug true
                         ;; :debug-body true
                         ;; :throw-entire-message? true
                         })))
      (select-keys ,,, [:status :reason-phrase :headers])))

;; Try this out:
;;
;; user> (use '[clj-http.examples.caching-middleware :as mw])
;; nil
;; user> (mw/example)
;; CACHE MISS
;; "Elapsed time: 1910.027361 msecs"
;; {:status 200, :reason-phrase "OK"}
;; user> (mw/example)
;; CACHE HIT
;; "Elapsed time: 0.83484 msecs"
;; {:status 200, :reason-phrase "OK"}
;; user>
