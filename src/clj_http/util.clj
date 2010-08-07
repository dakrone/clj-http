(ns clj-http.util
  "Helper functions for the HTTP client."
  (:import (java.net URLEncoder))
  (:import (org.apache.commons.codec.binary Base64))
  (:import (java.io ByteArrayInputStream ByteArrayOutputStream))
  (:import (java.util.zip InflaterInputStream DeflaterInputStream
                          GZIPInputStream GZIPOutputStream))
  (:import (org.apache.commons.io IOUtils)))

(defn url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [unencoded]
  (URLEncoder/encode unencoded "UTF-8"))

(defn base64-encode
  "Encode an array of bytes into a base64 encoded string."
  [unencoded]
  (String. (Base64/encodeBase64 unencoded)))

(defn gunzip
  "Returns a gunzip'd version of the given byte array."
  [b]
  (IOUtils/toByteArray (GZIPInputStream. (ByteArrayInputStream. b))))

(defn gzip
  "Returns a gzip'd version of the given byte array."
  [b]
  (let [baos (ByteArrayOutputStream.)
        gos  (GZIPOutputStream. baos)]
    (IOUtils/copy (ByteArrayInputStream. b) gos)
    (.close gos)
    (.toByteArray baos)))

(defn inflate
  "Returns a zlib inflate'd version of the given byte array."
  [b]
  (IOUtils/toByteArray (InflaterInputStream. (ByteArrayInputStream. b))))

(defn deflate
  "Returns a deflate'd version of the given byte array."
  [b]
  (IOUtils/toByteArray (DeflaterInputStream. (ByteArrayInputStream. b))))
