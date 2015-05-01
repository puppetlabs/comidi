# comidi

[![Build Status](https://magnum.travis-ci.com/puppetlabs/comidi.svg?token=ApBsaKK1zdeqHwzhXLzw&branch=master)](https://magnum.travis-ci.com/puppetlabs/comidi)

Puppet Labs utility functions and compojure-like syntax-sugar wrappers around
the [bidi](https://github.com/juxt/bidi) web routing library.

## Quick Start

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

## Motivation

Recently, we've had some features we needed to implement around web routing
which felt like they should be able to be handled at a general / library level,
rather than with app-specific code.  The primary example is tracking request
metrics for all of the routes in a given Clojure web app.

Compojure has proven exceedingly difficult to use for this, because the way it
builds up a route tree involves macros and nested functions that make it
basically impossible to do any introspection of the route tree after it is
constructed.

The lack of introspection capabilities in Compojure seems to be a common enough
problem that other routing libraries, which are data-driven, are popping up and
gaining some popularity.  Pedestal (Cognitect's library) is one example of this,
but the other one that kept coming up in searches for me was
[bidi](https://github.com/juxt/bidi).

Bidi is a bi-directional web routing library that represents the route tree as
a pure data structure (think simple vectors and maps).  After you've composed
your route tree via whatever usual Clojure mechanisms you prefer, you can then
ask bidi to convert it into a normal ring handler function, and wrap it with
middleware just like you would with any other ring handler.  The difference is
that you can still hang on to a reference to the route tree data structure and
do all kinds of cool things with it.  It seems to be gaining traction; I know that
it's popular in the ClojureScript community and that David Nolen has been using
it for some stuff.

After playing with bidi for a few hours I was hooked.  I did a POC to validate
that I could do what I wanted to in terms of writing some generalized HTTP metrics
around a bidi route tree, and it worked like a charm.  At that point it was a
question of how hard it would be to port existing Compojure apps over to be able
to take advantage of these capabilities.

Compojure's routing syntax is pretty nice to code against.  I thought it would be
ideal to be able to use some of that same syntax, while still ending up with a bidi
route tree at the end of the day.  It turned out that this wasn't too difficult
to do; enter `comidi`.

## What does Comidi do?

Comidi simply provides some macros and functions that are intended to feel very similar
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

## Why closed source?

Because the original inspiration for this work was to be able to supplement a
generic metrics library, and metrics is one of the differentiators for PE Puppet
Server.  And because it's easy to go from closed to open, and not the other way
around.  I expect this will probably go OSS soon.

## What's next?

* The metrics library that I alluded to several times above is basically ready,
  just needs to be pulled out into a separate library; expect an announcement
  very soon.

* API docs: looking into swagger integration.  I could swear I found some bidi-swagger
  bindings somewhere a while back, but am not finding them at the moment.  It
  might be possible to re-use some of the code from `compojure-api` because of
  the similarity between the comidi API and the compojure API.

* You tell me!  This is pre-1.0 and the API should still be considered fungible.
  If there's something you need that this library isn't doing, we can probably
  do it.  Ping us or submit a PR.
