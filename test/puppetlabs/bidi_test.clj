(ns puppetlabs.bidi-test
  (require [clojure.test :refer :all]
           [puppetlabs.bidi :as pl-bidi]
           [schema.test :as schema-test]
           [puppetlabs.bidi :refer :all]
           [schema.core :as schema]
           [clojure.zip :as zip]))

(use-fixtures :once schema-test/validate-schemas)

(defn replace-regexes-for-equality-check
  [xs]
  (loop [loc (zip/vector-zip xs)]
    (if (zip/end? loc)
      (zip/root loc)
      (recur
        (let [node (zip/node loc)]
          (if (pattern? node)
            (zip/edit loc #(str "REGEX: " (.pattern %)))
            (zip/next loc)))))))

(deftest handler-schema-test
  (testing "handler schema"
    (is (nil? (schema/check Handler :foo)))
    (is (nil? (schema/check Handler (fn [] :foo))))
    (is (nil? (schema/check Handler {:get (fn [] :foo)})))
    (is (nil? (schema/check Handler {:post :foo})))))

(deftest pattern-schema-test
  (testing "pattern schema"
    (is (nil? (schema/check BidiPattern "/foo")))
    (is (nil? (schema/check BidiPattern :foo)))
    (is (nil? (schema/check BidiPattern ["/foo/" :foo "/foo"])))
    (is (nil? (schema/check BidiPattern ["/foo/" [#".*" :rest]])))))

(deftest destination-schema-test
  (testing "route destination schema"
    (is (nil? (schema/check BidiRouteDestination :foo)))
    (is (nil? (schema/check BidiRouteDestination (fn [] nil))))
    (is (nil? (schema/check BidiRouteDestination {:get (fn [] nil)})))
    (is (nil? (schema/check BidiRouteDestination {:get :my-handler})))
    (is (nil? (schema/check BidiRouteDestination [[["/foo/" :foo "/foo"] :foo]])))
    (is (not (nil? (schema/check BidiRouteDestination [["/foo/" :foo "/foo"] :foo]))))
    (is (nil? (schema/check BidiRouteDestination [[["/foo/" :foo]
                                                   :foo-handler]
                                                  [["/bar/" :bar]
                                                   {:get :bar-handler}]])))))

(deftest route-schema-test
  (testing "route schema"
    (is (nil? (schema/check BidiRoute [:foo :foo])))
    (is (nil? (schema/check BidiRoute ["/foo" [[:foo :foo]]])))
    (is (not (nil? (schema/check BidiRoute ["/foo" [:foo :foo]]))))
    (is (nil? (schema/check BidiRoute ["" [[["/foo/" :foo]
                                            :foo-handler]
                                           [["/bar/" :bar]
                                            {:get :bar-handler}]]])))))

(deftest update-route-info-test
  (let [orig-route-info {:path           []
                         :request-method :any}]
    (testing "HTTP verb keyword causes request-method to be updated"
      (doseq [verb [:get :post :put :delete :head]]
        (is (= {:path           []
                :request-method verb}
               (update-route-info* orig-route-info verb)))))
    (testing "string path elements get added to the path"
      (is (= {:path           ["/foo"]
              :request-method :any}
             (update-route-info* orig-route-info "/foo"))))
    (testing "keyword path elements get added to the path"
      (is (= {:path           [:foo]
              :request-method :any}
             (update-route-info* orig-route-info :foo))))
    (testing "vector path elements get flattened and added to the path"
      (is (= {:path           ["/foo/" :foo]
              :request-method :any}
             (update-route-info* orig-route-info ["/foo/" :foo]))))
    (testing "regex path element gets added to the path"
      (is (= {:path ["/foo/" ["REGEX: .*" :foo]]
              :request-method :any}
             (-> (update-route-info* orig-route-info ["/foo/" [#".*" :foo]])
                 (update-in [:path] replace-regexes-for-equality-check)))))))

(deftest route-metadata-test
  (testing "route metadata includes ordered list of routes and lookup by handler"
    (let [routes ["" [[["/foo/" :foo]
                       :foo-handler]
                      [["/bar/" :bar]
                       [["/baz" {:get :baz-handler}]
                        ["/bam" {:put :bam-handler}]
                        ["/bap" {:any :bap-handler}]]]
                      ["/buzz" {:post :buzz-handler}]]]
          expected-foo-meta {:path '("" "/foo/" :foo)
                             :request-method :any}
          expected-baz-meta {:path '("" "/bar/" :bar "/baz")
                             :request-method :get}
          expected-bam-meta {:path '("" "/bar/" :bar "/bam")
                             :request-method :put}
          expected-bap-meta {:path '("" "/bar/" :bar "/bap")
                             :request-method :any}
          expected-buzz-meta {:path '("" "/buzz")
                              :request-method :post}]
      (is (= (pl-bidi/route-metadata routes)
             {:routes [expected-foo-meta
                       expected-baz-meta
                       expected-bam-meta
                       expected-bap-meta
                       expected-buzz-meta]
              :handlers {:foo-handler expected-foo-meta
                         :baz-handler expected-baz-meta
                         :bam-handler expected-bam-meta
                         :bap-handler expected-bap-meta
                         :buzz-handler expected-buzz-meta}})))))

(deftest routes-test
  (is (= ["" [["/foo" :foo-handler]
              [["/bar/" :bar] :bar-handler]]]
         (routes ["/foo" :foo-handler]
                 [["/bar/" :bar] :bar-handler]))))

(deftest context-test
  (testing "simple context"
    (is (= ["/foo" [["/bar" :bar-handler]
                    [["/baz" :baz] :baz-handler]]]
           (context "/foo"
                    ["/bar" :bar-handler]
                    [["/baz" :baz] :baz-handler]))))
  (testing "context with variable"
    (is (= [["/foo" :foo] [["/bar" :bar-handler]
                           [["/baz" :baz] :baz-handler]]]
           (context ["/foo" :foo]
                    ["/bar" :bar-handler]
                    [["/baz" :baz] :baz-handler])))))

(deftest routes->handler-test
  (testing "routes are matched against a request properly, with route params"
    (let [handler (routes->handler ["/foo"
                                    [[""
                                      [["/bar"
                                        (fn [req] :bar)]
                                       [["/baz/" :baz]
                                        (fn [req]
                                          {:endpoint :baz
                                           :route-params (:route-params req)})]]]]])]
      (is (= :bar (handler {:uri "/foo/bar"})))
      (is (= {:endpoint :baz
              :route-params {:baz "howdy"}}
             (handler {:uri "/foo/baz/howdy"})))))
  (testing "request-methods are honored"
    (let [handler (routes->handler ["/foo" {:get (fn [req] :foo)}])]
      (is (nil? (handler {:uri "/foo"})))
      (is (= :foo (handler {:uri "/foo" :request-method :get})))))
  (testing "contexts can bind route variables"
    (let [handler (routes->handler
                    (context ["/foo/" :foo]
                             [["/bar/" :bar]
                              (fn [req] (:route-params req))]))]
      (is (= {:foo "hi"
              :bar "there"}
             (handler {:uri "/foo/hi/bar/there"})))))
  (testing "route metadata is added to fn metadata"
    (let [foo-handler (fn [req] :foo)
          handler (routes->handler ["/foo" {:get foo-handler}])]
      (let [route-meta (:route-metadata (meta handler))]
        (is (= {:routes [{:path           ["/foo"]
                          :request-method :get}]
                :handlers {foo-handler {:path           ["/foo"]
                                        :request-method :get}}}
               route-meta))))))

(deftest routes->handler-middleware-test
  (let [handler (routes->handler
                  (context ["/foo/" :foo]
                           [["/bar/" :bar]
                            (fn [req] (:route-params req))])
                  (fn [f]
                    (fn [req]
                      {:result (f req)
                       :route-info (:route-info req)})))]
    (is (= {:result {:foo "hi"
                     :bar "there"}
            :route-info {:path ["/foo/" :foo "/bar/" :bar]
                         :request-method :any}}
           (handler {:uri "/foo/hi/bar/there"})))))

(deftest context-handler-test
  (let [handler (context-handler ["/foo/" :foo]
                                 [["/bar/" :bar]
                                  (fn [req] (:route-params req))])]
    (is (= {:foo "hi"
            :bar "there"}
           (handler {:uri "/foo/hi/bar/there"})))))


(deftest compojure-macros-test
  (let [routes (context ["/foo/" :foo]
                        (ANY ["/any/" :any] [foo any]
                             (str "foo: " foo " any: " any))
                        (GET ["/get/" :get] [foo get]
                             (fn [req] {:foo foo
                                        :get get}))
                        (HEAD ["/head/" :head] [foo head]
                              {:foo foo
                               :head head})
                        (PUT "/put" [foo]
                             {:status 500
                              :body foo})
                        (POST ["/post/" :post] [post]
                              post)
                        (DELETE ["/delete/" :delete] [foo delete]
                                (atom {:foo foo
                                       :delete delete})))
        handler (routes->handler routes)]
    (is (nil? (handler {:uri "/foo/hi/get/there" :request-method :post})))
    (is (nil? (handler {:uri "/foo/hi/head/there" :request-method :get})))
    (is (nil? (handler {:uri "/foo/hi/put" :request-method :get})))
    (is (nil? (handler {:uri "/foo/hi/post/there" :request-method :get})))
    (is (nil? (handler {:uri "/foo/hi/delete/there" :request-method :get})))

    (is (= "foo: hi any: there" (:body (handler {:uri "/foo/hi/any/there"}))))
    (is (= {:foo "hi"
            :get "there"}
           (select-keys
             (handler {:uri "/foo/hi/get/there" :request-method :get})
             [:foo :get])))
    (is (= {:foo "hi"
            :head "there"}
           (select-keys
             (handler {:uri "/foo/hi/head/there" :request-method :head})
             [:foo :head])))
    (is (= {:status 500
            :body "hi"}
           (select-keys
             (handler {:uri "/foo/hi/put" :request-method :put})
             [:status :body])))
    (is (= {:status 200
            :body "there"}
           (select-keys
             (handler {:uri "/foo/hi/post/there" :request-method :post})
             [:status :body])))
    (is (= {:status 200
            :foo "hi"
            :delete "there"}
           (select-keys
             (handler {:uri "/foo/hi/delete/there" :request-method :delete})
             [:status :foo :delete])))))

(deftest not-found-test
  (testing "root not-found handler"
    (let [handler (routes->handler (not-found "nobody's home, yo"))]
      (is (= {:status 404
              :body   "nobody's home, yo"}
             (select-keys
               (handler {:uri "/hi/there"})
               [:body :status])))))
  (testing "nested not-found handler"
    (let [handler (routes->handler
                    (routes
                      ["/bar" [["" (fn [req] :bar)]
                               (not-found "nothing else under bar!")]]
                      (not-found "nothing else under root!")))]
      (is (= :bar (handler {:uri "/bar"})))
      (is (= {:status 404
              :body "nothing else under bar!"}
             (select-keys
               (handler {:uri "/bar/baz"})
               [:status :body])))
      (is (= {:status 404
              :body "nothing else under root!"}
             (select-keys
               (handler {:uri "/yo/mang"})
               [:status :body]))))))

(deftest regex-test
  (let [handler (routes->handler
                  ["/foo" [[["/boo/" [#".*" :rest]]
                             (fn [req] (:rest (:route-params req)))]]])]
    (is (= "hi/there"
           (handler {:uri "/foo/boo/hi/there"})))))




