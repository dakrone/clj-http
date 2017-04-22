(ns clj-http.test.links-test
  (:require [clj-http.links :refer :all]
            [clojure.test :refer :all]))

(defn- link-handler [link-header]
  (wrap-links (constantly {:headers {"link" link-header}})))

(deftest test-wrap-links
  (testing "absolute link"
    (let [handler (link-handler "<http://example.com/page2.html>; rel=next")]
      (is (= (:links (handler {}))
             {:next {:href "http://example.com/page2.html"}}))))
  (testing "relative link"
    (let [handler (link-handler "</page2.html>;rel=next")]
      (is (= (:links (handler {}))
             {:next {:href "/page2.html"}}))))
  (testing "extra params"
    (let [handler (link-handler "</page2.html>; rel=next; title=\"Page 2\"")]
      (is (= (:links (handler {}))
             {:next {:href "/page2.html", :title "Page 2"}}))))
  (testing "multiple headers"
    (let [handler (link-handler "</p1>;rel=prev, </p3>;rel=next,</>;rel=home")]
      (is (= (:links (handler {}))
             {:prev {:href "/p1"}
              :next {:href "/p3"}
              :home {:href "/"}}))))
  (testing "no :links key if no link headers"
    (let [handler  (wrap-links (constantly {:headers {}}))
          response (handler {})]
      (is (not (contains? response :links))))))

(deftest t-multiple-link-headers
  (let [handler (link-handler ["<http://example.com/Zl_A>; rel=shorturl"
                               "<http://example.com/foo.png>; rel=icon"])
        resp (handler {})]
    (is (= (:links resp)
           {:shorturl {:href "http://example.com/Zl_A"}
            :icon {:href "http://example.com/foo.png"}}))))
