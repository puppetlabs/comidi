# comidi

A committee approach to defining Clojure HTTP routes.

[![Build Status](https://travis-ci.org/puppetlabs/comidi.svg?branch=master)](https://travis-ci.org/puppetlabs/comidi)

Comidi is a library containing utility functions and [compojure](https://github.com/weavejester/compojure)-like syntax-sugar
wrappers around the [bidi](https://github.com/juxt/bidi) web routing library.
It aims to provide a way to define your web routes that takes advantage of the
strengths of both bidi and compojure:

* Route definitions are, at the end of the day, simple data structures (like bidi),
  so you can compose / introspect them.
* Helper functions / macros for defining routes still provide the nice syntax
  of compojure; destructuring the request in a simple binding form, 'rendering'
  the response whether you define it as a string/map literal, a function reference,
  an inline body form, etc.

## Quick Start

[![Clojars Project](http://clojars.org/puppetlabs/comidi/latest-version.svg)](http://clojars.org/puppetlabs/comidi)

```clj
(let [my-routes (context "/my-app/"
                    (routes
                        (GET "/foo/something" request
                            "foo!")
                        (POST ["/bar/" :bar] [bar]
                            (str "bar:" bar))
                        (PUT ["/baz/" [#".*" :rest]] request
                            (call-baz-fn request))
                        (ANY ["/bam/" [#"(bip|bap)" :rest]] request
                            {:orig-req request
                             :rest    (-> request :route-params :rest)))
      app        (-> (routes->handler my-routes)
                     wrap-with-my-middleware)]
   (add-ring-handler app))
```

Notable differences from compojure above:

* use vectors to separate segments of a route, rather than using special syntax
  inside of a string (e.g. `["/bar/" :bar]` instead of compojure's `"/bar/:bar")
* use a nested vector with a regex (e.g. `[".*" :rest]`) to match a regex
* `context` macro does not provide a binding form for request vars like compojure's
  does.

Other than those differences, the API should be very close to compojure's.

## What does Comidi do?

Comidi provides some macros and functions that are intended to feel very similar
to the compojure routing macros / functions, but under the hood they construct,
compose, and return bidi route trees rather than compojure handler functions.

This way, you can define your routes with almost exactly the same syntax you've
been using (or port over a compojure app with minimal effort), but end up with
an introspectable route tree data structure that you can do all sorts of cool
things with before you wrap it as a ring handler.

Under the hood: comidi uses bidi to do all of the work for routing, and uses
a few functions from compojure to maintain some of the nice syntax.  Specifically,
it uses compojure's route destructuring to bind local variables for parameters
from the requests, and it uses compojure's "rendering" functions to allow you
to define the implementation of your route flexibly (so, just like in compojure,
your route definition can be a literal return value, a reference to a function,
a call to a function, a String, etc.)

Comidi also provides a function called `route-metadata`.  This function
walks over your route tree and generates a metadata structure that gives you
information about the all of the routes; e.g.:

```clj
(clojure.pprint/pprint
  (-> (route-metadata (routes
                        (GET "/foo" request
                          "foo!")
                        (PUT ["/bar/" :bar] [bar]
                          (str "bar: " bar))))
      :routes))
```

```
[{:route-id "foo", :path ["" "/foo"], :request-method :get}
 {:route-id "bar-:bar", :path ["" "/bar/" :bar], :request-method :put}]
```

Comidi also provides its own middleware function, `wrap-with-route-metadata`.  If
you use this middleware, your ring request map will be supplemented with two
extra keys: `:route-metadata`, which gives you access to the metadata for all of
the routes in your route tree, and `:route-info`, which tells you which of those
routes the request matches.  e.g.:

```clj
(clojure.pprint/pprint
  (let [my-routes (routes
                    (ANY "/foo" request
                      {:route-info (:route-info request)}))
        handler (-> my-routes
                    routes->handler
                    (wrap-with-route-metadata my-routes))]
    (:route-info (handler {:uri "/foo"}))))
```

```clj
{:route-id "foo", :path ["" "/foo"], :request-method :any}
```

## What's next?

* Metrics library for tracking request metrics

* API docs: looking into swagger integration.  I could swear I found some bidi-swagger
  bindings somewhere a while back, but am not finding them at the moment.  It
  might be possible to re-use some of the code from `compojure-api` because of
  the similarity between the comidi API and the compojure API.

* You tell me!  This is pre-1.0 and the API should still be considered fungible.
  If there's something you need that this library isn't doing, we can probably
  do it.  Ping us or submit a PR.
