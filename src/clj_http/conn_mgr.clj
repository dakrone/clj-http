(ns clj-http.conn-mgr
  "Utility methods for Scheme registries and HTTP connection managers"
  (:require [clojure.java.io :as io])
  (:import (java.security KeyStore)
           (org.apache.http.conn.ssl SSLSocketFactory TrustStrategy)
           (org.apache.http.conn.scheme PlainSocketFactory
                                        SchemeRegistry Scheme)
           (org.apache.http.impl.conn SingleClientConnManager
                                      SchemeRegistryFactory)
           (org.apache.http.impl.conn.tsccm ThreadSafeClientConnManager)))

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

(defn ^KeyStore get-keystore [keystore-file keystore-pass]
  (when keystore-file
    (let [keystore (KeyStore/getInstance (KeyStore/getDefaultType))]
      (with-open [is (io/input-stream keystore-file)]
        (.load keystore is (.toCharArray keystore-pass))
        keystore))))

(defn get-keystore-scheme-registry
  [{:keys [keystore keystore-pass trust-store trust-store-pass]}]
  (let [ks (get-keystore keystore keystore-pass)
        ts (get-keystore trust-store trust-store-pass)
        factory (SSLSocketFactory. ks keystore-pass ts)]
    (doto (SchemeRegistryFactory/createDefault)
      (.register (Scheme. "https" 443 factory)))))


(defn ^SingleClientConnManager make-regular-conn-manager
  [{:keys [insecure? keystore trust-store] :as req}]
  (cond
    insecure? (SingleClientConnManager. insecure-scheme-registry)

    (or keystore trust-store)
    (SingleClientConnManager. (get-keystore-scheme-registry req))

    :else (SingleClientConnManager.)))


;; need the fully qualified class name because this fn is later used in a
;; macro from a different ns
(defn ^org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
  make-reusable-conn-manager
  "Given an timeout and optional insecure? flag, create a
  ThreadSafeClientConnManager with <timeout> seconds set as the timeout value."
  [{:keys [timeout insecure? keystore trust-store] :as config}]
  (let [registry (cond
                   insecure? insecure-scheme-registry

                   (or keystore trust-store)
                   (get-keystore-scheme-registry config)

                   :else regular-scheme-registry)]
    (ThreadSafeClientConnManager.
     registry timeout java.util.concurrent.TimeUnit/SECONDS)))

;; connection manager to be rebound during request execution
(def ^{:dynamic true} *connection-manager* nil)
