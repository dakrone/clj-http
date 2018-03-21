(ns clj-http.examples.logging-apache-requests
  "This is an example of configuring Apache's log4j2 logging from Clojure, so
  that the http client logging can be seen"
  (:require [clj-http.client :as http])
  (:import (org.apache.logging.log4j Level
                                     LogManager)))

;; This is a helper function to change the log level for log4j2. If you use a
;; different logging framework (and subsequently a different bridge for log4j
;; then you'll need to substitute your own logging configuration
(defn change-log-level! [logger-name level]
  (let [ctx (LogManager/getContext false)
        config (.getConfiguration ctx)
        logger-config (.getLoggerConfig config logger-name)]
    (.setLevel logger-config level)
    (.updateLoggers ctx)))

;; Here is an example of using it to change the root logger to "DEBUG" and the
;; back to "INFO" after a request has been completed
(defn post-page-with-debug []
  (change-log-level! LogManager/ROOT_LOGGER_NAME Level/DEBUG)
  (http/post "https://httpbin.org/post" {:body "this is a test"})
  (change-log-level! LogManager/ROOT_LOGGER_NAME Level/INFO))
