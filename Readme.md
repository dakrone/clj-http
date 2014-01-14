# `clj-http`

A Clojure HTTP library wrapping the [Apache
HttpComponents](http://hc.apache.org/) client.

This library has taken over from mmcgrana's clj-http. Please send a
pull request or open an issue if you have any problems

[![Continuous Integration status](https://secure.travis-ci.org/dakrone/clj-http.png)](http://travis-ci.org/dakrone/clj-http)

[Annotated source](http://dakrone.github.com/clj-http/)

## Installation

`clj-http` is available as a Maven artifact from
[Clojars](http://clojars.org/clj-http):

```clojure
[clj-http "0.7.8"]
```

Previous versions available as

```clojure
[clj-http "0.7.7"]
[clj-http "0.7.6"]
[clj-http "0.7.5"]
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

The client supports simple `get`, `head`, `put`, `post`, `delete`,
`copy`, `move`, `patch` and `options` requests. Responses are returned
as Ring-style response maps:

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
   :socket-timeout 1000  ;; in milliseconds
   :conn-timeout 1000    ;; in milliseconds
   :accept :json})

;; Specifying headers as either a string or collection:
(client/get "http://example.com"
  {:headers {"foo" ["bar" "baz"], "eggplant" "quux"}})

;; Set any specific client parameters manually:
(client/post "http://example.com"
  {:client-params {"http.protocol.allow-circular-redirects" false
                   "http.protocol.version" HttpVersion/HTTP_1_0
                   "http.useragent" "clj-http"}})

;; Need to contact a server with an untrusted SSL cert?
(client/get "https://alioth.debian.org" {:insecure? true})

;; If you don't want to follow-redirects automatically:
(client/get "http://site.come/redirects-somewhere" {:follow-redirects false})

;; Only follow a certain number of redirects:
(client/get "http://site.come/redirects-somewhere" {:max-redirects 5})

;; Throw an exception if redirected too many times:
(client/get "http://site.come/redirects-somewhere" {:max-redirects 5 :throw-exceptions true})

;; Throw an exception if the get takes too long. Timeouts in milliseconds.
(client/get "http://site.come/redirects-somewhere" {:socket-timeout 1000 :conn-timeout 1000})


;; Send form params as a urlencoded body (POST or PUT)
(client/post "http//site.com" {:form-params {:foo "bar"}})
;; Send form params as a json encoded body (POST or PUT)
(client/post "http//site.com" {:form-params {:foo "bar"} :content-type :json})
;; Send form params as a json encoded body (POST or PUT) with options
(client/post "http//site.com" {:form-params {:foo "bar"}
                               :content-type :json
                               :json-opts {:date-format "yyyy-MM-dd"})

;; Multipart form uploads/posts
;; takes a vector of maps, to preserve the order of entities, :name
;; will be used as the part name unless :part-name is specified
(client/post "http//example.org" {:multipart [{:name "title" :content "My Awesome Picture"}
                                              {:name "Content/type" :content "image/jpeg"}
                                              {:name "foo.txt" :part-name "eggplant" :content "Eggplants"}
                                              {:name "file" :content (clojure.java.io/file "pic.jpg")}]})
;; Multipart :content values can be one of the following:
;; String, InputStream, File, or a byte-array
;; Some Multipart bodies can also support more keys (like :encoding
;; and :mime-type), check src/clj-http/multipart.clj to see all flags

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

;; Digest authentication
(client/get "http://site.com/protected" {:digest-auth ["user" "pass"]})

;; OAuth 2
(client/get "http://site.com/protected" {:oauth-token "secret-token"})

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

;; :length is optional for passing in an InputStream; if not
;; supplied it will default to -1 to signal to HttpClient to use
;; chunked encoding
(client/post "http://site.com/resources"
             {:body (clojure.java.io/input-stream "/tmp/foo")})

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
(client/get "http://site.com/foo.json" {:as :json-strict})
(client/get "http://site.com/foo.json" {:as :json-string-keys})

;; Coerce as a clojure datastructure
(client/get "http://site.com/foo.clj" {:as :clojure})

;; Try to automatically coerce the output based on the content-type
;; header (this is currently a BETA feature!). Currently supports
;; text, json and clojure (with automatic charset detection)
;; clojure coercion requires "application/clojure" or
;; "application/edn" in the content-type header
(client/get "http://site.com/foo.json" {:as :auto})

;; Return the body as a stream
(client/get "http://site.com/bigrequest.html" {:as :stream})
;; Note that the connection to the server will NOT be closed until the
;; stream has been read
```

JSON coercion defaults to only an "unexceptional" statuses, meaning
status codes in the #{200 201 202 203 204 205 206 207 300 301 302 303
307} range. If you would like to change this, you can send the
`:coerce` option, which can be set to:

```clojure
:always        ;; always json decode the body
:unexceptional ;; only json decode when not an HTTP error response
:exceptional   ;; only json decode when it IS an HTTP error response
```

The `:coerce` setting defaults to `:unexceptional`.

#### Body decompression
By default, clj-http will add the `{"Accept-Encoding" "gzip, deflate"}`
header to requests, and automatically decompress the resulting gzip or
deflate stream if the `Content-Encoding` header is found on the
response. If this is undesired, the `{:decompress-body false}` option
can be specified:

```clojure
;; Auto-decompression used: (google requires a user-agent to send gzip data)
(def h {"User-Agent" "Mozilla/5.0 (Windows NT 6.1;) Gecko/20100101 Firefox/13.0.1"})
(def resp (client/get "http://google.com" {:headers h}))
(:orig-content-encoding resp)
=> "gzip" ;; <= google sent response gzipped

;; and without decompression:
(def resp2 (client/get "http://google.com" {:headers h :decompress-body false})
(:orig-content-encoding resp2)
=> nil
```

If clj-http decompresses something, the "content-encoding" header is
removed from the headers (because the encoding is no longer
true). This allows clj-http to be used as a pass-through proxy with
ring. The original content-encoding is available as
`:orig-content-encoding` in the response map if Auto-decompression is
enabled.

#### HTML Meta tag headers
HTML 4.01 allows using the tag `<meta http-equiv="..." />` and HTML 5
allows using the tag `<meta charset="..." />` to specify a header that
should be treated as an HTTP response header. By default, clj-http
will ignore the body of the response (other than the regular output
coercion), but if you need clj-http to parse the headers out of the
body, you can use the `:decode-body-headers` option:

```clojure
;; without decoding body headers (defaults to off):
(:headers (http/get "http://www.yomiuri.co.jp/"))
=> {"server" "Apache",
    "content-encoding" "gzip",
    "content-type" "text/html",
    "date" "Tue, 09 Oct 2012 18:02:41 GMT",
    "cache-control" "max-age=0, no-cache",
    "expires" "Tue, 09 Oct 2012 18:02:41 GMT",
    "etag" "\"1dfb-2686-4cba2686fb8b1\"",
    "pragma" "no-cache",
    "connection" "close"}

;; with decoding body headers, notice the content-type,
;; content-style-type and content-script-type headers:
(:headers (http/get "http://www.yomiuri.co.jp/" {:decode-body-headers true}))
=> {"server" "Apache",
    "content-encoding" "gzip",
    "content-script-type" "text/javascript",
    "content-style-type" "text/css",
    "content-type" "text/html; charset=Shift_JIS",
    "date" "Tue, 09 Oct 2012 18:02:59 GMT",
    "cache-control" "max-age=0, no-cache",
    "expires" "Tue, 09 Oct 2012 18:02:59 GMT",
    "etag" "\"1dfb-2686-4cba2686fb8b1\"",
    "pragma" "no-cache",
    "connection" "close"}
```

This can be used to have clj-http correctly interpret the body's
charset by using:

```clojure
(http/get "http://www.yomiuri.co.jp/" {:decode-body-headers true :as :auto})
=> ;; correctly formatted :body (Shift_JIS charset instead of UTF-8)
```

Note that this feature is currently beta and uses
[Crouton](https://github.com/weavejester/crouton) to parse the body of
the request. If you do not want to use this feature, you can exclude
Crouton from clj-http's dependencies without causing any problems like so:

```clojure
(defproject foo "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.3.0"]
                 [clj-http "0.6.0" :exclusions [crouton]]])
```

clj-http will automatically disable the `:decode-body-headers` option.

### Misc

A more general `request` function is also available, which is useful
as a primitive for building higher-level interfaces:

```clojure
(defn api-action [method path & [opts]]
  (client/request
    (merge {:method method :url (str "http://site.com/" path)} opts)))
```

### Exceptions

The client will throw exceptions on, well, exceptional status codes,
meaning all HTTP responses other than `#{200 201 202 203 204 205 206
207 300 301 302 303 307}`. clj-http will throw a
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

;; You can also ignore HTTP-status-code exceptions and handle them yourself:
(client/get "http://site.com/broken" {:throw-exceptions false})
;; Or ignore an unknown host (methods return 'nil' if this is set to
;; true and the host does not exist:
(client/get "http://aoeuntahuf89o.com" {:ignore-unknown-host? true})
````

(spacing added by me to be human readable)

### Proxies

A proxy can be specified by setting the Java properties:
`<scheme>.proxyHost` and `<scheme>.proxyPort` where `<scheme>` is the
client scheme used (normally 'http' or 'https'). `http.nonProxyHosts`
allows you to specify a pattern for hostnames which do not require
proxy routing - this is shared for all schemes. Additionally,
per-request proxies can be specified with the `proxy-host` and
`proxy-port` options (this overrides `http.nonProxyHosts` too):

```clojure
(client/get "http://foo.com" {:proxy-host "127.0.0.1" :proxy-port 8118})
```

You can also specify the `proxy-ignore-hosts` parameter with a list of
hosts where the proxy should be ignored. By default this list is
`#{"localhost" "127.0.0.1"}`.

### SOCKS Proxies

A SOCKS proxy can be used by creating a proxied connection manager
with `clj-http.conn-mgr/make-socks-proxied-conn-manager`. Then using
that connection manager in the request.

For example if you wanted to connect to a local socks proxy on port `8081` you would:

```clojure
(ns foo.bar
  (:require [clj-http.client :as client]
            [clj-http.conn-mgr :as conn-mgr]))

(client/get "https://google.com"
            {:connection-manager
             (conn-mgr/make-socks-proxied-conn-manager "localhost" 8081)})
```

You can also store the proxied connection manager and reuse it later.

### Keystores and Trust-stores

When sending a request, you can specify your own keystore/trust-store
to be used:

```clojure
(client/get "https://example.com" {:keystore "/path/to/keystore.ks"
                                   :keystore-type "jks" ; default: jks
                                   :keystore-pass "secretpass"
                                   :trust-store "/path/to/trust-store.ks"
                                   :trust-store-type "jks" ; default jks
                                   :trust-store-pass "trustpass"})
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

This will allow cookies to only be _written_ to the cookie store.
Cookies from the cookie-store will not automatically be sent with
future requests.

If you would like cookies from the cookie-store to automatically be
sent with each request, specify the cookie-store with the
`:cookie-store` option:

```clojure
(let [my-cs (clj-http.cookies/cookie-store)]
  (client/post "http://site.com/login" {:form-params {:username "..."
                                                      :password "..."}
                                        :cookie-store my-cs})
  (client/post "http://site.com/update" {:body my-data
                                         :cookie-store my-cs}))
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
(:links (client/get "https://api.github.com/gists"))
=> {:next {:href "https://api.github.com/gists?page=2"}
    :last {:href "https://api.github.com/gists?page=22884"}}
```

### Raw headers
By default clj-http forces lowercase header names when parsing the
response. If you want to preserve the exact response headers (with their
original casing), you can use the `:raw-headers` option on your request.
When you add this option you'll receive both the usual downcased headers
_and_ an additional map of raw headers in your response.

```clojure
(client/get "http://google.com" {:raw-headers true})
=> {:status 200
    :headers {"date" "Sun, 01 Aug 2010 07:03:49 GMT"
              "cache-control" "private, max-age=0"
              "content-type" "text/html; charset=ISO-8859-1"
              ...}
    :raw-headers {"Date" "Sun, 01 Aug 2010 07:03:49 GMT"
                  "Cache-Control" "private, max-age=0"
                  "Content-Type" "text/html; charset=ISO-8859-1"
                  ...}
    ...

```

### Using persistent connections
clj-http can use persistent connections to speed up connections if
multiple connections are being used:

```clojure
(with-connection-pool {:timeout 5 :threads 4 :insecure? false :default-per-route 10}
  (get "http://aoeu.com/1")
  (post "http://aoeu.com/2")
  (get "http://aoeu.com/3")
  ...
  (get "http://aoeu.com/999"))
```

This is MUCH faster than sequentially performing all requests, because
a persistent connection can be used instead creating a new connection
for each request.

If you would prefer to handle managing the connection manager
yourself, you can create a connection manager yourself and specify it
for each request:

```clojure
(def cm (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 2 :threads 3}))
(def cm2 (clj-http.conn-mgr/make-reusable-conn-manager {:timeout 10 :threads 1}))

(get "http://aoeu.com/1" {:connection-manager cm2})
(post "http://aoeu.com/2" {:connection-manager cm})
(get "http://aoeu.com/3" {:connection-manager cm2})
```

See the docstring on `make-reusable-conn-manager` for options and
default values.

### Redirects handling
clj-http conforms its behaviour regarding automatic redirects to the
[RFC](https://tools.ietf.org/html/rfc2616#section-10.3). It means that
redirects on status `301`, `302` and `307` are not redirected on
methods other than `GET` and `HEAD`. If you want a behaviour closer to
what most browser have, you can set `:force-redirects` to `true` in
your request to have automatic redirection work on all methods by
transforming the method of the request to `GET`.

### Custom middleware lists
Sometime it is desirable to run a request with some middleware enabled
and some left out, the `with-middleware` method provides this
functionality:

```clojure
(with-middleware [#'clj-http.client/wrap-method
                  #'clj-http.client/wrap-url
                  #'clj-http.client/wrap-exceptions]
  (get "http://example.com")
  (post "http://example.com/foo" {:body (.getBytes "foo")}))
```

To see available middleware, check the
`clj-http.client/default-middleware` var, which is a vector of the
default middleware that clj-http
uses. `clj-http.client/*current-middleware*` is bound to the current
list of middleware during request time.

## Debugging

There are four debugging methods you can use:

```clojure
;; print request info to *out*:
(client/get "http://example.org" {:debug true})

;; print request info to *out*, including request body:
(client/post "http://example.org" {:debug true :debug-body true :body "..."})

;; save the request that was sent in a :request key in the response:
(client/get "http://example.org" {:save-request? true})

;; save the request that was sent in a :request key in the response,
;; including the body content:
(client/get "http://example.org" {:save-request? true :debug-body true})

;; add an HttpResponseInterceptor to the request. This callback
;; is called for each redirects with the following args:
;; ^HttpResponse resp, HttpContext^ ctx
;; this allows low level debugging + access to socket.
;; see http://hc.apache.org/httpcomponents-core-ga/httpcore/apidocs/org/apache/http/HttpResponseInterceptor.html
(client/get "http://example.org" {:response-interceptor (fn [resp ctx] (println ctx))})
```


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

## Optional dependencies

clj-http currently has three optional dependencies, `cheshire`,
`crouton` and `tools.reader`. Any number of them  may be removed by
excluding them from the clj-http dependency in your project.clj:

```clojure
(defproject foo "0.1.0-SNAPSHOT"
  :dependencies [[org.clojure/clojure "1.5.1"]
                 [clj-http "0.3.4" :exclusions [cheshire crouton
                                                org.clojure/tools.reader]]])
```

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
