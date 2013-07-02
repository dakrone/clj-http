(defproject clj-http "0.7.4-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :global-vars {*warn-on-reflection* true}
  :min-lein-version "2.0.0"
  :dependencies [[org.apache.httpcomponents/httpcore "4.2.4"]
                 [org.apache.httpcomponents/httpclient "4.2.5"]
                 [org.apache.httpcomponents/httpmime "4.2.5"]
                 [commons-codec "1.7"]
                 [commons-io "2.4"]
                 [slingshot "0.10.3" :exclusions [org.clojure/clojure]]
                 [cheshire "5.2.0"]
                 [crouton "0.1.1" :exclusions [org.clojure/clojure]]
                 [org.clojure/tools.reader "0.7.4"
                  :exclusions [org.clojure/clojure]]]
  :profiles {:dev {:dependencies [[org.clojure/clojure "1.5.1"]
                                  [ring/ring-jetty-adapter "1.1.8"]
                                  [ring/ring-devel "1.1.8"]]}
             :1.2 {:dependencies [[org.clojure/clojure "1.2.1"]]}
             :1.3 {:dependencies [[org.clojure/clojure "1.3.0"]]}
             :1.4 {:dependencies [[org.clojure/clojure "1.4.0"]]}}
  :aliases {"all" ["with-profile" "dev,1.3:dev,1.4:dev"]}
  :plugins [[codox "0.6.4"]]
  :test-selectors {:default  #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
