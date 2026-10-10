(ns glimmer-android.conformance-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.string :as str]
            [glimmer.ratom :as r]
            [glimmer-android.core :as ga]
            [glimmer-android.fake :as fake]
            [glimmer-android.ffi :as f]
            [glimmer-android.widget :as w]))

(use-fixtures :each fake/run)

(defn- screen!
  "Mount a component whose hiccup the answered atom holds, from `hiccup`."
  [hiccup]
  (let [ui (r/atom hiccup)]
    (ga/mount! [(fn [] @ui)])
    ui))

(defn- top
  "The view mounted at the root."
  []
  (first (fake/children 0)))

(deftest create
  (screen! [:vbox {} [:label {:label "Hole 1"}] [:button {:label "Next"}]])
  (let [v (top) [l b :as cs] (fake/children v)]
    (is (= 3 (count (fake/live))) "three views")
    (is (= 2 (count cs)))
    (is (= [1 2 3] (mapv fake/kind [v l b])) "a vbox holding a label, then a button")
    (is (= ["Hole 1" "Next"] (mapv fake/text cs)))))

(deftest apply-props
  (let [ui (screen! [:vbox {} [:label {:label "Hole 1"}]])
        l  (first (fake/children (top)))]
    (swap! ui assoc-in [2 1 :label] "Hole 2")
    (is (= [l] (fake/children (top))) "the same view")
    (is (= "Hole 2" (fake/text l)))
    (is (= 2 (count (fake/calls "ga_create"))) "nothing made")))

(deftest append
  (let [ui (screen! [:vbox {} [:label {:label "a"}] [:button {:label "b"}]])
        before (fake/children (top))]
    (swap! ui conj [:label {:label "last"}])
    (let [cs (fake/children (top))]
      (is (= 4 (count (fake/live))) "a fourth view")
      (is (= before (pop cs)) "the others where they were")
      (is (= "last" (fake/text (peek cs))) "the new one last"))))

(deftest remove-child
  (let [ui (screen! [:vbox {} [:label {:label "a"}] [:button {:label "b"}]])
        [l b] (fake/children (top))]
    (swap! ui pop)
    (is (= [l] (fake/children (top))))
    (is (= [b] (fake/released)))))

(deftest replace-child
  (let [ui (screen! [:vbox {} [:label {:label "x"}] [:label {:label "y"}]])
        [l y] (fake/children (top))]
    (swap! ui assoc 2 [:button {:label "x"}])
    (let [[n y'] (fake/children (top))]
      (is (not= l n) "a new id")
      (is (= 3 (fake/kind n)) "a button, in the label's place")
      (is (= y y') "its sibling untouched")
      (is (= [l] (fake/released)) "the label released"))))

(defn- keyed [ks]
  (into [:vbox {}] (for [k ks] ^{:key k} [:label {:label (name k)}])))

(deftest reorder
  (let [ui (screen! (keyed [:a :b :c]))
        before (fake/children (top))]
    (reset! ui (keyed [:c :b :a]))
    (is (= (reverse before) (fake/children (top))) "the same views, reversed in C")
    (is (= ["c" "b" "a"] (mapv fake/text (fake/children (top)))))
    (is (= (fake/children (top)) (get-in @w/tree [(top) :children])) "and in the tree")
    (is (empty? (fake/released)) "nothing released")))

(deftest prop-removal
  (let [ui (screen! [:vbox {:background "#1a5c38"}])]
    (is (pos? (first (fake/last-set (top) "ga_set_box"))))
    (reset! ui [:vbox {}])
    (is (= 0 (first (fake/last-set (top) "ga_set_box"))) "the colour is 0")))

(deftest handler-replacement
  (let [hit (atom nil)
        ui  (screen! [:vbox {} [:button {:label "Tap" :on-click #(reset! hit :first)}]])
        b   (first (fake/children (top)))]
    (swap! ui assoc-in [2 1 :on-click] #(reset! hit :second))
    (is (= [b] (fake/children (top))) "the same button")
    (fake/tap! b)
    (is (= :second @hit) "the new handler runs")
    (is (= [["ga_set_on_click" b 1]] (fake/calls "ga_set_on_click")) "the listener set once")))

(deftest subtree-release
  (let [ui (screen! [:vbox {} [:vbox {} [:hbox {} [:label {:label "a"}] [:label {:label "b"}]]]])
        inner (first (fake/children (top)))
        hbox  (first (fake/children inner))
        [a b] (fake/children hbox)]
    (reset! ui [:vbox {}])
    (is (empty? (fake/children (top))))
    (is (= [a b hbox inner] (fake/released)) "all four, children first")
    (is (= [(top)] (fake/live)))))

(deftest teardown
  (screen! [:vbox {} [:label {:label "a"}] [:hbox {} [:button {:label "b" :on-click identity}]]])
  (let [made (keys (dissoc (fake/views) 0))]
    (ga/stop!)
    (is (empty? (fake/children 0)) "the root is empty")
    (is (= (set made) (set (fake/released))) "every id released")
    (is (empty? (fake/live)))
    (is (empty? @w/handlers) "no handler")
    (is (= {0 {:tag :root :children []}} @w/tree))))

(deftest released-ids-are-refused
  (let [ui (screen! [:vbox {} [:label {:label "a"}] [:label {:label "b"}]])
        gone (second (fake/children (top)))]
    (swap! ui pop)
    (let [e (try (f/set-text gone "again") nil (catch :default e e))]
      (is (some? e) "Clojure refuses it")
      (is (str/includes? (ex-message e) "ga_set_text"))
      (is (str/includes? (ex-message e) (str gone)))
      (is (= {:call "ga_set_text" :id gone} (ex-data e))))
    (is (empty? (fake/refused)) "C never saw it")
    (is (= 0 (f/ga-set-text* gone "again")) "and C refuses it too")))

(deftest an-id-is-never-given-out-twice
  (let [ui (screen! [:vbox {} [:label {:label "a"}] [:label {:label "b"}] [:label {:label "c"}]])]
    (swap! ui pop)                                    ; remove c
    (swap! ui assoc 2 [:button {:label "a"}])         ; replace a
    (swap! ui into [[:label {:label "d"}] [:label {:label "e"}]])
    (is (= 2 (count (fake/released))))
    (is (every? true? (second (reduce (fn [[gone oks] [ev id]]
                                        (if (= :release ev)
                                          [(conj gone id) oks]
                                          [gone (conj oks (every? #(> id %) gone))]))
                                      [#{} []] (fake/history))))
        "every id made is greater than every id released before it")
    (is (apply distinct? (map second (filter #(= :create (first %)) (fake/history)))))
    (is (not-any? (set (fake/released)) (keys @w/tree)) "no released id in the tree")
    (is (empty? (fake/refused)))))
