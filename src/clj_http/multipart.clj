(ns clj-http.multipart
  "Namespace used for clj-http to create multipart entities and bodies."
  (:import (java.io File InputStream)
           (java.nio.charset Charset)
           (org.apache.http.entity.mime MultipartEntity)
           (org.apache.http.entity.mime.content ByteArrayBody
                                                FileBody
                                                InputStreamBody
                                                StringBody)))

;; we don't need to make a fake byte-array every time, only once
(def byte-array-type (type (byte-array 0)))

(defn make-file-body
  "Create a FileBody object from the given map, requiring at least :content"
  [{:keys [name mime-type content encoding]}]
  (cond
   (and name mime-type content encoding)
   (FileBody. content name mime-type encoding)

   (and mime-type content encoding)
   (FileBody. content mime-type encoding)

   (and mime-type content)
   (FileBody. content mime-type)

   content
   (FileBody. content)

   :else
   (throw (Exception. "Multipart file body must contain at least :content"))))


(defn make-input-stream-body
  "Create an InputStreamBody object from the given map, requiring at least
  :content and :name."
  [{:keys [name mime-type content]}]
  (cond
   (and content mime-type name)
   (InputStreamBody. content mime-type name)

   (and content name)
   (InputStreamBody. content name)

   :else
   (throw (Exception. (str "Multipart input stream body must contain "
                           "at least :content and :name")))))


(defn make-byte-array-body
  "Create a ByteArrayBody object from the given map, requiring at least :content
  and :name."
  [{:keys [name mime-type content]}]
  (cond
   (and content name mime-type)
   (ByteArrayBody. content mime-type name)

   (and content name)
   (ByteArrayBody. content name)

   :else
   (throw (Exception. (str "Multipart byte array body must contain "
                           "at least :content and :name")))))


(defn make-string-body
  "Create a StringBody object from the given map, requiring at least :content.
  If :encoding is specified, it will be created using the Charset for
  that encoding."
  [{:keys [mime-type content encoding]}]
  (cond
   (and content mime-type encoding)
   (StringBody. content mime-type (Charset/forName encoding))

   (and content encoding)
   (StringBody. content (Charset/forName encoding))

   content
   (StringBody. content)

   :else
   (throw (Exception. (str "Multipart string body must contain "
                           "at least :content")))))


(defn create-multipart-entity
  "Takes a multipart vector of maps and creates a MultipartEntity with each
   map added as a part, depending on the type of content."
  [multipart]
  (let [mp-entity (MultipartEntity.)]
    (doseq [m multipart]
      (let [klass (type (:content m))
            name (or (:part-name m) (:name m))
            ;; TODO: replace with multimethod? actually helpful?
            part (cond
                  (isa? klass File)
                  (make-file-body m)

                  (isa? klass InputStream)
                  (make-input-stream-body m)

                  (= klass byte-array-type)
                  (make-byte-array-body m)

                  (= klass String)
                  (make-string-body m))]
        (.addPart mp-entity name part)))
    mp-entity))
