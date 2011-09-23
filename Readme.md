# `clj-http`

A Clojure HTTP library wrapping the [Apache HttpComponents](http://hc.apache.org/) client.

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

The client will throw exceptions on, well, exceptional status codes:

```clojure
(client/get "http://site.com/broken")
=> Exception: 500
````

The client will also follow redirects on the appropriate `30*` status
codes.

The client transparently accepts and decompresses the `gzip` and
`deflate` content encodings.

A proxy can be specified by setting the Java properties:
`<scheme>.proxyHost` and `<scheme>.proxyPort` where `<scheme>` is the client
scheme used (normally 'http' or 'https').

## Installation

`clj-http` is available as a Maven artifact from
[Clojars](http://clojars.org/clj-http):

    [clj-http "0.2.1"]

Previous versions available as

    [clj-http "0.2.0"]
    [clj-http "0.1.3"]

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

    Run tests against 1.2.1 and 1.3
    $ lein multi deps
    $ lein multi test
    $ lein multi test :all

## License

Released under the MIT License:
<http://www.opensource.org/licenses/mit-license.php>
