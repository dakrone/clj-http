(ns clj-http.core2
  "Core HTTP request/response implementation. Rewrite for Apache 4.3"
  (:require [clj-http.conn-mgr :as conn]
            [clj-http.headers :as headers]
            [clj-http.multipart :as mp]
            [clj-http.util :refer [opt]]
            [clojure.pprint])
  (:import (java.io FilterInputStream InputStream)
           (org.apache.http HttpHost)
           (org.apache.http.client.methods HttpGet)
           (org.apache.http.client.protocol HttpClientContext)
           (org.apache.http.config RegistryBuilder)
           (org.apache.http.conn HttpClientConnectionManager)
           (org.apache.http.conn.routing HttpRoute)
           (org.apache.http.conn.ssl BrowserCompatHostnameVerifier
                                     SSLConnectionSocketFactory SSLContexts)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.impl.client BasicCredentialsProvider HttpClients)
           (org.apache.http.impl.conn PoolingHttpClientConnectionManager)))

(defn http-route []
  ;; TODO add proxy support
  (HttpRoute. (HttpHost. "www.google.com" 80 "https")))

(defn ssl-context []
  (SSLContexts/createSystemDefault))

(defn hostname-verifier []
  (BrowserCompatHostnameVerifier.))

(defn registry-builder []
  (-> (RegistryBuilder/create)
      (.register "http" PlainConnectionSocketFactory/INSTANCE)
      (.register "https" (SSLConnectionSocketFactory.
                          (ssl-context) (hostname-verifier)))
      (.build)))

(defn pooling-conn-mgr []
  (PoolingHttpClientConnectionManager. (registry-builder)))

(defn http-client [conn-mgr]
  (-> (HttpClients/custom)
      (.setConnectionManager conn-mgr)
      (.build)))

(defn http-get []
  (HttpGet. "https://www.google.com"))

(defn http-context []
  (HttpClientContext/create))

(defn credentials-provider []
  (BasicCredentialsProvider.))

(defn- coerce-body-entity
  "Coerce the http-entity from an HttpResponse to a stream that closes itself
  and the connection manager when closed."
  [^HttpEntity http-entity ^HttpClientConnectionManager conn-mgr response]
  (when http-entity
    (proxy [FilterInputStream]
        [^InputStream (.getContent http-entity)]
      (close []
        (try
          ;; Eliminate the reflection warning from proxy-super
          (let [^InputStream this this]
            (proxy-super close))
          (finally
            (.close response)
            (.shutdown conn-mgr)))))))

(defn request [{:keys [cookie-store] :as req}]
  (let [conn-mgr (pooling-conn-mgr)
        client (http-client conn-mgr)
        context (http-context)
        get-req (http-get)
        response (.execute client get-req context)
        entity (.getEntity response)
        status (.getStatusLine response)]
    (when cookie-store
      (.setCookieStore context cookie-store))
    {:body (coerce-body-entity entity conn-mgr response)
     :length (.getContentLength entity)
     :chunked? (.isChunked entity)
     :repeatable? (.isRepeatable entity)
     :streaming? (.isStreaming entity)
     :status (.getStatusCode status)}))
