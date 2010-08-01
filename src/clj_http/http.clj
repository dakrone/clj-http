(ns clj-http.http
  (:import (java.net URL URLEncoder))
  (:import org.apache.http.HttpEntity)
  (:import org.apache.http.HttpResponse)
  (:import org.apache.http.HttpRequestInterceptor)
  (:import org.apache.http.auth.AuthScope)
  (:import org.apache.http.auth.AuthState)
  (:import org.apache.http.protocol.BasicHttpContext)
  (:import org.apache.http.impl.auth.BasicScheme)
  (:import org.apache.http.auth.UsernamePasswordCredentials)
  (:import org.apache.http.client.methods.HttpGet)
  (:import org.apache.http.client.methods.HttpUriRequest)
  (:import org.apache.http.client.methods.HttpRequestBase)
  (:import org.apache.http.client.protocol.ClientContext)
  (:import org.apache.http.protocol.HttpContext)
  (:import org.apache.http.impl.client.DefaultHttpClient)
  (:use [clojure.contrib.java-utils :only [as-str]])
	(:use [clojure.contrib.str-utils :only [str-join]])
  (:require [clojure.contrib.http.agent :as ha])
  (:require [clojure.contrib.io :as io]))

(defn url
  [link]
  (URL. link))

(defn url-encode
  "Wrapper around java.net.URLEncoder returning a (UTF-8) URL encoded
representation of argument, either a string or map."
  [arg]
  (if (map? arg)
    (str-join \& (map #(str-join \= (map url-encode %)) arg))
    (URLEncoder/encode (as-str arg) "UTF-8")))

;;TODO: what to do about various means of encouding, i.e. amper vs. slash
(defn url-encode-slash
  "Wrapper around java.net.URLEncoder returning a (UTF-8) URL encoded
representation of argument, either a string or map."
  [arg]
  (if (map? arg)
    (str-join \/ (map #(str-join \/ (map url-encode %)) arg))
    (URLEncoder/encode (as-str arg) "UTF-8")))

(defn with-params [url params]
  (str url "/" (url-encode params)))

(defn parse-xml-str [s]
     (clojure.xml/parse (java.io.ByteArrayInputStream. (.getBytes s))))

        ;; // create the request
        ;; this.request = isPost ? new HttpPost(urlStr) : new HttpGet(urlStr);

        ;; // add request parameters to body (for POST)
        ;; if (isPost && requestParameterMap != null) {
        ;;     List <NameValuePair> nvps = new ArrayList <NameValuePair>();
        ;;     for (String key: requestParameterMap.keySet()) {
        ;;         nvps.add(new BasicNameValuePair(key, requestParameterMap.get(key)));
        ;;     }
        ;;     ((HttpPost) this.request).setEntity(new UrlEncodedFormEntity(nvps, HTTP.UTF_8));
        ;; }




;;http://hc.apache.org/httpcomponents-client-4.0.1/tutorial/html/authentication.html
(defn preemptive-auth [creds]
            (proxy [HttpRequestInterceptor] []
              (process
               [#^HttpUriRequest request
                #^HttpContext context]

               (let [#^AuthState auth-state (.getAttribute context ClientContext/TARGET_AUTH_STATE)
                     #^java.net.URI u (.getURI request)
                     #^String target-host (.getHost u)
                     target-port (or (.getPort u)
                                     (if (= "https" (.getScheme u))
                                       443
                                       80))]

                 (when-not (.getAuthScheme auth-state)
                   (.setCredentials (.getAttribute context ClientContext/CREDS_PROVIDER)
				    (AuthScope. target-host target-port) creds)
                   (.setAuthScheme auth-state (BasicScheme.))
                   (.setCredentials auth-state creds))))))

;;http://svn.apache.org/repos/asf/httpcomponents/httpclient/branches/4.0.x/httpclient/src/examples/org/apache/http/examples/client/ClientAuthentication.java
(defn request
  ([u]
     (ha/string
      (ha/http-agent
       (url u))))
  ([url username password]
     (let [uri (java.net.URI. url)
	   client (DefaultHttpClient.)
	   creds (UsernamePasswordCredentials. username password)
	   _ (.setCredentials (.getCredentialsProvider client)
			      (AuthScope.
			       (.getHost uri)
			       (if (= "https"
				      (.getScheme uri))
				 443 80))
			      creds)
	   context (BasicHttpContext.)
	   basic-auth (BasicScheme.)
	   _ (.setAttribute context "preemptive-auth" basic-auth)
	   _ (.addRequestInterceptor client (preemptive-auth creds) 0)
	   req (HttpGet. uri)
	   res (.execute client req context)
	   entity (.getEntity res)
	   status (.getStatusLine res)
	   response {:code (.getStatusCode status)
                     :reason (.getReasonPhrase status)
                     :content (io/slurp* (.getContent entity))
                     :headers (iterator-seq (.headerIterator res))}
	   _ (.shutdown (.getConnectionManager client))]
       response)))


;; example calls
;; (defn trip [id]
;;   (let [params (merge {"id" id}
;; 		      {"include_objects" "true"
;; 		       "format" "json"})]
;;     (decode-from-str (:content
;; 		      (request
;; 		       (str (mk-url "get" "trip") "/"
;; 			    (url-encode-slash params))
;; 		      "username" "password")))))

;; (defn- get-session []
;; (request
;; "http://www.kayak.com/k/ident/apisession?token=3PWihQksZae68TfQ0jnlyA"))

;; (defn- extract-sid [x]
;;   (let [parsed (parse-xml-str x)]
;;     (first (:content (first (filter #(= :sid (:tag %)) (:content parsed)))))))

;; (defn- extract-searchid [x]
;;   (let [parsed (parse-xml-str x)]
;;     (first (:content (first (filter #(= :searchid (:tag %)) (:content parsed)))))))

;; (defn- search-hotels
;; "location must be the region code (same as airporrt code) - city names do not seem to work."
;; [session-id location]
;;   (request
;;    (str "http://www.kayak.com/s/apisearch?basicmode=true&othercity=" location "&destcode=&checkin_date=09/09/2010&checkout_date=09/13/2010&guests=&1&rooms=1cabin=b&action=dohotels&apimode=1&_sid_=" session-id)))

;; (defn- get-hotels [session-id search-id]
;; (request
;;  (str "http://www.kayak.com/s/basic/hotel?searchid=" search-id "&c=10&apimode=1&_sid_=" session-id)))

;; (defn hotels [location]
;;   (let [sid (extract-sid (get-session))
;; 	searchid (extract-searchid (search-hotels sid location))]
;;     (parse-xml-str (get-hotels sid searchid))))
