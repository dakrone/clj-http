(ns clj-http.core
  "Core HTTP request/response implementation."
  (:require [clojure.pprint])
  (:import (java.io File FilterInputStream InputStream)
           (java.net URI)
           (org.apache.http HeaderIterator HttpRequest HttpEntity
                            HttpEntityEnclosingRequest
                            HttpResponse Header HttpHost)
           (org.apache.http.util EntityUtils)
           (org.apache.http.entity ByteArrayEntity StringEntity)
           (org.apache.http.entity.mime MultipartEntity)
           (org.apache.http.entity.mime.content ByteArrayBody
                                                FileBody
                                                InputStreamBody
                                                StringBody)
           (org.apache.http.client HttpClient)
           (org.apache.http.client.methods HttpGet HttpHead HttpPut
                                           HttpPost HttpDelete
                                           HttpEntityEnclosingRequestBase)
           (org.apache.http.client.params CookiePolicy ClientPNames)
           (org.apache.http.conn ClientConnectionManager)
           (org.apache.http.conn.params ConnRoutePNames)
           (org.apache.http.conn.scheme PlainSocketFactory
                                        SchemeRegistry Scheme)
           (org.apache.http.conn.ssl SSLSocketFactory TrustStrategy)
           (org.apache.http.impl.conn SingleClientConnManager)
           (org.apache.http.impl.conn.tsccm ThreadSafeClientConnManager)
           (org.apache.http.impl.client DefaultHttpClient)
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

(def ^SSLSocketFactory insecure-socket-factory
  (doto (SSLSocketFactory. (reify TrustStrategy
                             (isTrusted [_ _ _] true)))
    (.setHostnameVerifier SSLSocketFactory/ALLOW_ALL_HOSTNAME_VERIFIER)))

(def insecure-scheme-registry
  (doto (SchemeRegistry.)
    (.register (Scheme. "http" 80 (PlainSocketFactory/getSocketFactory)))
    (.register (Scheme. "https" 443 insecure-socket-factory))))

(def regular-scheme-registry
  (doto (SchemeRegistry.)
    (.register (Scheme. "http" 80 (PlainSocketFactory/getSocketFactory)))
    (.register (Scheme. "https" 443 (SSLSocketFactory/getSocketFactory)))))

(defn make-regular-conn-manager ^SingleClientConnManager [& [insecure?]]
  (if insecure?
    (SingleClientConnManager. insecure-scheme-registry)
    (SingleClientConnManager.)))

(defn make-reusable-conn-manager
  "Given an timeout and optional insecure? flag, create a
  ThreadSafeClientConnManager with <timeout> seconds set as the timeout value."
  ;; need the fully qualified class name because this fn is later used in a
  ;; macro from a different ns
  ^org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
  [timeout & [insecure?]]
  (let [sr (if insecure? insecure-scheme-registry regular-scheme-registry)]
    (ThreadSafeClientConnManager.
     sr timeout java.util.concurrent.TimeUnit/SECONDS)))

(def ^{:dynamic true} *connection-manager* nil)

(defn create-multipart-entity
  "Takes a multipart map and creates a MultipartEntity with each key/val pair
   added as a part, determining part type by the val type."
  [multipart]
  (let [mp-entity (MultipartEntity.)]
    (doseq [[k v] multipart]
      (let [klass (type v)
            keytext (name k)
            part (cond
                  (isa? klass File)
                  (FileBody. v keytext)

                  (isa? klass InputStream)
                  (InputStreamBody. v keytext)

                  (= klass (type (byte-array 0)))
                  (ByteArrayBody. v keytext)

                  (= klass String)
                  (StringBody. v))]
        (.addPart mp-entity keytext part)))
    mp-entity))

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

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response.

   Note that where Ring uses InputStreams for the request and response bodies,
   the clj-http uses ByteArrays for the bodies."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout
           conn-timeout multipart debug insecure? save-request? proxy-host
           proxy-port as] :as req}]
  (let [conn-mgr (or *connection-manager* (make-regular-conn-manager insecure?))
        http-client (DefaultHttpClient.
                      ^org.apache.http.conn.ClientConnectionManager conn-mgr)
        scheme (name scheme)]
    (add-client-params! http-client scheme socket-timeout
                        conn-timeout server-name proxy-host proxy-port)
    (let [http-url (str scheme "://" server-name
                        (when server-port (str ":" server-port))
                        uri
                        (when query-string (str "?" query-string)))
          req (assoc req :http-url http-url)
          #^HttpRequest
          http-req (case request-method
                     :get    (proxy-get-with-body http-url)
                     :head   (HttpHead. http-url)
                     :put    (HttpPut. http-url)
                     :post   (HttpPost. http-url)
                     :delete (proxy-delete-with-body http-url))]
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
                    (create-multipart-entity multipart))
        (when (and body (not (instance? HttpHead http-req)))
          (if (instance? HttpEntity body)
            (.setEntity #^HttpEntityEnclosingRequest http-req body)
            (.setEntity #^HttpEntityEnclosingRequest http-req
                        (if (string? body)
                          (StringEntity. body "UTF-8")
                          (ByteArrayEntity. body))))))
      (when debug
        (println "Request:")
        (clojure.pprint/pprint
         (assoc req :body (if (isa? (:body req) String)
                            (format "... %s bytes ..."
                                    (count (:body req)))
                            (bean (:body req)))))
        (println "HttpRequest:")
        (clojure.pprint/pprint (bean http-req)))
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
              (dissoc :save-request?))
          resp)))))
