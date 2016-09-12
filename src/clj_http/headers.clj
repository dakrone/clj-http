(ns clj-http.headers
  "Provides wrap-header-map, which is middleware allows headers to be
  specified more flexibly. In requests and responses, headers can be
  accessed as strings or keywords of any case. In requests, string
  header names will be sent to the server with their casing unchanged,
  while keyword header names will be transformed into their canonical
  HTTP representation (e.g. :accept-encoding will become
  \"Accept-Encoding\")."
  (:require [clojure.string :as s]
            [potemkin :as potemkin])
  (:import (java.util Locale)
           (org.apache.http Header HeaderIterator)))

(def special-cases
  "A collection of HTTP headers that do not follow the normal
  Looks-Like-This casing."
  ["Content-MD5"
   "DNT"
   "ETag"
   "P3P"
   "TE"
   "WWW-Authenticate"
   "X-ATT-DeviceId"
   "X-UA-Compatible"
   "X-WebKit-CSP"
   "X-XSS-Protection"])

(defn special-case
  "Returns the special-case capitalized version of a string if that
  string is a special case, otherwise returns the string unchanged."
  [^String s]
  (or (first (filter #(.equalsIgnoreCase ^String % s) special-cases))
      s))

(defn ^String lower-case
  "Converts a string to all lower-case, using the root locale.

  Warning: This is not a general purpose lower-casing function -- it
  is useful for case-insensitive comparisons of strings, not for
  converting a string into something that's useful for humans."
  [^CharSequence s]
  (when s
    (.toLowerCase (.toString s) Locale/ROOT)))

(defn title-case
  "Converts a character to titlecase."
  [^Character c]
  (when c
    (Character/toTitleCase c)))

(defn canonicalize
  "Transforms a keyword header name into its canonical string
  representation.

  The canonical string representation is title-cased words separated
  by dashes, like so: :date -> \"Date\", :DATE -> \"Date\", and
  :foo-bar -> \"Foo-Bar\".

  However, there is special-casing for some common headers, so: :p3p
  -> \"P3P\", and :content-md5 -> \"Content-MD5\"."
  [k]
  (when k
    (-> (name k)
        (lower-case)
        (s/replace #"(?:^.|-.)"
                   (fn [s]
                     (if (next s)
                       (str (first s)
                            (title-case (second s)))
                       (str (title-case (first s))))))
        (special-case))))

(defn normalize
  "Turns a string or keyword into normalized form, which is a
  lowercase string."
  [k]
  (when k
    (lower-case (name k))))

(defn header-iterator-seq
  "Takes a HeaderIterator and returns a seq of vectors of name/value
  pairs of headers."
  [^HeaderIterator headers]
  (for [^Header h (iterator-seq headers)]
    [(.getName h) (.getValue h)]))

(defn assoc-join
  "Like assoc, but will join multiple values into a vector if the
  given key is already present into the map."
  [headers name value]
  (update-in headers [name]
             (fn [existing]
               (cond (vector? existing)
                     (conj existing value)
                     (nil? existing)
                     value
                     :else
                     [existing value]))))

;; a map implementation that stores both the original (or canonical)
;; key and value for each key/value pair, but performs lookups and
;; other operations using the normalized -- this allows a value to be
;; looked up by many similar keys, and not just the exact precise key
;; it was originally stored with.
(potemkin/def-map-type HeaderMap [m mta]
  (get [_ k v]
       (second (get m (normalize k) [nil v])))
  (assoc [_ k v]
         (HeaderMap. (assoc m (normalize k) [(if (keyword? k)
                                               (canonicalize k)
                                               k)
                                             v])
                     mta))
  (dissoc [_ k]
          (HeaderMap. (dissoc m (normalize k)) mta))
  (keys [_]
        (map first (vals m)))
  (meta [_]
        mta)
  (with-meta [_ mta]
    (HeaderMap. m mta))
  clojure.lang.Associative
  (containsKey [_ k]
               (contains? m (normalize k)))
  (empty [_]
         (HeaderMap. {} nil)))

(defn header-map
  "Returns a new header map with supplied mappings."
  [& keyvals]
  (into (HeaderMap. {} nil)
        (apply array-map keyvals)))

(defn- header-map-request
  [req]
  (let [req-headers (:headers req)]
    (if req-headers
      (-> req (assoc :headers (into (header-map) req-headers)
                     :use-header-maps-in-response? true))
      req)))

(defn wrap-header-map
  "Middleware that converts headers from a map into a header-map."
  [client]
  (fn
    ([req]
     (client (header-map-request req)))
    ([req respond raise]
     (client (header-map-request req) respond raise))))
