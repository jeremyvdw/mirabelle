(ns mirabelle.integration-test
  (:require [cheshire.core :as json]
            [clojure.test :refer :all]
            [org.httpkit.client :as http]
            [mirabelle.b64 :as b64]
            [mirabelle.core :as core]))

(defn system-fixture
  [f]
  (core/start!)
  (f)
  (core/stop!))

(use-fixtures :once system-fixture)

(deftest http-test
  (testing "healthz"
    (let [resp @(http/get "http://localhost:5558/healthz")]
      (is (= 200 (:status resp)))
      (is (= {:message "ok"}
             (-> resp :body (json/parse-string true))))))
  (testing "add-stream"
    (let [body (-> {:config (-> {:actions {:action :index :params [[:host]]}}
                                pr-str
                                b64/to-base64)}
                   json/generate-string)
          resp @(http/post "http://localhost:5558/api/v1/stream/test-foo"
                           {:headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body body})]
      (is (= 200 (:status resp)))
      (is (= {:message "stream added"}
             (-> resp :body (json/parse-string true))))))
  (testing "push-event"
    (let [body (-> {:event {:host "test-foo" :time 3}}
                   json/generate-string)
          resp @(http/put "http://localhost:5558/api/v1/stream/test-foo"
                          {:headers {"Content-Type" "application/json"
                                     "Accept" "application/json"}
                           :body body})]
      (is (= 200 (:status resp)))
      (is (= {:message "ok"}
             (-> resp :body (json/parse-string true)))))
    (let [body (-> {:event {:host "test-foo"}}
                   json/generate-string)
          resp @(http/put "http://localhost:5558/api/v1/stream/test-bar"
                          {:headers {"Content-Type" "application/json"
                                     "Accept" "application/json"}
                           :body body})]
      (is (= 404 (:status resp)))
      (is (= {:error "Stream :test-bar not found"}
             (-> resp :body (json/parse-string true))))))
  (testing "search-index"
    (let [body (-> {:query (-> [:always-true]
                               pr-str
                               b64/to-base64)}
                   json/generate-string)
          resp @(http/post "http://localhost:5558/api/v1/index/test-foo/search"
                           {:headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body body})]
      (is (= 200 (:status resp)))
      (is (= {:events [{:host "test-foo" :time 3}]}
             (-> resp :body (json/parse-string true)))))
    (let [body (-> {:query (-> [:always-true]
                               pr-str
                               b64/to-base64)}
                   json/generate-string)
          resp @(http/post "http://localhost:5558/api/v1/index/test-bar/search"
                           {:headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body body})]
      (is (= 404 (:status resp)))
      (is (= {:error "stream :test-bar not found"}
             (-> resp :body (json/parse-string true))))))
  (testing "get-stream"
    (let [resp @(http/get "http://localhost:5558/api/v1/stream/test-foo"
                          {:headers {"Accept" "application/json"}})]
      (is (= 200 (:status resp)))
      (is (= {:config (-> {:actions {:action :index :params [[:host]]} :default false}
                          pr-str
                          b64/to-base64)
              :current-time 3}
             (-> resp :body (json/parse-string true))))))
  (testing "list-streams"
    (let [resp @(http/get "http://localhost:5558/api/v1/stream"
                          {:headers {"Accept" "application/json"}})
          streams (-> resp :body (json/parse-string true) :streams set)]
      (is (= 200 (:status resp)))
      (is (streams "test-foo"))))
  (testing "current-time"
    (let [resp @(http/get "http://localhost:5558/api/v1/current-time"
                          {:headers {"Accept" "application/json"}})]
      (is (= 200 (:status resp)))
      (is (= {:current-time 0}
             (-> resp :body (json/parse-string true))))))
  (testing "remove-stream"
    (let [resp @(http/delete "http://localhost:5558/api/v1/stream/test-foo"
                             {:headers {"Accept" "application/json"}})]
      (is (= 200 (:status resp)))
      (is (= {:message "stream removed"}
             (-> resp :body (json/parse-string true)))))
    (let [resp @(http/get "http://localhost:5558/api/v1/stream")
          streams (-> resp :body (json/parse-string true) :streams set)]
      (is (= 200 (:status resp)))
      (is (not (streams "test-foo")))))
  (testing "not-found"
    (let [resp @(http/delete "http://localhost:5558/api/v1/notfound"
                             {:headers {"Accept" "application/json"}})]
      (is (= 404 (:status resp)))
      (is (= {:error "not found"}
             (-> resp :body (json/parse-string true))))))
  (testing "search-index: wring parameter"
    (let [body (-> {:query nil}
                   json/generate-string)
          resp @(http/post "http://localhost:5558/api/v1/index/test-foo/search"
                           {:headers {"Content-Type" "application/json"
                                      "Accept" "application/json"}
                            :body body})]
      (is (= 400 (:status resp)))
      (is (= {:error "field query is incorrect"}
             (-> resp :body (json/parse-string true)))))))
