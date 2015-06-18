(ns example.comidi-metrics-web-app-test
  (:require [clojure.test :refer :all]
            [puppetlabs.trapperkeeper.bootstrap :as tk-bootstrap]
            [puppetlabs.trapperkeeper.config :as tk-config]
            [puppetlabs.trapperkeeper.testutils.bootstrap :refer [with-app-with-config]]
            [puppetlabs.http.client.sync :as http-client]
            [cheshire.core :as json]
            [example.traffic-generator :as traffic-generator]
            [puppetlabs.trapperkeeper.testutils.logging :as logutils]))

(defn get-services-from-bootstrap
  []
  (tk-bootstrap/parse-bootstrap-config! "./dev/example/bootstrap.cfg"))

(defn get-config-from-file
  []
  (tk-config/load-config "./dev/example/example.conf"))

(defn http-get
  [uri]
  (http-client/get uri {:as :text}))

(defn parse
  [resp]
  (-> resp
    :body
    json/parse-string))

(deftest test-example-web-service
  (logutils/with-test-logging
    (with-app-with-config app
      (get-services-from-bootstrap)
      (assoc-in (get-config-from-file)
        [:global :logging-config]
        "./dev-resources/logback-test.xml")

      (traffic-generator/generate-random-traffic 49)

      (testing "example app endpoints are accessible"
        (let [resp (http-get "http://localhost:8080/example/v1/foo")]
         (is (= 200 (:status resp)))))
      (testing "status endpoint is accessible"
        (let [resp (http-get "http://localhost:8080/status/v1/services")]
          (is (= 200 (:status resp)))
          (let [body (parse resp)]
            (is (contains? body "example-web-service"))
            (is (empty? (get-in body ["example-web-service" "status"]))))))
      (testing "at debug level, status endpoint returns metrics data"
        (let [resp (http-get "http://localhost:8080/status/v1/services?level=debug")]
          (is (= 200 (:status resp)))
          (let [body (parse resp)]
            (is (contains? body "example-web-service"))
            (let [route-metrics (get-in body ["example-web-service" "status" "debug" "route-metrics" "routes"])]
              (is (= #{"total" "other" "example-v1-foo"
                       "example-v1-bar-:bar" "example-v1-baz-:baz-bam-:bam"}
                    (->> route-metrics keys set)))
              (is (= 50 (get-in route-metrics ["total" "count"])))
              (is (= 0 (get-in route-metrics ["other" "count"])))
              (is (= 50 (apply + (map #(get-in route-metrics [% "count"])
                                    ["example-v1-foo" "example-v1-bar-:bar"
                                     "example-v1-baz-:baz-bam-:bam"]))))
              (if (= 0 (get-in route-metrics ["example-v1-foo" "count"]))
                (is (= 0 (get-in route-metrics ["example-v1-foo" "mean"])))
                (is (<= 5 (get-in route-metrics ["example-v1-foo" "mean"]))))
              (if (= 0 (get-in route-metrics ["example-v1-bar-:bar" "count"]))
                (is (= 0 (get-in route-metrics ["example-v1-bar-:bar" "mean"])))
                (is (<= 10 (get-in route-metrics ["example-v1-bar-:bar" "mean"])))))
            (let [app-metrics (get-in body ["example-web-service" "status" "debug" "app-metrics"])]
              (is (= #{"baz-task1" "baz-task2"}
                     (->> app-metrics keys set)))
              (if (= 0 (get-in app-metrics ["baz-task1" "count"]))
                (is (= 0 (get-in app-metrics ["baz-task1" "mean"])))
                (is (<= 5 (get-in app-metrics ["baz-task1" "mean"]))))
              (if (= 0 (get-in app-metrics ["baz-task2" "count"]))
                (is (= 0 (get-in app-metrics ["baz-task2" "mean"])))
                (is (<= 20 (get-in app-metrics ["baz-task2" "mean"])))))))))))
