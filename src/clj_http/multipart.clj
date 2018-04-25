(ns clj-http.multipart
  "Namespace used for clj-http to create multipart entities and bodies."
  (:import (java.io File InputStream)
           (java.nio.charset Charset)
           (org.apache.hc.client5.http.entity.mime ByteArrayBody
                                                   ContentBody
                                                   FileBody
                                                   HttpMultipartMode
                                                   InputStreamBody
                                                   MultipartEntityBuilder
                                                   StringBody)
           (org.apache.hc.core5.http ContentType)))

;; we don't need to make a fake byte-array every time, only once
(def byte-array-type (type (byte-array 0)))

(defmulti
  make-multipart-body
  "Create a body object from the given map, dispatching on the type of its
   content. By default supported content body types are:
   - String
   - byte array (requires providing name)
   - InputStream (requires providing name)
   - File
   - org.apache.http.entity.mime.content.ContentBody (which is just returned)"
  (fn [multipart] (type (:content multipart))))

(defmethod make-multipart-body nil
  [multipart]
  (throw (Exception. "Multipart content cannot be nil")))

(defmethod make-multipart-body :default
  [multipart]
  (throw (Exception. (str "Unsupported type for multipart content: "
                          (type (:content multipart))))))

(defmethod make-multipart-body File
  ;; Create a FileBody object from the given map, requiring at least :content
  [{:keys [^String name ^String mime-type ^File content ^String encoding]}]
  (cond
    (and name mime-type content encoding)
    (FileBody. content (ContentType/create mime-type encoding) name)

    (and mime-type content encoding)
    (FileBody. content (ContentType/create mime-type encoding))

    (and name mime-type content)
    (FileBody. content (ContentType/create mime-type) name)

    (and mime-type content)
    (FileBody. content (ContentType/create mime-type))

    content
    (FileBody. content)

    :else
    (throw (Exception. "Multipart file body must contain at least :content"))))

(defmethod make-multipart-body InputStream
  ;; Create an InputStreamBody object from the given map, requiring at least
  ;; :content and :name. If no :length is specified, clj-http will use
  ;; chunked transfer-encoding, if :length is specified, clj-http will
  ;; workaround things be proxying the InputStreamBody to return a length.
  [{:keys [^String name ^String mime-type ^InputStream content length]}]
  (cond
    (and content name length)
    (if mime-type
      (proxy [InputStreamBody] [content (ContentType/create mime-type) name]
        (getContentLength []
          length))
      (proxy [InputStreamBody] [content name]
        (getContentLength []
          length)))

    (and content mime-type name)
    (InputStreamBody. content (ContentType/create mime-type) name)

    (and content name)
    (InputStreamBody. content name)

    :else
    (throw (Exception. (str "Multipart input stream body must contain "
                            "at least :content and :name")))))

(defmethod make-multipart-body byte-array-type
  ;; Create a ByteArrayBody object from the given map, requiring at least
  ;; :content and :name.
  [{:keys [^String name ^String mime-type ^bytes content]}]
  (cond
    (and content name mime-type)
    (ByteArrayBody. content (ContentType/create mime-type) name)

    (and content name)
    (ByteArrayBody. content name)

    :else
    (throw (Exception. (str "Multipart byte array body must contain "
                            "at least :content and :name")))))

(defmulti  ^java.nio.charset.Charset encoding-to-charset class)
(defmethod encoding-to-charset nil [encoding] nil)
(defmethod encoding-to-charset java.nio.charset.Charset [encoding] encoding)
(defmethod encoding-to-charset java.lang.String [encoding]
  (java.nio.charset.Charset/forName encoding))

(defmethod make-multipart-body String
  ;; Create a StringBody object from the given map, requiring at least :content.
  ;; If :encoding is specified, it will be created using the Charset for that
  ;; encoding.
  [{:keys [^String mime-type ^String content encoding]}]
  (cond
    (and content mime-type encoding)
    (StringBody.
     content (ContentType/create mime-type (encoding-to-charset encoding)))

    (and content encoding)
    (StringBody.
     content (ContentType/create "text/plain" (encoding-to-charset encoding)))

    content
    (StringBody. content (ContentType/create "text/plain" (Charset/forName "UTF-8")))))

(defmethod make-multipart-body ContentBody
  ;; Use provided org.apache.http.entity.mime.content.ContentBody directly
  [{:keys [^ContentBody content]}]
  content)

(defn create-multipart-entity
  "Takes a multipart vector of maps and creates a MultipartEntity with each map
  added as a part, depending on the type of content. If a mime-subtype or
  multipart-mode are specified, they are set on the multipart builder, otherwise
  'form-data' and strict mode are used."
  [multipart mime-subtype multipart-mode]
  (let [mp-entity (doto (MultipartEntityBuilder/create)
                    (.setCharset (encoding-to-charset "UTF-8"))
                    (.setMimeSubtype (or mime-subtype "form-data")))]
    (if multipart-mode
      (.setMode mp-entity multipart-mode)
      (.setStrictMode mp-entity))
    (doseq [m multipart]
      (let [name (or (:part-name m) (:name m))
            part (make-multipart-body m)]
        (.addPart mp-entity name part)))
    (.build mp-entity)))
