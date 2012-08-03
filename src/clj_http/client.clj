(ns clj-http.client
  "Batteries-included HTTP client."
  (:use [clj-http.cookies :only [wrap-cookies]]
        [clj-http.links :only [wrap-links]]
        [slingshot.slingshot :only [throw+]]
        [clojure.stacktrace :only [root-cause]]
        [clojure.walk :only [prewalk]])
  (:require [clojure.string :as str]
            [clj-http.conn-mgr :as conn]
            [clj-http.core :as core]
            [clj-http.util :as util])
  (:import (java.io InputStream File)
           (java.net URL UnknownHostException)
           (org.apache.http.entity ByteArrayEntity InputStreamEntity
                                   FileEntity StringEntity))
  (:refer-clojure :exclude (get)))

(def json-enabled?
  (try
    (require 'cheshire.core)
    true
    (catch Throwable _
      false)))

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

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn parse-url
  "Parse a URL string into a map of interesting parts."
  [url]
  (let [url-parsed (URL. url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :user-info (.getUserInfo url-parsed)
     :query-string (.getQuery url-parsed)}))

;; Statuses for which clj-http will not throw an exception
(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

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

(defn wrap-exceptions
  "Middleware that throws a slingshot exception if the response is not a
  regular response. If :throw-entire-message? is set to true, the entire
  response is used as the message, instead of just the status number."
  [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (unexceptional-status? status))
        resp
        (if (:throw-entire-message? req)
          (throw+ resp "clj-http: status %d %s" (:status %) resp)
          (throw+ resp "clj-http: status %s" (:status %)))))))

(declare wrap-redirects)

(defn follow-redirect
  [client {:keys [url] :as req} {:keys [trace-redirects] :as resp}]
  (let [raw-redirect (get-in resp [:headers "location"])
        redirect (str (URL. (URL. url) raw-redirect))]
    ((wrap-redirects client) (assoc req
                               :url redirect
                               :trace-redirects trace-redirects))))

(defn wrap-redirects
  "Middleware that follows redirects in the response. A slingshot exception is
  thrown if too many redirects occur. Options

  :follow-redirects - default:true, whether to follow redirects
  :max-redirects - default:20, maximum number of redirects to follow
  :force-redirects - default:false, force redirecting methods to GET requests

  In the response:

  :redirects-count - number of redirects
  :trace-redirects - vector of sites the request was redirected from"
  [client]
  (fn [{:keys [request-method follow-redirects max-redirects
               redirects-count trace-redirects url force-redirects
               throw-exceptions]
        :or {redirects-count 1 trace-redirects []
             ;; max-redirects default taken from Firefox
             max-redirects 20}
        :as req}]
    (let [{:keys [status] :as resp} (client req)
          resp-r (assoc resp :trace-redirects (conj trace-redirects url))]
      (cond
       (= false follow-redirects)
       resp
       (not (redirect? resp-r))
       resp-r
       (and max-redirects (> redirects-count max-redirects))
       (if throw-exceptions
         (throw+ resp-r "Too many redirects: %s" redirects-count)
         resp-r)
       (= 303 status)
       (follow-redirect client (assoc req :request-method :get
                                      :redirects-count (inc redirects-count))
                        resp-r)
       (#{301 302 307} status)
       (cond
        (#{:get :head} request-method)
        (follow-redirect client (assoc req :redirects-count
                                       (inc redirects-count)) resp-r)
        force-redirects
        (follow-redirect client (assoc req
                                  :request-method :get
                                  :redirects-count (inc redirects-count))
                         resp-r)
        :else
        resp-r)
       :else
       resp-r))))

;; Multimethods for Content-Encoding dispatch automatically
;; decompressing response bodies
(defmulti decompress-body
  (fn [resp] (or (get-in resp [:headers "Content-Encoding"])
                 (get-in resp [:headers "content-encoding"]))))

(defmethod decompress-body "gzip"
  [resp]
  (update resp :body util/gunzip))

(defmethod decompress-body "deflate"
  [resp]
  (update resp :body util/inflate))

(defmethod decompress-body :default [resp] resp)

(defn wrap-decompression
  "Middleware handling automatic decompression of responses from web servers. If
  :decompress-body is set to false, does not automatically set `Accept-Encoding`
  header or decompress body."
  [client]
  (fn [req]
    (if (= false (:decompress-body req))
      (client req)
      (let [req-c (update req :headers assoc "Accept-Encoding" "gzip, deflate")
            resp-c (client req-c)]
        (decompress-body resp-c)))))

;; Multimethods for coercing body type to the :as key
(defmulti coerce-response-body (fn [as _] as))

(defmethod coerce-response-body :byte-array [_ resp] resp)

(defmethod coerce-response-body :stream [_ resp] resp)

(defmethod coerce-response-body :json [_ {:keys [body status] :as resp}]
  (if (and json-enabled? (unexceptional-status? status))
    (assoc resp :body (json-decode (String. #^"[B" body "UTF-8") true))
    (assoc resp :body (String. #^"[B" body "UTF-8"))))

(defmethod coerce-response-body
  :json-string-keys
  [_ {:keys [body status] :as resp}]
  (if (and json-enabled? (unexceptional-status? status))
    (assoc resp :body (json-decode (String. #^"[B" body "UTF-8")))
    (assoc resp :body (String. #^"[B" body "UTF-8"))))

(defmethod coerce-response-body :clojure [_ {:keys [status body] :as resp}]
  (assoc resp :body (read-string (String. #^"[B" body "UTF-8"))))

(defmethod coerce-response-body :auto [_ {:keys [status body] :as resp}]
  (assoc resp
    :body
    (let [typestring (get-in resp [:headers "content-type"])]
      (cond
       (.startsWith (str typestring) "text/")
       (if-let [charset (second (re-find #"charset=(.*)"
                                         (str typestring)))]
         (String. #^"[B" body ^String charset)
         (String. #^"[B" body "UTF-8"))

       (.startsWith (str typestring) "application/clojure")
       (if-let [charset (second (re-find #"charset=(.*)"
                                         (str typestring)))]
         (read-string (String. #^"[B" body ^String charset))
         (read-string (String. #^"[B" body "UTF-8")))

       (and (.startsWith (str typestring) "application/json")
            json-enabled?
            (unexceptional-status? status))
       (if-let [charset (second (re-find #"charset=(.*)"
                                         (str typestring)))]
         (json-decode (String. #^"[B" body ^String charset) true)
         (json-decode (String. #^"[B" body "UTF-8") true))

       :else
       (String. #^"[B" body "UTF-8")))))

(defmethod coerce-response-body :default [as {:keys [status body] :as resp}]
  (cond
   (string? as)  (assoc resp :body (String. #^"[B" body ^String as))
   :else (assoc resp :body (String. #^"[B" body "UTF-8"))))

(defn wrap-output-coercion
  "Middleware converting a response body from a byte-array to a different
  object. Defaults to a String if no :as key is specified, the
  `coerce-response-body` multimethod may be extended to add
  additional coercions."
  [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (if body
        (coerce-response-body as resp)
        resp))))

(defn wrap-input-coercion
  "Middleware coercing the :body of a request from a number of formats into an
  Apache Entity. Currently supports Strings, Files, InputStreams
  and byte-arrays."
  [client]
  (fn [{:keys [body body-encoding length] :as req}]
    (if body
      (cond
       (string? body)
       (client (-> req (assoc :body (StringEntity. body (or body-encoding
                                                            "UTF-8"))
                              :character-encoding (or body-encoding
                                                      "UTF-8"))))
       (instance? File body)
       (client (-> req (assoc :body (FileEntity. body (or body-encoding
                                                          "UTF-8")))))

       ;; A length of -1 instructs HttpClient to use chunked encoding.
       (instance? InputStream body)
       (client (-> req (assoc :body
                         (InputStreamEntity. body (or (:length req) -1)))))

       (instance? (Class/forName "[B") body)
       (client (-> req (assoc :body (ByteArrayEntity. body))))

       :else
       (client req))
      (client req))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type
  "Middleware converting a `:content-type <keyword>` option to the formal
  application/<name> format and adding it as a header."
  [client]
  (fn [{:keys [content-type character-encoding] :as req}]
    (if content-type
      (let [ctv (content-type-value content-type)
            ct (if character-encoding
                 (str ctv "; charset=" character-encoding)
                 ctv)]
        (client (update-in req [:headers] assoc "Content-Type" ct)))
      (client req))))

(defn wrap-accept
  "Middleware converting the :accept key in a request to application/<type>"
  [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                  (assoc-in [:headers "Accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding
  "Middleware converting the :accept-encoding option to an acceptable
  Accept-Encoding header in the request."
  [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                  (assoc-in [:headers "Accept-Encoding"]
                            (accept-encoding-value accept-encoding))))
      (client req))))

(defn generate-query-string [params]
  (str/join "&"
            (mapcat (fn [[k v]]
                      (if (sequential? v)
                        (map #(str (util/url-encode (name %1))
                                   "="
                                   (util/url-encode (str %2)))
                             (repeat k) v)
                        [(str (util/url-encode (name k))
                              "="
                              (util/url-encode (str v)))]))
                    params)))

(defn wrap-query-params
  "Middleware converting the :query-params option to a querystring on
  the request."
  [client]
  (fn [{:keys [query-params] :as req}]
    (if query-params
      (client (-> req (dissoc :query-params)
                  (assoc :query-string
                    (generate-query-string query-params))))
      (client req))))

(defn basic-auth-value [basic-auth]
  (let [basic-auth (if (string? basic-auth)
                     basic-auth
                     (str (first basic-auth) ":" (second basic-auth)))]
    (str "Basic " (util/base64-encode (util/utf8-bytes basic-auth)))))

(defn wrap-basic-auth
  "Middleware converting the :basic-auth option into an Authorization header."
  [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                  (assoc-in [:headers "Authorization"]
                            (basic-auth-value basic-auth))))
      (client req))))

(defn wrap-oauth
  "Middleware converting the :oauth-token option into an Authorization header."
  [client]
  (fn [req]
    (if-let [oauth-token (:oauth-token req)]
      (client (-> req (dissoc :oauth-token)
                  (assoc-in [:headers "Authorization"]
                            (str "Bearer " oauth-token))))
      (client req))))


(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info
  "Middleware converting the :user-info option into a :basic-auth option"
  [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method
  "Middleware converting the :method option into the :request-method option"
  [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                  (assoc :request-method m)))
      (client req))))

(defn wrap-form-params
  "Middleware wrapping the submission or form parameters."
  [client]
  (fn [{:keys [form-params content-type request-method]
        :or {content-type :x-www-form-urlencoded}
        :as req}]
    (if (and form-params (#{:post :put} request-method))
      (client (-> req
                  (dissoc :form-params)
                  (assoc :content-type (content-type-value content-type)
                         :body (if (and (= content-type :json) json-enabled?)
                                 (json-encode form-params)
                                 (generate-query-string form-params)))))
      (client req))))

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

(defn wrap-nested-params
  "Middleware wrapping nested parameters for query strings."
  [client]
  (fn [{:keys [query-params form-params content-type] :as req}]
    (if (= :json content-type)
      (client req)
      (client (reduce
               nest-params
               req
               [:query-params :form-params])))))

(defn wrap-url
  "Middleware wrapping request URL parsing."
  [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(defn wrap-unknown-host
  "Middleware ignoring unknown hosts when the :ignore-unknown-host? option
  is set."
  [client]
  (fn [{:keys [ignore-unknown-host?] :as req}]
    (try
      (client req)
      (catch Exception e
        (if (= (type (root-cause e)) java.net.UnknownHostException)
          (when-not ignore-unknown-host?
            (throw (root-cause e)))
          (throw (root-cause e)))))))

(defn wrap-request
  "Returns a battaries-included HTTP request function coresponding to the given
   core client. See client/client."
  [request]
  (-> request
      wrap-query-params
      wrap-basic-auth
      wrap-oauth
      wrap-user-info
      wrap-url
      wrap-redirects
      wrap-decompression
      wrap-input-coercion
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
      wrap-unknown-host))

(def ^{:doc
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

  The following additional behaviors over also automatically enabled:
   * Exceptions are thrown for status codes other than 200-207, 300-303, or 307
   * Gzip and deflate responses are accepted and decompressed
   * Input and output bodies are coerced as required and indicated by the :as
     option."}
  request
  (wrap-request #'core/request))

;; Inline function to throw a slightly more readable exception when
;; the URL is nil
(definline check-url! [url]
  `(when (nil? ~url)
     (throw (IllegalArgumentException. "Host URL cannot be nil"))))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :get :url url})))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :head :url url})))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :post :url url})))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :put :url url})))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :delete :url url})))

(defn options
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :options :url url})))

(defn copy
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :copy :url url})))

(defn move
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :move :url url})))

(defn patch
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (check-url! url)
  (request (merge req {:method :patch :url url})))

(defmacro with-connection-pool
  "Macro to execute the body using a connection manager. Creates a
  ThreadSafeClientConnectionManager to use for all requests within the body of
  the expression. An option map is allowed to set options for the connection
  manager.

  The following options are supported:

  :timeout - Time that connections are left open before automatically closing
    default: 5
  :threads - Maximum number of threads that will be used for connecting
    default: 4
  :insecure? - Boolean flag to specify allowing insecure HTTPS connections
    default: false

  :keystore - keystore file to be used for connection manager
  :keystore-pass - keystore password
  :trust-store - trust store file to be used for connection manager
  :trust-store-pass - trust store password

  Note that :insecure? and :keystore/:trust-store options are mutually exclusive

  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [opts & body]
  `(let [timeout# (or (:timeout ~opts) 5)
         threads# (or (:threads ~opts) 4)
         insecure?# (:insecure? ~opts)
         leftovers# (dissoc ~opts :timeout :threads :insecure?)]
     ;; I'm leaving the connection bindable for now because in the
     ;; future I'm toying with the idea of managing the connection
     ;; manager yourself and passing it into the request
     (binding [conn/*connection-manager*
               (doto (conn/make-reusable-conn-manager
                      (merge {:timeout timeout#
                              :insecure? insecure?#}
                             leftovers#))
                 (.setMaxTotal threads#))]
       (try
         ~@body
         (finally
           (.shutdown
            ^org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
            conn/*connection-manager*))))))
