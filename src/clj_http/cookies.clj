(ns clj-http.cookies
  (:import (org.apache.http.client.params ClientPNames CookiePolicy)
           (org.apache.http.cookie CookieOrigin)
           (org.apache.http.params BasicHttpParams)
           (org.apache.http.impl.cookie BasicClientCookie2)
           (org.apache.http.impl.cookie BrowserCompatSpecFactory)
           (org.apache.http.message BasicHeader))
  (:use [clojure.contrib.string :only (as-str blank? join lower-case)]
        clj-http.util))

(defn- cookie-spec []
  (.newInstance
   (BrowserCompatSpecFactory.)
   (doto (BasicHttpParams.)
     (.setParameter ClientPNames/COOKIE_POLICY CookiePolicy/BROWSER_COMPATIBILITY))))

(defn- compact-map
  "Removes all map entries where value is nil."
  [m] (reduce #(if (get m %2) (assoc %1 %2 (get m %2)) %1) (sorted-map) (sort (keys m))))

(defn- to-cookie
  "Converts a ClientCookie object into a tuple where the first item is
  the name of the cookie and the second item the content of the
  cookie."
  [cookie]
  [(.getName cookie)
   (compact-map
    {:comment (.getComment cookie)
     :comment-url (.getCommentURL cookie)
     :discard (not (.isPersistent cookie))
     :domain (.getDomain cookie)
     :expires (if (.getExpiryDate cookie) (.getExpiryDate cookie))
     :path (.getPath cookie)
     :ports (if (.getPorts cookie) (seq (.getPorts cookie)))
     :secure (.isSecure cookie)
     :value (url-decode (.getValue cookie))
     :version (.getVersion cookie)})])

(defn- to-basic-client-cookie
  "Converts a cookie seq into a BasicClientCookie2."
  [[cookie-name cookie-content]]
  (doto (BasicClientCookie2. (as-str cookie-name) (url-encode (as-str (:value cookie-content))))
    (.setComment (:comment cookie-content))
    (.setCommentURL (:comment-url cookie-content))
    (.setDiscard (or (:discard cookie-content) true))
    (.setDomain (:domain cookie-content))
    (.setExpiryDate (:expires cookie-content))
    (.setPath (:path cookie-content))
    (.setPorts (int-array (:ports cookie-content)))
    (.setSecure (or (:secure cookie-content) false))
    (.setVersion (or (:version cookie-content) 0))))

(defn decode-cookie
  "Decode the Set-Cookie string into a cookie seq."
  [set-cookie-str]
  (if-not (blank? set-cookie-str)
    (let [domain (lower-case (str (gensym))) ; I just want to parse a cookie without providing origin. How?
          origin (CookieOrigin. domain 80 "/" false)
          [cookie-name cookie-content] (to-cookie (first (.parse (cookie-spec) (BasicHeader. "set-cookie" set-cookie-str) origin)))]
      [cookie-name
       (if (= domain (:domain cookie-content))
         (dissoc cookie-content :domain) cookie-content)])))

(defn decode-cookies
  "Converts a cookie string or seq of strings into a cookie map."
  [cookies]
  (reduce #(assoc %1 (first %2) (second %2)) {}
          (map decode-cookie (if (sequential? cookies) cookies [cookies]))))

(defn decode-cookie-header
  "Decode the Set-Cookie header into the cookies key."
  [response]
  (if-let [cookies (get (:headers response) "set-cookie")]
    (assoc response
      :cookies (decode-cookies cookies)
      :headers (dissoc (:headers response) "set-cookie"))
    response))

(defn encode-cookie
  "Encode the cookie into a string used by the Cookie header."
  [cookie]
  (if-let [header (first (.formatCookies (cookie-spec) [(to-basic-client-cookie cookie)]))]
    (.getValue header)))

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
  [client]
  (fn [request]
    (let [response (client (encode-cookie-header request))]
      (decode-cookie-header response))))
