(defproject clj-http "0.2.8-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :repositories {"sona" "http://oss.sonatype.org/content/repositories/snapshots"}
  :warn-on-reflection false
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [org.apache.httpcomponents/httpclient "4.1.2"]
                 [org.apache.httpcomponents/httpmime "4.1.2"]
                 [commons-codec "1.5"]
                 [commons-io "2.1"]
                 [slingshot "0.10.1"]
                 [cheshire "2.0.4"]]
  :multi-deps {"1.2.1" [[org.clojure/clojure "1.2.1"]
                        [org.apache.httpcomponents/httpclient "4.1.2"]
                        [org.apache.httpcomponents/httpmime "4.1.2"]
                        [commons-codec "1.5"]
                        [commons-io "2.1"]
                        [slingshot "0.10.1"]
                        [cheshire "2.0.4"]]
               "1.4.0" [[org.clojure/clojure "1.4.0-alpha4"]
                        [org.apache.httpcomponents/httpclient "4.1.2"]
                        [org.apache.httpcomponents/httpmime "4.1.2"]
                        [commons-codec "1.5"]
                        [commons-io "2.1"]
                        [slingshot "0.10.1"]
                        [cheshire "2.0.4"]]}
  :dev-dependencies [[ring/ring-jetty-adapter "1.0.2"]
                     [ring/ring-devel "1.0.2"]
                     [lein-multi "1.1.0"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)}
  :checksum-deps true)
