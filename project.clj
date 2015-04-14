(defproject puppetlabs/comidi "0.1.0-SNAPSHOT"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [org.clojure/tools.reader "0.8.9"]
                 [ring/ring-core "1.3.2"]
                 [bidi "1.18.9"]
                 [compojure "1.3.2"]
                 [prismatic/schema "0.4.0"]
                 [puppetlabs/kitchensink "1.1.0"]])
