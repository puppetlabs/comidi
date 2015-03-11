(ns puppetlabs.bidi
  (:require [bidi.ring :as bidi-ring]
            [bidi.bidi :as bidi]
            [clojure.zip :as zip]
            [compojure.core :as compojure]
            [compojure.response :as compojure-response]
            [ring.util.response :as ring-response]
            [schema.core :as schema])
  (:import (java.util.regex Pattern)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

;; NOTE: This function should be added to kitchensink soon; we
;; can remove it from here once that's in a release.
(defn zipper?
  "Checks to see if the object has zip/make-node metadata on it (confirming it
to be a zipper."
  [obj]
  (contains? (meta obj) :zip/make-node))

(defn pattern?
  [x]
  (instance? Pattern x))

(def Zipper
  (schema/pred zipper?))

(def http-methods
  #{:any :get :post :put :delete :head})

(def RequestMethod
  (schema/enum :any :get :post :put :delete :head))

(def RegexPathElement
  [(schema/one Pattern "regex") (schema/one schema/Keyword "variable")])

(def PathElement
  (schema/conditional
    string? schema/Str
    keyword? schema/Keyword
    vector? RegexPathElement))

(def RouteInfo
  {:path [PathElement]
   :request-method RequestMethod})

(def Handler
  (schema/conditional
    keyword? schema/Keyword
    fn? (schema/pred fn?)
    map? {RequestMethod (schema/recursive #'Handler)}))

(def RouteMetadata
  {:routes [RouteInfo]
   :handlers {Handler RouteInfo}})

(def BidiPattern
  (schema/conditional
    keyword? schema/Keyword
    string?  schema/Str
    sequential?  [PathElement]))

(def BidiRouteDestination
  (schema/conditional
    #(nil? (schema/check Handler %)) Handler
    :else [[(schema/one BidiPattern "pattern")
            (schema/one (schema/recursive #'BidiRouteDestination) "destination")]]))

(def BidiRoute
  [(schema/one BidiPattern "pattern")
   (schema/one BidiRouteDestination "destination")])


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private

(defmacro handler-fn*
  "Helper macro, used by the compojure-like macros (GET/POST/etc.) to generate
  a function that provides compojure's destructuring and rendering support."
  [bindings body]
  `(fn [request#]
     (compojure-response/render
       (compojure/let-request [~bindings request#] ~@body)
       request#)))

(defn route-with-method*
  "Helper function, used by the compojure-like macros (GET/POST/etc.) to generate
  a bidi route that includes a wrapped handler function."
  [method pattern bindings body]
  `[~pattern {~method (handler-fn* ~bindings ~body)}])

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private - route metadata computation

(schema/defn ^:always-validate
  update-route-info* :- RouteInfo
  "Helper function, used to maintain a RouteInfo data structure that represents
  the current path elements of a route as we traverse the Bidi route tree via
  zipper."
  [route-info :- RouteInfo
   pattern :- BidiPattern]
  (cond
    (contains? http-methods pattern)
    (assoc-in route-info [:request-method] pattern)

    (nil? (schema/check RegexPathElement pattern))
    (update-in route-info [:path] concat [pattern])

    (sequential? pattern)
    (if-let [next (first pattern)]
      (update-route-info*
        (update-in route-info [:path] conj next)
        (rest pattern))
      route-info)

    :else
    (update-in route-info [:path] conj pattern)))

(declare breadth-route-metadata*)

(schema/defn ^:always-validate
  depth-route-metadata* :- RouteMetadata
  "Helper function used to traverse branches of the Bidi route tree, depth-first."
  [route-meta :- RouteMetadata
   route-info :- RouteInfo
   loc :- Zipper]
  (let [[pattern matched] (zip/node loc)]
    (cond
      (map? matched)
      (depth-route-metadata*
        route-meta
        route-info
        (-> loc zip/down zip/right (zip/edit #(into [] %)) zip/up))

      (vector? matched)
      (breadth-route-metadata*
        route-meta
        (update-route-info* route-info pattern)
        (-> loc zip/down zip/right zip/down))

      :else
      (let [route-info (update-route-info* route-info pattern)]
        (-> route-meta
            (update-in [:routes] conj route-info)
            (assoc-in [:handlers matched] route-info))))))

(schema/defn ^:always-validate
  breadth-route-metadata* :- RouteMetadata
  "Helper function used to traverse branches of the Bidi route tree, breadth-first."
  [route-meta :- RouteMetadata
   route-info :- RouteInfo
   loc :- Zipper]
  (loop [route-meta route-meta
         loc    loc]
    (let [routes (depth-route-metadata* route-meta route-info loc)]
      (if-let [next (zip/right loc)]
        (recur routes next)
        routes))))

(schema/defn ^:always-validate
  route-metadata :- RouteMetadata
  "Traverses a Bidi route tree and returns route metadata, which includes a list
  of RouteInfo objects (one per route), plus a mechanism to look up the
  RouteInfo for a given handler."
  [routes :- BidiRoute]
  (let [route-info {:path   []
                    :request-method :any}
        loc        (-> [routes] zip/vector-zip zip/down)]
    (breadth-route-metadata* {:routes []
                              :handlers {}} route-info loc)))

(schema/defn ^:always-validate
  make-handler :- (schema/pred fn?)
  "Create a Ring handler from the route definition data  structure. (This code
  is largely borrowed from bidi core.)  Arguments:

  - route-meta: metadata about the routes; allows us to look up the route info
                by handler.  You can get this by calling `route-metadata`.
  - routes: the Bidi route tree
  - handler-fn: this fn will be called on all of the handlers found in the bidi
                route tree; it is expected to return a ring handler fn for that
                route.  If you are using the compojure-like macros in this
                namespace or have nested your ring handler functions in the bidi
                tree by other means, you can just pass `identity` here, or pass
                in some middleware fn to wrap around the nested ring handlers.
                The handlers will have access to the `RouteInfo` of the matching
                bidi route via the `:route-info` key in the request map."
  [route-meta :- RouteMetadata
   routes :- BidiRoute
   handler-fn :- (schema/pred fn?)]
  (assert routes "Cannot create a Ring handler with a nil Route(s) parameter")
  (let [compiled-routes (bidi/compile-route routes)]
    (fn [{:keys [uri path-info] :as req}]
      (let [path (or path-info uri)
            {:keys [handler route-params] :as match-context}
            (apply bidi/match-route compiled-routes path (apply concat (seq req)))]
        (when handler
          (let [req (-> req
                        (update-in [:params] merge route-params)
                        (update-in [:route-params] merge route-params)
                        (assoc-in [:route-info] (get-in route-meta
                                                         [:handlers handler])))]
            (bidi-ring/request
              (handler-fn handler)
              req
              (apply dissoc match-context :handler (keys req)))))))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public - core functions

(schema/defn ^:always-validate
  routes :- BidiRoute
  "Combines multiple bidi routes into a single data structure; this is largely
  just a convenience function for grouping several routes together as a single
  object that can be passed around."
  [& routes :- [BidiRoute]]
  ["" (vec routes)])

(schema/defn ^:always-validate
  context :- BidiRoute
  [url-prefix :- BidiPattern
   & routes :- [BidiRoute]]
  "Combines multiple bidi routes together into a single data structure, but nests
  them all under the given url-prefix.  This is similar to compojure's `context`
  macro, but does not accept a binding form.  You can still destructure variables
  by passing a bidi pattern for `url-prefix`, and the variables will be available
  to all nested routes."
  [url-prefix (vec routes)])

(schema/defn ^:always-validate
  routes->handler :- (schema/pred fn?)
  "Given a bidi route tree, converts into a ring request handler function.  You
  may pass an optional middleware function that will be wrapped around the
  request handling; the middleware fn will have access to the `RouteInfo` of the
  matching bidi route via the `:route-info` key in the request map."
  ([routes :- BidiRoute
    route-middleware-fn :- (schema/maybe (schema/pred fn?))]
   (let [route-meta (route-metadata routes)]
     (with-meta
       (make-handler route-meta
                     routes
                     route-middleware-fn)
       {:route-metadata route-meta})))
  ([routes]
   (routes->handler routes identity)))

(schema/defn ^:always-validate
  context-handler :- (schema/pred fn?)
  "Convenience function that effectively composes `context` and `routes->handler`."
  ([url-prefix :- BidiPattern
    & routes :- [BidiRoute]]
    (routes->handler
      (apply context url-prefix routes))))


;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public - compojure-like convenience macros

(defmacro ANY
  [pattern bindings & body]
  `[~pattern (handler-fn* ~bindings ~body)])

(defmacro GET
  [pattern bindings & body]
  (route-with-method* :get pattern bindings body))

(defmacro HEAD
  [pattern bindings & body]
  (route-with-method* :head pattern bindings body))

(defmacro PUT
  [pattern bindings & body]
  (route-with-method* :put pattern bindings body))

(defmacro POST
  [pattern bindings & body]
  (route-with-method* :post pattern bindings body))

(defmacro DELETE
  [pattern bindings & body]
  (route-with-method* :delete pattern bindings body))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Public - pre-built routes

(defn not-found
  [body]
  [[[#".*" :rest]] (fn [request]
                   (-> (compojure-response/render body request)
                       (ring-response/status 404)))])
