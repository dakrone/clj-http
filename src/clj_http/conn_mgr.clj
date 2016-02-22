(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clj-http.util :refer [opt]]
            [clojure.java.io :as io])
  (:import (java.net Socket Proxy Proxy$Type InetSocketAddress)
           (java.security KeyStore)
           (java.security.cert X509Certificate)
           (javax.net.ssl SSLSession SSLSocket)
           (org.apache.http.config RegistryBuilder Registry)
           (org.apache.http.conn HttpClientConnectionManager)
           (org.apache.http.conn.ssl SSLSocketFactory)
           (org.apache.http.conn.scheme PlainSocketFactory
                                        SchemeRegistry Scheme)
           (org.apache.http.conn.socket PlainConnectionSocketFactory)
           (org.apache.http.impl.conn PoolingClientConnectionManager)
           (org.apache.http.impl.conn BasicHttpClientConnectionManager
                                      PoolingHttpClientConnectionManager)
           (org.apache.http.conn.ssl SSLConnectionSocketFactory
                                     DefaultHostnameVerifier
                                     NoopHostnameVerifier
                                     TrustStrategy)
           (org.apache.http.ssl SSLContexts)
           (org.apache.http.pool ConnPoolControl)))

(def ^SSLConnectionSocketFactory insecure-socket-factory
  (SSLConnectionSocketFactory.
   (-> (SSLContexts/custom)
       (.loadTrustMaterial nil (reify TrustStrategy
                                 (isTrusted [_ _ _] true)))
       (.build))
   NoopHostnameVerifier/INSTANCE))

(def ^SSLConnectionSocketFactory secure-ssl-socket-factory
  (SSLConnectionSocketFactory/getSocketFactory))

;; New Generic Socket Factories that can support socks proxy
(defn ^SSLSocketFactory SSLGenericSocketFactory
  "Given a function that returns a new socket, create an SSLSocketFactory that
  will use that socket."
  [socket-factory]
  (proxy [SSLSocketFactory] [(SSLContexts/createDefault)]
    (connectSocket [socket remoteAddress localAddress params]
      (let [^SSLSocketFactory this this] ;; avoid reflection
        (proxy-super connectSocket (socket-factory)
                     remoteAddress localAddress params)))))

(defn ^PlainSocketFactory PlainGenericSocketFactory
  "Given a Function that returns a new socket, create a PlainSocketFactory that
  will use that socket."
  [socket-factory]
  (proxy [PlainSocketFactory] []
    (createSocket [params]
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
        reg (doto (SchemeRegistry.)
              (.register
               (Scheme. "https" 443 (SSLGenericSocketFactory socket-factory)))
              (.register
               (Scheme. "http" 80 (PlainGenericSocketFactory socket-factory))))]
    (PoolingClientConnectionManager. reg)))

(def insecure-scheme-registry
  (-> (RegistryBuilder/create)
      (.register "http" PlainConnectionSocketFactory/INSTANCE)
      (.register "https" insecure-socket-factory)
      (.build)))

(def regular-scheme-registry
  (-> (RegistryBuilder/create)
      (.register "http" (PlainConnectionSocketFactory/getSocketFactory))
      (.register "https" secure-ssl-socket-factory)
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

(defn ^Registry get-keystore-scheme-registry
  [{:keys [keystore keystore-type keystore-pass keystore-instance
           trust-store trust-store-type trust-store-pass]
    :as req}]
  (let [ks (get-keystore keystore keystore-type keystore-pass)
        ts (get-keystore trust-store trust-store-type trust-store-pass)
        ssl-context (-> (SSLContexts/custom)
                        (.loadKeyMaterial
                         ks (when keystore-pass
                              (.toCharArray keystore-pass)))
                        (.loadTrustMaterial
                         ts nil)
                        (.build))
        hostname-verifier (if (opt req :insecure)
                            NoopHostnameVerifier/INSTANCE
                            (DefaultHostnameVerifier.))
        factory (SSLConnectionSocketFactory.
                 ssl-context hostname-verifier )]
    (-> (RegistryBuilder/create)
        (.register "https" factory)
        (.build))))

(defn ^BasicHttpClientConnectionManager make-regular-conn-manager
  [{:keys [keystore trust-store] :as req}]
  (cond
    (or keystore trust-store)
    (BasicHttpClientConnectionManager. (get-keystore-scheme-registry req))

    (opt req :insecure) (BasicHttpClientConnectionManager.
                         insecure-scheme-registry)

    :else (BasicHttpClientConnectionManager. regular-scheme-registry)))

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
    (PoolingHttpClientConnectionManager. registry)))

(defn reusable? [^HttpClientConnectionManager conn-mgr]
  (instance? PoolingHttpClientConnectionManager conn-mgr))

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

(defn shutdown-manager
  "Shut down the given connection manager, if it is not nil"
  [^HttpClientConnectionManager manager]
  (and manager (.shutdown manager)))

(def ^:dynamic *connection-manager*
  "connection manager to be rebound during request execution"
  nil)
