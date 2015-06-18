(ns example.comidi-metrics-web-app
  (:require [clojure.tools.logging :as log]
            [puppetlabs.trapperkeeper.core :as tk]
            [puppetlabs.comidi :as comidi]
            [puppetlabs.trapperkeeper.services.status.status-core :as status]
            [puppetlabs.metrics.http :as http-metrics]
            [puppetlabs.metrics :as metrics])
  (:import (java.util.concurrent TimeUnit)))

;; TODO: it might be worth moving this example app out into its own repo at
;; some point, because eventually it will also demo our comidi->swagger
;; integration, etc.  Or we could put it into comidi itself, if we OSS this
;; metrics library.

(defn rand-sleep
  [min max]
  (Thread/sleep (+ min (rand-int max))))

(defn handle-foo
  [req]
  (log/info "Handling foo request")
  (rand-sleep 5 10)
  "foo!")

(defn handle-bar
  [bar]
  (log/info "Handling bar request, params:" bar)
  (rand-sleep 10 20)
  "bar!")

(defn baz-task1
  [timer]
  (metrics/time! timer
    (rand-sleep 5 10)))

(defn baz-task2
  [timer]
  (metrics/time! timer
    (rand-sleep 20 50)))

(defn handle-baz
  [app-metrics baz bam]
  (log/info "Handling baz request, params:" baz bam)
  (baz-task1 (:baz-task1-timer app-metrics))
  (if (= (rand-int 3) 0)
    (baz-task2 (:baz-task2-timer app-metrics)))
  "baz!")

(defn example-routes
  [path app-metrics]

  ;; NOTE: the HTTP metrics are automatically built up from your comidi routes.
  ;; The example traffic generator is also dynamic based on these routes.
  ;;
  ;; Try adding or removing some routes from this route tree and re-running
  ;; the app, and notice that the metrics will be updated accordingly!

  (comidi/context path
    (comidi/context "/v1"
      (comidi/routes
        (comidi/ANY ["/foo"] request
          (handle-foo request))
        (comidi/ANY ["/bar/" :bar] [bar]
          (handle-bar bar))
        (comidi/ANY ["/baz/" :baz "/bam/" :bam] [baz bam]
          (handle-baz app-metrics baz bam))))))

(defn build-handler
  [routes http-metrics]
  (-> (comidi/routes->handler routes)
    (http-metrics/wrap-with-request-metrics http-metrics)
    (comidi/wrap-with-route-metadata routes)))

(defn register-app-metrics
  [metrics-registry]
  (log/info "Registering app-specific metrics for Example Web Service")
  {:baz-task1-timer (.timer metrics-registry (metrics/host-metric-name "localhost" "baz-task1-timer"))
   :baz-task2-timer (.timer metrics-registry (metrics/host-metric-name "localhost" "baz-task2-timer"))})

(def status-version 1)

(defn create-status-callback-fn
  [http-metrics app-metrics]
  (letfn [(get-count [id] (.. (get app-metrics id) getCount))
          (get-mean [id] (->> (get app-metrics id) .getSnapshot .getMean
                           (.toMillis TimeUnit/NANOSECONDS)))]
    (fn [level]
      (let [level>= (partial status/compare-levels >= level)
            status (cond-> {}
                     ;; no extra status info to add for info level, just including
                     ;; this for the sake of example
                     (level>= :info) identity
                     (level>= :debug) (->
                                        (assoc-in [:debug :route-metrics]
                                          (http-metrics/request-summary http-metrics))
                                        (assoc-in [:debug :app-metrics]
                                          {:baz-task1 {:count (get-count :baz-task1-timer)
                                                       :mean (get-mean :baz-task1-timer)}
                                           :baz-task2 {:count (get-count :baz-task2-timer)
                                                       :mean (get-mean :baz-task2-timer)}})))]
        {:is-running :true
         :status status}))))

(tk/defservice example-web-service
  [[:WebroutingService add-ring-handler get-route]
   [:MetricsService get-metrics-registry]
   [:StatusService register-status]]
  (init [this context]
    (let [path (get-route this)
          metrics-registry (get-metrics-registry)
          app-metrics (register-app-metrics metrics-registry)
          routes (example-routes path app-metrics)
          route-metadata (comidi/route-metadata routes)
          http-metrics (http-metrics/initialize-http-metrics!
                         metrics-registry
                         "localhost"
                         route-metadata)
          handler (build-handler routes http-metrics)]

      (log/infof "Registering status callback for Example Web Service")
      (register-status
        "example-web-service"
        (status/get-artifact-version "puppetlabs" "trapperkeeper-metrics")
        status-version
        (create-status-callback-fn http-metrics app-metrics))
      (log/infof "Registering Example Web Service at '%s'" path)
      (add-ring-handler this handler))
    context))


(defn -main
  [& args]
  (tk/boot-with-cli-data {:config "./dev/example/example.conf"
                          :bootstrap-config "./dev/example/bootstrap.cfg"}))