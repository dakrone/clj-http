(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clj-http.util :refer [opt]]
            [clojure.java.io :as io])
  (:import (java.net Socket Proxy Proxy$Type InetSocketAddress)
           (java.security KeyStore)
           (org.apache.http.config RegistryBuilder Registry)
           (org.apache.http.conn HttpClientConnectionManager)
           (org.apache.http.conn.ssl DefaultHostnameVerifier
                                     NoopHostnameVerifier
                                     SSLConnectionSocketFactory
                                     SSLContexts
                                     TrustStrategy)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.impl.conn BasicHttpClientConnectionManager
                                      PoolingHttpClientConnectionManager)
           (org.apache.http.impl.nio.conn PoolingNHttpClientConnectionManager)
           (javax.net.ssl SSLContext HostnameVerifier)
           (org.apache.http.nio.conn NHttpClientConnectionManager)
           (org.apache.http.nio.conn.ssl SSLIOSessionStrategy)
           (org.apache.http.impl.nio.reactor
            IOReactorConfig
            AbstractMultiworkerIOReactor$DefaultThreadFactory
            DefaultConnectingIOReactor)
           (org.apache.http.nio.conn NoopIOSessionStrategy)
           (org.apache.http.nio.protocol HttpAsyncRequestExecutor)
           (org.apache.http.impl.nio DefaultHttpClientIODispatch)
           (org.apache.http.config ConnectionConfig)))

(def ^:private insecure-context-verifier
  (delay {
          :context (-> (SSLContexts/custom)
                       (.loadTrustMaterial nil (reify TrustStrategy
                                                 (isTrusted [_ _ _] true)))
                       (.build))
          :verifier NoopHostnameVerifier/INSTANCE}))

(def ^:private insecure-socket-factory
  (delay
   (let [{:keys [context  verifier]} @insecure-context-verifier]
     (SSLConnectionSocketFactory. ^SSLContext context
                                  ^HostnameVerifier verifier))))

(def ^:private insecure-strategy
  (delay
   (let [{:keys [context  verifier]} @insecure-context-verifier]
     (SSLIOSessionStrategy. ^SSLContext context ^HostnameVerifier verifier))))

(def ^:private ^SSLConnectionSocketFactory secure-ssl-socket-factory
  (SSLConnectionSocketFactory/getSocketFactory))

(def ^:private ^SSLIOSessionStrategy secure-strategy
  (SSLIOSessionStrategy/getDefaultStrategy))

(defn ^SSLConnectionSocketFactory SSLGenericSocketFactory
  "Given a function that returns a new socket, create an
  SSLConnectionSocketFactory that will use that socket."
  ([socket-factory]
   (SSLGenericSocketFactory socket-factory (SSLContexts/createDefault)))
  ([socket-factory ^SSLContext ssl-context]
   (proxy [SSLConnectionSocketFactory] [ssl-context]
     (connectSocket [timeout socket host remoteAddress localAddress context]
       (let [^SSLConnectionSocketFactory this this] ;; avoid reflection
         (proxy-super connectSocket timeout (socket-factory) host remoteAddress
                      localAddress context))))))

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

(defn make-socks-proxied-conn-manager
  "Given an optional hostname and a port, create a connection manager that's
  proxied using a SOCKS proxy."
  ([^String hostname ^Integer port]
   (make-socks-proxied-conn-manager hostname port {}))
  ([^String hostname ^Integer port
    {:keys [keystore keystore-type keystore-pass
            trust-store trust-store-type trust-store-pass] :as opts}]
   (let [socket-factory #(socks-proxied-socket hostname port)
         ssl-context (when
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

(def ^:private insecure-scheme-registry
  (delay
   (-> (RegistryBuilder/create)
       (.register "http" PlainConnectionSocketFactory/INSTANCE)
       (.register "https" ^SSLConnectionSocketFactory @insecure-socket-factory)
       (.build))))

(def ^:private insecure-strategy-registry
  (delay
   (-> (RegistryBuilder/create)
       (.register "http" NoopIOSessionStrategy/INSTANCE)
       (.register "https" ^SSLIOSessionStrategy @insecure-strategy)
       (.build))))

(def ^:private regular-scheme-registry
  (-> (RegistryBuilder/create)
      (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
      (.register "https" secure-ssl-socket-factory)
      (.build)))

(def ^:private regular-strategy-registry
  (-> (RegistryBuilder/create)
      (.register "http" NoopIOSessionStrategy/INSTANCE)
      (.register "https" secure-strategy)
      (.build)))

(defn ^Registry get-keystore-scheme-registry
  [req]
  (let [{:keys [context verifier]} (get-keystore-context-verifier req)
        factory (SSLConnectionSocketFactory. ^SSLContext context
                                             ^HostnameVerifier verifier)]
    (-> (RegistryBuilder/create)
        (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
        (.register "https" factory)
        (.build))))

(defn ^Registry get-keystore-strategy-registry
  [req]
  (let [{:keys [context verifier]} (get-keystore-context-verifier req)
        strategy (SSLIOSessionStrategy. ^SSLContext context
                                        ^HostnameVerifier verifier)]
    (-> (RegistryBuilder/create)
        (.register "http" NoopIOSessionStrategy/INSTANCE)
        (.register "https" strategy)
        (.build))))

(defn ^BasicHttpClientConnectionManager make-regular-conn-manager
  [{:keys [keystore trust-store] :as req}]
  (cond
    (or keystore trust-store)
    (BasicHttpClientConnectionManager. (get-keystore-scheme-registry req))

    (opt req :insecure) (BasicHttpClientConnectionManager.
                         @insecure-scheme-registry)

    :else (BasicHttpClientConnectionManager. regular-scheme-registry)))

(defn- ^DefaultConnectingIOReactor make-ioreactor
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

(defn ^PoolingNHttpClientConnectionManager
  make-regular-async-conn-manager
  [{:keys [keystore trust-store] :as req}]
  (let [^Registry registry (cond
                             (or keystore trust-store)
                             (get-keystore-strategy-registry req)

                             (opt req :insecure)
                             @insecure-strategy-registry

                             :else regular-strategy-registry)
        io-reactor (make-ioreactor {:shutdown-grace-period 1})]
    (doto (PoolingNHttpClientConnectionManager. io-reactor registry)
      (.setMaxTotal 1))))

(definterface ReuseableAsyncConnectionManager)

;; need the fully qualified class name because this fn is later used in a
;; macro from a different ns
(defn ^org.apache.http.impl.conn.PoolingHttpClientConnectionManager
  make-reusable-conn-manager*
  "Given an timeout and optional insecure? flag, create a
  PoolingHttpClientConnectionManager with <timeout> seconds set as the
  timeout value."
  [{:keys [timeout keystore trust-store] :as config}]
  (let [registry (cond
                   (opt config :insecure) @insecure-scheme-registry

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else regular-scheme-registry)]
    (PoolingHttpClientConnectionManager.
     registry nil nil nil timeout java.util.concurrent.TimeUnit/SECONDS)))

(defn reusable? [conn-mgr]
  (or (instance? PoolingHttpClientConnectionManager conn-mgr)
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

  :keystore - keystore file to be used for connection manager
  :keystore-pass - keystore password
  :trust-store - trust store file to be used for connection manager
  :trust-store-pass - trust store password

  Note that :insecure? and :keystore/:trust-store options are mutually exclusive

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
    (.setMaxTotal conn-man threads)
    (when default-per-route
      (.setDefaultMaxPerRoute conn-man default-per-route))
    conn-man))

(defn- ^PoolingNHttpClientConnectionManager make-reusable-async-conn-manager*
  [{:keys [timeout keystore trust-store io-config] :as config}]
  (let [registry (cond
                   (opt config :insecure) @insecure-strategy-registry

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else regular-strategy-registry)
        io-reactor (make-ioreactor io-config)
        protocol-handler (HttpAsyncRequestExecutor.)
        io-event-dispatch (DefaultHttpClientIODispatch. protocol-handler
                                                        ConnectionConfig/DEFAULT)]
    (future (.execute io-reactor io-event-dispatch))
    (proxy [PoolingNHttpClientConnectionManager ReuseableAsyncConnectionManager]
        [io-reactor nil registry nil nil timeout
         java.util.concurrent.TimeUnit/SECONDS])))

(defn ^PoolingNHttpClientConnectionManager make-reuseable-async-conn-manager
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

(defmulti shutdown-manager
  "Shut down the given connection manager, if it is not nil"
  class)
(defmethod shutdown-manager nil [conn-mgr] nil)
(defmethod shutdown-manager org.apache.http.conn.HttpClientConnectionManager
  [^HttpClientConnectionManager  conn-mgr] (.shutdown conn-mgr))
(defmethod shutdown-manager
  org.apache.http.nio.conn.NHttpClientConnectionManager
  [^NHttpClientConnectionManager conn-mgr] (.shutdown conn-mgr))

(def ^:dynamic *connection-manager*
  "connection manager to be rebound during request execution"
  nil)

(def ^:dynamic *async-connection-manager*
  "connection manager to be rebound during async request execution"
  nil)
