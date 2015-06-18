(ns example.traffic-generator
  (:require [puppetlabs.http.client.sync :as http-client]
            [puppetlabs.comidi :as comidi]
            [example.comidi-metrics-web-app :as example-app]
            [clojure.string :as str]
            [cheshire.core :as json]
            [clojure.pprint :as pprint]))

(defn http-get
  [uri]
  (http-client/get uri {:as :text}))

(defn random-string
  []
  (rand-nth ["the" "quick" "brown" "fox" "jumps" "over" "the" "lazy" "dog"]))

(defn path-segment-to-url-segment
  [path-segment]
  (cond
    (string? path-segment) (str/replace path-segment #"^/?([^/]+)/?" "$1")
    (keyword? path-segment) (random-string)
    :else (throw (IllegalStateException. (str "Unrecognized path segment:" path-segment)))))

(defn path-to-url
  [path]
  (str/join "/" (map path-segment-to-url-segment (filter (some-fn keyword? not-empty)
                                                   path))))

(defn generate-random-uri
  [route-metadata]
  (let [route (rand-nth (:routes route-metadata))]
    (str "http://localhost:8080/" (path-to-url (:path route)))))

(defn generate-random-traffic
  [num-requests]

  ;; NOTE: this is a little crude.  We are hard-coding the URL prefix rather than
  ;; getting it from the web routing config, and we are also assuming a fairly
  ;; homogenous route structure where all of the routes will accept GET requests
  ;; with no query parameters.
  ;;
  ;; That said, there is some promise here for being able to do generative testing
  ;; by writing generators based on comidi route metadata in the future!  Especially
  ;; when we add some kind of prismatic-schema -> ring-swagger functionality to
  ;; comidi; then we might be able to use the schema data to do some fairly
  ;; sophisticated things with test generators.

  (let [route-metadata (comidi/route-metadata
                         (example-app/example-routes "/example" {}))]
    (dotimes [i num-requests]
      (let [random-uri (generate-random-uri route-metadata)]
        (http-get random-uri)))))

(defn -main
  [& args]
  (println "Generating 100 random requests.")
  (generate-random-traffic 100)
  (let [status-url "http://localhost:8080/status/v1/services?level=debug"]
    (println "Requesting status info from" status-url)
    (let [resp (http-get status-url)]
      (pprint/pprint (json/parse-string (:body resp))))))