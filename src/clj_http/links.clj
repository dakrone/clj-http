(ns clj-http.links)

(def ^:private quoted-string
  #"\"((?:[^\"]|\\\")*)\"")

(def ^:private token
  #"([^,\";]*)")

(def ^:private link-param
  (re-pattern (str "(\\w+)=(?:" quoted-string "|" token ")")))

(def ^:private uri-reference
  #"<([^>]*)>")

(def ^:private link-value
  (re-pattern (str uri-reference "((?:\\s*;\\s*" link-param ")*)")))

(def ^:private link-header
  (re-pattern (str "(?:\\s*(" link-value ")\\s*,?\\s*)")))

(defn read-link-params [params]
  (into {}
        (for [[_ name quot tok] (re-seq link-param params)]
          [(keyword name) (or quot tok)])))

(defn read-link-value [value]
  (let [[_ uri params] (re-matches link-value value)
        param-map      (read-link-params params)]
    [(keyword (:rel param-map))
     (-> param-map
         (assoc :href uri)
         (dissoc :rel))]))

(defn read-link-headers [header]
  (->> (re-seq link-header header)
       (map second)
       (map read-link-value)
       (into {})))

(defn wrap-links
  "Add a :links key to the response map that contains parsed Link headers. The
  links will be represented as a map, with the 'rel' value being the key. The
  URI is placed under the 'href' key, to mimic the HTML link element.

  e.g. Link: <http://example.com/page2.html>; rel=next; title=\"Page 2\"
       => {:links {:next {:href \"http://example.com/page2.html\"
                          :title \"Page 2\"}}}"
  [client]
  (fn [request]
    (let [response (client request)]
      (if-let [link-headers (get-in response [:headers "link"])]
        (let [link-headers (if (coll? link-headers)
                             link-headers
                             [link-headers])]
          (assoc response
            :links
            (apply merge (for [link-header link-headers]
                           (read-link-headers link-header)))))
        response))))
