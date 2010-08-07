(defproject clj-http "0.1.0-SNAPSHOT"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.2.0-RC2"]
     [org.clojure/clojure-contrib "1.2.0-RC2"]
     [org.apache.httpcomponents/httpclient "4.0.1"]
     [commons-codec "1.4"]
     [commons-io "1.4"]]
  :dev-dependencies
    [[swank-clojure "1.2.0"]
     [ring/ring-jetty-adapter "0.2.5"]
     [ring/ring-devel "0.2.5"]])
