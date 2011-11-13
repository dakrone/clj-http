# `clj-http`

A Clojure HTTP library wrapping the [Apache
HttpComponents](http://hc.apache.org/) client.

This library has taken over from mmcgrana's clj-http. Please send a
pull request or open an issue if you have any problems

## Usage

The main HTTP client functionality is provided by the
`clj-http.client` namespace:

```clojure
(require '[clj-http.client :as client])
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
    :body "<!doctype html>..."}
```

More example requests:

```clojure
(client/get "http://site.com/resources/id")

(client/get "http://site.com/resources/3" {:accept :json})

(client/post "http://site.com/resources" {:body byte-array})

(client/post "http://site.com/resources" {:body "string"})

(client/get "http://site.com/protected" {:basic-auth ["user" "pass"]})
(client/get "http://site.com/protected" {:basic-auth "user:pass"})

(client/get "http://site.com/search" {:query-params {"q" "foo, bar"}})

(client/get "http://site.com/favicon.ico" {:as :byte-array})

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

;; Send form params as a urlencoded body
(client/post "http//site.com" {:form-params {:foo "bar"}})
```

A more general `request` function is also available, which is useful
as a primitive for building higher-level interfaces:

```clojure
(defn api-action [method path & [opts]]
  (client/request
    (merge {:method method :url (str "http://site.com/" path)} opts)))
```

The client will throw exceptions on, well, exceptional status
codes. clj-http will throw a
[Slingshot](http://github.com/scgilardi/slingshot) Stone that can be
caught by a regular `(catch Exception e ...)` or in Slingshot's `try+`
block:

```clojure
(client/get "http://site.com/broken")
=> Stone Object thrown by throw+: {:status 404, :headers {"server" "nginx/1.0.4",
                                                          "x-runtime" "12ms",
                                                          "content-encoding" "gzip",
                                                          "content-type" "text/html; charset=utf-8",
                                                          "date" "Mon, 17 Oct 2011 23:15 :36 GMT",
                                                          "cache-control" "no-cache",
                                                          "status" "404 Not Found",
                                                          "transfer-encoding" "chunked",
                                                          "connection" "close"},
                                   :body "...body here..."}
   clj-http.client/wrap-exceptions/fn--227 (client.clj:37)

;; You can also ignore exceptions and handle them yourself:
(client/get "http://site.com/broken" {:throw-exceptions false})
````
(spacing added by me to be human readable)


The client will also follow redirects on the appropriate `30*` status
codes.

The client transparently accepts and decompresses the `gzip` and
`deflate` content encodings.

A proxy can be specified by setting the Java properties:
`<scheme>.proxyHost` and `<scheme>.proxyPort` where `<scheme>` is the client
scheme used (normally 'http' or 'https').

## Using persistent connections
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

## Faking clj-http responses

If you need to fake clj-http responses (for things like testing and
such), check out the
[clj-http-fake](https://github.com/myfreeweb/clj-http-fake) library.

## Installation

`clj-http` is available as a Maven artifact from
[Clojars](http://clojars.org/clj-http):

```clojure
[clj-http "0.2.4"]
```

Previous versions available as

```clojure
[clj-http "0.2.3"]
[clj-http "0.2.2"]
[clj-http "0.2.1"]
```

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

## Development

To run the tests:

    $ lein deps
    $ lein test

    Run all tests (including integration):
    $ lein test :all

    Run tests against 1.2.1, 1.3 and 1.4
    $ lein multi deps
    $ lein multi test
    $ lein multi test :all

## License

Released under the MIT License:
<http://www.opensource.org/licenses/mit-license.php>
