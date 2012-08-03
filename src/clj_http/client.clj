(ns clj-http.client
  "Batteries-included HTTP client."
  (:use [clj-http.cookies :only [wrap-cookies]]
        [clj-http.links :only [wrap-links]]
        [slingshot.slingshot :only [throw+]]
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

(defn json-encode
  "Resolve and apply cheshire's json encoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "encode")) args))

(defn json-decode
  "Resolve and apply cheshire's json decoding dynamically."
  [& args]
  {:pre [json-enabled?]}
  (apply (ns-resolve (symbol "cheshire.core") (symbol "decode")) args))

(defn update [m k f & args]
  (assoc m k (apply f (m k) args)))

(defn when-pos [v]
  (when (and v (pos? v)) v))

(defn parse-url [url]
  (let [url-parsed (URL. url)]
    {:scheme (keyword (.getProtocol url-parsed))
     :server-name (.getHost url-parsed)
     :server-port (when-pos (.getPort url-parsed))
     :uri (.getPath url-parsed)
     :user-info (.getUserInfo url-parsed)
     :query-string (.getQuery url-parsed)}))

(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

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

(defn wrap-exceptions [client]
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

(defn wrap-redirects [client]
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

(defn wrap-decompression [client]
  (fn [req]
    (if (get-in req [:headers "accept-encoding"])
      (client req)
      (let [req-c (update req :headers assoc "accept-encoding" "gzip, deflate")
            resp-c (client req-c)]
        (case (get-in resp-c [:headers "content-encoding"])
          "gzip" (update resp-c :body util/gunzip)
          "deflate" (update resp-c :body util/inflate)
          resp-c)))))


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

(defn wrap-output-coercion [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (if body
        (coerce-response-body as resp)
        resp))))

(defn wrap-input-coercion [client]
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
       (instance? InputStream body)
       (do
         (when-not (and length (pos? length))
           (throw
            (Exception. ":length key is required for InputStream bodies")))
         (client (-> req (assoc :body (InputStreamEntity. body length)))))

       (instance? (Class/forName "[B") body)
       (client (-> req (assoc :body (ByteArrayEntity. body))))

       :else
       (client req))
      (client req))))

(defn content-type-value [type]
  (if (keyword? type)
    (str "application/" (name type))
    type))

(defn wrap-content-type [client]
  (fn [{:keys [content-type] :as req}]
    (if content-type
      (client (-> req (assoc :content-type
                        (content-type-value content-type))))
      (client req))))

(defn wrap-accept [client]
  (fn [{:keys [accept] :as req}]
    (if accept
      (client (-> req (dissoc :accept)
                  (assoc-in [:headers "accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding [client]
  (fn [{:keys [accept-encoding] :as req}]
    (if accept-encoding
      (client (-> req (dissoc :accept-encoding)
                  (assoc-in [:headers "accept-encoding"]
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

(defn wrap-query-params [client]
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

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [basic-auth (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                  (assoc-in [:headers "authorization"]
                            (basic-auth-value basic-auth))))
      (client req))))

(defn wrap-oauth [client]
  (fn [req]
    (if-let [oauth-token (:oauth-token req)]
      (client (-> req (dissoc :oauth-token)
                  (assoc-in [:headers "authorization"]
                            (str "Bearer " oauth-token))))
      (client req))))


(defn parse-user-info [user-info]
  (when user-info
    (str/split user-info #":")))

(defn wrap-user-info [client]
  (fn [req]
    (if-let [[user password] (parse-user-info (:user-info req))]
      (client (assoc req :basic-auth [user password]))
      (client req))))

(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                  (assoc :request-method m)))
      (client req))))

(defn wrap-form-params [client]
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
  [client]
  (fn [{:keys [query-params form-params content-type] :as req}]
    (if (= :json content-type)
      (client req)
      (client (reduce
               nest-params
               req
               [:query-params :form-params])))))

(defn wrap-url [client]
  (fn [req]
    (if-let [url (:url req)]
      (client (-> req (dissoc :url) (merge (parse-url url))))
      (client req))))

(defn wrap-unknown-host [client]
  (fn [{:keys [ignore-unknown-host?] :as req}]
    (try
      (client req)
      (catch UnknownHostException e
        (when-not ignore-unknown-host?
          (throw e))))))

(defn wrap-headers [client]
  (let [lower-case-headers
        #(assoc %1 :headers (util/lower-case-keys (:headers %1)))]
    (fn [req]
      (-> (client (lower-case-headers req))
          (lower-case-headers)))))

(defn wrap-request
  "Returns a battaries-included HTTP request function coresponding to the given
   core client. See client/client."
  [request]
  (-> request
      wrap-headers
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

(def #^{:doc
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
