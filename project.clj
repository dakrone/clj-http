(defproject clj-http "0.9.3-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :global-vars {*warn-on-reflection* true}
  :min-lein-version "2.0.0"
  :exclusions [org.clojure/clojure]
  :dependencies [[org.apache.httpcomponents/httpcore "4.3.2"]
                 [org.apache.httpcomponents/httpclient "4.3.3"]
                 [org.apache.httpcomponents/httpmime "4.3.3"]
                 [commons-codec "1.9"]
                 [commons-io "2.4"]
                 [slingshot "0.10.3"]
                 [cheshire "5.3.1"]
                 [crouton "0.1.2"]
                 [org.clojure/tools.reader "0.8.4"]
                 [potemkin "0.3.4"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                  [org.clojure/tools.logging "0.2.6"]
                                  [log4j "1.2.17"]
                                  [ring/ring-jetty-adapter "1.2.2"]
                                  [ring/ring-devel "1.2.2"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}}
  :aliases {"all" ["with-profile" "dev,1.4:dev,1.5:dev"]}
  :plugins [[codox "0.6.4"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
