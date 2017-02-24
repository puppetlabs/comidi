(defproject puppetlabs/comidi "0.3.2-SNAPSHOT"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.8.0"]

                 [bidi "2.0.12" :exclusions [org.clojure/clojurescript]]
                 [compojure "1.5.0"]
                 [prismatic/schema "1.1.3"]

                 [puppetlabs/kitchensink "2.1.0"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]])
