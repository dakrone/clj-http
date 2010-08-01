(defproject clj-http "0.0.1-SNAPSHOT"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.2.0-RC1"]
     [org.clojure/clojure-contrib "1.2.0-RC1"]
     [org.apache.httpcomponents/httpclient "4.0.1"]
     [commons-codec "1.3"]]
  :dev-dependencies
    [[swank-clojure "1.2.0"]
     [lein-clojars "0.5.0"]
     [ring/ring-jetty-adapter "0.2.5"]])
