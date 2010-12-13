(defproject clj-http "0.1.2"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.2.0"]
     [org.clojure/clojure-contrib "1.2.0"]
     [org.apache.httpcomponents/httpclient "4.0.3"]
     [commons-codec "1.4"]
     [commons-io "1.4"]]
  :dev-dependencies
    [[swank-clojure "1.2.0"]
     [ring/ring-jetty-adapter "0.3.5"]
     [ring/ring-devel "0.3.5"]])
