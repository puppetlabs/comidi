(defproject puppetlabs/comidi "0.3.1"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.7.0"]
                 
                 ;; begin version conflict resolution dependencies
                 [clj-time "0.10.0"]
                 ;; end version conflict resolution dependencies

                 [bidi "1.23.1" :exclusions [org.clojure/clojurescript]]
                 [compojure "1.4.0"]
                 [prismatic/schema "1.0.4"]

                 [puppetlabs/kitchensink "1.1.0"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]])
