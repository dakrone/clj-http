(defproject clj-http "1.1.0"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :global-vars {*warn-on-reflection* true}
  :min-lein-version "2.0.0"
  :exclusions [org.clojure/clojure]
  :dependencies [[org.apache.httpcomponents/httpcore "4.4.1"]
                 [org.apache.httpcomponents/httpclient "4.4"]
                 [org.apache.httpcomponents/httpmime "4.4"]
                 [commons-codec "1.10"]
                 [commons-io "2.4"]
                 [com.cognitect/transit-clj "0.8.269"]
                 [slingshot "0.12.2"]
                 [cheshire "5.4.0"]
                 [crouton "0.1.2"]
                 [org.clojure/tools.reader "0.8.16"]
                 [potemkin "0.3.12"]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.6.0"]
                                  [org.clojure/tools.logging "0.3.1"]
                                  [log4j "1.2.17"]
                                  [ring/ring-jetty-adapter "1.3.2"]
                                  [ring/ring-devel "1.3.2"]]}
             :1.5 {:dependencies [[org.clojure/clojure "1.5.1"]]}
             :1.7 {:dependencies [[org.clojure/clojure "1.7.0-alpha5"]]}}
  :aliases {"all" ["with-profile" "dev,1.5:dev:dev,1.7"]}
  :plugins [[codox "0.6.4"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
