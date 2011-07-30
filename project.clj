(defproject com.zacharykim/clj-http "0.1.4"
  :description
    "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies
    [[org.clojure/clojure "1.2.0"]
     [org.apache.httpcomponents/httpclient "4.0.3"]
     [commons-codec "1.4"]
     [commons-io "1.4"]]
  :dev-dependencies
    [[swank-clojure "1.2.0"]
     [ring/ring-jetty-adapter "0.3.5"]
     [ring/ring-devel "0.3.5"]
     [robert/hooke "1.1.0"]
     [lein-clojars "0.7.0"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
