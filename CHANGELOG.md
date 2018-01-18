## 0.3.2

This is a minor bugfix release.

* [TK-464](https://tickets.puppetlabs.com/browse/TK-464) : Fix bug with URL parsing by updating bidi to 2.1.3. Also update clj-parent.

## 0.3.1

This is a minor bugfix release.

* [TK-297](https://tickets.puppetlabs.com/browse/TK-297) : Fix bug where `wrap-routes` didn't work properly with certain kinds of bidi route trees - Michal Růžička <michal.ruza@gmail.com> (159ef0f)
* Update dependencies on clojure, bidi, compojure, schema

## 0.3.0

Added mechanism for wrapping intermediate comidi routes with middleware.
Added support for "true/false" Bidi patterns.

## 0.2.2

Same content as 0.3.0 - please prefer that version

## 0.2.1

Added 'resources' route utility fn.
Routes can now only map to Ring handlers.

## 0.2.0

Make use of schema introduced in Bidi 1.20.0 (SERVER-777).
LICENSE and CONTRIBUTING updates.

## 0.1.3

Improved dependency specification.

## 0.1.2

Upgrade compojure to 1.3.3 for Clojure 1.7.0 compatibility.

## 0.1.1

Make repository public, deploy to clojars.

## 0.1.0

Initial release, with the goal of soliticing API feedback.
