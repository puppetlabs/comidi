(defproject puppetlabs/pl-bidi "0.1.0-SNAPSHOT"
  :description "Puppet Labs utility functions for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/pl-bidi"
  :dependencies [[org.clojure/clojure "1.6.0"]
                 [bidi "1.18.8-SNAPSHOT"]
                 [compojure "1.3.2"]
                 [prismatic/schema "0.2.2"]]

  :profiles {:dev {:dependencies [[spyscope "0.1.4" :exclusions [clj-time]]]
                   :injections [(require 'spyscope.core)]}})
