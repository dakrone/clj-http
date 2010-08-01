(defproject clj-http "0.0.1-SNAPSHOT"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.2.0-RC1"]
     [org.clojure/clojure-contrib "1.2.0-RC1"]
		 [org.apache.httpcomponents/httpclient "4.0.1"]
		 [hiccup "0.2.6"]
		 [org.danlarkin/clojure-json "1.1-SNAPSHOT"]]
  :dev-dependencies
    [[swank-clojure "1.2.0"]
     [lein-clojars "0.5.0"]])
