(ns clj-http.util
  "Helper functions for the HTTP client."
  (:require [clojure.string :refer [blank? lower-case split trim]]
            [clojure.walk :refer [postwalk]])
  (:import [java.io BufferedInputStream ByteArrayInputStream ByteArrayOutputStream EOFException InputStream PushbackInputStream]
           [java.net URLDecoder URLEncoder]
           [java.util.zip DeflaterInputStream GZIPInputStream GZIPOutputStream InflaterInputStream]
           org.apache.commons.codec.binary.Base64
           org.apache.commons.io.IOUtils))

(defn utf8-bytes
  "Returns the encoding's bytes corresponding to the given string. If no
  encoding is specified, UTF-8 is used."
  [^String s & [^String encoding]]
  (.getBytes s (or encoding "UTF-8")))

(defn utf8-string
  "Returns the String corresponding to the given encoding's decoding of the
  given bytes. If no encoding is specified, UTF-8 is used."
  [^"[B" b & [^String encoding]]
  (String. b (or encoding "UTF-8")))

(defn url-decode
  "Returns the form-url-decoded version of the given string, using either a
  specified encoding or UTF-8 by default."
  [^String encoded & [^String encoding]]
  (URLDecoder/decode encoded (or encoding "UTF-8")))

(defn url-encode
  "Returns an UTF-8 URL encoded version of the given string."
  [^String unencoded & [^String encoding]]
  (URLEncoder/encode unencoded (or encoding "UTF-8")))

(defn base64-encode
  "Encode an array of bytes into a base64 encoded string."
  [unencoded]
  (utf8-string (Base64/encodeBase64 unencoded)))

(defn gunzip
  "Returns a gunzip'd version of the given byte array."
  [b]
  (when b
    (cond
      (instance? InputStream b)
      (let [^PushbackInputStream b (PushbackInputStream. b)
            first-byte (int (try (.read b) (catch EOFException _ -1)))]
        (case first-byte
          -1 b
          (do (.unread b first-byte)
              (GZIPInputStream. b))))
      :else
      (IOUtils/toByteArray (GZIPInputStream. (ByteArrayInputStream. b))))))

(defn gzip
  "Returns a gzip'd version of the given byte array."
  [b]
  (when b
    (let [baos (ByteArrayOutputStream.)
          gos  (GZIPOutputStream. baos)]
      (IOUtils/copy (ByteArrayInputStream. b) gos)
      (.close gos)
      (.toByteArray baos))))

(defn force-stream
  "Force b as InputStream if it is a ByteArray."
  ^InputStream [b]
  (if (instance? InputStream b)
    b
    (ByteArrayInputStream. b)))

(defn force-byte-array
  "force b as byte array if it is an InputStream, also close the stream"
  ^bytes [b]
  (if (instance? InputStream b)
    (let [^PushbackInputStream bs (PushbackInputStream. b)]
      (try
        (let [first-byte (int (try (.read bs) (catch EOFException _ -1)))]
          (case first-byte
            -1 (byte-array 0)
            (do (.unread bs first-byte)
                (IOUtils/toByteArray bs))))
        (finally (.close bs))))
    b))

(defn force-string
  "Convert s (a ByteArray or InputStream) to String."
  ^String [s ^String charset]
  (if (instance? InputStream s)
    (let [^PushbackInputStream bs (PushbackInputStream. s)]
      (try
        (let [first-byte (int (try (.read bs) (catch EOFException _ -1)))]
          (case first-byte
            -1 ""
            (do (.unread bs first-byte)
                (IOUtils/toString bs charset))))
        (finally (.close bs))))
    (IOUtils/toString ^"[B" s charset)))

(defn inflate
  "Returns a zlib inflate'd version of the given byte array or InputStream."
  [b]
  (when b
    ;; This weirdness is because HTTP servers lie about what kind of deflation
    ;; they're using, so we try one way, then if that doesn't work, reset and
    ;; try the other way
    (let [stream (BufferedInputStream. (if (instance? InputStream b)
                                         b
                                         (ByteArrayInputStream. b)))
          _ (.mark stream 512)
          iis (InflaterInputStream. stream)
          readable? (try (.read iis) true
                         (catch java.util.zip.ZipException _ false))]
      (.reset stream)
      (if readable?
        (InflaterInputStream. stream)
        (InflaterInputStream. stream (java.util.zip.Inflater. true))))))

(defn deflate
  "Returns a deflate'd version of the given byte array."
  [b]
  (when b
    (IOUtils/toByteArray (DeflaterInputStream. (ByteArrayInputStream. b)))))

(defn lower-case-keys
  "Recursively lower-case all map keys that are strings."
  [m]
  (let [f (fn [[k v]] (if (string? k) [(lower-case k) v] [k v]))]
    (postwalk (fn [x] (if (map? x) (into {} (map f x)) x)) m)))

(defn opt
  "Check the request parameters for a keyword  boolean option, with or without
  the ?

  Returns false if either of the values are false, or the value of
  (or key1 key2) otherwise (truthy)"
  [req param]
  (let [param-? (keyword (str (name param) "?"))
        v1 (clojure.core/get req param)
        v2 (clojure.core/get req param-?)]
    (if (false? v1)
      false
      (if (false? v2)
        false
        (or v1 v2)))))

(defn- trim-quotes [s]
  (when s
    (clojure.string/replace s #"^\s*(\"(.*)\"|(.*?))\s*$" "$2$3")))

(defn parse-content-type
  "Parse `s` as an RFC 2616 media type."
  [s]
  (when-let [m (re-matches #"\s*(([^/]+)/([^ ;]+))\s*(\s*;.*)?" (str s))]
    {:content-type (keyword (nth m 1))
     :content-type-params
     (->> (split (str (nth m 4)) #"\s*;\s*")
          (remove blank?)
          (map #(split % #"="))
          (mapcat (fn [[k v]] [(keyword (lower-case k)) (trim-quotes v)]))
          (apply hash-map))}))
