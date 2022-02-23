(defproject puppetlabs/comidi "1.0.0"
  :description "Puppet Labs utility functions and compojure-like wrappers for use with the bidi web routing library"
  :url "https://github.com/puppetlabs/comidi"

  :pedantic? :abort

  :min-lein-version "2.9.1"

  :parent-project {:coords [puppetlabs/clj-parent "4.9.4"]
                   :inherit [:managed-dependencies]}

  :dependencies [[org.clojure/clojure]

                 [bidi]
                 [compojure]
                 [prismatic/schema]
                 [puppetlabs/kitchensink]]

  :deploy-repositories [["releases" {:url "https://clojars.org/repo"
                                     :username :env/clojars_jenkins_username
                                     :password :env/clojars_jenkins_password
                                     :sign-releases false}]]

  :plugins  [[lein-parent "0.3.7"]])
