(ns clj-http.examples.progress-download
  (:require [clj-http.client :as http]
            [clojure.java.io :refer [output-stream]])
  (:import (org.apache.commons.io.input CountingInputStream)))

(defn print-progress-bar
  "Render a simple progress bar given the progress and total. If the total is zero
   the progress will run as indeterminated."
  ([progress total] (print-progress-bar progress total {}))
  ([progress total {:keys [bar-width]
                    :or   {bar-width 50}}]
   (if (pos? total)
     (let [pct (/ progress total)
           render-bar (fn []
                        (let [bars (Math/floor (* pct bar-width))
                              pad (- bar-width bars)]
                          (str (clojure.string/join (repeat bars "="))
                               (clojure.string/join (repeat pad " ")))))]
       (print (str "[" (render-bar) "] "
                   (int (* pct 100)) "% "
                   progress "/" total)))
     (let [render-bar (fn [] (clojure.string/join (repeat bar-width "-")))]
       (print (str "[" (render-bar) "] "
                   progress "/?"))))))

(defn insert-at [v idx val]
  "Addes value into a vector at an specific index."
  (-> (subvec v 0 idx)
      (conj val)
      (into (subvec v idx))))

(defn insert-after [v needle val]
  "Finds an item into a vector and adds val just after it.
   If needle is not found, the input vector will be returned."
  (let [index (.indexOf v needle)]
    (if (neg? index)
      v
      (insert-at v (inc index) val))))

(defn wrap-downloaded-bytes-counter
  "Middleware that provides an CountingInputStream wrapping the stream output"
  [client]
  (fn [req]
    (let [resp (client req)
          counter (CountingInputStream. (:body resp))]
      (merge resp {:body                     counter
                   :downloaded-bytes-counter counter}))))

(defn download-with-progress [url target]
  (http/with-middleware
    (-> http/default-middleware
        (insert-after http/wrap-redirects wrap-downloaded-bytes-counter)
        (conj http/wrap-lower-case-headers))
    (let [request (http/get url {:as :stream})
          length (Integer. (get-in request [:headers "content-length"] 0))
          buffer-size (* 1024 10)]
      (println)
      (with-open [input (:body request)
                  output (output-stream target)]
        (let [buffer (make-array Byte/TYPE buffer-size)
              counter (:downloaded-bytes-counter request)]
          (loop []
            (let [size (.read input buffer)]
              (when (pos? size)
                (.write output buffer 0 size)
                (print "\r")
                (print-progress-bar (.getByteCount counter) length)
                (recur))))))
      (println))))

;; Example of progress bar output (sample steps)
;;
;; [===                                               ] 7% 2094930/26572400
;; [==============================                    ] 60% 16062930/26572400
;; [=========================================         ] 83% 22290930/26572400
;; [==================================================] 100% 26572400/26572400
;;
;; In case the content-length is unknown, the bar will be displayed as:
;;
;; [--------------------------------------------------] 4211440/?
