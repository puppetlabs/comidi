(def ks-version "1.1.0")
(def tk-version "1.1.1")

(defproject puppetlabs/comidi "0.1.4-SNAPSHOT"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.reader "0.8.9"]

                 [ring/ring-core "1.3.2"]
                 [clj-time "0.7.0"]
                 [commons-io "2.4"]
                 [commons-codec "1.9"]
                 [slingshot "0.12.2"]
                 [org.slf4j/slf4j-api "1.7.7"]

                 [bidi "1.18.9" :exclusions [org.clojure/clojurescript]]
                 [compojure "1.3.3"]
                 [prismatic/schema "0.4.0"]
                 [puppetlabs/kitchensink ~ks-version]
                 [puppetlabs/trapperkeeper-metrics "0.1.1-SNAPSHOT"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :profiles {:dev {:source-paths ["dev"]
                   :dependencies [[puppetlabs/trapperkeeper ~tk-version :classifier "test" :exclusions [org.clojure/tools.macro]]
                                  [puppetlabs/kitchensink ~ks-version :classifier "test" :exclusions [slingshot]]
                                  [puppetlabs/trapperkeeper-status "0.1.1"]
                                  [puppetlabs/trapperkeeper-webserver-jetty9 "1.3.1"]
                                  [puppetlabs/http-client "0.4.4"]]}}

  :aliases {"example" ["run" "-m" "example.comidi-metrics-web-app"]
            "example-data" ["run" "-m" "example.traffic-generator"]})

