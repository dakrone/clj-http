(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore)
           (java.security.cert X509Certificate)
           (javax.net.ssl SSLSession SSLSocket)
           (org.apache.http.conn.ssl AllowAllHostnameVerifier SSLSocketFactory
                                     TrustStrategy X509HostnameVerifier)
           (org.apache.http.conn.scheme PlainSocketFactory
                                        SchemeRegistry Scheme)
           (org.apache.http.impl.conn SingleClientConnManager
                                      SchemeRegistryFactory)
           (org.apache.http.impl.conn PoolingClientConnectionManager)
           (org.apache.http.conn.params ConnPerRouteBean)))

(def ^SSLSocketFactory insecure-socket-factory
  (SSLSocketFactory. (reify TrustStrategy
                       (isTrusted [_ _ _] true))
                     (reify X509HostnameVerifier
                       (^void verify [this ^String host ^SSLSocket sock]
                         ;; for some strange reason, only TLSv1 really
                         ;; works here, if you know why, tell me.
                         (.setEnabledProtocols
                          sock (into-array String ["TLSv1"]))
                         (.setWantClientAuth sock false)
                         (let [session (.getSession sock)]
                           (when-not session
                             (.startHandshake sock))
                           (aget (.getPeerCertificates session) 0)
                           ;; normally you'd want to verify the cert
                           ;; here, but since this is an insecure
                           ;; socketfactory, we don't
                           nil))
                       (^void verify [_ ^String _ ^X509Certificate _]
                         nil)
                       (^void verify [_ ^String _ ^"[Ljava.lang.String;" _
                                      ^"[Ljava.lang.String;" _]
                         nil)
                       (^boolean verify [_ ^String _ ^SSLSession _]
                         true))))

(def insecure-scheme-registry
  (doto (SchemeRegistry.)
    (.register (Scheme. "http" 80 (PlainSocketFactory/getSocketFactory)))
    (.register (Scheme. "https" 443 insecure-socket-factory))))

(def regular-scheme-registry
  (doto (SchemeRegistry.)
    (.register (Scheme. "http" 80 (PlainSocketFactory/getSocketFactory)))
    (.register (Scheme. "https" 443 (SSLSocketFactory/getSocketFactory)))))

(defn ^KeyStore get-keystore [keystore-file keystore-type ^String keystore-pass]
  (when keystore-file
    (let [keystore (KeyStore/getInstance (or keystore-type
                                             (KeyStore/getDefaultType)))]
      (with-open [is (io/input-stream keystore-file)]
        (.load keystore is (.toCharArray keystore-pass))
        keystore))))

(defn get-keystore-scheme-registry
  [{:keys [keystore keystore-type keystore-pass
           trust-store trust-store-type trust-store-pass insecure?]}]
  (let [ks (get-keystore keystore keystore-type keystore-pass)
        ts (get-keystore trust-store trust-store-type trust-store-pass)
        factory (SSLSocketFactory. ks keystore-pass ts)]
    (if insecure?
      (.setHostnameVerifier factory
                            SSLSocketFactory/ALLOW_ALL_HOSTNAME_VERIFIER))
    (doto (SchemeRegistryFactory/createDefault)
      (.register (Scheme. "https" 443 factory)))))


(defn ^SingleClientConnManager make-regular-conn-manager
  [{:keys [insecure? keystore trust-store] :as req}]
  (cond
   (or keystore trust-store)
   (SingleClientConnManager. (get-keystore-scheme-registry req))

   insecure? (SingleClientConnManager. insecure-scheme-registry)

   :else (SingleClientConnManager.)))


;; need the fully qualified class name because this fn is later used in a
;; macro from a different ns
(defn ^org.apache.http.impl.conn.PoolingClientConnectionManager
  make-reusable-conn-manager*
  "Given an timeout and optional insecure? flag, create a
  PoolingClientConnectionManager with <timeout> seconds set as the
  timeout value."
  [{:keys [timeout insecure? keystore trust-store] :as config}]
  (let [registry (cond
                  insecure? insecure-scheme-registry

                  (or keystore trust-store)
                  (get-keystore-scheme-registry config)

                  :else regular-scheme-registry)]
    (PoolingClientConnectionManager.
     registry timeout java.util.concurrent.TimeUnit/SECONDS)))

(def dmcpr ConnPerRouteBean/DEFAULT_MAX_CONNECTIONS_PER_ROUTE)

(defn make-reusable-conn-manager
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
        default-per-route (or (:default-per-route opts) dmcpr)
        insecure? (:insecure? opts)
        leftovers (dissoc opts :timeout :threads :insecure?)]
    (doto (make-reusable-conn-manager* (merge {:timeout timeout
                                               :insecure? insecure?}
                                              leftovers))
      (.setMaxTotal threads)
      (.setDefaultMaxPerRoute default-per-route))))

(defn shutdown-manager
  "Define function to shutdown manager"
  [manager]
  (and manager (.shutdown manager)))

(def ^{:dynamic true
       :doc "connection manager to be rebound during request execution"}
  *connection-manager* nil)
