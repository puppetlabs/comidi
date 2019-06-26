(ns puppetlabs.comidi-test
  (:require [clojure.test :refer :all]
            [puppetlabs.comidi :as comidi :refer :all]
            [schema.test :as schema-test]
            [schema.core :as schema]
            [clojure.zip :as zip]
            [bidi.bidi :as bidi]))

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

(deftest update-route-info-test
  (let [orig-route-info {:path           []
                         :request-method :any}]
    (testing "HTTP verb keyword causes request-method to be updated"
      (doseq [verb [:get :post :put :delete :head]]
        (is (= {:path           []
                :request-method verb}
               (update-route-info* orig-route-info verb)))))
    (testing "string path gets added to the path"
      (is (= {:path           ["/foo"]
              :request-method :any}
             (update-route-info* orig-route-info "/foo"))))
    (testing "string path elements get added to the path"
      (is (= {:path           ["/foo"]
              :request-method :any}
             (update-route-info* orig-route-info ["/foo"]))))
    (testing "keyword path elements get added to the path"
      (is (= {:path           [:foo]
              :request-method :any}
             (update-route-info* orig-route-info [:foo]))))
    (testing "vector path elements get flattened and added to the path"
      (is (= {:path           ["/foo/" :foo]
              :request-method :any}
             (update-route-info* orig-route-info ["/foo/" :foo]))))
    (testing "boolean true is handled specially"
      (is (= {:path           ["*"]
              :request-method :any}
             (update-route-info* orig-route-info true))))
    (testing "boolean false is handled specially"
      (is (= {:path           ["!"]
              :request-method :any}
             (update-route-info* orig-route-info false))))
    (testing "regex path element gets added to the path"
      (is (= {:path ["/foo/" ["REGEX: .*" :foo]]
              :request-method :any}
             (-> (update-route-info* orig-route-info ["/foo/" [#".*" :foo]])
                 (update-in [:path] replace-regexes-for-equality-check)))))))

(deftest route-metadata-test
  (testing "route metadata includes ordered list of routes and lookup by handler"
    (let [routes ["" [[["/foo/" :foo] :foo-handler]
                      [["/bar/" :bar]
                       [["/baz" {:get :baz-handler}]
                        ["/bam" {:put :bam-handler}]
                        ["/boop" :boop-handler]
                        ["/bap" {:options :bap-handler}]]]
                      ["/buzz" {:post :buzz-handler}]
                      [true {:get :true-handler}]]]
          expected-foo-meta {:path           ["" "/foo/" :foo]
                             :route-id           "foo-:foo"
                             :request-method :any}
          expected-baz-meta {:path           ["" "/bar/" :bar "/baz"]
                             :route-id           "bar-:bar-baz"
                             :request-method :get}
          expected-bam-meta {:path           ["" "/bar/" :bar "/bam"]
                             :route-id           "bar-:bar-bam"
                             :request-method :put}
          expected-boop-meta {:path           ["" "/bar/" :bar "/boop"]
                              :route-id       "bar-:bar-boop"
                              :request-method :any}
          expected-bap-meta {:path           ["" "/bar/" :bar "/bap"]
                             :route-id           "bar-:bar-bap"
                             :request-method :options}
          expected-buzz-meta {:path           ["" "/buzz"]
                              :route-id           "buzz"
                              :request-method :post}
          expected-true-meta {:path           ["" "*"]
                              :route-id       "*"
                              :request-method :get}]
      (is (= (comidi/route-metadata* routes)
             {:routes   [expected-foo-meta
                         expected-baz-meta
                         expected-bam-meta
                         expected-boop-meta
                         expected-bap-meta
                         expected-buzz-meta
                         expected-true-meta]
              :handlers {:foo-handler  expected-foo-meta
                         :baz-handler  expected-baz-meta
                         :bam-handler  expected-bam-meta
                         :boop-handler expected-boop-meta
                         :bap-handler  expected-bap-meta
                         :buzz-handler expected-buzz-meta
                         :true-handler expected-true-meta}})))))

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
                                          {:endpoint     :baz
                                           :route-params (:route-params req)})]
                                       [true
                                        (fn [req] :true)]]]]])]
      (is (= :true (handler {:uri "/foo/something/else"})))
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
             (handler {:uri "/foo/hi/bar/there"}))))))

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

(deftest route-names-test
  (let [test-routes (routes
                     ; Bidi Pattern: Path
                     (GET "/foo" request
                          "foo!")
                     (GET ["/foo/something/"] request
                          "foo something!")
                     ; Bidi Pattern: [ PatternSegment+ ]
                     (POST ["/bar"] request
                           "bar!")
                     (POST ["/bar" "bie"] request
                           "barbie!")
                     (PUT ["/baz" [#".*" :rest]] request
                          "baz!")
                     (ANY ["/bam/" [#"(?:bip|bap)" :rest]] request
                          "bam!")
                     (HEAD ["/bang/" [#".*" :rest] "/pow/" :pow] request
                           "bang!")
                     ; Bidi Pattern: false
                     (GET false request
                          "catch none!")
                     (GET "/false" request
                          "omg why would you do this?")
                     (GET ["/is" :false] request
                          "it hurts so bad")
                     ; Bidi Pattern: true
                     (GET true request
                          "catch all!")
                     (GET "/true" request
                          "omg why would you do this?")
                     (GET ["/is" :true] request
                          "it hurts so bad"))
        route-meta (route-metadata test-routes)
        route-ids (map :route-id (:routes route-meta))]
    (is (= #{"foo"
             "foo-something"
             "bar"
             "bar-bie"
             "baz-/*/"
             "bam-/bip_bap/"
             "bang-/*/-pow-:pow"
             "*"
             "true"
             "is-:true"
             "!"
             "false"
             "is-:false"}
           (set route-ids)))))

(deftest wrap-with-route-metadata-test
  (let [test-routes (routes
                      (ANY ["/foo/" :foo] request
                        "foo!")
                      (GET ["/bar/" :bar] request
                          "bar!")
                      (GET "/false" request "falsefalse!")
                      (GET false request "truefalse!")
                      (GET "/true" request "falsetrue!")
                      (GET true request "truetrue!"))
        route-meta  (route-metadata test-routes)
        test-atom   (atom {})
        test-middleware (fn [f]
                          (fn [req]
                            (reset! test-atom (select-keys req [:route-info :route-metadata]))
                            (f req)))
        handler (-> (routes->handler test-routes)
                    test-middleware
                    (wrap-with-route-metadata test-routes))]
    (handler {:uri "/foo/something"})
    (is (= (-> test-atom deref :route-info :route-id) "foo-:foo"))
    (is (= (-> test-atom deref :route-metadata) route-meta))

    (handler {:uri "/bar/something" :request-method :get})
    (is (= (-> test-atom deref :route-info :route-id) "bar-:bar"))
    (is (= (-> test-atom deref :route-metadata) route-meta))

    (handler {:uri "/false" :request-method :get})
    (is (= (-> test-atom deref :route-info :route-id) "false"))
    (is (= (-> test-atom deref :route-metadata) route-meta))

    (handler {:uri "/true" :request-method :get})
    (is (= (-> test-atom deref :route-info :route-id) "true"))
    (is (= (-> test-atom deref :route-metadata) route-meta))

    (handler {:uri "/wat" :request-method :get})
    (is (= (-> test-atom deref :route-info :route-id) "*"))
    (is (= (-> test-atom deref :route-metadata) route-meta))))

(deftest route-handler-uses-existing-match-context-test
  (testing "Middleware can provide match-context for comidi handler"
    (let [routes (routes
                   (GET ["/foo-:foo"] request
                     "foo!"))
          fake-match-context {:handler (fn [req] (-> req :route-params :bar))
                              :route-params {:bar "bar!"}}
          wrap-with-fake-match-context (fn [app]
                                         (fn [req]
                                           (let [req (assoc req :match-context fake-match-context)]
                                             (app req))))
          handler (-> routes
                      routes->handler
                      wrap-with-fake-match-context)]
      (is (= "bar!" (handler {:uri "/bunk"}))))))

(deftest wrap-leaves-with-middleware-test
  (let [inner-middleware (fn [handler]
                           (fn [request]
                             (update-in (handler request) [:body] #(str "inner-" %))))
        bb-wrapper-middleware (fn [handler]
                                (fn [request]
                                  (update-in (handler request) [:body] #(str "bb-wrapper-" %))))
        outer-middleware (fn [handler]
                           (fn [request]
                             (update-in (handler request) [:body] #(str "outer-" %))))
        aa-route (GET "/aa" request "aa!")
        bb-route (ANY "/bb" request "bb!")
        cc-route (ANY "/cc" request "cc!")
        dd-route (DELETE "/dd" request "dd!")
        ee-route (GET "/ee" request "ee!")
        ff-route (ANY "/ff" request "ff!")
        gh-route (ANY (bidi/alts "/gg" "/hh") request "gg-or-hh!")
        left-routes (context "/left" aa-route bb-route)
        middle-routes (context "/middle" cc-route dd-route)
        right-routes (context "/right" ee-route ff-route)
        alternate-routes ["/alts" [gh-route]]
        handler (-> (routes left-routes middle-routes right-routes alternate-routes) routes->handler)]
    (testing "Routes without middleware applied"
      (is (= (:body (handler {:uri "/left/aa" :request-method :get})) "aa!"))
      (is (= (:body (handler {:uri "/left/bb" :request-method :post})) "bb!"))
      (is (= (:body (handler {:uri "/middle/cc" :request-method :get})) "cc!"))
      (is (= (:body (handler {:uri "/middle/dd" :request-method :delete})) "dd!"))
      (is (= (:body (handler {:uri "/right/ee" :request-method :get})) "ee!"))
      (is (= (:body (handler {:uri "/right/ff" :request-method :delete})) "ff!"))
      (is (= (:body (handler {:uri "/alts/gg" :request-method :put})) "gg-or-hh!"))
      (is (= (:body (handler {:uri "/alts/hh" :request-method :post})) "gg-or-hh!"))
      (is (= (:body (handler {:uri "/alts/ii" :request-method :post})) nil)))
    (testing "Routes but now with middleware applied"
      (let [wrapped-bb-route (-> bb-route (wrap-routes bb-wrapper-middleware))
            left-routes (-> (context "/left" aa-route wrapped-bb-route)
                            (wrap-routes inner-middleware)
                            (wrap-routes outer-middleware))
            right-routes (-> right-routes (wrap-routes outer-middleware))
            alternate-routes (-> alternate-routes (wrap-routes inner-middleware) (wrap-routes outer-middleware))
            handler (-> (routes left-routes middle-routes right-routes alternate-routes) routes->handler)]
        (is (= (:body (handler {:uri "/left/aa" :request-method :get})) "outer-inner-aa!"))
        (is (= (:body (handler {:uri "/left/bb" :request-method :post})) "outer-inner-bb-wrapper-bb!"))
        (is (= (:body (handler {:uri "/middle/cc" :request-method :get})) "cc!"))
        (is (= (:body (handler {:uri "/middle/dd" :request-method :delete})) "dd!"))
        (is (= (:body (handler {:uri "/right/ee" :request-method :get})) "outer-ee!"))
        (is (= (:body (handler {:uri "/right/ff" :request-method :delete})) "outer-ff!"))
        (is (= (:body (handler {:uri "/alts/gg" :request-method :delete})) "outer-inner-gg-or-hh!"))
        (is (= (:body (handler {:uri "/alts/hh" :request-method :delete})) "outer-inner-gg-or-hh!"))))))

(deftest destructuring-test
  (testing "Compojure-style destructuring works as expected"
    (let [test-request {:uri    "/foo"
                        :params {:aa "aa"
                                 :bb "bb"}}]
      (testing "A single binding outside a vector is the whole request"
        ((routes->handler (ANY "/foo" request
                               (is (= test-request (select-keys request [:uri :params])))
                               "foo!")) test-request))
      (testing "A vector binding called 'request' tries to bind to non-existent query param of the same name"
        ((routes->handler (ANY "/foo" [request]
                               (is (nil? request))
                               "foo!")) test-request))
      (testing "A vector binding with two params binds as expected"
        ((routes->handler (ANY "/foo" [aa bb]
                               (is (= aa "aa"))
                               (is (= bb "bb"))
                               "foo!")) test-request))
      (testing "A vector binding with two valid params, one invalid param and an ':as request' segment binds as expected"
        ((routes->handler (ANY "/foo" [aa bb cc :as request]
                               (is (= aa "aa"))
                               (is (= bb "bb"))
                               (is (nil? cc))
                               (is (= test-request (select-keys request [:uri :params])))
                               "foo!")) test-request)))))
