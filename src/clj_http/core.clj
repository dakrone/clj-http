(ns clj-http.core
  "Core HTTP request/response implementation."
  (:import (java.net URI))
  (:import (org.apache.http HttpRequest HttpEntityEnclosingRequest HttpResponse Header))
  (:import (org.apache.http.util EntityUtils))
  (:import (org.apache.http.entity ByteArrayEntity))
  (:import (org.apache.http.client HttpClient))
  (:import (org.apache.http.client.methods HttpGet HttpHead HttpPut HttpPost HttpDelete
                                           HttpEntityEnclosingRequestBase))
  (:import (org.apache.http.client.params CookiePolicy ClientPNames))
  (:import (org.apache.http.impl.client DefaultHttpClient)))

(defn- parse-headers [#^HttpResponse http-resp]
  (into {} (map (fn [#^Header h] [(.toLowerCase (.getName h)) (.getValue h)])
                (iterator-seq (.headerIterator http-resp)))))

(defn- set-client-param [#^HttpClient client key val]
  (when val
    (-> client
        (.getParams)
        (.setParameter key val))))

(defn- proxy-delete-with-body [url]
  (let [res (proxy [HttpEntityEnclosingRequestBase] []
              (getMethod [] "DELETE"))]
    (.setURI res (URI. url))
    res))

(defn request
  "Executes the HTTP request corresponding to the given Ring request map and
   returns the Ring response map corresponding to the resulting HTTP response.

   Note that where Ring uses InputStreams for the request and response bodies,
   the clj-http uses ByteArrays for the bodies."
  [{:keys [request-method scheme server-name server-port uri query-string
           headers content-type character-encoding body socket-timeout conn-timeout]}]
  (let [http-client (DefaultHttpClient.)]
    (try
      (doto http-client
        (set-client-param ClientPNames/COOKIE_POLICY CookiePolicy/BROWSER_COMPATIBILITY)
        (set-client-param "http.socket.timeout" socket-timeout)
        (set-client-param "http.connection.timeout" conn-timeout))
      (let [http-url (str scheme "://" server-name
                          (if server-port (str ":" server-port))
                          uri
                          (if query-string (str "?" query-string)))
            #^HttpRequest
            http-req (case request-method
                           :get    (HttpGet. http-url)
                           :head   (HttpHead. http-url)
                           :put    (HttpPut. http-url)
                           :post   (HttpPost. http-url)
                           :delete (proxy-delete-with-body http-url))]
        (if (and content-type character-encoding)
          (.addHeader http-req "Content-Type"
                      (str content-type "; charset=" character-encoding)))
        (if (and content-type (not character-encoding))
          (.addHeader http-req "Content-Type" content-type))
        (.addHeader http-req "Connection" "close")
        (doseq [[header-n header-v] headers]
          (.addHeader http-req header-n header-v))
        (if body
          (let [http-body (ByteArrayEntity. body)]
            (.setEntity #^HttpEntityEnclosingRequest http-req http-body)))
        (let [http-resp (.execute http-client http-req)
              http-entity (.getEntity http-resp)
              resp {:status (.getStatusCode (.getStatusLine http-resp))
                    :headers (parse-headers http-resp)
                    :body (if http-entity (EntityUtils/toByteArray http-entity))}]
          (.shutdown (.getConnectionManager http-client))
          resp)))))
