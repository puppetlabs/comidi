(ns puppetlabs.comidi
  (:require [bidi.ring :as bidi-ring]
            [bidi.bidi :as bidi]
            [clojure.zip :as zip]
            [compojure.core :as compojure]
            [compojure.response :as compojure-response]
            [ring.util.response :as ring-response]
            [schema.core :as schema]
            [puppetlabs.kitchensink.core :as ks]
            [clojure.string :as str])
  (:import (java.util.regex Pattern)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Schemas

(defn pattern?
  [x]
  (instance? Pattern x))

(def Zipper
  (schema/pred ks/zipper?))

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

(def RouteInfoWithId
  (merge RouteInfo
         {:route-id schema/Str}))

(def Handler
  (schema/conditional
    keyword? schema/Keyword
    fn? (schema/pred fn?)
    map? {RequestMethod (schema/recursive #'Handler)}))

(def RouteMetadata
  {:routes [RouteInfoWithId]
   :handlers {Handler RouteInfoWithId}})

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
;;; Private - route id computation

(defn slashes->dashes
  "Convert all forward slashes to hyphens"
  [s]
  (str/replace s #"\/" "-"))

(defn remove-leading-and-trailing-dashes
  [s]
  (-> s
      (str/replace #"^-" "")
      (str/replace #"-$" "")))

(defn special-chars->underscores
  "Convert all non-alpha chars except * and - to underscores"
  [s]
  (str/replace s #"[^\w\*\-]" "_"))

(defn collapse-consecutive-underscores
  [s]
  (str/replace s #"_+" "_"))

(defn remove-leading-and-trailing-underscores
  [s]
  (-> s
      (str/replace #"^_" "")
      (str/replace #"_$" "")))

(defn add-regex-symbols
  "Wrap a regex pattern with forward slashes to make it easier to recognize as a regex"
  [s]
  (str "/" s "/"))

(schema/defn ^:always-validate
  path-element->route-id-element :- schema/Str
  "Given a String path element from comidi route metadata, convert it into a string
  suitable for use in building a route id string."
  [path-element :- schema/Str]
  (-> path-element
      slashes->dashes
      remove-leading-and-trailing-dashes
      special-chars->underscores
      collapse-consecutive-underscores
      remove-leading-and-trailing-underscores))

(schema/defn ^:always-validate
  regex-path-element->route-id-element :- schema/Str
  "Given a Regex path element from comidi route metadata, convert it into a string
  suitable for use in building a route id string."
  [path-element :- RegexPathElement]
  (-> path-element
      first
      str
      path-element->route-id-element
      add-regex-symbols))

(schema/defn ^:always-validate
  route-path-element->route-id-element :- schema/Str
  "Given a route path element from comidi route metadata, convert it into a string
  suitable for use in building a route id string.  This function is mostly
  responsible for determining the type of the path element and dispatching to
  the appropriate function."
  [path-element :- PathElement]
  (cond
    (string? path-element)
    (path-element->route-id-element path-element)

    (keyword? path-element)
    (pr-str path-element)

    (nil? (schema/check RegexPathElement path-element))
    (regex-path-element->route-id-element path-element)

    :else
    (throw (IllegalStateException. (str "Unrecognized path element: " path-element)))))

(schema/defn ^:always-validate
  route-path->route-id :- schema/Str
  "Given a route path (from comidi route-metadata), build a route-id string for
  the route.  This route-id can be used as a unique identifier for a route."
  [route-path :- BidiPattern]
  (->> route-path
       (map route-path-element->route-id-element)
       (filter #(not (empty? %)))
       (str/join "-")))

(schema/defn ^:always-validate
  add-route-name :- RouteInfoWithId
  "Given a RouteInfo, compute a route-id and return a RouteInfoWithId."
  [route-info :- RouteInfo]
  (assoc route-info :route-id (route-path->route-id (:path route-info))))

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
      (let [route-info (-> (update-route-info* route-info pattern)
                           add-route-name)]
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
  route-metadata* :- RouteMetadata
  "Traverses a Bidi route tree and returns route metadata, which includes a list
  of RouteInfo objects (one per route), plus a mechanism to look up the
  RouteInfo for a given handler."
  [routes :- BidiRoute]
  (let [route-info {:path           []
                    :request-method :any}
        loc (-> [routes] zip/vector-zip zip/down)]
    (breadth-route-metadata* {:routes   []
                              :handlers {}} route-info loc)))

(def memoized-route-metadata*
  (memoize route-metadata*))

(defn make-handler
  "Create a Ring handler from the route definition data
  structure. Matches a handler from the uri in the request, and invokes
  it with the request as a parameter. (This code is largely copied from the
  bidi upstream, but we add support for inserting the match-context via
  middleware.)"
  ([route handler-fn]
   (fn [{:keys [uri path-info] :as req}]
     (let [path (or path-info uri)
           {:keys [handler route-params] :as match-context}
           (or (:match-context req)
               (apply bidi/match-route route path (apply concat (seq req))))]
       (when handler
         (bidi-ring/request
           (handler-fn handler)
           (-> req
               (update-in [:params] merge route-params)
               (update-in [:route-params] merge route-params))
           (apply dissoc match-context :handler (keys req))
           )))))
  ([route] (make-handler route identity)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;; Private - helpers for compojure-like syntax

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

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;; Public - core functions

(schema/defn ^:always-validate
  route-metadata :- RouteMetadata
  "Build up a map of metadata describing the routes.  This metadata map can be
  used for introspecting the routes after building the handler, and can also
  be used with the `wrap-with-route-metadata` middleware."
  [routes :- BidiRoute]
  (memoized-route-metadata* routes))

(schema/defn ^:always-validate
  wrap-with-route-metadata :- (schema/pred fn?)
  "Ring middleware; adds the comidi route-metadata to the request map, as well
  as a :route-info key that can be used to determine which route a given request
  matches."
  [app :- (schema/pred fn?)
   routes :- BidiRoute]
  (let [compiled-routes (bidi/compile-route routes)
        route-meta      (route-metadata routes)]
    (fn [{:keys [uri path-info] :as req}]
      (let [path (or path-info uri)
            {:keys [handler] :as match-context}
            (apply bidi/match-route compiled-routes path (apply concat (seq req)))
            route-info (get-in route-meta [:handlers handler])]
        (app (assoc req
               :route-metadata route-meta
               :route-info route-info
               :match-context match-context))))))

(schema/defn ^:always-validate
  routes :- BidiRoute
  "Combines multiple bidi routes into a single data structure; this is largely
  just a convenience function for grouping several routes together as a single
  object that can be passed around."
  [& routes :- [BidiRoute]]
  ["" (vec routes)])

(schema/defn ^:always-validate
  context :- BidiRoute
  "Combines multiple bidi routes together into a single data structure, but nests
  them all under the given url-prefix.  This is similar to compojure's `context`
  macro, but does not accept a binding form.  You can still destructure variables
  by passing a bidi pattern for `url-prefix`, and the variables will be available
  to all nested routes."
  [url-prefix :- BidiPattern
   & routes :- [BidiRoute]]
  [url-prefix (vec routes)])

(schema/defn ^:always-validate
  routes->handler :- (schema/pred fn?)
  "Given a bidi route tree, converts into a ring request handler function.  You
  may pass an optional handler function which will be wrapped around the
  bidi leaf."
  ([routes :- BidiRoute
    handler-fn :- (schema/maybe (schema/pred fn?))]
    (let [compiled-routes (bidi/compile-route routes)]
      (make-handler compiled-routes handler-fn)))
  ([routes]
   (routes->handler routes identity)))

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
