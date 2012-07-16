(defproject clj-http "0.5.1-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :repositories {"sona" "http://oss.sonatype.org/content/repositories/snapshots"}
  :warn-on-reflection false
  :min-lein-version "2.0.0"
  :dependencies [[org.clojure/clojure "1.4.0"]
                 [org.apache.httpcomponents/httpclient "4.1.3"]
                 [org.apache.httpcomponents/httpmime "4.1.3"]
                 [commons-codec "1.5"]
                 [commons-io "2.1"]
                 [slingshot "0.10.3"]
                 [cheshire "4.0.1"]]
  :profiles {:dev {:dependencies [[ring/ring-jetty-adapter "1.1.0"]
                                  [ring/ring-devel "1.1.0"]]}
             :1.2 {:dependencies [[org.clojure/clojure "1.2.1"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.0-alpha2"]]}}
  :aliases {"all" ["with-profile" "dev,1.2:dev,1.3:dev:1.5,dev"]}
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
