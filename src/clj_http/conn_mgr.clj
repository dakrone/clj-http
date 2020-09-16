(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clj-http.util :refer [opt]]
            [clojure.java.io :as io])
  (:import (java.net Socket Proxy Proxy$Type InetSocketAddress)
           (java.security KeyStore)
           (javax.net.ssl KeyManager
                          TrustManager)
           (org.apache.hc.core5.util TimeValue)
           (org.apache.hc.core5.http.config RegistryBuilder Registry)
           (org.apache.hc.core5.http.io SocketConfig SocketConfig$Builder)
           (org.apache.hc.client5.http.ssl DefaultHostnameVerifier
                                           NoopHostnameVerifier
                                           SSLConnectionSocketFactory)
           (org.apache.hc.core5.ssl SSLContexts
                                    TrustStrategy)
           (org.apache.hc.client5.http.socket PlainConnectionSocketFactory)
           (org.apache.hc.client5.http.impl.io PoolingHttpClientConnectionManager)
           (org.apache.hc.client5.http.impl.nio PoolingAsyncClientConnectionManager
                                                PoolingAsyncClientConnectionManagerBuilder)
           (javax.net.ssl SSLContext HostnameVerifier)))

(defn ^SSLConnectionSocketFactory SSLGenericSocketFactory
  "Given a function that returns a new socket, create an
  SSLConnectionSocketFactory that will use that socket."
  ([socket-factory]
   (SSLGenericSocketFactory socket-factory nil))
  ([socket-factory ^SSLContext ssl-context]
   (let [^SSLContext ssl-context' (or ssl-context (SSLContexts/createDefault))]
     (proxy [SSLConnectionSocketFactory] [ssl-context']
       (connectSocket [timeout socket host remoteAddress localAddress context]
         (let [^SSLConnectionSocketFactory this this] ;; avoid reflection
           (proxy-super connectSocket timeout (socket-factory) host remoteAddress
                        localAddress context)))))))

(defn ^PlainConnectionSocketFactory PlainGenericSocketFactory
  "Given a Function that returns a new socket, create a
  PlainConnectionSocketFactory that will use that socket."
  [socket-factory]
  (proxy [PlainConnectionSocketFactory] []
    (createSocket [context]
      (socket-factory))))

(defn socks-proxied-socket
  "Create a Socket proxied through socks, using the given hostname and port"
  [^String hostname ^Integer port]
  (Socket. (Proxy. Proxy$Type/SOCKS (InetSocketAddress. hostname port))))

(defn capturing-socket
  "Create a java.net.Socket that will capture data sent in and out of it."
  [output-stream]
  (proxy [java.net.Socket] []
    ;; TODO: implement capturing the read data, currently I don't know of a good
    ;; way to proxy reading input into an arbitrary place
    (getInputStream []
      (proxy-super getInputStream))
    (getOutputStream []
      (let [stream (proxy-super getOutputStream)]
        (proxy [java.io.FilterOutputStream] [stream]
          (write
            ([b]
             (.write output-stream b)
             (proxy-super write b))
            ([b off len]
             (.write output-stream b off len)
             (proxy-super write b off len))))))))

(defn ^KeyStore get-keystore*
  [keystore-file keystore-type ^String keystore-pass]
  (when keystore-file
    (let [keystore (KeyStore/getInstance (or keystore-type
                                             (KeyStore/getDefaultType)))]
      (with-open [is (io/input-stream keystore-file)]
        (.load keystore is (when keystore-pass (.toCharArray keystore-pass)))
        keystore))))

(defn ^KeyStore get-keystore [keystore & args]
  (if (instance? KeyStore keystore)
    keystore
    (apply get-keystore* keystore args)))

(defn get-keystore-context-verifier
  ;; TODO: use something else for passwords
  ;; Note: JVM strings aren't ideal for passwords - see
  ;; https://tinyurl.com/azm3ab9
  [{:keys [keystore keystore-type ^String keystore-pass keystore-instance
           trust-store trust-store-type trust-store-pass]
    :as req}]
  (let [ks (get-keystore keystore keystore-type keystore-pass)
        ts (get-keystore trust-store trust-store-type trust-store-pass)]
    {:context (-> (SSLContexts/custom)
                  (.loadKeyMaterial
                   ks (when keystore-pass
                        (.toCharArray keystore-pass)))
                  (.loadTrustMaterial
                   ts nil)
                  (.build))
     :verifier (if (opt req :insecure)
                 NoopHostnameVerifier/INSTANCE
                 (DefaultHostnameVerifier.))}))

(defn get-managers-context-verifier
  "Given an instance or seqable data structure of TrustManager or KeyManager
  will create and return an SSLContexts object including the resulting managers"
  [{:keys [trust-managers key-managers]
    :as req}]
  (let [x-or-xs->x-array (fn [type x-or-xs]
                           (cond
                             (or (-> x-or-xs class .isArray)
                                 (sequential? x-or-xs))
                             (into-array type (seq x-or-xs))

                             :else
                             (into-array type [x-or-xs])))
        trust-managers (when trust-managers
                         (x-or-xs->x-array TrustManager trust-managers))
        key-managers (when key-managers
                       (x-or-xs->x-array KeyManager key-managers))]
    {:context (doto (.build (SSLContexts/custom))
                (.init key-managers trust-managers nil))
     :verifier (if (opt req :insecure)
                 NoopHostnameVerifier/INSTANCE
                 (DefaultHostnameVerifier.))}))

(defn make-socks-proxied-conn-manager
  "Given an optional hostname and a port, create a connection manager that's
  proxied using a SOCKS proxy."
  ([^String hostname ^Integer port]
   (make-socks-proxied-conn-manager hostname port {}))
  ([^String hostname ^Integer port
    {:keys [keystore keystore-type keystore-pass
            trust-store trust-store-type trust-store-pass
            trust-managers key-managers] :as opts}]
   (let [socket-factory #(socks-proxied-socket hostname port)
         ssl-context (cond
                       (or trust-managers key-managers)
                       (-> opts get-managers-context-verifier :context)

                       (some (complement nil?)
                             [keystore keystore-type keystore-pass trust-store
                              trust-store-type trust-store-pass])
                       (-> opts get-keystore-context-verifier :context))
         reg (-> (RegistryBuilder/create)
                 (.register "http" (PlainGenericSocketFactory socket-factory))
                 (.register "https"
                            (SSLGenericSocketFactory
                             socket-factory ssl-context))
                 (.build))]
     (PoolingHttpClientConnectionManager. reg))))

(defn make-capturing-socket-conn-manager
  "Given an optional hostname and a port, create a connection manager captures
  Socket data. `output` should be an `OutputStream` where all output from this
  factory will be sent."
  [output]
  (let [socket-factory #(capturing-socket output)
        reg (-> (RegistryBuilder/create)
                (.register "http" (PlainGenericSocketFactory socket-factory))
                (.register "https" (SSLGenericSocketFactory socket-factory nil))
                (.build))]
    (PoolingHttpClientConnectionManager. reg)))

(def ^:private insecure-scheme-registry
  (delay
    (let [context (-> (SSLContexts/custom)
                      (.loadTrustMaterial nil (reify TrustStrategy
                                                (isTrusted [_ _ _] true)))
                      (.build))
          verifier NoopHostnameVerifier/INSTANCE]
      (-> (RegistryBuilder/create)
          (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
          (.register "https" (SSLConnectionSocketFactory. ^SSLContext context
                                                          ^HostnameVerifier verifier))
          (.build)))))

(def ^:private regular-scheme-registry
  (delay (-> (RegistryBuilder/create)
             (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
             (.register "https" (SSLConnectionSocketFactory/getSocketFactory))
             (.build))))

(defn ^Registry get-custom-scheme-registry
  [{:keys [context verifier]}]
  (let [factory (SSLConnectionSocketFactory. ^SSLContext context
                                             ^HostnameVerifier verifier)]
    (-> (RegistryBuilder/create)
        (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
        (.register "https" factory)
        (.build))))

(defn ^Registry get-keystore-scheme-registry
  [req]
  (-> req
      get-keystore-context-verifier
      get-custom-scheme-registry))

(defn ^Registry get-managers-scheme-registry
  [req]
  (-> req
      get-managers-context-verifier
      get-custom-scheme-registry))

(defn ^SocketConfig get-socket-config
  "Creates a socket config from a map.

  The following options are supported:

  :socket/backlog-size
  :socket/rcv-buf-size
  :socket/snd-buf-size
  :socket/socks-proxy-address
  :socket/keepalive
  :socket/linger
  :socket/reuse-address
  :socket/timeout
  :socket/tcp-nodelay

  See https://javadoc.io/doc/org.apache.httpcomponents.core5/httpcore5/latest/index.html
  "
  ([config]
   (get-socket-config (SocketConfig/custom) config))
  ([^SocketConfig$Builder builder {:keys [socket-timeout]
                                   :socket/keys [backlog-size
                                                 rcv-buf-size
                                                 snd-buf-size
                                                 socks-proxy-address
                                                 keepalive
                                                 linger
                                                 reuse-address
                                                 timeout
                                                 tcp-nodelay]}]
   (cond-> builder
     backlog-size (.setBacklogSize backlog-size)
     rcv-buf-size (.setRcvBufSize rcv-buf-size)
     snd-buf-size (.setSndBufSize snd-buf-size)
     socks-proxy-address (.setSocksProxyAddress socks-proxy-address)
     keepalive (.setSoKeepAlive keepalive)
     linger (.setSoLinger linger)
     reuse-address (.setSoReuseAddress reuse-address)

     ;; set the timeout, falling back to non-namespaced key for backwards compatibility
     timeout (.setSoTimeout timeout java.util.concurrent.TimeUnit/SECONDS)
     socket-timeout (.setSoTimeout socket-timeout java.util.concurrent.TimeUnit/SECONDS)

     tcp-nodelay (.setTcpNoDelay tcp-nodelay)
     true (.build))))


(defn ^PoolingHttpClientConnectionManager make-regular-conn-manager
  [{:keys [dns-resolver
           keystore trust-store
           key-managers trust-managers] :as req}]
  (let [registry (cond (or key-managers trust-managers)
                       (get-managers-scheme-registry req)

                       (or keystore trust-store)
                       (get-keystore-scheme-registry req)

                       (opt req :insecure)
                       @insecure-scheme-registry

                       :else @regular-scheme-registry)
        ;; NOTE: Use PoolingHttPclientConnectionManager instead of BasicHttpClientManager seems to throw a lot of errors when managed by a http-client.
        ;; TODO: replace with make-reusable-conn-mgr*
        conn-manager (PoolingHttpClientConnectionManager. registry nil nil dns-resolver)]
    (.setDefaultSocketConfig conn-manager (get-socket-config req))
    conn-manager))

#_(defn- ^DefaultConnectingIOReactor make-ioreactor
  [{:keys [connect-timeout interest-op-queued io-thread-count rcv-buf-size
           select-interval shutdown-grace-period snd-buf-size
           so-keep-alive so-linger so-timeout tcp-no-delay]}]
  (as-> (IOReactorConfig/custom) c
    (if-some [v connect-timeout] (.setConnectTimeout c v) c)
    (if-some [v interest-op-queued] (.setInterestOpQueued c v) c)
    (if-some [v io-thread-count] (.setIoThreadCount c v) c)
    (if-some [v rcv-buf-size] (.setRcvBufSize c v) c)
    (if-some [v select-interval] (.setSelectInterval c v) c)
    (if-some [v shutdown-grace-period] (.setShutdownGracePeriod c v) c)
    (if-some [v snd-buf-size] (.setSndBufSize c v) c)
    (if-some [v so-keep-alive] (.setSoKeepAlive c v) c)
    (if-some [v so-linger] (.setSoLinger c v) c)
    (if-some [v so-timeout] (.setSoTimeout c v) c)
    (if-some [v tcp-no-delay] (.setTcpNoDelay c v) c)
    (DefaultConnectingIOReactor. (.build c))))

(defn ^PoolingAsyncClientConnectionManager
  make-regular-async-conn-manager
  [{:keys [keystore trust-store
           key-managers trust-managers] :as req}]
  ;; TODO: there are no longer un-reusable async connection managers, so this can go away
  (.build (PoolingAsyncClientConnectionManagerBuilder/create))
  #_(let [^Registry registry (cond
                             (or key-managers trust-managers)
                             (get-managers-strategy-registry req)
                             (or keystore trust-store)
                             (get-keystore-strategy-registry req)

                             (opt req :insecure)
                             @insecure-strategy-registry

                             :else @regular-strategy-registry)
        io-reactor (make-ioreactor {:shutdown-grace-period 1})]
    (doto (PoolingNHttpClientConnectionManager. io-reactor registry)
      (.setMaxTotal 1))))

(definterface ReuseableAsyncConnectionManager)

;; need the fully qualified class name because this fn is later used in a
;; macro from a different ns
(defn ^PoolingHttpClientConnectionManager
  make-reusable-conn-manager*
  "Given an timeout and optional insecure? flag, create a
  PoolingHttpClientConnectionManager with <timeout> seconds set as the
  timeout value."
  [{:keys [dns-resolver
           timeout
           keystore trust-store
           key-managers trust-managers

           ;; new stuff to manage
           connection-pool-reuse-policy
           connection-pool-concurrency-policy] :as config}]
  (let [registry (cond
                   (opt config :insecure)
                   @insecure-scheme-registry

                   (or key-managers trust-managers)
                   (get-managers-scheme-registry config)

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else @regular-scheme-registry)]

    (PoolingHttpClientConnectionManager. registry
                                         connection-pool-concurrency-policy
                                         connection-pool-reuse-policy
                                         (TimeValue/ofSeconds timeout)
                                         nil
                                         dns-resolver
                                         nil)))

(defn reusable? [conn-mgr]
  (or (instance? PoolingHttpClientConnectionManager conn-mgr)
      (instance? PoolingAsyncClientConnectionManager conn-mgr)
      (instance? ReuseableAsyncConnectionManager conn-mgr)))

(defn ^PoolingHttpClientConnectionManager make-reusable-conn-manager
  "Creates a default pooling connection manager with the specified options.

  The following options are supported:

  :timeout - Time that connections are left open before automatically closing
    default: 5
  :threads - Maximum number of threads that will be used for connecting
    default: 4
  :default-per-route - Maximum number of simultaneous connections per host
    default: 2
  :insecure? - Boolean flag to specify allowing insecure HTTPS connections
    default: false
  :connection-pool-reuse-policy - WIP
  :connection-pool-concurrency-policy - WIP

  :keystore - keystore file to be used for connection manager
  :keystore-pass - keystore password
  :trust-store - trust store file to be used for connection manager
  :trust-store-pass - trust store password

  :key-managers - KeyManager objects to be used for connection manager
  :trust-managers - TrustManager objects to be used for connection manager

  :dns-resolver - Use a custom DNS resolver instead of the default DNS resolver.
  :socket/*     - Default Socket Options. Same as `get-socket-config`.

  Note that :insecure? and :keystore/:trust-store/:key-managers/:trust-managers options are mutually exclusive

  Note that :key-managers/:trust-managers have precedence over :keystore/:trust-store options


  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [opts]
  (let [timeout (or (:timeout opts) 5)
        threads (or (:threads opts) 4)
        default-per-route (:default-per-route opts)
        insecure? (opt opts :insecure)
        leftovers (dissoc opts :timeout :threads :insecure? :insecure)
        conn-man (make-reusable-conn-manager* (merge {:timeout timeout
                                                      :insecure? insecure?}
                                                     leftovers))]
    (.setDefaultSocketConfig conn-man (get-socket-config opts))
    (.setMaxTotal conn-man threads)
    (when default-per-route
      (.setDefaultMaxPerRoute conn-man default-per-route))
    conn-man))

(defn- ^PoolingAsyncClientConnectionManager make-reusable-async-conn-manager*
  [{:keys [dns-resolver
           timeout keystore trust-store io-config
           key-managers trust-managers] :as config}]
  #_(let [registry (cond
                   (opt config :insecure) @insecure-strategy-registry

                   (or key-managers trust-managers)
                   (get-managers-scheme-registry config)

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else @regular-strategy-registry)
        io-reactor (make-ioreactor io-config)
        protocol-handler (HttpAsyncRequestExecutor.)
        io-event-dispatch (DefaultHttpClientIODispatch. protocol-handler
                                                        ConnectionConfig/DEFAULT)]
    (future (.execute io-reactor io-event-dispatch))
    (proxy [PoolingNHttpClientConnectionManager ReuseableAsyncConnectionManager]
        [io-reactor nil registry nil dns-resolver timeout
         java.util.concurrent.TimeUnit/SECONDS])))

(defn ^PoolingAsyncClientConnectionManager make-reusable-async-conn-manager
  "Creates a default pooling async connection manager with the specified
  options. Handles the same options as make-reusable-conn-manager plus
  :io-config which should be a map containing some of the following keys:

  :connect-timeout - int the default connect timeout value for connection
    requests (default 0, meaning no timeout)
  :interest-op-queued - boolean, whether or not I/O interest operations are to
    be queued and executed asynchronously or to be applied to the underlying
    SelectionKey immediately (default false)
  :io-thread-count - int, the number of I/O dispatch threads to be used
    (default is the number of available processors)
  :rcv-buf-size - int the default value of the SO_RCVBUF parameter for
    newly created sockets (default is 0, meaning the system default)
  :select-interval - long, time interval in milliseconds at which to check for
    timed out sessions and session requests (default 1000)
  :shutdown-grace-period - long, grace period in milliseconds to wait for
    individual worker threads to terminate cleanly (default 500)
  :snd-buf-size - int, the default value of the SO_SNDBUF parameter for
    newly created sockets (default is 0, meaning the system default)
  :so-keep-alive - boolean, the default value of the SO_KEEPALIVE parameter for
    newly created sockets (default false)
  :so-linger - int, the default value of the SO_LINGER parameter for
    newly created sockets (default -1)
  :so-timeout - int, the default socket timeout value for I/O operations
    (default 0, meaning no timeout)
  :tcp-no-delay - boolean, the default value of the TCP_NODELAY parameter for
    newly created sockets (default true)

  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [opts]
  (let [timeout (or (:timeout opts) 5)
        threads (or (:threads opts) 4)
        default-per-route (:default-per-route opts)
        insecure? (opt opts :insecure)
        leftovers (dissoc opts :timeout :threads :insecure? :insecure)
        conn-man (make-reusable-async-conn-manager*
                  (merge {:timeout timeout :insecure? insecure?} leftovers))]
    (.setMaxTotal conn-man threads)
    (when default-per-route
      (.setDefaultMaxPerRoute conn-man default-per-route))
    conn-man))

(defn ^PoolingAsyncClientConnectionManager make-reuseable-async-conn-manager
  "Wraps correctly-spelled version - keeping for backwards compatibility."
  [opts]
  (make-reusable-async-conn-manager opts))

(defmulti shutdown-manager
  "Shut down the given connection manager, if it is not nil"
  class)

(defmethod shutdown-manager nil [conn-mgr] nil)

(defmethod shutdown-manager PoolingHttpClientConnectionManager
  [^PoolingHttpClientConnectionManager  conn-mgr] (.close conn-mgr))

(defmethod shutdown-manager
  PoolingAsyncClientConnectionManager
  [^PoolingAsyncClientConnectionManager conn-mgr] (.close conn-mgr))

(defmethod shutdown-manager
  BasicHttpClientConnectionManager
  [^BasicHttpClientConnectionManager conn-mgr] (.close conn-mgr))

(def ^:dynamic *connection-manager*
  "connection manager to be rebound during request execution"
  nil)

(def ^:dynamic *async-connection-manager*
  "connection manager to be rebound during async request execution"
  nil)
