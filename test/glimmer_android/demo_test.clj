(ns glimmer-android.demo-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [glimmer-android.core :as ga]
            [glimmer-android.demo :as demo]
            [glimmer-android.fake :as fake]))

(use-fixtures :each (fn [t] (try (fake/run t) (finally (ga/back! nil)))))

(deftest a-throw-at-the-entry-is-printed-not-passed-to-c
  (let [out (with-out-str
              (with-redefs [ga/take-back! (fn [_] (throw (ex-info "boom" {})))]
                (#'demo/main)))]
    (is (re-find #"glimmer-demo: start threw: boom" out))))

(deftest the-fetches-screen-asks-the-local-server
  (ga/mount! [demo/fetches-screen])
  (let [ok (first (filter #(= "Fetch ok" (fake/text %)) (fake/live)))]
    (is ok "a Fetch ok button")
    (fake/tap! ok)
    (is (= "http://10.0.2.2:8765/ok" (:url (last (fake/fetches)))))
    (is (= {"X-Glimmer" "demo"} (:headers (last (fake/fetches)))))
    (let [out (with-out-str (fake/answer-fetch! (:handle (last (fake/fetches))) {:status 200 :body "⛳ ok"}))]
      (is (re-find #"glimmer-demo: fetch ok -> 200 " out))))
  (ga/stop!))
