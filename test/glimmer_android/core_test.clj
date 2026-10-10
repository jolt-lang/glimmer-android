(ns glimmer-android.core-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [glimmer.ratom :as r]
            [glimmer-android.core :as ga]
            [glimmer-android.fake :as fake]
            [glimmer-android.widget :as w]))

(use-fixtures :each fake/run)

(defn- texts [] (keep fake/text (keys (fake/views))))

(deftest a-tap-re-renders-the-label
  (let [n (r/atom 0)
        screen (fn [] [:vbox {} [:label {:label (str "Tapped " @n " times")}]
                                [:button {:label "Tap" :on-click #(swap! n inc)}]])]
    (ga/mount! [screen])
    (is (some #{"Tapped 0 times"} (texts)))
    (fake/tap! (fake/first-of-kind 3))
    (is (some #{"Tapped 1 times"} (texts)) "re-rendered before the tap returned")
    (ga/stop!)))

(deftest stopping-unmounts-and-forgets
  (ga/mount! [(fn [] [:vbox {} [:button {:label "Tap" :on-click identity}]])])
  (ga/stop!)
  (is (empty? (fake/children 0)) "the root is empty")
  (is (= [2 1] (fake/released)) "the button, then the vbox that held it")
  (is (= {0 {:tag :root :children []}} @w/tree) "the tree is forgotten")
  (is (empty? @w/handlers) "no handler outlives the screen"))

(deftest a-keyed-reorder-moves-the-view
  (let [order (r/atom [:a :b :c])]
    (ga/mount! [(fn [] [:vbox {} (for [k @order] [:label {:key k :label (name k)}])])])
    (reset! order [:c :a :b])
    (let [vbox (first (fake/children 0))]
      (is (= ["c" "a" "b"] (mapv fake/text (fake/children vbox))) "in C")
      (is (= (fake/children vbox) (get-in @w/tree [vbox :children])) "in the tree"))
    (ga/stop!)))

(deftest a-reorder-after-a-missing-sibling-goes-last
  (reset! w/tree {0 {:tag :root :children []} 1 {:tag :vbox :children [2 3]}})
  (let [out (with-out-str (is (= 1 (#'ga/after 1 3 99)) "after the one other child"))]
    (is (re-find #"no sibling 99" out)))
  (w/reset-tree!))

(deftest a-handler-that-throws-is-caught-and-printed
  (ga/mount! [(fn [] [:button {:label "Bad" :on-click #(throw (ex-info "boom" {}))}])])
  (let [out (with-out-str (fake/tap! (fake/first-of-kind 3)))]
    (is (re-find #"a handler threw: boom" out)))
  (ga/stop!))

(deftest taking-back-tells-c-once-per-change
  (ga/take-back! true)
  (ga/take-back! true)
  (ga/take-back! false)
  (ga/take-back! false)
  (ga/take-back! true)
  (is (= [["ga_set_back" 1] ["ga_set_back" 0] ["ga_set_back" 1]] (fake/calls "ga_set_back"))
      "a repeat of the same value calls nothing"))

(deftest a-back-runs-the-handler-and-nothing-else
  (let [backs (atom 0)]
    (ga/back! #(swap! backs inc))
    (ga/mount! [(fn [] [:button {:label "Tap" :on-click identity}])])
    (let [before (count (fake/calls))]
      (fake/back!)
      (is (= 1 @backs) "the handler ran")
      (is (= before (count (fake/calls))) "nothing else reached C"))
    (ga/back! nil)
    (ga/stop!)))

(deftest a-back-with-no-handler-does-nothing
  (ga/back! nil)
  (is (= "" (with-out-str (fake/back!))) "nothing printed, nothing thrown")
  (is (empty? (fake/calls)) "nothing reached C"))

(deftest a-back-handler-that-throws-is-caught-and-printed
  (ga/back! #(throw (ex-info "boom" {})))
  (let [out (with-out-str (fake/back!))]
    (is (re-find #"the Back handler threw: boom" out)))
  (ga/back! nil))

(deftest stopping-forgets-back-taken-but-keeps-the-handler
  (let [backs (atom 0)]
    (ga/back! #(swap! backs inc))
    (ga/take-back! true)
    (ga/stop!)
    (ga/take-back! true)
    (is (= [["ga_set_back" 1] ["ga_set_back" 1]] (fake/calls "ga_set_back"))
        "the next Activity starts with no callback, so C is told again")
    (fake/back!)
    (is (= 1 @backs) "the handler outlives the Activity")
    (ga/back! nil)))

(deftest the-files-directory-and-the-version
  (is (= "/tmp/fake-files" (ga/files-dir)))
  (is (= "1.0" (ga/version)))
  (binding [fake/*files-dir* "" fake/*version* ""]
    (is (nil? (ga/files-dir)) "an empty one is none")
    (is (nil? (ga/version)) "an empty one is none")))

(deftest a-stop-runs-the-background-handler
  (let [runs (atom [])]
    (ga/on-background! #(swap! runs conj :first))
    (fake/background!)
    (is (= [:first] @runs))
    (testing "a second on-background! replaces the first"
      (ga/on-background! #(swap! runs conj :second))
      (fake/background!)
      (is (= [:first :second] @runs)))
    (testing "a handler that throws is printed, not passed to C"
      (ga/on-background! #(throw (ex-info "boom" {})))
      (is (re-find #"the background handler threw: boom" (with-out-str (fake/background!)))))
    (ga/on-background! nil)))

(deftest a-link-reaches-c
  (is (nil? (ga/open-url! "https://example.com")))
  (is (= [["ga_open_url" "https://example.com"]] (fake/calls "ga_open_url")))
  (testing "nothing to open it is printed, not thrown"
    (binding [fake/*opens?* false]
      (let [out (with-out-str (is (nil? (ga/open-url! "mailto:someone@example.com"))))]
        (is (re-find #"nothing opened mailto:someone@example.com" out))))))
