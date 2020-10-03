(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clj-http.util :refer [opt]]
            [clojure.java.io :as io]
            [clojure.string :as str])
  (:import java.net.InetSocketAddress
           java.security.KeyStore
           [javax.net.ssl HostnameVerifier KeyManager SSLContext TrustManager]
           org.apache.commons.io.output.TeeOutputStream
           org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager
           [org.apache.hc.client5.http.impl.nio PoolingAsyncClientConnectionManager PoolingAsyncClientConnectionManagerBuilder]
           org.apache.hc.client5.http.socket.PlainConnectionSocketFactory
           [org.apache.hc.client5.http.ssl DefaultHostnameVerifier NoopHostnameVerifier SSLConnectionSocketFactory]
           [org.apache.hc.core5.http.config Registry RegistryBuilder]
           [org.apache.hc.core5.http.io SocketConfig SocketConfig$Builder]
           [org.apache.hc.core5.pool PoolConcurrencyPolicy PoolReusePolicy]
           org.apache.hc.core5.reactor.IOReactorConfig
           [org.apache.hc.core5.ssl SSLContexts TrustStrategy]
           org.apache.hc.core5.util.TimeValue))

;; -- Helpers  -----------------------------------------------------------------
(defn into-inetaddress [socks-proxy-address]
  (cond
    (instance? InetSocketAddress socks-proxy-address)
    socks-proxy-address

    (map? socks-proxy-address)
    (InetSocketAddress. ^String (:hostname socks-proxy-address) ^Integer (:port socks-proxy-address))

    :else
    (throw (IllegalArgumentException. "Unable to coerce into inetaddress"))))

;; -- SocketFactory Helpers  ---------------------------------------------------
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

;; -- Custom SSL Contexts  -----------------------------------------------------
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

(defn- ssl-context-for-keystore
  ;; TODO: use something else for passwords
  ;; Note: JVM strings aren't ideal for passwords - see
  ;; https://tinyurl.com/azm3ab9
  [{:keys [keystore keystore-type ^String keystore-pass
           trust-store trust-store-type trust-store-pass]}]
  (let [ks (get-keystore keystore keystore-type keystore-pass)
        ts (get-keystore trust-store trust-store-type trust-store-pass)]
    (-> (SSLContexts/custom)
        (.loadKeyMaterial
         ks (when keystore-pass
              (.toCharArray keystore-pass)))
        (.loadTrustMaterial
         ts nil)
        (.build))))

(defn- ssl-context-for-trust-or-key-manager
  "Given an instance or seqable data structure of TrustManager or KeyManager
  will create and return an SSLContexts object including the resulting managers"
  [{:keys [trust-managers key-managers]}]
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
    (doto (.build (SSLContexts/custom))
      (.init key-managers trust-managers nil))))

(defn- ssl-context-insecure
  "Creates a SSL Context that trusts all material."
  []
  (-> (SSLContexts/custom)
      (.loadTrustMaterial (reify TrustStrategy
                            (isTrusted [_ chain auth-type] true)))
      (.build)))

(defn ^SSLContext get-ssl-context
  "Gets the SSL Context from a request or connection pool settings"
  [{:keys [keystore trust-store key-managers trust-managers] :as req}]
  (cond (or keystore trust-store)
        (ssl-context-for-keystore req)

        (or key-managers trust-managers)
        (ssl-context-for-trust-or-key-manager req)

        (opt req :insecure)
        (ssl-context-insecure)

        :else
        (SSLContexts/createDefault)))

;; -- Connection Configurations  -----------------------------------------------
(defn ^SocketConfig get-socket-config
  "Creates a socket config from a map.

  The following options are supported:

  :socket-timeout (for backwards compatibility)

  :socket/backlog-size
  :socket/rcv-buf-size
  :socket/snd-buf-size
  :socket/socks-proxy-address
  :socket/keepalive
  :socket/linger
  :socket/reuse-address
  :socket/timeout
  :socket/tcp-no-delay

  See https://javadoc.io/doc/org.apache.httpcomponents.core5/httpcore5/latest/index.html
  "
  ([config]
   (get-socket-config (SocketConfig/custom) config))
  ([^SocketConfig$Builder builder {:keys [socket-timeout
                                          :socket/backlog-size
                                          :socket/rcv-buf-size
                                          :socket/snd-buf-size
                                          :socket/socks-proxy-address
                                          :socket/keepalive
                                          :socket/linger
                                          :socket/reuse-address
                                          :socket/timeout
                                          :socket/tcp-no-delay]}]
   (cond-> builder
     backlog-size (.setBacklogSize backlog-size)
     rcv-buf-size (.setRcvBufSize rcv-buf-size)
     snd-buf-size (.setSndBufSize snd-buf-size)
     socks-proxy-address (.setSocksProxyAddress (into-inetaddress socks-proxy-address))
     keepalive (.setSoKeepAlive keepalive)
     linger (.setSoLinger linger)
     reuse-address (.setSoReuseAddress reuse-address)

     ;; set the timeout, falling back to non-namespaced key for backwards compatibility
     timeout (.setSoTimeout timeout java.util.concurrent.TimeUnit/MILLISECONDS)
     socket-timeout (.setSoTimeout socket-timeout java.util.concurrent.TimeUnit/MILLISECONDS)
     tcp-no-delay (.setTcpNoDelay tcp-no-delay)

     true (.build))))

(defn ^IOReactorConfig ioreactor-config
  [{:keys [backlog-size io-thread-count rcv-buf-size
           select-interval snd-buf-size
           so-keep-alive so-linger so-timeout tcp-no-delay]}]
  (let [builder (cond-> (IOReactorConfig/custom)
                  backlog-size (.setBacklogSize backlog-size)
                  io-thread-count (.setIoThreadCount io-thread-count)
                  rcv-buf-size (.setRcvBufSize rcv-buf-size)
                  select-interval (.setSelectInterval select-interval)
                  snd-buf-size (.setSndBufSize snd-buf-size)
                  so-keep-alive (.setSoKeepAlive so-keep-alive)
                  so-linger (.setSoLinger (TimeValue/ofMilliseconds so-linger))
                  so-timeout (.setSoTimeout so-timeout)
                  tcp-no-delay (.setTcpNoDelay tcp-no-delay))]
    (.build builder)))

;; -- Connection Managers  -----------------------------------------------------
(defn into-registry [registry]
  (cond
    (instance? Registry registry)
    registry

    (map? registry)
    (let [registry-builder (RegistryBuilder/create)]
      (doseq [[k v] registry]
        (.register registry-builder k v))
      (.build registry-builder))

    :else
    (throw (IllegalArgumentException. "Expected a registry"))))

;; TODO: take documentation from make-reusable-conn-manager
(defn ^PoolingHttpClientConnectionManager make-conn-manager
  "Creates a blocking connection manager with the specified options.

  The following options are supported:

  :connection-time-to-live - Time that connections are left open before automatically closing
    default: 5000 ms
  :pool-reuse-policy - Connection Pool Reuse Policy. One of `:lifo` or `:fifo`. See `org.apache.hc.core5.pool.PoolReusePolicy`.
  :dns-resolver - Use a custom DNS resolver instead of the default DNS resolver.
  :max-conn-per-route - Maximum number of simultaneous connections per host
    default: 2
  :max-conn-total - Sets the maximum total of connections in the pool.
  :pool-concurrency-policy - Concurrency Policy of Pool. One of `:lax` or `:strict`. See `org.apache.hc.core5.pool.PoolConcurrencyPolicy`.
  :scheme-port-resolver - A custom implementation of SchemePortResolver
  :validate-after-inactivity - How to wait before checking keepalive of connections

  SSL Connections can also be customized by setting these options:

  :insecure? - Boolean flag to specify allowing insecure HTTPS connections
    default: false
  :keystore - keystore file to be used for connection manager
  :keystore-pass - keystore password
  :trust-store - trust store file to be used for connection manager
  :trust-store-pass - trust store password
  :key-managers - KeyManager objects to be used for connection manager
  :trust-managers - TrustManager objects to be used for connection manager

  Note that :insecure? and :keystore/:trust-store/:key-managers/:trust-managers
  options are mutually exclusive.

  :socket-factory-registry - A custom socket facotry registry. This can be a
  clojure map *or* an instance of Registry<ConnectionSocketFactory>. This setting will override the SSL Connections section.

  Socket Configuration
  :default-socket-config - SocketConfig to use by default.
  :socket/*     - Default Socket Options. Same as `get-socket-config`.

  `:default-socket-config` is mututally exclusive to :socket/* options.

  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [{:keys [connection-time-to-live
           pool-reuse-policy
           default-socket-config
           dns-resolver
           max-conn-per-route
           max-conn-total
           pool-concurrency-policy
           scheme-port-resolver
           sockety-factory-registry
           validate-after-inactivity
           conn-factory] :as req :or {connection-time-to-live 5000
                                      max-conn-per-route 2}}]
  (let [socket-factory-registry (or sockety-factory-registry
                                    (into-registry
                                     {"http" (PlainConnectionSocketFactory/getSocketFactory)
                                      "https" (let [ssl-context (get-ssl-context req)
                                                    verifier (if (opt req :insecure)
                                                               NoopHostnameVerifier/INSTANCE
                                                               (DefaultHostnameVerifier.))]
                                                (SSLConnectionSocketFactory. ssl-context ^HostnameVerifier verifier))}))
        pool-concurrency-policy (when pool-concurrency-policy
                                  (PoolConcurrencyPolicy/valueOf (str/upper-case (name pool-concurrency-policy))))

        pool-reuse-policy (when pool-reuse-policy
                            (PoolReusePolicy/valueOf (str/upper-case (name pool-reuse-policy))))

        connection-time-to-live (when connection-time-to-live
                                  (TimeValue/ofMilliseconds connection-time-to-live))

        conn-mgr (PoolingHttpClientConnectionManager.
                  socket-factory-registry
                  pool-concurrency-policy
                  pool-reuse-policy
                  connection-time-to-live
                  scheme-port-resolver
                  dns-resolver
                  conn-factory)]
    (.setDefaultSocketConfig conn-mgr (or default-socket-config
                                          (get-socket-config req)))
    (when max-conn-per-route
      (.setDefaultMaxPerRoute conn-mgr max-conn-per-route))
    (when max-conn-total
      (.setMaxTotal conn-mgr max-conn-total))
    (when validate-after-inactivity
      (.setValidateAfterInactivity conn-mgr validate-after-inactivity))
    conn-mgr))


(defn ^PoolingAsyncClientConnectionManager make-async-conn-manager
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
  [{:keys [dns-resolver tls-strategy] :as req}]
  (-> (PoolingAsyncClientConnectionManagerBuilder/create)
      (.setDnsResolver dns-resolver)
      (.setTlsStrategy (or tls-strategy
                           (let [ssl-context (get-ssl-context req)
                                 verifier (if (opt req :insecure)
                                            NoopHostnameVerifier/INSTANCE
                                            (DefaultHostnameVerifier.))]
                             (org.apache.hc.client5.http.ssl.DefaultClientTlsStrategy. ssl-context verifier))))
      (.build)))

(defn shutdown-manager
  "Shut down the given connection manager, if it is not nil"
  [^java.io.Closeable conn-mgr]
  (when conn-mgr
    (.close conn-mgr)))

(def ^:dynamic *connection-manager*
  "connection manager to be rebound during request execution"
  nil)

(def ^:dynamic *async-connection-manager*
  "connection manager to be rebound during async request execution"
  nil)

;; -- Custom Connection Managers  ----------------------------------------------
(defn capturing-socket
  "Create a java.net.Socket that will capture data sent in and out of it."
  [^java.io.OutputStream output-stream]
  (proxy [java.net.Socket] []
    ;; TODO: implement capturing the read data, currently I don't know of a good
    ;; way to proxy reading input into an arbitrary place
    (getInputStream []
      (let [this ^java.net.Socket this]
        (proxy-super getInputStream)))
    (getOutputStream []
      (let [this ^java.net.Socket this
            stream (proxy-super getOutputStream)]
        (TeeOutputStream.
         stream
         output-stream)))))

(defn make-capturing-socket-conn-manager
  "Given an optional hostname and a port, create a connection manager captures
  Socket data. `output` should be an `OutputStream` where all output from this
  factory will be sent."
  [output]
  (let [socket-factory #(capturing-socket output)]
    (make-conn-manager {:socket-factory-registry
                        {"http" (PlainGenericSocketFactory socket-factory)
                         "https" (SSLGenericSocketFactory socket-factory nil)}})))
