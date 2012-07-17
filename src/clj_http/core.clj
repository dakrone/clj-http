(ns clj-http.core
  "Core HTTP request/response implementation."
  (:require [clojure.pprint]
            [clj-http.conn-mgr :as conn]
            [clj-http.multipart :as mp])
  (:import (java.io ByteArrayOutputStream File FilterInputStream InputStream)
           (java.net URI)
           (org.apache.http HeaderIterator HttpRequest HttpEntity
                            HttpEntityEnclosingRequest
                            HttpResponse Header HttpHost
                            HttpResponseInterceptor)
           (org.apache.http.util EntityUtils)
           (org.apache.http.entity ByteArrayEntity StringEntity)
           (org.apache.http.client HttpClient HttpRequestRetryHandler)
           (org.apache.http.client.methods HttpGet HttpHead HttpPut
                                           HttpPost HttpDelete HttpOptions
                                           HttpEntityEnclosingRequestBase)
           (org.apache.http.client.params CookiePolicy ClientPNames)
           (org.apache.http.conn ClientConnectionManager)
           (org.apache.http.conn.params ConnRoutePNames)
           (org.apache.http.impl.client DefaultHttpClient)
           (org.apache.http.impl.conn SingleClientConnManager)
           (org.apache.http.util EntityUtils)))

(defn parse-headers
  "Takes a HeaderIterator and returns a map of names to values.

   If a name appears more than once (like `set-cookie`) then the value
   will be a vector containing the values in the order they appeared
   in the headers."
  [#^HeaderIterator headers]
  (->> (iterator-seq headers)
       (map (fn [#^Header h] [(.toLowerCase (.getName h)) (.getValue h)]))
       (group-by first)
       (map (fn [[name headers]]
              (let [values (map second headers)]
                [name (let [[value & tail] values]
                        (if tail values value))])))
       (into {})))

(defn set-client-param [#^HttpClient client key val]
  (when-not (nil? val)
    (-> client
        (.getParams)
        (.setParameter key val))))

(defn make-proxy-method-with-body
  [method]
  (fn [url]
    (doto (proxy [HttpEntityEnclosingRequestBase] []
            (getMethod [] (.toUpperCase (name method))))
      (.setURI (URI. url)))))

(def proxy-delete-with-body (make-proxy-method-with-body :delete))
(def proxy-get-with-body (make-proxy-method-with-body :get))
(def proxy-copy-with-body (make-proxy-method-with-body :copy))
(def proxy-move-with-body (make-proxy-method-with-body :move))
(def proxy-patch-with-body (make-proxy-method-with-body :patch))

(def ^{:dynamic true} *cookie-store* nil)

(defn- default-proxy-host-for
  [scheme]
  (System/getProperty (str scheme ".proxyHost")))

(defn- default-proxy-port-for
  [scheme]
  (Integer/parseInt (System/getProperty (str scheme ".proxyPort"))))

(defn add-client-params!
  "Add various client params to the http-client object, if needed."
  [http-client scheme socket-timeout conn-timeout server-name
   proxy-host proxy-port]
  (doto http-client
    (set-client-param ClientPNames/COOKIE_POLICY
                      CookiePolicy/BROWSER_COMPATIBILITY)
    (set-client-param ClientPNames/HANDLE_REDIRECTS false)
    (set-client-param "http.socket.timeout"
                      (and socket-timeout (Integer. ^Long socket-timeout)))
    (set-client-param "http.connection.timeout"
                      (and conn-timeout (Integer. ^Long conn-timeout))))
  (when (nil? (#{"localhost" "127.0.0.1"} server-name))
    (when-let [effective-proxy-host (or proxy-host
                                        (default-proxy-host-for scheme))]
      (let [effective-proxy-port (or proxy-port
                                     (default-proxy-port-for scheme))]
        (set-client-param http-client ConnRoutePNames/DEFAULT_PROXY
                          (HttpHost. effective-proxy-host
                                     effective-proxy-port))))))

(defn- coerce-body-entity
  "Coerce the http-entity from an HttpResponse to either a byte-array, or a
  stream that closes itself and the connection manager when closed."
  [{:keys [as]} ^HttpEntity http-entity ^ClientConnectionManager conn-mgr]
  (when http-entity
    (if (= :stream as)
      (proxy [FilterInputStream]
          [(.getContent http-entity)]
        (close []
          (try
            (proxy-super close)
            (finally
              (when (instance? SingleClientConnManager conn-mgr)
                (.shutdown conn-mgr))))))
      (EntityUtils/toByteArray http-entity))))

(defn- print-debug!
  "Print out debugging information to *out* for a given request."
  [{:keys [debug-body body] :as req} http-req]
  (println "Request:" (type body))
  (clojure.pprint/pprint
   (assoc req
     :body (if debug-body
             (cond
              (isa? (type body) String)
              body

              (isa? (type body) HttpEntity)
              (let [baos (ByteArrayOutputStream.)]
                (.writeTo body baos)
                (.toString baos "UTF-8"))

              :else nil)
             (if (isa? (type body) String)
               (format "... %s bytes ..."
                       (count body))
               (and body (bean body))))
     :body-type (type body)))
  (println "HttpRequest:")
  (clojure.pprint/pprint (bean http-req)))

(defn http-request-for
  "Provides the HttpRequest object for a particular request-method and url"
  [request-method http-url]
  (case request-method
    :get     (proxy-get-with-body http-url)
    :head    (HttpHead. http-url)
    :put     (HttpPut. http-url)
    :post    (HttpPost. http-url)
    :options (HttpOptions. http-url)
    :delete  (proxy-delete-with-body http-url)
    :copy    (proxy-copy-with-body http-url)
    :move    (proxy-move-with-body http-url)
    :patch   (proxy-patch-with-body http-url)
    (throw (IllegalArgumentException.
            (str "Invalid request method " request-method)))))

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response.

   Note that where Ring uses InputStreams for the request and response bodies,
   the clj-http uses ByteArrays for the bodies."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout
           conn-timeout multipart debug debug-body insecure? save-request?
           proxy-host proxy-port as cookie-store retry-handler response-interceptor] :as req}]
  (let [conn-mgr (or conn/*connection-manager*
                     (conn/make-regular-conn-manager req))
        http-client (DefaultHttpClient. ^ClientConnectionManager conn-mgr)
        scheme (name scheme)]
    (when-let [cookie-store (or cookie-store *cookie-store*)]
      (.setCookieStore http-client cookie-store))
    (when retry-handler
      (.setHttpRequestRetryHandler
       http-client
       (proxy [HttpRequestRetryHandler] []
         (retryRequest [e cnt context]
           (retry-handler e cnt context)))))
    (add-client-params! http-client scheme socket-timeout
                        conn-timeout server-name proxy-host proxy-port)
    (let [http-url (str scheme "://" server-name
                        (when server-port (str ":" server-port))
                        uri
                        (when query-string (str "?" query-string)))
          req (assoc req :http-url http-url)
          #^HttpRequest http-req (http-request-for request-method http-url)]
      (when response-interceptor
        (.addResponseInterceptor
         http-client
         (proxy [HttpResponseInterceptor] []
           (process [resp ctx]
             (response-interceptor resp ctx)))))
      (when (and content-type character-encoding)
        (.addHeader http-req "Content-Type"
                    (str content-type "; charset=" character-encoding)))
      (when (and content-type (not character-encoding))
        (.addHeader http-req "Content-Type" content-type))
      (when (instance? SingleClientConnManager conn-mgr)
        (.addHeader http-req "Connection" "close"))
      (doseq [[header-n header-v] headers]
        (.addHeader http-req header-n header-v))
      (if multipart
        (.setEntity #^HttpEntityEnclosingRequest http-req
                    (mp/create-multipart-entity multipart))
        (when (and body (instance? HttpEntityEnclosingRequest http-req))
          (if (instance? HttpEntity body)
            (.setEntity #^HttpEntityEnclosingRequest http-req body)
            (.setEntity #^HttpEntityEnclosingRequest http-req
                        (if (string? body)
                          (StringEntity. body "UTF-8")
                          (ByteArrayEntity. body))))))
      (when debug (print-debug! req http-req))
      (let [http-resp (.execute http-client http-req)
            http-entity (.getEntity http-resp)
            resp {:status (.getStatusCode (.getStatusLine http-resp))
                  :headers (parse-headers (.headerIterator http-resp))
                  :body (coerce-body-entity req http-entity conn-mgr)}]

        (when (and (instance? SingleClientConnManager conn-mgr)
                   (not= :stream as))
          (.shutdown ^ClientConnectionManager conn-mgr))
        (if save-request?
          (-> resp
              (assoc :request req)
              (assoc-in [:request :body-type] (type body))
              (update-in [:request]
                         #(if debug-body
                            (assoc % :body-content
                                   (cond
                                    (isa? (type (:body %)) String)
                                    (:body %)

                                    (isa? (type (:body %)) HttpEntity)
                                    (let [baos (ByteArrayOutputStream.)]
                                      (.writeTo (:body %) baos)
                                      (.toString baos "UTF-8"))

                                    :else nil))
                            %))
              (assoc-in [:request :http-req] http-req)
              (dissoc :save-request?))
          resp)))))
