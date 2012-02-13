(ns clj-http.client
  "Batteries-included HTTP client."
  (:use [clj-http.cookies :only (wrap-cookies)]
        [slingshot.slingshot :only [throw+]])
  (:require [clojure.string :as str]
            [cheshire.core :as json]
            [clj-http.core :as core]
            [clj-http.util :as util])
  (:import (java.io InputStream File)
           (java.net URL UnknownHostException)
           (org.apache.http.entity ByteArrayEntity InputStreamEntity
                                   FileEntity StringEntity))
  (:refer-clojure :exclude (get)))

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

(defn wrap-exceptions [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (or (not (clojure.core/get req :throw-exceptions true))
              (unexceptional-status? status))
        resp
        (throw+ resp "clj-http: status %s" (:status %))))))

(declare wrap-redirects)

(defn follow-redirect
  [client req {:keys [trace-redirects] :as resp}]
  (let [url (get-in resp [:headers "location"])]
    ((wrap-redirects client) (assoc req
                               :url url
                               :trace-redirects trace-redirects))))

(defn wrap-redirects [client]
  (fn [{:keys [request-method follow-redirects max-redirects
               redirects-count trace-redirects url]
        :or {redirects-count 1 trace-redirects []}
        :as req}]
    (let [{:keys [status] :as resp} (client req)
          resp-r (assoc resp :trace-redirects (conj trace-redirects url))]
      (cond
       (= false follow-redirects)
       resp
       (and max-redirects (> redirects-count max-redirects))
       (if (:throw-exceptions req)
         (throw+ resp-r "Too many redirects: %s" redirects-count)
         resp-r)
       (and (#{301 302 307} status) (#{:get :head} request-method))
       (follow-redirect client (assoc req :redirects-count
                                      (inc redirects-count)) resp-r)
       (and (= 303 status) (= :head request-method))
       (follow-redirect client (assoc req :request-method :get
                                      :redirects-count (inc redirects-count))
                        resp-r)
       :else
       resp-r))))

(defn wrap-decompression [client]
  (fn [req]
    (if (get-in req [:headers "Accept-Encoding"])
      (client req)
      (let [req-c (update req :headers assoc "Accept-Encoding" "gzip, deflate")
            resp-c (client req-c)]
        (case (or (get-in resp-c [:headers "Content-Encoding"])
                  (get-in resp-c [:headers "content-encoding"]))
          "gzip" (update resp-c :body util/gunzip)
          "deflate" (update resp-c :body util/inflate)
          resp-c)))))

(defn wrap-output-coercion [client]
  (fn [{:keys [as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (if body
        (cond
         (keyword? as)
         (condp = as
           ;; Don't do anything when it's a byte-array
           :byte-array resp

           ;; Don't do anything when it's a stream
           :stream resp

           ;; Convert to json from UTF-8 string
           :json
           (assoc resp :body (json/decode (String. #^"[B" body "UTF-8") true))

           ;; Convert to json with strings as keys
           :json-string-keys
           (assoc resp :body (json/decode (String. #^"[B" body "UTF-8")))

           ;; Attempt to automatically coerce the body, returning a
           ;; string if no coercions are found
           :auto
           (assoc resp
             :body
             (let [typestring (get-in resp [:headers "content-type"])]
               (cond
                (.startsWith (str typestring) "text/")
                (if-let [charset (second (re-find #"charset=(.*)"
                                                  (str typestring)))]
                  (String. #^"[B" body ^String charset)
                  (String. #^"[B" body "UTF-8"))

                (.startsWith (str typestring) "application/json")
                (if-let [charset (second (re-find #"charset=(.*)"
                                                  (str typestring)))]
                  (json/decode (String. #^"[B" body ^String charset) true)
                  (json/decode (String. #^"[B" body "UTF-8") true))

                :else
                (String. #^"[B" body "UTF-8"))))

           ;; No :as matches found
           (assoc resp :body (String. #^"[B" body "UTF-8")))

         ;; Try the charset given if a string is specified
         (string? as)
         (assoc resp :body (String. #^"[B" body ^String as))
         ;; Return a regular UTF-8 string body
         :else
         (assoc resp :body (String. #^"[B" body "UTF-8")))
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
                  (assoc-in [:headers "Accept"]
                            (content-type-value accept))))
      (client req))))

(defn accept-encoding-value [accept-encoding]
  (str/join ", " (map name accept-encoding)))

(defn wrap-accept-encoding [client]
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
                  (assoc-in [:headers "Authorization"]
                            (basic-auth-value basic-auth))))
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
  (fn [{:keys [form-params request-method] :as req}]
    (if (and form-params (= :post request-method))
      (client (-> req
                  (dissoc :form-params)
                  (assoc :content-type (content-type-value
                                        :x-www-form-urlencoded)
                         :body (generate-query-string form-params))))
      (client req))))

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
        (if ignore-unknown-host?
          nil
          (throw e))))))

(defn wrap-request
  "Returns a battaries-included HTTP request function coresponding to the given
   core client. See client/client."
  [request]
  (-> request
      wrap-query-params
      wrap-user-info
      wrap-url
      wrap-redirects
      wrap-decompression
      wrap-input-coercion
      wrap-output-coercion
      wrap-exceptions
      wrap-basic-auth
      wrap-accept
      wrap-accept-encoding
      wrap-content-type
      wrap-form-params
      wrap-method
      wrap-cookies
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

(defmacro ^{ :private true } not-nil?
  [x]
  `(not (nil? ~x)))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  { :pre [(not-nil? url)] }
  (request (merge req {:method :get :url url})))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  { :pre [(not-nil? url)] }
  (request (merge req {:method :head :url url})))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  { :pre [(not-nil? url)] }
  (request (merge req {:method :post :url url})))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  { :pre [(not-nil? url)] }
  (request (merge req {:method :put :url url})))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  { :pre [(not-nil? url)] }
  (request (merge req {:method :delete :url url})))

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

  If the value 'nil' is specified or the value is not set, the default value
  will be used."
  [opts & body]
  `(let [timeout# (or (:timeout ~opts) 5)
         threads# (or (:threads ~opts) 4)
         insecure?# (:insecure? ~opts)]
     ;; I'm leaving the connection bindable for now because in the
     ;; future I'm toying with the idea of managing the connection
     ;; manager yourself and passing it into the request
     (binding [core/*connection-manager*
               (doto (core/make-reusable-conn-manager timeout# insecure?#)
                 (.setMaxTotal threads#))]
       (try
         ~@body
         (finally
          (.shutdown
           ^org.apache.http.impl.conn.tsccm.ThreadSafeClientConnManager
           core/*connection-manager*))))))
