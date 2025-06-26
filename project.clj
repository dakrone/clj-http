(defproject clj-http "3.13.2-SNAPSHOT"
  :description "A Clojure HTTP library wrapping the Apache HttpComponents client."
  :url "https://github.com/dakrone/clj-http/"
  :license {:name "The MIT License"
            :url "http://opensource.org/licenses/mit-license.php"
            :distribution :repo}
  :global-vars {*warn-on-reflection* false}
  :min-lein-version "2.0.0"
  :dependencies [[org.apache.httpcomponents/httpcore "4.4.16"]
                 [org.apache.httpcomponents/httpclient "4.5.14"]
                 [org.apache.httpcomponents/httpclient-cache "4.5.14"]
                 [org.apache.httpcomponents/httpasyncclient "4.1.5"]
                 [org.apache.httpcomponents/httpmime "4.5.14"]
                 [commons-codec "1.16.1"]
                 [commons-io "2.16.1"]
                 [slingshot "0.12.2"]
                 [potemkin "0.4.7"]]
  :resource-paths ["resources"]
  :profiles {:dev {:dependencies [;; optional deps
                                  [cheshire "5.13.0"]
                                  [crouton "0.1.2" :exclusions [[org.jsoup/jsoup]]]
                                  [org.jsoup/jsoup "1.17.2"]
                                  [org.clojure/tools.reader "1.4.1"]
                                  [com.cognitect/transit-clj "1.0.333"]
                                  [ring/ring-codec "1.2.0"]
                                  ;; other (testing) deps
                                  [org.clojure/clojure "1.12.1"]
                                  [org.clojure/tools.logging "1.3.0"]
                                  [ring/ring-jetty-adapter "1.12.1"]
                                  [ring/ring-devel "1.12.1"]
                                  [javax.servlet/javax.servlet-api "4.0.1"]
                                  ;; caching example deps
                                  [org.clojure/core.cache "1.1.234"]
                                  ;; logging
                                  [org.apache.logging.log4j/log4j-api "2.23.1"]
                                  [org.apache.logging.log4j/log4j-core "2.23.1"]
                                  [org.apache.logging.log4j/log4j-1.2-api "2.23.1"]
                                  [org.apache.logging.log4j/log4j-slf4j2-impl "2.23.1"]]
                   :plugins [[lein-ancient "0.7.0"]
                             [jonase/eastwood "0.2.5"]
                             [lein-kibit "0.1.5"]
                             [lein-nvd "0.5.2"]]}
             :1.8 {:dependencies [[org.clojure/clojure "1.8.0"]]}
             :1.9 {:dependencies [[org.clojure/clojure "1.9.0"]]}
             :1.10 {:dependencies [[org.clojure/clojure "1.10.3"]]}
             :1.11 {:dependencies [[org.clojure/clojure "1.11.4"]]}}
  :aliases {"all" ["with-profile" "dev,1.8:dev,1.9:dev,1.10:dev,1.11:dev"]}
  :plugins [[codox "0.6.4"]]
  :test-selectors {:default #(not (:integration %))
                   :integration :integration
                   :all (constantly true)})
