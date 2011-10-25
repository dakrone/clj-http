<<<<<<< HEAD
(defproject clj-http "0.1.3"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.3.0"]
     [org.apache.httpcomponents/httpclient "4.0.3"]
     [commons-codec "1.4"]
     [commons-io "1.4"]]
  :dev-dependencies
    [[swank-clojure "1.3.2"]
     [ring/ring-jetty-adapter "0.3.5"]
     [ring/ring-devel "0.3.5"]
     [robert/hooke "1.1.0"]]
=======
(defproject clj-http "0.2.3-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.apache.httpcomponents/httpclient "4.1.2"]
                 [commons-codec "1.5"]
                 [commons-io "1.4"]
                 [slingshot "0.5.0"]]
  :multi-deps {"1.2.1" [[org.clojure/clojure "1.2.1"]
                      [org.apache.httpcomponents/httpclient "4.1.2"]
                      [commons-codec "1.5"]
                      [commons-io "1.4"]
                      [slingshot "0.5.0"]]
               "1.4.0" [[org.clojure/clojure "1.4.0-alpha1"]
                      [org.apache.httpcomponents/httpclient "4.1.2"]
                      [commons-codec "1.5"]
                      [commons-io "1.4"]
                      [slingshot "0.5.0"]]}
  :dev-dependencies [[swank-clojure "1.3.3"]
                     [ring/ring-jetty-adapter "0.3.11"]
                     [ring/ring-devel "0.3.11"]
                     [lein-multi "1.0.0"]]
>>>>>>> f65710896139e5185636aa2257a1d8f9db90d02c
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
