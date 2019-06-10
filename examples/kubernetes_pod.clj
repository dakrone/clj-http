(:ns clj-http.examples.kubernetes-pod
  "This is an example of calling the Kubernetes API from inside a pod. K8s uses a
   custom CA so that you can authenticate the API server, and provides a token per pod
   so that each pod can authenticate itself with the APi server.
   
   If you are still having 401/403 errors, look carefully at the message, if it includes 
   a ServiceAccount name, this part worked, and your problem is likely at the Role/RoleBinding level."
  (:require [clj-http.client :as http]
            [less.awful.ssl :refer [trust-store]]))

;; Note that this is not a working example, you'll need to figure out your K8s API path.
(let [k8s-trust-store (trust-store (clojure.java.io/file "/var/run/secrets/kubernetes.io/serviceaccount/ca.crt"))
      bearer-token (format "Bearer %s" (slurp "/var/run/secrets/kubernetes.io/serviceaccount/token"))
      kube-api-host (System/getenv "KUBERNETES_SERVICE_HOST")
      kube-api-port (System/getenv "KUBERNETES_SERVICE_PORT")]
  (http/get 
    (format "https://%s:%s/apis/<something-protected>" kube-api-host kube-api-port)
    {:trust-store k8s-trust-store
     :headers {:authorization bearer-token}}))
   
