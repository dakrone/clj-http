(ns clj-http.multipart
  "Namespace used for clj-http to create multipart entities and bodies."
  (:import (java.io File InputStream)
           (java.nio.charset Charset)
           (org.apache.http.entity ContentType)
           (org.apache.http.entity.mime MultipartEntity)
           (org.apache.http.entity.mime.content ContentBody
                                                ByteArrayBody
                                                FileBody
                                                InputStreamBody
                                                StringBody)))

;; we don't need to make a fake byte-array every time, only once
(def byte-array-type (type (byte-array 0)))

(defn make-file-body
  "Create a FileBody object from the given map, requiring at least :content"
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

(defn make-input-stream-body
  "Create an InputStreamBody object from the given map, requiring at least
  :content and :name. If no :length is specified, clj-http will use
  chunked transfer-encoding, if :length is specified, clj-http will
  workaround things be proxying the InputStreamBody to return a length."
  [{:keys [^String name ^String mime-type ^InputStream content length]}]
  (cond
    (and content name length)
    (if mime-type
      (proxy [InputStreamBody] [content mime-type name]
        (getContentLength []
          length))
      (proxy [InputStreamBody] [content name]
        (getContentLength []
          length)))

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
  [{:keys [^String name ^String mime-type ^bytes content]}]
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
  [{:keys [mime-type ^String content encoding]}]
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

(defn make-multipart-body
  "Create a body object from the given map, dispatching on the type
  of its content. Requires the content to be of type File, InputStream,
  ByteArray, String, or instance of org.apache.http.entity.mime.content.ContentBody."
  [multipart]
  (let [klass (type (:content multipart))]
    ;; TODO: replace with multimethod? actually helpful?
    (cond
      (isa? klass ContentBody)
      (:content multipart)

      (isa? klass File)
      (make-file-body multipart)

      (isa? klass InputStream)
      (make-input-stream-body multipart)

      (= klass byte-array-type)
      (make-byte-array-body multipart)

      (= klass String)
      (make-string-body multipart)

      :else
      (throw (Exception. (str "Multipart content must be of type File, "
                              "InputStream, ByteArray, or String."))))))

(defn create-multipart-entity
  "Takes a multipart vector of maps and creates a MultipartEntity with each
  map added as a part, depending on the type of content."
  [multipart]
  (let [mp-entity (MultipartEntity.)]
    (doseq [m multipart]
      (let [name (or (:part-name m) (:name m))
            part (make-multipart-body m)]
        (.addPart mp-entity name part)))
    mp-entity))
