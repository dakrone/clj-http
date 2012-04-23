# `clj-http`

A Clojure HTTP library wrapping the [Apache
HttpComponents](http://hc.apache.org/) client.

This library has taken over from mmcgrana's clj-http. Please send a
pull request or open an issue if you have any problems

[![Continuous Integration status](https://secure.travis-ci.org/dakrone/clj-http.png)](http://travis-ci.org/dakrone/clj-http)

## Installation

`clj-http` is available as a Maven artifact from
[Clojars](http://clojars.org/clj-http):

```clojure
[clj-http "0.4.0"]
```

Previous versions available as

```clojure
[clj-http "0.3.6"]
[clj-http "0.3.5"]
[clj-http "0.3.4"]
```

## Usage

The main HTTP client functionality is provided by the
`clj-http.client` namespace.

Require it in the REPL:

```clojure
(require '[clj-http.client :as client])
```

Require it in your application:

```clojure
(ns my-app.core
  (:require [clj-http.client :as client]))
```

The client supports simple `get`, `head`, `put`, `post`, and `delete`
requests. Responses are returned as Ring-style response maps:

```clojure
(client/get "http://google.com")
=> {:status 200
    :headers {"date" "Sun, 01 Aug 2010 07:03:49 GMT"
              "cache-control" "private, max-age=0"
              "content-type" "text/html; charset=ISO-8859-1"
              ...}
    :body "<!doctype html>..."
    :cookies {"PREF" {:domain ".google.com", :expires #<Date Wed Apr 02 09:10:22 EDT 2014>, :path "/", :value "...", :version 0}}
    :trace-redirects ["http://google.com" "http://www.google.com/" "http://www.google.fr/"]}
```
:trace-redirects will contain the chain of the redirections followed.

More example requests:

```clojure
(client/get "http://site.com/resources/id")

(client/get "http://site.com/resources/3" {:accept :json})

;; Various options:
(client/post "http://site.com/api"
  {:basic-auth ["user" "pass"]
   :body "{\"json\": \"input\"}"
   :headers {"X-Api-Version" "2"}
   :content-type :json
   :socket-timeout 1000
   :conn-timeout 1000
   :accept :json})

;; Need to contact a server with an untrusted SSL cert?
(client/get "https://alioth.debian.org" {:insecure? true})

;; If you don't want to follow-redirects automatically:
(client/get "http://site.come/redirects-somewhere" {:follow-redirects false})

;; Only follow a certain number of redirects:
(client/get "http://site.come/redirects-somewhere" {:max-redirects 5})

;; Throw an exception if redirected too many times:
(client/get "http://site.come/redirects-somewhere" {:max-redirects 5 :throw-exceptions true})

;; Send form params as a urlencoded body (POST or PUT)
(client/post "http//site.com" {:form-params {:foo "bar"}})
;; Send form params as a json encoded body (POST or PUT)
(client/post "http//site.com" {:form-params {:foo "bar"} :content-type :json})

;; Multipart form uploads/posts
;; a map or vector works as the multipart object. Use a vector of
;; vectors if you need to preserve order, a map otherwise.
(client/post "http//example.org" {:multipart [["title" "My Awesome Picture"]
                                              ["Content/type" "image/jpeg"]
                                              ["file" (clojure.java.io/file "pic.jpg")]]})
;; Multipart values can be one of the following:
;; String, InputStream, File, or a byte-array

;; Apache's http client automatically retries on IOExceptions, if you
;; would like to handle these retries yourself, you can specify a
;; :retry-handler. Return true to retry, false to stop trying:
(client/post "http://example.org" {:multipart [["title" "Foo"]
                                               ["Content/type" "text/plain"]
                                               ["file" (clojure.java.io/file "/tmp/missing-file")]]
                                   :retry-handler (fn [ex try-count http-context]
                                                    (println "Got:" ex)
                                                    (if (> try-count 4) false true))})

;; Basic authentication
(client/get "http://site.com/protected" {:basic-auth ["user" "pass"]})
(client/get "http://site.com/protected" {:basic-auth "user:pass"})

;; Query parameters
(client/get "http://site.com/search" {:query-params {"q" "foo, bar"}})

;; "Nested" query parameters
;; (this yields a query string of `a[e][f]=6&a[b][c]=5`)
(client/get "http://site.com/search" {:query-params {:a {:b {:c 5} :e {:f 6})

;; Provide cookies — uses same schema as :cookies returned in responses
;; (see the cookie store option for easy cross-request maintenance of cookies)
(client/get "http://site.com"
  {:cookies {"ring-session" {:discard true, :path "/", :value "", :version 0}}})

;; Support for IPv6!
(client/get "http://[2001:62f5:9006:e472:cabd:c8ff:fee3:8ddf]")
```

The client will also follow redirects on the appropriate `30*` status
codes.

The client transparently accepts and decompresses the `gzip` and
`deflate` content encodings.

### Input coercion

```clojure
;; body as a byte-array
(client/post "http://site.com/resources" {:body my-byte-array})

;; body as a string
(client/post "http://site.com/resources" {:body "string"})

;; :body-encoding is optional and defaults to "UTF-8"
(client/post "http://site.com/resources"
             {:body "string" :body-encoding "UTF-8"})

;; body as a file
(client/post "http://site.com/resources"
             {:body (clojure.java.io/file "/tmp/foo") :body-encoding "UTF-8"})

;; :length is NOT optional for passing an InputStream in
(client/post "http://site.com/resources"
             {:body (clojure.java.io/input-stream "/tmp/foo") :length 1000})
```

### Output coercion

```clojure
;; The default output is a string body
(client/get "http://site.com/foo.txt")

;; Coerce as a byte-array
(client/get "http://site.com/favicon.ico" {:as :byte-array})

;; Coerce as something other than UTF-8 string
(client/get "http://site.com/string.txt" {:as "UTF-16"})

;; Coerce as json
(client/get "http://site.com/foo.json" {:as :json})
(client/get "http://site.com/foo.json" {:as :json-string-keys})

;; Coerce as a clojure datastructure
(client/get "http://site.com/foo.clj {:as :clojure})

;; Try to automatically coerce the output based on the content-type
;; header (this is currently a BETA feature!). Currently supports
;; text, json and clojure (with automatic charset detection)
(client/get "http://site.com/foo.json" {:as :auto})

;; Return the body as a stream
(client/get "http://site.com/bigrequest.html" {:as :stream})
;; Note that the connection to the server will NOT be closed until the
;; stream has been read
```

A more general `request` function is also available, which is useful
as a primitive for building higher-level interfaces:

```clojure
(defn api-action [method path & [opts]]
  (client/request
    (merge {:method method :url (str "http://site.com/" path)} opts)))
```

### Exceptions

The client will throw exceptions on, well, exceptional status
codes. clj-http will throw a
[Slingshot](http://github.com/scgilardi/slingshot) Stone that can be
caught by a regular `(catch Exception e ...)` or in Slingshot's `try+`
block:

```clojure
(client/get "http://site.com/broken")
=> ExceptionInfo clj-http: status 404  clj-http.client/wrap-exceptions/fn--583 (client.clj:41)
;; Or, if you would like the Exception message to contain the entire response:
(client/get "http://site.com/broken" {:throw-entire-message? true})
=> ExceptionInfo clj-http: status 404 {:status 404,
                                       :headers {"server" "nginx/1.0.4",
                                                 "x-runtime" "12ms",
                                                 "content-encoding" "gzip",
                                                 "content-type" "text/html; charset=utf-8",
                                                 "date" "Mon, 17 Oct 2011 23:15 :36 GMT",
                                                 "cache-control" "no-cache",
                                                 "status" "404 Not Found",
                                                 "transfer-encoding" "chunked",
                                                 "connection" "close"},
                                       :body "...body here..."}
   clj-http.client/wrap-exceptions/fn--584 (client.clj:42

;; You can also ignore exceptions and handle them yourself:
(client/get "http://site.com/broken" {:throw-exceptions false})
;; Or ignore an unknown host (methods return 'nil' if this is set to
;; true and the host does not exist:
(client/get "http://aoeuntahuf89o.com" {:ignore-unknown-host? true})
````
(spacing added by me to be human readable)

### Proxies

A proxy can be specified by setting the Java properties:
`<scheme>.proxyHost` and `<scheme>.proxyPort` where `<scheme>` is the client
scheme used (normally 'http' or 'https'). Additionally, per-request
proxies can be specified with the `proxy-host` and `proxy-port`
options:

```clojure
(client/get "http://foo.com" {:proxy-host "127.0.0.1" :proxy-port 8118})
```

### Cookie stores

clj-http can simplify the maintenance of cookies across requests if it
is provided with a _cookie store_.

```clojure
(binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
  (client/post "http://site.com/login" {:form-params {:username "..."
                                                      :password "..."}})
  (client/get "http://site.com/secured-page")
  ...)
```

(The `clj-http.cookies/cookie-store` function returns a new empty
instance of a default implementation of
`org.apache.http.client.CookieStore`.)

Alternatively, you can provide a cookie store on a per-request basis
that will supercede any cookie store that has been dynamically bound to
`clj-http.core/*cookie-store*`:

```clojure
(binding [clj-http.core/*cookie-store* (clj-http.cookies/cookie-store)]
  (client/post "http://site.com/login" {:form-params {:username "..."
                                                      :password "..."}})
  (let [data (:body (client/get "http://site.com/secured-page" {:as :json}))]
    (client/post "http://othersite.com/update" {:form-params data
                                                :cookie-store othersite-cookie-store})
  ...))
```

You can also us the `get-cookies` function to retrieve the cookies
from a cookie store:

```clojure
(def cs (clj-http.cookies/cookie-store))

(client/get "http://google.com" {:cookie-store cs})

(clojure.pprint/pprint (clj-http.cookies/get-cookies cs))
{"NID"
 {:domain ".google.com",
  :expires #<Date Tue Oct 02 10:12:06 MDT 2012>,
  :path "/",
  :value
  "58=c387....",
  :version 0},
 "PREF"
 {:domain ".google.com",
  :expires #<Date Wed Apr 02 10:12:06 MDT 2014>,
  :path "/",
  :value
  "ID=3ba...:FF=0:TM=133...:LM=133...:S=_iRM...",
  :version 0}}
```

### Link headers
clj-http parses any [link headers](http://tools.ietf.org/html/rfc5988)
returned in the response, and adds them to the `:links` key on the
response map. This is particularly useful for paging RESTful APIs:

```clojure
(:links (http/get "https://api.github.com/gists"))
=> {:next {:href "https://api.github.com/gists?page=2"}
    :last {:href "https://api.github.com/gists?page=22884"}}
```

### Using persistent connections
clj-http can use persistent connections to speed up connections if
multiple connections are being used:

```clojure
(with-connection-pool {:timeout 5 :threads 4 :insecure? false}
  (get "http://aoeu.com/1")
  (post "http://aoeu.com/2")
  (get "http://aoeu.com/3")
  ...
  (get "http://aoeu.com/999"))
```

This is MUCH faster than sequentially performing all requests, because
a persistent connection can be used instead creating a new connection
for each request.

This feature is fairly new, please let me know if you have any feedback!

### Redirects handling
clj-http conforms its behaviour regarding automatic redirects to the
[RFC](https://tools.ietf.org/html/rfc2616#section-10.3). It means that
redirects on status `301`, `302` and `307` are not redirected on
methods other than `GET` and `HEAD`. If you want a behaviour closer to
what most browser have, you can set `:force-redirects` to `true` in
your request to have automatic redirection work on all methods by
transforming the method of the request to `GET`.

## Faking clj-http responses

If you need to fake clj-http responses (for things like testing and
such), check out the
[clj-http-fake](https://github.com/myfreeweb/clj-http-fake) library.

## Design

The design of `clj-http` is inspired by the
[Ring](http://github.com/mmcgrana/ring) protocol for Clojure HTTP
server applications.

The client in `clj-http.core` makes HTTP requests according to a given
Ring request map and returns Ring response maps corresponding to the
resulting HTTP response. The function `clj-http.client/request` uses
Ring-style middleware to layer functionality over the core HTTP
request/response implementation. Methods like `clj-http.client/get`
are sugar over this `clj-http.client/request` function.

## Known issues / Issues you may run into

### VerifyError class org.codehaus.jackson.smile.SmileParser overrides final method getBinaryValue...

This is actually caused by your project attempting to use
[clj-json](https://github.com/mmcgrana/clj-json/)
and [cheshire](https://github.com/dakrone/cheshire) in the same
classloader. You can fix the issue by either not using clj-json (and
thus choosing cheshire), or specifying an exclusion for clj-http in
your project like this:

```clojure
(defproject foo "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [clj-http "0.3.4" :exclusions [cheshire]]])
```

Note that if you exclude cheshire, json decoding of response bodies
and json encoding of form-params cannot happen, you are responsible
for your own encoding/decoding.

As of clj-http 0.3.5, you should no longer see this, as Cheshire 3.1.0
and clj-json can now live together without causing problems.

### clj-http-lite

Like clj-http but need something more lightweight without as many
external dependencies? Check out
[clj-http-lite](https://github.com/hiredman/clj-http-lite) for a
project that can be used as a drop-in replacement for clj-http.

## Development

To run the tests:

    $ lein deps
    $ lein test

    Run all tests (including integration):
    $ lein test :all

    Run tests against 1.2.1, 1.3 and 1.4
    $ lein all test
    $ lein all test :all

## License

Released under the MIT License:
<http://www.opensource.org/licenses/mit-license.php>
