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
           (org.apache.http.nio.conn NoopIOSessionStrategy)))

(def insecure-context-verifier
  {
   :context (-> (SSLContexts/custom)
                (.loadTrustMaterial nil (reify TrustStrategy
                                          (isTrusted [_ _ _] true)))
                (.build))
   :verifier NoopHostnameVerifier/INSTANCE})

(def ^SSLIOSessionStrategy insecure-socket-factory
  (let [{:keys [context  verifier]} insecure-context-verifier]
    (SSLConnectionSocketFactory. ^SSLContext context
                                 ^HostnameVerifier verifier)))

(def ^SSLIOSessionStrategy insecure-strategy
  (let [{:keys [context  verifier]} insecure-context-verifier]
    (SSLIOSessionStrategy. ^SSLContext context ^HostnameVerifier verifier)))

(def ^SSLConnectionSocketFactory secure-ssl-socket-factory
  (SSLConnectionSocketFactory/getSocketFactory))

(def ^SSLIOSessionStrategy secure-strategy
  (SSLIOSessionStrategy/getDefaultStrategy))

(defn ^SSLConnectionSocketFactory SSLGenericSocketFactory
  "Given a function that returns a new socket, create an
  SSLConnectionSocketFactory that will use that socket."
  [socket-factory]
  (proxy [SSLConnectionSocketFactory] [(SSLContexts/createDefault)]
    (connectSocket [timeout socket host remoteAddress localAddress context]
      (let [^SSLConnectionSocketFactory this this] ;; avoid reflection
        (proxy-super connectSocket timeout (socket-factory) host remoteAddress
                     localAddress context)))))

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

(defn make-socks-proxied-conn-manager
  "Given an optional hostname and a port, create a connection manager that's
  proxied using a SOCKS proxy."
  [^String hostname ^Integer port]
  (let [socket-factory #(socks-proxied-socket hostname port)
        reg (-> (RegistryBuilder/create)
                (.register "http" (PlainGenericSocketFactory socket-factory))
                (.register "https" (SSLGenericSocketFactory socket-factory))
                (.build))]
    (PoolingHttpClientConnectionManager. reg)))

(def insecure-scheme-registry
  (-> (RegistryBuilder/create)
      (.register "http" PlainConnectionSocketFactory/INSTANCE)
      (.register "https" insecure-socket-factory)
      (.build)))

(def insecure-strategy-registry
  (-> (RegistryBuilder/create)
      (.register "http" NoopIOSessionStrategy/INSTANCE)
      (.register "https" insecure-strategy)
      (.build)))

(def regular-scheme-registry
  (-> (RegistryBuilder/create)
      (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
      (.register "https" secure-ssl-socket-factory)
      (.build)))

(def regular-strategy-registry
  (-> (RegistryBuilder/create)
      (.register "http" NoopIOSessionStrategy/INSTANCE)
      (.register "https" secure-strategy)
      (.build)))

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
  [{:keys [keystore keystore-type ^String keystore-pass keystore-instance   ; Note: JVM strings aren't ideal for passwords - see http://stackoverflow.com/questions/8881291/why-is-char-preferred-over-string-for-passwords
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

(defn ^Registry get-keystore-scheme-registry
  [req]
  (let [{:keys [context verifier]} (get-keystore-context-verifier req)
        factory (SSLConnectionSocketFactory. ^SSLContext context
                                             ^HostnameVerifier verifier)]
    (-> (RegistryBuilder/create)
        (.register "https" factory)
        (.build))))

(defn ^Registry get-keystore-strategy-registry
  [req]
  (let [{:keys [context verifier]} (get-keystore-context-verifier req)
        strategy (SSLIOSessionStrategy. ^SSLContext context
                                        ^HostnameVerifier verifier)]
    (-> (RegistryBuilder/create)
        (.register "https" strategy)
        (.build))))

(defn ^BasicHttpClientConnectionManager make-regular-conn-manager
  [{:keys [keystore trust-store] :as req}]
  (cond
    (or keystore trust-store)
    (BasicHttpClientConnectionManager. (get-keystore-scheme-registry req))

    (opt req :insecure) (BasicHttpClientConnectionManager.
                         insecure-scheme-registry)

    :else (BasicHttpClientConnectionManager. regular-scheme-registry)))

(defn- ^DefaultConnectingIOReactor default-ioreactor []
  (DefaultConnectingIOReactor. IOReactorConfig/DEFAULT nil))

(defn ^PoolingNHttpClientConnectionManager
  make-regular-async-conn-manager
  [{:keys [keystore trust-store] :as req}]
  (let [^Registry registry (cond
                             (or keystore trust-store)
                             (get-keystore-strategy-registry req)

                             (opt req :insecure)
                             insecure-strategy-registry

                             :else regular-strategy-registry)]
    (doto
        (PoolingNHttpClientConnectionManager. (-> (IOReactorConfig/custom)
                                                  (.setShutdownGracePeriod 1)
                                                  .build
                                                  DefaultConnectingIOReactor.)
                                              registry)
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
                   (opt config :insecure) insecure-scheme-registry

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
  [{:keys [timeout keystore trust-store] :as config}]
  (let [registry (cond
                   (opt config :insecure) insecure-strategy-registry

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else regular-strategy-registry)]
    (proxy [PoolingNHttpClientConnectionManager ReuseableAsyncConnectionManager]
        [(default-ioreactor) nil registry nil nil timeout
         java.util.concurrent.TimeUnit/SECONDS])))

(defn ^PoolingNHttpClientConnectionManager make-reuseable-async-conn-manager
  "Creates a default pooling async connection manager with the specified
  options. See alos make-reusable-conn-manager"
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
(defmethod shutdown-manager nil                                                   [conn-mgr] nil)
(defmethod shutdown-manager org.apache.http.conn.HttpClientConnectionManager      [^HttpClientConnectionManager  conn-mgr] (.shutdown conn-mgr))
(defmethod shutdown-manager org.apache.http.nio.conn.NHttpClientConnectionManager [^NHttpClientConnectionManager conn-mgr] (.shutdown conn-mgr))

(def ^:dynamic *connection-manager*
  "connection manager to be rebound during request execution"
  nil)

(def ^:dynamic *async-connection-manager*
  "connection manager to be rebound during async request execution"
  nil)
