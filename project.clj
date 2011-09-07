(defproject clj-http "0.2.0-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :dependencies [[org.clojure/clojure "1.2.1"]
                 [org.apache.httpcomponents/httpclient "4.1.2"]
                 [commons-codec "1.5"]
                 [commons-io "1.4"]]
  :multi-deps {"1.3" [[org.clojure/clojure "1.3.0-beta3"]
                      [org.apache.httpcomponents/httpclient "4.1.2"]
                      [commons-codec "1.5"]
                      [commons-io "1.4"]]}
  :dev-dependencies [[swank-clojure "1.3.2"]
                     [ring/ring-jetty-adapter "0.3.11"]
                     [ring/ring-devel "0.3.11"]
                     [robert/hooke "1.1.2"]
                     [clj-stacktrace "0.2.3"]
                     [lein-multi "1.0.0"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
