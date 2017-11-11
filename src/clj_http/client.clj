(ns clj-http.client
  "Batteries-included HTTP client."
  (:require [clj-http.conn-mgr :as conn]
            [clj-http.cookies :refer [wrap-cookies]]
            [clj-http.core :as core]
            [clj-http.headers :refer [wrap-header-map]]
            [clj-http.links :refer [wrap-links]]
            [clj-http.util :refer [opt] :as util]
            [clojure.stacktrace :refer [root-cause]]
            [clojure.string :as str]
            [clojure.walk :refer [keywordize-keys prewalk]])
  (:import (java.io InputStream File ByteArrayOutputStream ByteArrayInputStream)
           (java.net URL UnknownHostException)
           (org.apache.http.entity BufferedHttpEntity ByteArrayEntity
                                   InputStreamEntity FileEntity StringEntity)
           (org.apache.http.impl.conn PoolingHttpClientConnectionManager)
           (org.apache.http.impl.nio.conn PoolingNHttpClientConnectionManager)
           (org.apache.http.impl.nio.client HttpAsyncClients))
  (:refer-clojure :exclude [get update]))

;; Cheshire is an optional dependency, so we check for it at compile time.
(def json-enabled?
  (try
    (require 'cheshire.core)
    true
    (catch Throwable _ false)))

;; Crouton is an optional dependency, so we check for it at compile time.
(def crouton-enabled?
  (try
    (require 'crouton.html)
    true
    (catch Throwable _ false)))

;; tools.reader is an optional dependency, so check at compile time.
(def edn-enabled?
  (try
    (require 'clojure.tools.reader.edn)
    true
    (catch Throwable _ false)))

;; Transit is an optional dependency, so check at compile time.
(def transit-enabled?
  (try
    (require 'cognitect.transit)
    true
    (catch Throwable _ false)))

;; ring-codec is an optional dependency, so we check for it at compile time.
(def ring-codec-enabled?
  (try
    (require 'ring.util.codec)
    true
    (catch Throwable _ false)))

(defn ^:dynamic parse-edn
  "Resolve and apply tool.reader's EDN parsing."
  [& args]
  {:pre [edn-enabled?]}
  (apply (ns-resolve (symbol "clojure.tools.reader.edn")
                     (symbol "read-string"))
         {:readers @(or (resolve '*data-readers*) (atom {}))} args))

(defn ^:dynamic parse-html
  "Resolve and apply crouton's HTML parsing."
  [& args]
  {:pre [crouton-enabled?]}
  (apply (ns-resolve (symbol "crouton.html") (symbol "parse")) args))

(defn- transit-opts-by-type
  "Return the Transit options by type."
  [opts type class-name]
  {:pre [transit-enabled?]}
  (cond
    (empty? opts)
    opts
    (contains? opts type)
    (clojure.core/get opts type)
    :else
    (let [class (Class/forName class-name)]
      (println "Deprecated use of :transit-opts found.")
      (update-in opts [:handlers]
                 (fn [handlers]
                   (->> handlers
                        (filter #(instance? class (second %)))
                        (into {})))))))

(defn- transit-read-opts
  "Return the Transit read options."
  [opts]
  {:pre [transit-enabled?]}
  (transit-opts-by-type opts :decode "com.cognitect.transit.ReadHandler"))

(defn- transit-write-opts
  "Return the Transit write options."
  [opts]
  {:pre [transit-enabled?]}
  (transit-opts-by-type opts :encode "com.cognitect.transit.WriteHandler"))

(defn ^:dynamic parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [in type & [opts]]
  {:pre [transit-enabled?]}
  (when (pos? (.available in))
    (let [reader (ns-resolve 'cognitect.transit 'reader)
          read (ns-resolve 'cognitect.transit 'read)]
      (read (reader in type (transit-read-opts opts))))))

(defn ^:dynamic transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  {:pre [transit-enabled?]}
  (let [output (ByteArrayOutputStream.)
        writer (ns-resolve 'cognitect.transit 'writer)
        write (ns-resolve 'cognitect.transit 'write)]
    (write (writer output type (transit-write-opts opts)) out)
    (.toByteArray output)))

(defn ^:dynamic json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

(defn ^:dynamic json-decode
  "Resolve and apply cheshire's json decoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

(defn ^:dynamic json-decode-strict
  "Resolve and apply cheshire's json decoding dynamically (with lazy parsing
  disabled)."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode-strict")) args))

(defn ^:dynamic form-decode
  "Resolve and apply ring-codec's form decoding dynamically."
  [& args]
  {:pre [ring-codec-enabled?]}
  (apply (ns-resolve (symbol "ring.util.codec") (symbol "form-decode")) args))

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn dissoc-in
  "Dissociates an entry from a nested associative structure returning a new
  nested structure. keys is a sequence of keys. Any empty maps that result
  will not be present in the new structure."
  [m [k & ks :as keys]]
  (if ks
    (if-let [nextmap (clojure.core/get m k)]
      (let [newmap (dissoc-in nextmap ks)]
        (if (seq newmap)
          (assoc m k newmap)
          (dissoc m k)))
      m)
    (dissoc m k)))

(defn url-encode-illegal-characters
  "Takes a raw url path or query and url-encodes any illegal characters.
  Minimizes ambiguity by encoding space to %20."
  [path-or-query]
  (when path-or-query
    (-> path-or-query
        (str/replace " " "%20")
        (str/replace #"[^a-zA-Z0-9\.\-\_\~\!\$\&\'\(\)\*\+\,\;\=\:\@\/\%\?]"
                     util/url-encode))))

(defn parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (url-encode-illegal-characters (.getPath url-parsed))
     :user-info (if-let [user-info (.getUserInfo url-parsed)]
                  (util/url-decode user-info))
     :query-string (url-encode-illegal-characters (.getQuery url-parsed))}))

(defn unparse-url
  "Takes a map of url-parts and generates a string representation.
  WARNING: does not do any sort of encoding! Don't use this for strict RFC
  following!"
  [{:keys [scheme server-name server-port uri user-info query-string]}]
  (str (name scheme) "://"
       (if (seq user-info)
         (str user-info "@" server-name)
         server-name)
       (when server-port
         (str ":" server-port))
       uri
       (when (seq query-string)
         (str "?" query-string))))

;; Statuses for which clj-http will not throw an exception
(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 304 307})

;; helper methods to determine realm of a response
(defn success?
  [{:keys [status]}]
  (<= 200 status 299))

(defn missing?
  [{:keys [status]}]
  (= status 404))

(defn conflict?
  [{:keys [status]}]
  (= status 409))

(defn redirect?
  [{:keys [status]}]
  (<= 300 status 399))

(defn client-error?
  [{:keys [status]}]
  (<= 400 status 499))

(defn server-error?
  [{:keys [status]}]
  (<= 500 status 599))

(defn- exceptions-response
  [req {:keys [status] :as resp}]
  (if (unexceptional-status? status)
    resp
    (if (false? (opt req :throw-exceptions))
      resp
      (let [data (assoc resp :type ::unexceptional-status)]
        (if (opt req :throw-entire-message)
          (throw (ex-info (format "clj-http: status %d %s" status resp) data))
          (throw (ex-info (format "clj-http: status %s" status) data)))))))

(defn wrap-exceptions
  "Middleware that throws a ex-info exception if the response is not a
  regular response. If :throw-entire-message? is set to true, the entire
  response is used as the message, instead of just the status number."
  [client]
  (fn
    ([req]
     (exceptions-response req (client req)))
    ([req response raise]
     (client req
             (fn [resp]
               (response (exceptions-response req resp)))
             raise))))

(declare reuse-pool)

(defn- respond*
  [resp req]
  (if (:async? req)
    ((:respond req) resp)
    resp))

;; Multimethods for Content-Encoding dispatch automatically
;; decompressing response bodies
(defmulti decompress-body
  (fn [resp] (get-in resp [:headers "content-encoding"])))

(defmethod decompress-body "gzip"
  [resp]
  (-> resp
      (update :body util/gunzip)
      (assoc :orig-content-encoding (get-in resp [:headers "content-encoding"]))
      (dissoc-in [:headers "content-encoding"])))

(defmethod decompress-body "deflate"
  [resp]
  (-> resp
      (update :body util/inflate)
      (assoc :orig-content-encoding (get-in resp [:headers "content-encoding"]))
      (dissoc-in [:headers "content-encoding"])))

(defmethod decompress-body :default [resp]
  (assoc resp
         :orig-content-encoding
         (get-in resp [:headers "content-encoding"])))

(defn- decompression-request
  [req]
  (if (false? (opt req :decompress-body))
    req
    (update-in req [:headers "accept-encoding"]
               #(str/join ", " (remove nil? [% "gzip, deflate"])))))

(defn- decompression-response
  [req resp]
  (if (false? (opt req :decompress-body))
    resp
    (decompress-body resp)))

(defn wrap-decompression
  "Middleware handling automatic decompression of responses from web servers. If
  :decompress-body is set to false, does not automatically set `Accept-Encoding`
  header or decompress body."
  [client]
  (fn
    ([req]
     (decompression-response req (client (decompression-request req))))
    ([req respond raise]
     (client (decompression-request req)
             #(respond (decompression-response req %))
             raise))))

;; Multimethods for coercing body type to the :as key
(defmulti coerce-response-body (fn [req _] (:as req)))

(defmethod coerce-response-body :byte-array [_ resp]
  (assoc resp :body (util/force-byte-array (:body resp))))

(defmethod coerce-response-body :stream [_ resp]
  (let [body (:body resp)]
    (cond (instance? InputStream body) resp
          ;; This shouldn't happen, but we plan for it anyway
          (instance? (Class/forName "[B") body)
          (assoc resp :body (ByteArrayInputStream. body)))))

(defn coerce-json-body
  [{:keys [coerce]} {:keys [body status] :as resp} keyword? strict? & [charset]]
  (let [^String charset (or charset (-> resp :content-type-params :charset)
                            "UTF-8")
        body (util/force-byte-array body)
        decode-func (if strict? json-decode-strict json-decode)]
    (if json-enabled?
      (cond
        (= coerce :always)
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (unexceptional-status? status)
             (or (nil? coerce) (= coerce :unexceptional)))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        (and (not (unexceptional-status? status)) (= coerce :exceptional))
        (assoc resp :body (decode-func (String. ^"[B" body charset) keyword?))

        :else (assoc resp :body (String. ^"[B" body charset)))
      (assoc resp :body (String. ^"[B" body charset)))))

(defn coerce-clojure-body
  [request {:keys [body] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")
        body            (util/force-byte-array body)]
    (assoc resp :body (cond
                        (empty? body) nil
                        edn-enabled? (parse-edn (String. ^"[B" body charset))
                        :else (binding [*read-eval* false]
                                (read-string (String. ^"[B" body charset)))))))

(defn coerce-transit-body
  [{:keys [transit-opts coerce] :as request}
   {:keys [body status] :as resp} type & [charset]]
  (let [^String charset (or charset (-> resp :content-type-params :charset)
                            "UTF-8")
        body (util/force-byte-array body)]
    (if-not (empty? body)
      (if transit-enabled?
        (cond
          (= coerce :always)
          (assoc resp :body (parse-transit
                             (ByteArrayInputStream. body) type transit-opts))

          (and (unexceptional-status? status)
               (or (nil? coerce) (= coerce :unexceptional)))
          (assoc resp :body (parse-transit
                             (ByteArrayInputStream. body) type transit-opts))

          (and (not (unexceptional-status? status)) (= coerce :exceptional))
          (assoc resp :body (parse-transit
                             (ByteArrayInputStream. body) type transit-opts))

          :else (assoc resp :body (String. ^"[B" body charset)))
        (assoc resp :body (String. ^"[B" body charset)))
      (assoc resp :body nil))))

(defn coerce-form-urlencoded-body
  [request {:keys [body] :as resp}]
  (let [^String charset (or (-> resp :content-type-params :charset) "UTF-8")
        body-bytes (util/force-byte-array body)]
    (if ring-codec-enabled?
      (assoc resp :body (-> (String. ^"[B" body-bytes charset)
                            form-decode keywordize-keys))
      (assoc resp :body (String. ^"[B" body-bytes charset)))))

(defmulti coerce-content-type (fn [req resp] (:content-type resp)))

(defmethod coerce-content-type :application/clojure [req resp]
  (coerce-clojure-body req resp))

(defmethod coerce-content-type :application/edn [req resp]
  (coerce-clojure-body req resp))

(defmethod coerce-content-type :application/json [req resp]
  (coerce-json-body req resp true false))

(defmethod coerce-content-type :application/transit+json [req resp]
  (coerce-transit-body req resp :json))

(defmethod coerce-content-type :application/transit+msgpack [req resp]
  (coerce-transit-body req resp :msgpack))

(defmethod coerce-content-type :application/x-www-form-urlencoded [req resp]
  (coerce-form-urlencoded-body req resp))

(defmethod coerce-content-type :default [req resp]
  (if-let [charset (-> resp :content-type-params :charset)]
    (coerce-response-body {:as charset} resp)
    (coerce-response-body {:as :default} resp)))

(defmethod coerce-response-body :auto [request resp]
  (let [header (get-in resp [:headers "content-type"])]
    (->> (merge resp (util/parse-content-type header))
         (coerce-content-type request))))

(defmethod coerce-response-body :json [req resp]
  (coerce-json-body req resp true false))

(defmethod coerce-response-body :json-strict [req resp]
  (coerce-json-body req resp true true))

(defmethod coerce-response-body :json-strict-string-keys [req resp]
  (coerce-json-body req resp false true))

(defmethod coerce-response-body :json-string-keys [req resp]
  (coerce-json-body req resp false false))

(defmethod coerce-response-body :clojure [req resp]
  (coerce-clojure-body req resp))

(defmethod coerce-response-body :transit+json [req resp]
  (coerce-transit-body req resp :json))

(defmethod coerce-response-body :transit+msgpack [req resp]
  (coerce-transit-body req resp :msgpack))

(defmethod coerce-response-body :x-www-form-urlencoded [req resp]
  (coerce-form-urlencoded-body req resp))

(defmethod coerce-response-body :default
  [{:keys [as]} {:keys [body] :as resp}]
  (let [body-bytes (util/force-byte-array body)]
    (cond
      (string? as)  (assoc resp :body (String. ^"[B" body-bytes ^String as))
      :else (assoc resp :body (String. ^"[B" body-bytes "UTF-8")))))

(defn- output-coercion-response
  [req {:keys [body] :as resp}]
  (if body
    (coerce-response-body req resp)
    resp))

(defn wrap-output-coercion
  "Middleware converting a response body from a byte-array to a different
  object. Defaults to a String if no :as key is specified, the
  `coerce-response-body` multimethod may be extended to add
  additional coercions."
  [client]
  (fn
    ([req]
     (output-coercion-response req (client req)))
    ([req respond raise]
     (client req
             #(respond (output-coercion-response req %))
             raise))))

(defn maybe-wrap-entity
  "Wrap an HttpEntity in a BufferedHttpEntity if warranted."
  [{:keys [entity-buffering]} entity]
  (if (and entity-buffering (not= BufferedHttpEntity (class entity)))
    (BufferedHttpEntity. entity)
    entity))

(defn- input-coercion-request
  [{:keys [body body-encoding length]
    :or {^String body-encoding "UTF-8"} :as req}]
  (if body
    (cond
      (string? body)
      (-> req (assoc :body (maybe-wrap-entity
                            req (StringEntity. ^String body
                                               ^String body-encoding))
                     :character-encoding (or body-encoding
                                             "UTF-8")))
      (instance? File body)
      (-> req (assoc :body
                     (maybe-wrap-entity
                      req (FileEntity. ^File body
                                       ^String body-encoding))))

      ;; A length of -1 instructs HttpClient to use chunked encoding.
      (instance? InputStream body)
      (-> req
          (assoc :body
                 (if length
                   (InputStreamEntity.
                    ^InputStream body (long length))
                   (maybe-wrap-entity
                    req
                    (InputStreamEntity. ^InputStream body -1)))))

      (instance? (Class/forName "[B") body)
      (-> req (assoc :body (maybe-wrap-entity
                            req (ByteArrayEntity. body))))

      :else
      req)
    req))

(defn wrap-input-coercion
  "Middleware coercing the :body of a request from a number of formats into an
  Apache Entity. Currently supports Strings, Files, InputStreams
  and byte-arrays."
  [client]
  (fn
    ([req]
     (client (input-coercion-request req)))
    ([req respond raise]
     (client (input-coercion-request req) respond raise))))

(defn get-headers-from-body
  "Given a map of body content, return a map of header-name to header-value."
  [body-map]
  (let [;; parse out HTML content
        h (or (:content body-map)
              (:content (first (filter #(= (:tag %) :html) body-map))))
        ;; parse out <head> tags
        heads (:content (first (filter #(= (:tag %) :head) h)))
        ;; parse out attributes of 'meta' head tags
        attrs (map :attrs (filter #(= (:tag %) :meta) heads))
        ;; parse out the 'http-equiv' meta head tags
        http-attrs (filter :http-equiv attrs)
        ;; parse out HTML5 charset meta tags
        html5-charset (filter :charset attrs)
        ;; convert http-attributes into map of headers (lowercased)
        headers (apply merge (map (fn [{:keys [http-equiv content]}]
                                    {(.toLowerCase ^String http-equiv) content})
                                  http-attrs))
        ;; merge in html5 charset setting
        headers (merge headers
                       (when-let [cs (:charset (first html5-charset))]
                         {"content-type" (str "text/html; charset=" cs)}))]
    headers))

(defn- additional-header-parsing-response
  [req resp]
  (if (and (opt req :decode-body-headers)
           crouton-enabled?
           (:body resp)
           (let [^String content-type (get-in resp [:headers "content-type"])]
             (or (str/blank? content-type)
                 (.startsWith content-type "text"))))
    (let [body-bytes (util/force-byte-array (:body resp))
          body-stream1 (java.io.ByteArrayInputStream. body-bytes)
          body-map (parse-html body-stream1)
          additional-headers (get-headers-from-body body-map)
          body-stream2 (java.io.ByteArrayInputStream. body-bytes)]
      (assoc resp
             :headers (merge (:headers resp) additional-headers)
             :body body-stream2))
    resp))

(defn wrap-additional-header-parsing
  "Middleware that parses additional http headers from the body of a web page,
  adding them into the headers map of the response if any are found. Only looks
  at the body if the :decode-body-headers option is set to a truthy value. Will
  be silently disabled if crouton is excluded from clj-http's dependencies. Will
  do nothing if no body is returned, e.g. HEAD requests"
  [client]
  (fn
    ([req]
     (additional-header-parsing-response req (client req)))
    ([req respond raise]
     (client req
             #(respond (additional-header-parsing-response req %)) raise))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn- content-type-request
  [{:keys [content-type character-encoding] :as req}]
  (if content-type
    (let [ctv (content-type-value content-type)
          ct (if character-encoding
               (str ctv "; charset=" character-encoding)
               ctv)]
      (update-in req [:headers] assoc "content-type" ct))
    req))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [client]
  (fn
    ([req]
     (client (content-type-request req)))
    ([req respond raise]
     (client (content-type-request req) respond raise))))

(defn- accept-request
  [{:keys [accept] :as req}]
  (if accept
    (-> req (dissoc :accept)
        (assoc-in [:headers "accept"]
                  (content-type-value accept)))
    req))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [client]
  (fn
    ([req]
     (client (accept-request req)))
    ([req respond raise]
     (client (accept-request req) respond raise))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn- accept-encoding-request
  [{:keys [accept-encoding] :as req}]
  (if accept-encoding
    (-> req
        (dissoc :accept-encoding)
        (assoc-in [:headers "accept-encoding"]
                  (accept-encoding-value accept-encoding)))
    req))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [client]
  (fn
    ([req]
     (client (accept-encoding-request req)))
    ([req respond raise]
     (client (accept-encoding-request req) respond raise))))

(defn detect-charset
  "Given a charset header, detect the charset, returns UTF-8 if not found."
  [content-type]
  (or
   (when-let [found (when content-type
                      (re-find #"(?i)charset\s*=\s*([^\s]+)" content-type))]
     (second found))
   "UTF-8"))

(defn- multi-param-suffix [index multi-param-style]
  (case multi-param-style
    :indexed (str "[" index "]")
    :array "[]"
    ""))

(defn generate-query-string-with-encoding [params encoding multi-param-style]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map-indexed
                         #(str (util/url-encode (name k) encoding)
                               (multi-param-suffix %1 multi-param-style)
                               "="
                               (util/url-encode (str %2) encoding)) v)
                        [(str (util/url-encode (name k) encoding)
                              "="
                              (util/url-encode (str v) encoding))]))
                    params)))

(defn generate-query-string [params & [content-type multi-param-style]]
  (let [encoding (detect-charset content-type)]
    (generate-query-string-with-encoding params encoding multi-param-style)))

(defn- query-params-request
  [{:keys [query-params content-type multi-param-style]
    :or {content-type :x-www-form-urlencoded}
    :as req}]
  (if query-params
    (-> req (dissoc :query-params)
        (update-in [:query-string]
                   (fn [old-query-string new-query-string]
                     (if-not (empty? old-query-string)
                       (str old-query-string "&" new-query-string)
                       new-query-string))
                   (generate-query-string
                    query-params
                    (content-type-value content-type)
                    multi-param-style)))
    req))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [client]
  (fn
    ([req]
     (client (query-params-request req)))
    ([req respond raise]
     (client (query-params-request req) respond raise))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (util/base64-encode (util/utf8-bytes basic-auth)))))

(defn- basic-auth-request
  [req]
  (if-let [basic-auth (:basic-auth req)]
    (-> req (dissoc :basic-auth)
        (assoc-in [:headers "authorization"]
                  (basic-auth-value basic-auth)))
    req))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (basic-auth-request req)))
    ([req respond raise]
     (client (basic-auth-request req) respond raise))))

(defn- oauth-request
  [req]
  (if-let [oauth-token (:oauth-token req)]
    (-> req (dissoc :oauth-token)
        (assoc-in [:headers "authorization"]
                  (str "Bearer " oauth-token)))
    req))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [client]
  (fn
    ([req]
     (client (oauth-request req)))
    ([req respond raise]
     (client (oauth-request req) respond raise))))


(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn- user-info-request
  [req]
  (if-let [[user password] (parse-user-info (:user-info req))]
    (assoc req :basic-auth [user password])
    req))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [client]
  (fn
    ([req]
     (client (user-info-request req)))
    ([req respond raise]
     (client (user-info-request req) respond raise))))

(defn- method-request
  [req]
  (if-let [m (:method req)]
    (-> req (dissoc :method)
        (assoc :request-method m))
    req))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [client]
  (fn
    ([req]
     (client (method-request req)))
    ([req respond raise]
     (client (method-request req) respond raise))))

(defmulti coerce-form-params
  (fn [req] (keyword (content-type-value (:content-type req)))))

(defmethod coerce-form-params :application/edn
  [{:keys [form-params]}]
  (pr-str form-params))

(defn- coerce-transit-form-params [type {:keys [form-params transit-opts]}]
  (when-not transit-enabled?
    (throw (ex-info (format (str "Can't encode form params as "
                                 "\"application/transit+%s\". "
                                 "Transit dependency not loaded.")
                            (name type))
                    {:type :transit-not-loaded
                     :form-params form-params
                     :transit-opts transit-opts
                     :transit-type type})))
  (transit-encode form-params type transit-opts))

(defmethod coerce-form-params :application/transit+json [req]
  (coerce-transit-form-params :json req))

(defmethod coerce-form-params :application/transit+msgpack [req]
  (coerce-transit-form-params :msgpack req))

(defmethod coerce-form-params :application/json
  [{:keys [form-params json-opts]}]
  (when-not json-enabled?
    (throw (ex-info (str "Can't encode form params as \"application/json\". "
                         "Cheshire dependency not loaded.")
                    {:type :cheshire-not-loaded
                     :form-params form-params
                     :json-opts json-opts})))
  (json-encode form-params json-opts))

(defmethod coerce-form-params :default [{:keys [content-type
                                                multi-param-style
                                                form-params
                                                form-param-encoding]}]
  (if form-param-encoding
    (generate-query-string-with-encoding form-params
                                         form-param-encoding multi-param-style)
    (generate-query-string form-params
                           (content-type-value content-type)
                           multi-param-style)))

(defn- form-params-request
  [{:keys [form-params content-type request-method]
    :or {content-type :x-www-form-urlencoded}
    :as req}]
  (if (and form-params (#{:post :put :patch :delete} request-method))
    (-> req
        (dissoc :form-params)
        (assoc :content-type (content-type-value content-type)
               :body (coerce-form-params req)))
    req))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [client]
  (fn
    ([req]
     (client (form-params-request req)))
    ([req respnd raise]
     (client (form-params-request req) respnd raise))))

(defn- nest-params
  [request param-key]
  (if-let [params (request param-key)]
    (assoc request param-key (prewalk
                              #(if (and (vector? %) (map? (second %)))
                                 (let [[fk m] %]
                                   (reduce
                                    (fn [m [sk v]]
                                      (assoc m (str (name fk)
                                                    \[ (name sk) \]) v))
                                    {}
                                    m))
                                 %)
                              params))
    request))

(defn- nest-params-request
  [{:keys [content-type] :as req}]
  (if (or (nil? content-type)
          (= content-type :x-www-form-urlencoded))
    (reduce
     nest-params
     req
     [:query-params :form-params])
    req))

(defn wrap-nested-params
  "Middleware wrapping nested parameters for query strings."
  [client]
  (fn
    ([req]
     (client (nest-params-request req)))
    ([req respond raise]
     (client (nest-params-request req) respond raise))))

(defn- url-request
  [req]
  (if-let [url (:url req)]
    (-> req (dissoc :url) (merge (parse-url url)))
    req))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [client]
  (fn
    ([req]
     (client (url-request req)))
    ([req respond raise]
     (client (url-request req) respond raise))))

(defn wrap-unknown-host
  "Middleware ignoring unknown hosts when the :ignore-unknown-host? option
  is set."
  [client]
  (fn
    ([req]
     (try
       (client req)
       (catch Exception e
         (if (= (type (root-cause e)) UnknownHostException)
           (when-not (opt req :ignore-unknown-host)
             (throw (root-cause e)))
           (throw (root-cause e))))))
    ([req respond raise]
     (client (assoc req :unknown-host-respond respond) respond raise))))

(defn wrap-lower-case-headers
  "Middleware lowercasing all headers, as per RFC (case-insensitive) and
  Ring spec."
  [client]
  (let [lower-case-headers
        #(if-let [headers (:headers %1)]
           (assoc %1 :headers (util/lower-case-keys headers))
           %1)]
    (fn
      ([req]
       (-> (client (lower-case-headers req))
           (lower-case-headers)))
      ([req respond raise]
       (client (lower-case-headers req)
               #(respond (lower-case-headers %))
               raise)))))

(defn- request-timing-response
  [resp start]
  (assoc resp :request-time (- (System/currentTimeMillis) start)))

(defn wrap-request-timing
  "Middleware that times the request, putting the total time (in milliseconds)
  of the request into the :request-time key in the response."
  [client]
  (fn
    ([req]
     (let [start (System/currentTimeMillis)
           resp (client req)]
       (request-timing-response resp start)))
    ([req respond raise]
     (let [start (System/currentTimeMillis)]
       (client req
               #(respond (request-timing-response % start))
               raise)))))

(defn- async-pooling-request
  [req release]
  (let [handler (:oncancel req)]
    (assoc req :oncancel
           (fn []
             (release)
             (when handler
               (handler))))))

(def ^:dynamic *pooling-info*
  "The pooling-info used in pooling function"
  nil)

(defn wrap-async-pooling
  "Middleware that handle pooling async request. It use the value of key
  :pooling-info, the value is a map contains three key-value

  :conn-mgr - connection manager used by the HttpAsyncClient
  :allocate - a function with no args, will be invoked when connection allocate
  :release  - a function with no args, will be invoked when connection finished

  You must shutdown the conn-mgr when you don't need it."
  [client]
  (fn
    ([req]
     (client req))
    ([req respond raise]
     (if-let [pooling-info (or *pooling-info* (:pooling-info req))]
       (let [{:keys [allocate release conn-mgr]} pooling-info]
         (binding [conn/*async-connection-manager* conn-mgr]
           (allocate)
           (try
             (client (async-pooling-request req release)
                     (fn [resp]
                       (respond (assoc resp :pooling-info pooling-info))
                       (release))
                     (fn [ex]
                       (release)
                       (raise ex)))
             (catch Throwable ex
               (release)
               (throw ex)))))
       (client req respond raise)))))

(def default-middleware
  "The default list of middleware clj-http uses for wrapping requests."
  [wrap-request-timing
   wrap-async-pooling
   wrap-header-map
   wrap-query-params
   wrap-basic-auth
   wrap-oauth
   wrap-user-info
   wrap-url
   wrap-decompression
   wrap-input-coercion
   ;; put this before output-coercion, so additional charset
   ;; headers can be used if desired
   wrap-additional-header-parsing
   wrap-output-coercion
   wrap-exceptions
   wrap-accept
   wrap-accept-encoding
   wrap-content-type
   wrap-form-params
   wrap-nested-params
   wrap-method
   wrap-cookies
   wrap-links
   wrap-unknown-host])

(def ^:dynamic
  *current-middleware*
  "Available at any time to retrieve the middleware being used.
  Automatically bound when `with-middleware` is used."
  default-middleware)

(defn wrap-request
  "Returns a batteries-included HTTP request function corresponding to the given
  core client. See default-middleware for the middleware wrappers that are used
  by default"
  [request]
  (reduce (fn wrap-request* [request middleware]
            (middleware request))
          request
          default-middleware))

(def ^:dynamic request
  "Executes the HTTP request corresponding to the given map and returns
  the response map for corresponding to the resulting HTTP response.

  In addition to the standard Ring request keys, the following keys are also
  recognized:
  * :url
  * :method
  * :query-params
  * :basic-auth
  * :content-type
  * :accept
  * :accept-encoding
  * :as

  The following keys make an async HTTP request, like ring's CPS handler.
  * :async?
  * :respond
  * :raise

  The following additional behaviors are also automatically enabled:
  * Exceptions are thrown for status codes other than 200-207, 300-303, or 307
  * Gzip and deflate responses are accepted and decompressed
  * Input and output bodies are coerced as required and indicated by the :as
  option."
  (wrap-request #'core/request))

;; Inline function to throw a slightly more readable exception when
;; the URL is nil
(definline check-url! [url]
  `(when (nil? ~url)
     (throw (IllegalArgumentException. "Host URL cannot be nil"))))

(defn- request*
  [{:keys [async?] :as req} [respond raise]]
  (if async?
    (if (some nil? [respond raise])
      (throw (IllegalArgumentException.
              "If :async? is true, you must pass respond and raise"))
      (request (dissoc req :respond :raise) respond raise))
    (request req)))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :get :url url}) r))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :head :url url}) r))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :post :url url}) r))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :put :url url}) r))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :delete :url url}) r))

(defn options
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :options :url url}) r))

(defn copy
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :copy :url url}) r))

(defn move
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :move :url url}) r))

(defn patch
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req & r]]
  (check-url! url)
  (request* (merge req {:method :patch :url url}) r))

(defmacro with-middleware
  "Perform the body of the macro with a custom middleware list.

  It is highly recommended to at least include:
  clj-http.client/wrap-url
  clj-http.client/wrap-method

  Unless you really know what you are doing."
  [middleware & body]
  `(let [m# ~middleware]
     (binding [*current-middleware* m#
               clj-http.client/request (reduce #(%2 %1)
                                               clj-http.core/request
                                               m#)]
       ~@body)))

(defmacro with-additional-middleware
  "Perform the body of the macro with a list of additional middleware.

  The given `middleware-seq' is concatenated to the beginning of the
  `*current-middleware*' sequence."
  [middleware-seq & body]
  `(with-middleware (concat ~middleware-seq *current-middleware*)
     ~@body))

(defmacro with-connection-pool
  "Macro to execute the body using a connection manager. Creates a
  PoolingHttpClientConnectionManager to use for all requests within the
  body of the expression. An option map is allowed to set options for the
  connection manager.

  The following options are supported:

  :timeout - Time that connections are left open before automatically closing
  default: 5
  :threads - Maximum number of threads that will be used for connecting
  default: 4
  :default-per-route - Maximum number of simultaneous connections per host
  default: 2
  :insecure? - Boolean flag to specify allowing insecure HTTPS connections
  default: false

  :keystore - keystore file path or KeyStore instance to be used for
  connection manager
  :keystore-pass - keystore password
  :trust-store - trust store file path or KeyStore instance to be used for
  connection manager
  :trust-store-pass - trust store password

  Note that :insecure? and :keystore/:trust-store options are mutually exclusive

  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [opts & body]
  ;; I'm leaving the connection bindable for now because in the
  ;; future I'm toying with the idea of managing the connection
  ;; manager yourself and passing it into the request
  `(let [cm# (conn/make-reusable-conn-manager ~opts)]
     (binding [conn/*connection-manager* cm#]
       (try
         ~@body
         (finally
           (.shutdown
            ^PoolingHttpClientConnectionManager
            conn/*connection-manager*))))))

(defn reuse-pool
  "A helper function takes a request options map and a response map respond
  from a pooled async request, the returned options map will be set to reuse
  the connection pool which used by the former request"
  [options response]
  (if-let [info (:pooling-info response)]
    (assoc options :pooling-info info)
    options))

(defmacro with-async-connection-pool
  [opts & body]
  `(let [cm# (conn/make-reuseable-async-conn-manager ~opts)
         count# (atom 0)
         all-requested# (atom false)
         p-info# {:conn-mgr cm#
                  :allocate (fn [] (swap! count# inc))
                  :release  (fn [] (swap! count# dec))}
         ;; A http client hold the conn-mgr and start the io-reactor.
         ;; In this context any other client's ConnectionManagerShared will be
         ;; set to true.
         holder-client# (-> (HttpAsyncClients/custom)
                            (.setConnectionManager cm#)
                            (.build)
                            (.start))]
     (add-watch count# :close-conn-mgr
                (fn [key# identity# old# new#]
                  (if (and (not= old# new#) (<= new# 0) @all-requested#)
                    (.shutdown
                     ^PoolingNHttpClientConnectionManager
                     cm#))))
     (binding [*pooling-info* p-info#]
       (try
         ~@body
         (finally
           (swap! all-requested# not)
           (if (= 0 @count#)
             (.shutdown
              ^PoolingNHttpClientConnectionManager
              cm#)))))))
