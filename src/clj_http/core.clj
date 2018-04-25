(ns clj-http.core
  "Core HTTP request/response implementation. Rewrite for Apache 4.3"
  (:require [clj-http.conn-mgr :as conn]
            [clj-http.headers :as headers]
            [clj-http.multipart :as mp]
            [clj-http.util :refer [opt]]
            [clojure.pprint])
  (:import (java.io ByteArrayOutputStream FilterInputStream InputStream)
           (java.net URI URL ProxySelector InetAddress)
           (java.util Locale)
           (org.apache.http HttpEntity HeaderIterator HttpHost HttpRequest
                            HttpEntityEnclosingRequest HttpResponse
                            HttpRequestInterceptor HttpResponseInterceptor
                            ProtocolException)
           (org.apache.http.auth UsernamePasswordCredentials AuthScope
                                 NTCredentials)
           (org.apache.http.client HttpRequestRetryHandler RedirectStrategy
                                   CredentialsProvider)
           (org.apache.http.client.config RequestConfig CookieSpecs)
           (org.apache.http.client.methods HttpDelete HttpGet HttpPost HttpPut
                                           HttpOptions HttpPatch
                                           HttpHead
                                           HttpEntityEnclosingRequestBase
                                           CloseableHttpResponse
                                           HttpUriRequest HttpRequestBase)
           (org.apache.http.client.protocol HttpClientContext)
           (org.apache.http.client.utils URIUtils)
           (org.apache.http.config RegistryBuilder)
           (org.apache.http.conn.routing HttpRoute HttpRoutePlanner)
           (org.apache.http.conn.ssl BrowserCompatHostnameVerifier
                                     SSLConnectionSocketFactory SSLContexts)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.conn.util PublicSuffixMatcherLoader)
           (org.apache.http.cookie CookieSpecProvider)
           (org.apache.http.entity ByteArrayEntity StringEntity)
           (org.apache.http.impl.client BasicCredentialsProvider
                                        CloseableHttpClient HttpClients
                                        DefaultRedirectStrategy
                                        LaxRedirectStrategy HttpClientBuilder)
           (org.apache.http.client.cache HttpCacheContext)
           (org.apache.http.impl.client.cache CacheConfig
                                              CachingHttpClientBuilder)
           (org.apache.http.impl.cookie DefaultCookieSpecProvider)
           (org.apache.http.impl.conn SystemDefaultRoutePlanner
                                      DefaultProxyRoutePlanner)
           (org.apache.http.impl.nio.client HttpAsyncClientBuilder
                                            HttpAsyncClients
                                            CloseableHttpAsyncClient)
           (org.apache.http.message BasicHttpResponse)
           (java.util.concurrent ExecutionException)
           (org.apache.http.entity.mime HttpMultipartMode)))

(def CUSTOM_COOKIE_POLICY "_custom")

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

(defn graceful-redirect-strategy
  "Similar to the default redirect strategy, however, does not throw an error
  when the maximum number of redirects has been reached. Still supports
  validating that the new redirect host is not empty."
  [req]
  (let [validate? (opt req :validate-redirects)]
    (reify RedirectStrategy
      (getRedirect [this request response context]
        (let [new-request (.getRedirect DefaultRedirectStrategy/INSTANCE
                                        request response context)]
          (when (or validate? (nil? validate?))
            (let [uri (.getURI new-request)
                  new-host (URIUtils/extractHost uri)]
              (when (nil? new-host)
                (throw
                 (ProtocolException.
                  (str "Redirect URI does not specify a valid host name: "
                       uri))))))
          new-request))

      (isRedirected [this request response context]
        (let [^HttpClientContext typed-context context
              max-redirects (-> (.getRequestConfig typed-context)
                                .getMaxRedirects)
              num-redirects (count (.getRedirectLocations typed-context))]
          (if (<= max-redirects num-redirects)
            false
            (.isRedirected DefaultRedirectStrategy/INSTANCE
                           request response typed-context)))))))

(defn default-redirect-strategy
  "Proxies calls to whatever original redirect strategy is passed in, however,
  if :validate-redirects is set in the request, checks that the redirected host
  is not empty."
  [^RedirectStrategy original req]
  (let [validate? (opt req :validate-redirects)]
    (reify RedirectStrategy
      (getRedirect [this request response context]
        (let [new-request (.getRedirect original request response context)]
          (when (or validate? (nil? validate?))
            (let [uri (.getURI new-request)
                  new-host (URIUtils/extractHost uri)]
              (when (nil? new-host)
                (throw
                 (ProtocolException.
                  (str "Redirect URI does not specify a valid host name: "
                       uri))))))
          new-request))

      (isRedirected [this request response context]
        (.isRedirected original request response context)))))

(defn get-redirect-strategy [{:keys [redirect-strategy] :as req}]
  (case redirect-strategy
    :none (reify RedirectStrategy
            (getRedirect [this request response context] nil)
            (isRedirected [this request response context] false))

    ;; Like default, but does not throw exceptions when max redirects is
    ;; reached.
    :graceful (graceful-redirect-strategy req)

    :default (default-redirect-strategy DefaultRedirectStrategy/INSTANCE req)
    :lax (default-redirect-strategy (LaxRedirectStrategy.) req)
    nil (default-redirect-strategy DefaultRedirectStrategy/INSTANCE req)

    ;; use directly as reifed RedirectStrategy
    redirect-strategy))

(defn ^HttpClientBuilder add-retry-handler [^HttpClientBuilder builder handler]
  (when handler
    (.setRetryHandler
     builder
     (proxy [HttpRequestRetryHandler] []
       (retryRequest [e cnt context]
         (handler e cnt context)))))
  builder)

(defn create-custom-cookie-policy-registry
  "Given a function that will take an HttpContext and return a CookieSpec,
  create a new Registry for the cookie policy under the CUSTOM_COOKIE_POLICY
  string."
  [cookie-spec-fn]
  (-> (RegistryBuilder/create)
      (.register CUSTOM_COOKIE_POLICY
                 (proxy [CookieSpecProvider] []
                   (create [context]
                     (cookie-spec-fn context))))
      (.build)))

(defmulti get-cookie-policy
  "Method to retrieve the cookie policy that should be used for the request.
  This is a multimethod that may be extended to return your own cookie policy.
  Dispatches based on the `:cookie-policy` key in the request map."
  (fn get-cookie-dispatch [request] (:cookie-policy request)))

(defmethod get-cookie-policy :none none-cookie-policy
  [_] CookieSpecs/IGNORE_COOKIES)
(defmethod get-cookie-policy :default default-cookie-policy
  [_] CookieSpecs/DEFAULT)
(defmethod get-cookie-policy nil nil-cookie-policy
  [_] CookieSpecs/DEFAULT)
(defmethod get-cookie-policy :netscape netscape-cookie-policy
  [_] CookieSpecs/NETSCAPE)
(defmethod get-cookie-policy :standard standard-cookie-policy
  [_] CookieSpecs/STANDARD)
(defmethod get-cookie-policy :stardard-strict standard-strict-cookie-policy
  [_] CookieSpecs/STANDARD_STRICT)

(defn request-config [{:keys [conn-timeout
                              socket-timeout
                              conn-request-timeout
                              max-redirects
                              cookie-spec]
                       :as req}]
  (let [config (-> (RequestConfig/custom)
                   (.setConnectTimeout (or conn-timeout -1))
                   (.setSocketTimeout (or socket-timeout -1))
                   (.setConnectionRequestTimeout
                    (or conn-request-timeout -1))
                   (.setRedirectsEnabled true)
                   (.setCircularRedirectsAllowed
                    (boolean (opt req :allow-circular-redirects)))
                   (.setRelativeRedirectsAllowed
                    ((complement false?)
                     (opt req :allow-relative-redirects))))]
    (if cookie-spec
      (.setCookieSpec config CUSTOM_COOKIE_POLICY)
      (.setCookieSpec config (get-cookie-policy req)))
    (when max-redirects (.setMaxRedirects config max-redirects))
    (.build config)))

(defmulti ^:private construct-http-host (fn [proxy-host proxy-port]
                                          (class proxy-host)))
(defmethod construct-http-host String
  [^String proxy-host ^Long proxy-port]
  (if proxy-port
    (HttpHost. proxy-host proxy-port)
    (HttpHost. proxy-host)))
(defmethod construct-http-host java.net.InetAddress
  [^InetAddress proxy-host ^Long proxy-port]
  (if proxy-port
    (HttpHost. proxy-host proxy-port)
    (HttpHost. proxy-host)))

(defn ^HttpRoutePlanner get-route-planner
  "Return an HttpRoutePlanner that either use the supplied proxy settings
  if any, or the JVM/system proxy settings otherwise"
  [^String proxy-host ^Long proxy-port proxy-ignore-hosts http-url]
  (let [ignore-proxy? (and http-url
                           (contains? (set proxy-ignore-hosts)
                                      (.getHost (URL. http-url))))]
    (if (and proxy-host (not ignore-proxy?))
      (DefaultProxyRoutePlanner. (construct-http-host proxy-host proxy-port))
      (SystemDefaultRoutePlanner. (ProxySelector/getDefault)))))

(defn build-cache-config
  "Given a request with :cache-config as a map or a CacheConfig object, return a
  CacheConfig object, or nil if no cache config is found. If :cache-config is a
  map, it checks for the following options:
  - :allow-303-caching
  - :asynchronous-worker-idle-lifetime-secs
  - :asynchronous-workers-core
  - :asynchronous-workers-max
  - :heuristic-caching-enabled
  - :heuristic-coefficient
  - :heuristic-default-lifetime
  - :max-cache-entries
  - :max-object-size
  - :max-update-retries
  - :never-cache-http10-responses-with-query-string
  - :revalidation-queue-size
  - :shared-cache
  - :weak-etag-on-put-delete-allowed"
  [request]
  (when-let [cc (:cache-config request)]
    (if (instance? CacheConfig cc)
      cc
      (let [config (CacheConfig/custom)
            {:keys [allow-303-caching
                    asynchronous-worker-idle-lifetime-secs
                    asynchronous-workers-core
                    asynchronous-workers-max
                    heuristic-caching-enabled
                    heuristic-coefficient
                    heuristic-default-lifetime
                    max-cache-entries
                    max-object-size
                    max-update-retries
                    never-cache-http10-responses-with-query-string
                    revalidation-queue-size
                    shared-cache
                    weak-etag-on-put-delete-allowed]} cc]
        (when (instance? Boolean allow-303-caching)
          (.setAllow303Caching config allow-303-caching))
        (when asynchronous-worker-idle-lifetime-secs
          (.setAsynchronousWorkerIdleLifetimeSecs
           config asynchronous-worker-idle-lifetime-secs))
        (when asynchronous-workers-core
          (.setAsynchronousWorkersCore config asynchronous-workers-core))
        (when asynchronous-workers-max
          (.setAsynchronousWorkersMax config asynchronous-workers-max))
        (when (instance? Boolean heuristic-caching-enabled)
          (.setHeuristicCachingEnabled config heuristic-caching-enabled))
        (when heuristic-coefficient
          (.setHeuristicCoefficient config heuristic-coefficient))
        (when heuristic-default-lifetime
          (.setHeuristicDefaultLifetime config heuristic-default-lifetime))
        (when max-cache-entries
          (.setMaxCacheEntries config max-cache-entries))
        (when max-object-size
          (.setMaxObjectSize config max-object-size))
        (when max-update-retries
          (.setMaxUpdateRetries config max-update-retries))
        ;; I would add this option, but there is a bug in 4.x CacheConfig that
        ;; it does not actually correctly use the object from the builder.
        ;; It's fixed in 5.0 however
        ;; (when (boolean? never-cache-http10-responses-with-query-string)
        ;;   (.setNeverCacheHTTP10ResponsesWithQueryString
        ;;    config never-cache-http10-responses-with-query-string))
        (when revalidation-queue-size
          (.setRevalidationQueueSize config revalidation-queue-size))
        (when (instance? Boolean shared-cache)
          (.setSharedCache config shared-cache))
        (when (instance? Boolean weak-etag-on-put-delete-allowed)
          (.setWeakETagOnPutDeleteAllowed config weak-etag-on-put-delete-allowed))
        (.build config)))))

(defn build-http-client
  "Builds an Apache `HttpClient` from a clj-http request map. Optional arguments
  `http-url` and `proxy-ignore-hosts` are used to specify the host and a list of
  hostnames to ignore for any proxy settings. They can be safely ignored if not
  using proxies."
  [{:keys [retry-handler request-interceptor
           response-interceptor proxy-host proxy-port
           http-builder-fns cookie-spec
           cookie-policy-registry ntlm-auth]
    :as req}
   caching?
   conn-mgr
   & [http-url proxy-ignore-hosts]]
  ;; have to let first, otherwise we get a reflection warning on (.build)
  (let [cache? (opt req :cache)
        ^HttpClientBuilder builder (-> (if caching?
                                         (CachingHttpClientBuilder/create)
                                         (HttpClients/custom))
                                       (.setConnectionManager conn-mgr)
                                       (.setRedirectStrategy
                                        (get-redirect-strategy req))
                                       (add-retry-handler retry-handler)
                                       ;; By default, get the proxy settings
                                       ;; from the jvm or system properties
                                       (.setRoutePlanner
                                        (get-route-planner
                                         proxy-host proxy-port
                                         proxy-ignore-hosts http-url)))]
    (when-let [[user password host domain] ntlm-auth]
      (.setDefaultCredentialsProvider
       builder
       (doto (BasicCredentialsProvider.)
         (.setCredentials AuthScope/ANY
                          (NTCredentials. user password host domain)))))
    (when cache?
      (.setCacheConfig builder (build-cache-config req)))
    (when (or cookie-policy-registry cookie-spec)
      (if cookie-policy-registry
        ;; They have a custom registry they'd like to re-use, so use that
        (.setDefaultCookieSpecRegistry builder cookie-policy-registry)
        ;; They have only a one-time function for cookie spec, so use that
        (.setDefaultCookieSpecRegistry
         builder (create-custom-cookie-policy-registry cookie-spec))))
    (when request-interceptor
      (.addInterceptorLast
       builder (proxy [HttpRequestInterceptor] []
                 (process [req ctx]
                   (request-interceptor req ctx)))))

    (when response-interceptor
      (.addInterceptorLast
       builder (proxy [HttpResponseInterceptor] []
                 (process [resp ctx]
                   (response-interceptor
                    resp ctx)))))
    (doseq [http-builder-fn http-builder-fns]
      (http-builder-fn builder req))
    (.build builder)))

(defn build-async-http-client
  "Builds an Apache `HttpAsyncClient` from a clj-http request map. Optional
  arguments `http-url` and `proxy-ignore-hosts` are used to specify the host
  and a list of hostnames to ignore for any proxy settings. They can be safely
  ignored if not using proxies."
  [{:keys [request-interceptor response-interceptor
           proxy-host proxy-port async-http-builder-fns]
    :as req}
   conn-mgr & [http-url proxy-ignore-hosts]]
  ;; have to let first, otherwise we get a reflection warning on (.build)
  (let [^HttpAsyncClientBuilder builder (-> (HttpAsyncClients/custom)
                                            (.setConnectionManager conn-mgr)
                                            (.setRedirectStrategy
                                             (get-redirect-strategy req))
                                            ;; By default, get the proxy
                                            ;; settings from the jvm or system
                                            ;; properties
                                            (.setRoutePlanner
                                             (get-route-planner
                                              proxy-host proxy-port
                                              proxy-ignore-hosts http-url)))]
    (when (conn/reusable? conn-mgr)
      (.setConnectionManagerShared builder true))

    (when request-interceptor
      (.addInterceptorLast
       builder (proxy [HttpRequestInterceptor] []
                 (process [req ctx]
                   (request-interceptor req ctx)))))

    (when response-interceptor
      (.addInterceptorLast
       builder (proxy [HttpResponseInterceptor] []
                 (process [resp ctx]
                   (response-interceptor
                    resp ctx)))))
    (doseq [async-http-builder-fn async-http-builder-fns]
      (async-http-builder-fn builder req))
    (.build builder)))

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

(def ^:dynamic *cookie-store* nil)

(defn make-proxy-method [method url]
  (doto (proxy [HttpRequestBase] []
          (getMethod
            []
            (str method)))
    (.setURI (URI/create url))))

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
    (if body
      ((make-proxy-method-with-body request-method) http-url)
      (make-proxy-method request-method http-url))))

(defn ^HttpClientContext http-context [caching? request-config http-client-context]
  (let [^HttpClientContext typed-context (or http-client-context
                                             (if caching?
                                               (HttpCacheContext/create)
                                               (HttpClientContext/create)))]
    (doto typed-context
      (.setRequestConfig request-config))))

(defn ^CredentialsProvider credentials-provider []
  (BasicCredentialsProvider.))

(defn- coerce-body-entity
  "Coerce the http-entity from an HttpResponse to a stream that closes itself
  and the connection manager when closed."
  [^HttpEntity http-entity conn-mgr ^CloseableHttpResponse response]
  (if http-entity
    (proxy [FilterInputStream]
        [^InputStream (.getContent http-entity)]
      (close []
        (try
          ;; Eliminate the reflection warning from proxy-super
          (let [^InputStream this this]
            (proxy-super close))
          (finally
            (when (instance? CloseableHttpResponse response)
              (.close response))
            (when-not (conn/reusable? conn-mgr)
              (conn/shutdown-manager conn-mgr))))))
    (when-not (conn/reusable? conn-mgr)
      (conn/shutdown-manager conn-mgr))))

(defn- print-debug!
  "Print out debugging information to *out* for a given request."
  [{:keys [debug-body body] :as req} http-req]
  (println "Request:" (type body))
  (clojure.pprint/pprint
   (assoc req
          :body (if (opt req :debug-body)
                  (cond
                    (isa? (type body) String)
                    body

                    (isa? (type body) HttpEntity)
                    (let [baos (ByteArrayOutputStream.)]
                      (.writeTo ^HttpEntity body baos)
                      (.toString baos "UTF-8"))

                    :else nil)
                  (if (isa? (type body) String)
                    (format "... %s bytes ..."
                            (count body))
                    (and body (bean body))))
          :body-type (type body)))
  (println "HttpRequest:")
  (clojure.pprint/pprint (bean http-req)))

(defn- build-response-map
  [^HttpResponse response req ^HttpUriRequest http-req http-url
   conn-mgr ^HttpClientContext context ^CloseableHttpClient client]
  (let [^HttpEntity entity (.getEntity response)
        status (.getStatusLine response)
        protocol-version (.getProtocolVersion status)
        body (:body req)
        response
        {:body (coerce-body-entity entity conn-mgr response)
         :http-client client
         :headers (parse-headers
                   (.headerIterator response)
                   (opt req :use-header-maps-in-response))
         :length (if (nil? entity) 0 (.getContentLength entity))
         :chunked? (if (nil? entity) false (.isChunked entity))
         :repeatable? (if (nil? entity) false (.isRepeatable entity))
         :streaming? (if (nil? entity) false (.isStreaming entity))
         :status (.getStatusCode status)
         :protocol-version  {:name (.getProtocol protocol-version)
                             :major (.getMajor protocol-version)
                             :minor (.getMinor protocol-version)}
         :reason-phrase (.getReasonPhrase status)
         :trace-redirects (mapv str (.getRedirectLocations context))
         :cached (when (instance? HttpCacheContext context)
                   (when-let [cache-resp (.getCacheResponseStatus context)]
                     (-> cache-resp str keyword)))}]
    (if (opt req :save-request)
      (-> response
          (assoc :request req)
          (assoc-in [:request :body-type] (type body))
          (assoc-in [:request :http-url] http-url)
          (update-in [:request]
                     #(if (opt req :debug-body)
                        (assoc % :body-content
                               (cond
                                 (isa? (type (:body %)) String)
                                 (:body %)

                                 (isa? (type (:body %)) HttpEntity)
                                 (let [baos (ByteArrayOutputStream.)]
                                   (.writeTo ^HttpEntity (:body %) baos)
                                   (.toString baos "UTF-8"))

                                 :else nil))
                        %))
          (assoc-in [:request :http-req] http-req))
      response)))

(defn- get-conn-mgr
  [async? req]
  (if async?
    (or conn/*async-connection-manager*
        (conn/make-regular-async-conn-manager req))
    (or conn/*connection-manager*
        (conn/make-regular-conn-manager req))))

(defn request
  ([req] (request req nil nil))
  ([{:keys [body conn-timeout conn-request-timeout connection-manager
            cookie-store cookie-policy headers multipart mime-subtype
            http-multipart-mode query-string redirect-strategy max-redirects
            retry-handler request-method scheme server-name server-port
            socket-timeout uri response-interceptor proxy-host proxy-port
            http-client-context http-request-config http-client
            proxy-ignore-hosts proxy-user proxy-pass digest-auth ntlm-auth]
     :as req} respond raise]
   (let [async? (opt req :async)
         cache? (opt req :cache)
         scheme (name scheme)
         http-url (str scheme "://" server-name
                       (when server-port (str ":" server-port))
                       uri
                       (when query-string (str "?" query-string)))
         conn-mgr (or connection-manager
                      (get-conn-mgr async? req))
         proxy-ignore-hosts (or proxy-ignore-hosts
                                #{"localhost" "127.0.0.1"})
         ^RequestConfig request-config (or http-request-config
                                           (request-config req))
         ^HttpClientContext context
         (http-context cache? request-config http-client-context)
         ^HttpUriRequest http-req (http-request-for
                                   request-method http-url body)]
     (when-not (conn/reusable? conn-mgr)
       (.addHeader http-req "Connection" "close"))
     (when-let [cookie-jar (or cookie-store *cookie-store*)]
       (.setCookieStore context cookie-jar))
     (when-let [[user pass] digest-auth]
       (.setCredentialsProvider
        context
        (doto (credentials-provider)
          (.setCredentials (AuthScope. nil -1 nil)
                           (UsernamePasswordCredentials. user pass)))))
     (when (and proxy-user proxy-pass)
       (let [authscope (AuthScope. proxy-host proxy-port)
             creds (UsernamePasswordCredentials. proxy-user proxy-pass)]
         (.setCredentialsProvider
          context
          (doto (credentials-provider)
            (.setCredentials authscope creds)))))
     (if multipart
       (.setEntity ^HttpEntityEnclosingRequest http-req
                   (mp/create-multipart-entity multipart mime-subtype http-multipart-mode))
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
     (when (opt req :debug) (print-debug! req http-req))
     (if-not async?
       (let [^CloseableHttpClient client
             (or http-client
                 (build-http-client req cache?
                                    conn-mgr http-url proxy-ignore-hosts))]
         (try
           (build-response-map (.execute client http-req context)
                               req http-req http-url conn-mgr context client)
           (catch Throwable t
             (when-not (conn/reusable? conn-mgr)
               (conn/shutdown-manager conn-mgr))
             (throw t))))
       (let [^CloseableHttpAsyncClient client
             (build-async-http-client req conn-mgr http-url proxy-ignore-hosts)]
         (when cache?
           (throw (IllegalArgumentException.
                   "caching is not yet supported for async clients")))
         (.start client)
         (.execute client http-req context
                   (reify org.apache.http.concurrent.FutureCallback
                     (failed [this ex]
                       (when-not (conn/reusable? conn-mgr)
                         (conn/shutdown-manager conn-mgr))
                       (if (opt req :ignore-unknown-host)
                         ((:unknown-host-respond req) nil)
                         (raise ex)))
                     (completed [this resp]
                       (try
                         (respond (build-response-map
                                   resp req http-req http-url
                                   conn-mgr context client))
                         (catch Throwable t
                           (when-not (conn/reusable? conn-mgr)
                             (conn/shutdown-manager conn-mgr))
                           (raise t))))
                     (cancelled [this]
                       ;; Run the :oncancel function if available
                       (when-let [oncancel (:oncancel req)]
                         (oncancel))
                       ;; Attempt to abort the execution of the request
                       (.abort http-req)
                       (when-not (conn/reusable? conn-mgr)
                         (conn/shutdown-manager conn-mgr))))))))))
