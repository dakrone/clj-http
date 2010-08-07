# `clj-http`

A Clojure HTTP library wrapping the [Apache HttpComponents](http://hc.apache.org/) client.

## Usage

The main HTTP client functionality is provided by the `clj-http.client` namespace:

    (require '[clj-http.client :as client])

The client supports simple `get`, `head`, `put`, `post`, and `delete` requests. Responses are returned as Ring-style response maps:

    (client/get "http://google.com")
    => {:status 200
        :headers {"date" "Sun, 01 Aug 2010 07:03:49 GMT"
                  "cache-control" "private, max-age=0"
                  "content-type" "text/html; charset=ISO-8859-1"
                  ...}
        :body #<byte[] [B@aaaec4e>}

Other simple query options:

    (client/get "http://site.com/resources/id")

    (client/get "http://site.com/resources/3" {:accept :json})

    (client/post "http://site.com/resources" {:body byte-array})

    (client/post "http://site.com/resources" {:body "string"})

    (client/get "http://site.com/protected" {:basic-auth ["user" "pass"]})

    (client/get "http://site.com/search" {:query-params {"q" "foo, bar"}})

    (client/post "http://site.com/api"
      {:basic-auth ["user" "pass"]
       :body "{\"json\": \"input\"}"
       :headers {"X-Api-Version" "2"}
       :content-type :json
       :character-encoding "UTF-8"
       :accept :json})

A more general `response` function is available, which is useful as a primitive for building higher-level interfaces:

   (defn api-action [method path & [opts]]
     (client/request
       (merge {:method method :url (str "http://site.com/" path)} opts)))

The client will throw exceptions on, well, exceptional status codes:

    (client/get "http://site.com/broken")
    => Exception: 500

The client will also follow redirects on the appropriate 30* status codes.

## Installation

`clj-http` is available as a Maven artifact from [Clojars](http://clojars.org/clj-http).

## Design

The design of `clj-http` is inspired by the [Ring](http://github.com/mmcgrana/ring) protocol for Clojure HTTP server applications.

The client in `clj-http.core` makes HTTP requests according to a given Ring request map and returns Ring response maps corresponding the resulting HTTP response. The function `clj-http.client/request` using Ring-style middleware to add several layers of sugar and functionality to the core HTTP request/response implementation. Finally, methods like `clj-http.client/get` are simple sugar over the this main `clj-http.client/request` function.

## Development

Running the tests:

    $ lein deps
    $ clj test/server.clj
    $ lein test

## License

Released under the MIT License: <www.opensource.org/licenses/mit-license.php>
