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
