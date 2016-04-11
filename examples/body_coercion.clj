(ns clj-http.examples.body-coercion
  (:require [clj-http.client :as http]
            [camel-snake-kebab.core :refer [->kebab-case-keyword]]))

;; register your own body coercers by participating in the coerce-response-body multimethod
;; dispatch to it by using {:as :json-kebab-keys} as an argument to http client calls

;; this example uses camel-snake-kebab to turn a camel-cased JSON API into
;; idiomatic kebab-cased keywords in clojure data structures and is much
;; faster than applying via postwalk or similar

(defmethod http/coerce-response-body :json-kebab-keys [req resp]
  (http/coerce-json-body req resp (memoize ->kebab-case-keyword) false))

;; example of use; note that in the response, the first field is called userId
;;
;; (:body (http/get "http://jsonplaceholder.typicode.com/posts/1" {:as :json-kebab-keys}))
;; =>
;; {:user-id 1,
;;  :id 1,
;;  :title "sunt aut facere repellat provident occaecati excepturi optio reprehenderit",
;;  :body "quia et suscipit\nsuscipit recusandae consequuntur expedita et cum\nreprehenderit molestiae ut ut quas totam\nnostrum rerum est autem sunt rem eveniet architecto"
;; }
