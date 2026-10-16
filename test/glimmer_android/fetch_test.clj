(ns glimmer-android.fetch-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [glimmer-android.core :as ga]
            [glimmer-android.fake :as fake]))

(use-fixtures :each fake/run)

(defn- pending [] @@#'ga/fetches)

(deftest a-fetch-asks-c-with-its-url-headers-and-timeouts
  (let [h (ga/fetch! "https://example.com/course?lat=1"
                     {:headers {"User-Agent" "Tiger5" "Accept-Language" "en"}
                      :timeout-ms {:connect 5000 :read 4000 :total 9000}}
                     (fn [_]))]
    (is (= [{:handle h :url "https://example.com/course?lat=1"
             :headers {"User-Agent" "Tiger5" "Accept-Language" "en"}
             :timeout-ms {:connect 5000 :read 4000 :total 9000}}]
           (fake/fetches)))))

(deftest the-timeouts-default-to-ten-five-and-ten
  (ga/fetch! "https://example.com/" {} (fn [_]))
  (is (= {:connect 10000 :read 5000 :total 10000} (:timeout-ms (first (fake/fetches)))))
  (ga/fetch! "https://example.com/" {:timeout-ms {:connect 3000}} (fn [_]))
  (is (= {:connect 3000 :read 5000 :total 10000} (:timeout-ms (second (fake/fetches))))
      "a partial map keeps the other defaults"))

(deftest an-answer-arrives-once
  (let [seen (atom [])
        h    (ga/fetch! "https://example.com/" {} #(swap! seen conj %))]
    (fake/answer-fetch! h {:status 200 :body "⛳ ok"})
    (fake/answer-fetch! h {:status 200 :body "again"})
    (is (= [{:status 200 :body "⛳ ok"}] @seen) "the second answer is dropped")
    (is (empty? (pending)))))

(deftest any-status-is-an-answer-and-no-response-is-an-error
  (let [seen (atom [])
        ask  #(ga/fetch! "https://example.com/" {} (fn [a] (swap! seen conj a)))]
    (fake/answer-fetch! (ask) {:status 404 :body "nothing here"})
    (fake/answer-fetch! (ask) {:status 204 :body nil})
    (fake/answer-fetch! (ask) {:error "SocketTimeoutException: timeout"})
    (is (= [{:status 404 :body "nothing here"}
            {:status 204 :body ""}
            {:error "SocketTimeoutException: timeout"}]
           @seen))))

(deftest a-cancelled-fetch-is-never-answered
  (let [seen (atom [])
        h    (ga/fetch! "https://example.com/" {} #(swap! seen conj %))]
    (is (nil? (ga/cancel-fetch! h)))
    (is (= [h] (fake/cancels)) "C was asked to cancel it")
    (fake/answer-fetch! h {:status 200 :body "late"})
    (is (empty? @seen) "an answer already posted when the cancel ran is dropped")))

(deftest a-cancel-with-nothing-pending-does-nothing
  (is (nil? (ga/cancel-fetch! -1)))
  (let [h (ga/fetch! "https://example.com/" {} (fn [_]))]
    (fake/answer-fetch! h {:status 200 :body ""})
    (is (nil? (ga/cancel-fetch! h)) "a fetch already answered")
    (is (nil? (ga/cancel-fetch! nil))))
  (is (empty? (fake/cancels)) "C is never asked"))

(deftest an-unknown-handle-is-dropped
  (is (nil? (fake/answer-fetch! 12345 {:status 200 :body "?"}))))

(deftest a-fetch-that-cannot-start-throws
  (binding [fake/*fetches?* false]
    (is (= "glimmer-android: could not start a fetch of https://example.com/x"
           (try (ga/fetch! "https://example.com/x" {} (fn [_])) nil
                (catch :default e (ex-message e))))))
  (is (empty? (pending)) "nothing is left pending"))

(deftest a-callback-that-throws-is-printed
  (let [h   (ga/fetch! "https://example.com/" {} (fn [_] (throw (ex-info "boom" {}))))
        out (with-out-str (fake/answer-fetch! h {:status 200 :body ""}))]
    (is (re-find #"glimmer-android: a fetch's callback threw: boom" out))))
