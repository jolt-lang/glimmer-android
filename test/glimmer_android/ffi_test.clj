(ns glimmer-android.ffi-test
  (:require [clojure.test :refer [deftest is]]
            [glimmer-android.ffi :as f]))

(deftest a-failed-call-is-refused-by-name
  (with-redefs [f/ga-create* (constantly 0)]
    (is (= "glimmer-android: ga_create failed; logcat says why"
           (try (f/create 1) nil (catch :default e (ex-message e)))))))

(deftest a-good-call-answers-its-value
  (with-redefs [f/ga-create* (constantly 7)]
    (is (= 7 (f/create 1)))))

(deftest arguments-take-the-declared-types
  (let [seen (atom nil)]
    (with-redefs [f/ga-set-padding* (fn [& args] (reset! seen args) 1)
                  f/ga-set-checked* (fn [& args] (reset! seen args) 1)]
      (f/set-padding 3 24 24 24.0 8)
      (is (= [3 24.0 24.0 24.0 8.0] (vec @seen)) "longs become doubles")
      (f/set-checked 3 true)
      (is (= [3 1] (vec @seen)) "a boolean becomes 1"))))

(deftest a-released-view-is-refused
  (f/reset-released!)
  (with-redefs [f/ga-release* (constantly 1)
                f/ga-set-text* (constantly 1)]
    (f/release 5)
    (is (= "glimmer-android: ga_set_text on released view 5"
           (try (f/set-text 5 "x") nil (catch :default e (ex-message e)))))
    (f/reset-released!)
    (is (= 1 (f/set-text 5 "x")) "forgotten when the Activity goes")))
