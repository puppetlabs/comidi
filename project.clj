(defproject puppetlabs/comidi "0.1.1"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"

  :pedantic? :abort

  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.reader "0.8.9"]
                 [ring/ring-core "1.3.2"]
                 [bidi "1.18.9"]
                 [compojure "1.3.2"]
                 [prismatic/schema "0.4.0"]
                 [puppetlabs/kitchensink "1.1.0"]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]])
