(ns clj-http.client
  "Batteries-included HTTP client."
  (:import (java.net URL))
  (:require [clojure.contrib.string :as str])
  (:require [clj-http.core :as core])
  (:require [clj-http.util :as util])
  (:refer-clojure :exclude (get)))

(def unexceptional-status?
  #{200 201 202 203 204 205 206 207 300 301 302 303 307})

(defn wrap-exceptions [client]
  (fn [req]
    (let [{:keys [status] :as resp} (client req)]
      (if (unexceptional-status? status)
        resp
        (throw (Exception. (str status)))))))


(defn wrap-body-as [client]
  (fn [{:keys [body-as] :as req}]
    (let [{:keys [body] :as resp} (client req)]
      (cond
        (or (nil? body) (nil? body-as))
          resp
        (= :string body-as)
          (assoc resp :body (String. body "UTF-8"))))))


(defn wrap-body-coerce [client]
  (fn [{:keys [body] :as req}]
    (if (string? body)
      (client (-> req (assoc :body (.toString body "UTF-8")
                             :character-encoding "UTF-8")))
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
      (client (-> req (dissoc :accept)
                      (assoc-in [:headers "Accept-Encoding"]
                        (accept-encoding-value accept-encoding))))
      (client req))))


(defn generate-query-string [params]
  (str/join "&"
    (map (fn [[k v]] (str (util/url-encode (name k)) "="
                          (util/url-encode (str v))))
         params)))

(defn wrap-query-params [client]
  (fn [req]
    (if-let [qp (:query-params client)]
      (let [qs (generate-query-string qp)]
        (client (-> req (dissoc :query-params)
                        (assoc :query-string qs))))
      (client req))))


(defn basic-auth-value [user password]
  (util/base64-encode (-> (str "Basic " user ":" password)
                        (.getBytes "UTF-8"))))

(defn wrap-basic-auth [client]
  (fn [req]
    (if-let [[user password] (:basic-auth req)]
      (client (-> req (dissoc :basic-auth)
                      (assoc-in [:headers "Authorization"]
                        (basic-auth-value user password))))
      (client req))))


(defn wrap-method [client]
  (fn [req]
    (if-let [m (:method req)]
      (client (-> req (dissoc :method)
                      (assoc :request-method m)))
      (client req))))


(defn if-pos [v]
  (if (and v (pos? v)) v))

(defn wrap-url [client]
  (fn [req]
    (if-let [url (:url req)]
      (let [url-parsed (URL. url)]
        (client (-> req (assoc :scheme (.getProtocol url-parsed)
                               :server-name (.getHost url-parsed)
                               :server-port (if-pos (.getPort url-parsed))
                               :uri  (.getPath url-parsed)
                               :query-string (.getQuery url-parsed)))))
      (client req))))

(def #^{:doc
  "Executes the HTTP request corresponding to the given map and returns the
   response map for corresponding to the resulting HTTP response.

   In addition to the standard Ring request keys, the following keys are also
   recognized:
   * :url
   * :method
   * :query-params
   * :basic-auth
   * :content-type
   * :accept
   * :accept-encoding
   * :body-as

  Note that where Ring uses InputStreams for the request and response bodies,
  the clj-http uses ByteArrays for the bodies.

  The following additional behaviors over also automatically enabled:
   * string request bodies are converted to byte arrays
   * exceptions are thrown for status codes other than 200-207, 300-303, or 307"}
  request
  (-> #'core/request
    (wrap-exceptions)
    (wrap-body-as)
    (wrap-body-coerce)
    (wrap-query-params)
    (wrap-basic-auth)
    (wrap-accept)
    (wrap-accept-encoding)
    (wrap-content-type)
    (wrap-method)
    (wrap-url)))

(defn get
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :get :url url})))

(defn head
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :head :url url})))

(defn post
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :put :url url})))

(defn put
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :post :url url})))

(defn delete
  "Like #'request, but sets the :method and :url as appropriate."
  [url & [req]]
  (request (merge req {:method :delete :url url})))
