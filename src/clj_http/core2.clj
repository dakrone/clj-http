(ns clj-http.core2
  "Core HTTP request/response implementation. Rewrite for Apache 4.3"
  (:require [clj-http.conn-mgr :as conn]
            [clj-http.headers :as headers]
            [clj-http.multipart :as mp]
            [clj-http.util :refer [opt]]
            [clojure.pprint])
  (:import (java.io FilterInputStream InputStream)
           (java.net URI)
           (java.util Locale)
           (org.apache.http HttpEntity HeaderIterator HttpHost HttpRequest
                            HttpEntityEnclosingRequest HttpResponse)
           (org.apache.http.client HttpRequestRetryHandler)
           (org.apache.http.client.config RequestConfig)
           (org.apache.http.client.methods HttpDelete HttpGet HttpPost HttpPut
                                           HttpOptions HttpPatch
                                           HttpHead
                                           HttpEntityEnclosingRequestBase)
           (org.apache.http.client.protocol HttpClientContext)
           (org.apache.http.config RegistryBuilder)
           (org.apache.http.conn HttpClientConnectionManager)
           (org.apache.http.conn.routing HttpRoute)
           (org.apache.http.conn.ssl BrowserCompatHostnameVerifier
                                     SSLConnectionSocketFactory SSLContexts)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.entity ByteArrayEntity StringEntity)
           (org.apache.http.impl.client BasicCredentialsProvider
                                        CloseableHttpClient HttpClients)
           (org.apache.http.impl.conn BasicHttpClientConnectionManager
                                      PoolingHttpClientConnectionManager)))

(defn parse-headers
  "Takes a HeaderIterator and returns a map of names to values.

  If a name appears more than once (like `set-cookie`) then the value
  will be a vector containing the values in the order they appeared
  in the headers."
  [^HeaderIterator headers & [use-header-maps-in-response?]]
  (if-not use-header-maps-in-response?
    (->> (headers/header-iterator-seq headers)
         (map (fn [[k v]]
                [(.toLowerCase ^String k) v]))
         (reduce (fn [hs [k v]]
                   (headers/assoc-join hs k v))
                 {}))
    (->> (headers/header-iterator-seq headers)
         (reduce (fn [hs [k v]]
                   (headers/assoc-join hs k v))
                 (headers/header-map)))))

(defn http-client [conn-mgr]
  (-> (HttpClients/custom)
      (.setConnectionManager conn-mgr)
      (.build)))

(defn http-get []
  (HttpGet. "https://www.google.com"))

(defn make-proxy-method-with-body
  [method]
  (fn [url]
    (doto (proxy [HttpEntityEnclosingRequestBase] []
            (getMethod [] (.toUpperCase (name method) Locale/ROOT)))
      (.setURI (URI. url)))))

(def proxy-delete-with-body (make-proxy-method-with-body :delete))
(def proxy-get-with-body (make-proxy-method-with-body :get))
(def proxy-copy-with-body (make-proxy-method-with-body :copy))
(def proxy-move-with-body (make-proxy-method-with-body :move))
(def proxy-patch-with-body (make-proxy-method-with-body :patch))

(defn http-request-for
  "Provides the HttpRequest object for a particular request-method and url"
  [request-method ^String http-url body]
  (case request-method
    :get     (if body
               (proxy-get-with-body http-url)
               (HttpGet. http-url))
    :head    (HttpHead. http-url)
    :put     (HttpPut. http-url)
    :post    (HttpPost. http-url)
    :options (HttpOptions. http-url)
    :delete  (if body
               (proxy-delete-with-body http-url)
               (HttpDelete. http-url))
    :copy    (proxy-copy-with-body http-url)
    :move    (proxy-move-with-body http-url)
    :patch   (if body
               (proxy-patch-with-body http-url)
               (HttpPatch. http-url))
    (throw (IllegalArgumentException.
            (str "Invalid request method " request-method)))))

(defn http-context [request-config]
  (doto (HttpClientContext/create)
    (.setRequestConfig request-config)))

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

(defn request
  [{:keys [body
           conn-timeout
           connection-manager
           cookie-store
           headers
           multipart
           query-string
           retry-handler
           request-method
           scheme
           server-name
           server-port
           socket-timeout
           uri]
    :as req}]
  (let [scheme (name scheme)
        http-url (str scheme "://" server-name
                      (when server-port (str ":" server-port))
                      uri
                      (when query-string (str "?" query-string)))
        conn-mgr (or connection-manager (conn/basic-conn-mgr))
        ^RequestConfig request-config (-> (RequestConfig/custom)
                                          (.setConnectTimeout (or conn-timeout -1))
                                          (.setSocketTimeout (or socket-timeout -1))
                                          (.build))
        ^CloseableHttpClient client (http-client conn-mgr)
        ^HttpClientContext context (http-context request-config)
        ^HttpRequest http-req (http-request-for request-method http-url body)]
    (when-not (conn/reusable? conn-mgr)
      (.addHeader http-req "Connection" "close"))
    (when cookie-store
      (.setCookieStore context cookie-store))
    (when retry-handler
      (.setRetryHandler client
                        (proxy [HttpRequestRetryHandler] []
                          (retryRequest [e cnt context]
                            (retry-handler e cnt context)))))
    (if multipart
      (.setEntity ^HttpEntityEnclosingRequest http-req
                  (mp/create-multipart-entity multipart))
      (when (and body (instance? HttpEntityEnclosingRequest http-req))
        (if (instance? HttpEntity body)
          (.setEntity ^HttpEntityEnclosingRequest http-req body)
          (.setEntity ^HttpEntityEnclosingRequest http-req
                      (if (string? body)
                        (StringEntity. ^String body "UTF-8")
                        (ByteArrayEntity. body))))))
    (doseq [[header-n header-v] headers]
      (if (coll? header-v)
        (doseq [header-vth header-v]
          (.addHeader http-req header-n header-vth))
        (.addHeader http-req header-n (str header-v))))
    (let [^HttpResponse response (.execute client http-req context)
          ^HttpEntity entity (.getEntity response)
          status (.getStatusLine response)]
      {:body (coerce-body-entity entity conn-mgr response)
       :headers (parse-headers
                 (.headerIterator response)
                 (opt req :use-header-maps-in-response))
       :length (if (nil? entity) 0 (.getContentLength entity))
       :chunked? (if (nil? entity) false (.isChunked entity))
       :repeatable? (if (nil? entity) false (.isRepeatable entity))
       :streaming? (if (nil? entity) false (.isStreaming entity))
       :status (.getStatusCode status)})))
