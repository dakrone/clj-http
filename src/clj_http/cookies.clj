(ns clj-http.cookies
  "Namespace dealing with HTTP cookies"
  (:require [clj-http.util :refer [opt]]
            [clojure.string :refer [blank? join lower-case]])
  (:import (org.apache.http.client.params ClientPNames CookiePolicy)
           (org.apache.http.cookie ClientCookie CookieOrigin
                                   CookieSpec CookieSpecProvider)
           (org.apache.http.params BasicHttpParams)
           (org.apache.http.impl.cookie BasicClientCookie2)
           (org.apache.http.impl.cookie BrowserCompatSpecFactory)
           (org.apache.http.impl.cookie DefaultCookieSpec)
           (org.apache.http.message BasicHeader)
           org.apache.http.client.CookieStore
           (org.apache.http.impl.client BasicCookieStore)
           (org.apache.http Header)
           (org.apache.http.protocol BasicHttpContext)))

(defn default-cookie-spec ^CookieSpec []
  (.create
   (BrowserCompatSpecFactory.)
   (BasicHttpContext.)))

(defn cookie-spec ^CookieSpec
  [validate-fn]
  (if (nil? validate-fn)
    (default-cookie-spec)
    (proxy [DefaultCookieSpec] []
      (match [cookie origin] true)
      (validate [cookie origin] (validate-fn cookie origin)))))

(defn cookie-spec-provider ^CookieSpecProvider
  [validate-fn]
  (proxy [Object CookieSpecProvider] []
    (create [context] (cookie-spec validate-fn))))

(defn compact-map
  "Removes all map entries where value is nil."
  [m]
  (reduce (fn [newm k]
            (if (not (nil? (get m k)))
              (assoc newm k (get m k))
              newm))
          (sorted-map) (sort (keys m))))

(defn to-cookie
  "Converts a ClientCookie object into a tuple where the first item is
  the name of the cookie and the second item the content of the
  cookie."
  [^ClientCookie cookie]
  [(.getName cookie)
   (compact-map
    {:comment (.getComment cookie)
     :comment-url (.getCommentURL cookie)
     :discard (not (.isPersistent cookie))
     :domain (.getDomain cookie)
     :expires (when (.getExpiryDate cookie) (.getExpiryDate cookie))
     :path (.getPath cookie)
     :ports (when (.getPorts cookie) (seq (.getPorts cookie)))
     :secure (.isSecure cookie)
     :value (.getValue cookie)
     :version (.getVersion cookie)})])

(defn ^BasicClientCookie2
  to-basic-client-cookie
  "Converts a cookie seq into a BasicClientCookie2."
  [[cookie-name cookie-content]]
  (doto (BasicClientCookie2. (name cookie-name)
                             (name (:value cookie-content)))
    (.setComment (:comment cookie-content))
    (.setCommentURL (:comment-url cookie-content))
    (.setDiscard (:discard cookie-content true))
    (.setDomain (:domain cookie-content))
    (.setExpiryDate (:expires cookie-content))
    (.setPath (:path cookie-content))
    (.setPorts (int-array (:ports cookie-content)))
    (.setSecure (:secure cookie-content false))
    (.setVersion (:version cookie-content 0))))

(defn decode-cookie
  "Decode the Set-Cookie string into a cookie seq."
  [cookie-spec set-cookie-str]
  (if-not (blank? set-cookie-str)
    ;; I just want to parse a cookie without providing origin. How?
    (let [domain (lower-case (str (gensym)))
          origin (CookieOrigin. domain 80 "/" false)
          [cookie-name cookie-content] (-> cookie-spec
                                           (.parse (BasicHeader.
                                                    "set-cookie"
                                                    set-cookie-str)
                                                   origin)
                                           first
                                           to-cookie)]
      [cookie-name
       (if (= domain (:domain cookie-content))
         (dissoc cookie-content :domain) cookie-content)])))

(defn decode-cookies
  "Converts a cookie string or seq of strings into a cookie map."
  [cookie-spec cookies]
  (reduce #(assoc %1 (first %2) (second %2)) {}
          (map #(decode-cookie cookie-spec %) (if (sequential? cookies) cookies [cookies]))))

(defn decode-cookie-header
  "Decode the Set-Cookie header into the cookies key."
  [cookie-spec response]
  (if-let [cookies (get (:headers response) "set-cookie")]
    (assoc response
           :cookies (decode-cookies cookie-spec cookies)
           :headers (dissoc (:headers response) "set-cookie"))
    response))

(defn encode-cookie
  "Encode the cookie into a string used by the Cookie header."
  [cookie]
  (when-let [header (-> (default-cookie-spec)
                        (.formatCookies [(to-basic-client-cookie cookie)])
                        first)]
    (.getValue ^Header header)))

(defn encode-cookies
  "Encode the cookie map into a string."
  [cookie-map] (join ";" (map encode-cookie (seq cookie-map))))

(defn encode-cookie-header
  "Encode the :cookies key of the request into a Cookie header."
  [request]
  (if (:cookies request)
    (-> request
        (assoc-in [:headers "Cookie"] (encode-cookies (:cookies request)))
        (dissoc :cookies))
    request))

(defn wrap-cookies
  "Middleware wrapping cookie handling. Handles converting
  the :cookies request parameter into the 'Cookies' header for an HTTP
  request."
  [client]
  (fn [request]
    (let [cookie-validation (:cookie-validation request)
          cookie-spec (cookie-spec cookie-validation)
          response (client (encode-cookie-header request))]
      (if (= false (opt request :decode-cookies))
        response
        (decode-cookie-header cookie-spec response)))))

(defn cookie-store
  "Returns a new, empty instance of the default implementation of the
  org.apache.http.client.CookieStore interface."
  []
  (BasicCookieStore.))

(defn get-cookies
  "Given a cookie-store, return a map of cookie name to a map of cookie values."
  [^CookieStore cookie-store]
  (when cookie-store
    (into {} (map to-cookie (.getCookies cookie-store)))))
