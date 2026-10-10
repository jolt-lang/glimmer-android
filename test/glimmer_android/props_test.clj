(ns glimmer-android.props-test
  (:require [clojure.test :refer [deftest is testing]]
            [glimmer-android.props :as p]))

(deftest colours-are-argb
  (is (= 0xFFFFFFFF (p/argb "#ffffff")))
  (is (= 0xFF006747 (p/argb "#006747")))
  (is (= 0xFFFFFFFF (p/argb "#fff")) "the short form")
  (is (= 0xD9FFFFFF (p/argb "#ffffff" 0.85)) "alpha 0.85 is 217")
  (is (= 0 (p/argb nil)) "none is transparent"))

(deftest pango-units-are-points
  (is (= 56.0 (p/pango-pt "57344")))
  (is (= 15.0 (p/pango-pt "15360")))
  (is (< -1.17 (p/pango-pt "-1187") -1.15)))

(deftest markup-becomes-runs
  (testing "one span: the whole text, one style"
    (is (= {:text "ROUNDS"
            :runs [{:start 0 :end 6 :size 34.0 :color 0xFFFFFFFF :bold true}]}
           (p/runs [:span {:size "34816" :foreground "#ffffff" :weight "bold"} "ROUNDS"]))))
  (testing "letter spacing in em, and a family read only as bold"
    (let [{:keys [runs]} (p/runs [:span {:size "57344" :font_family "Helvetica-Bold"
                                         :letter_spacing "-1187"} "Headline"])
          r (first runs)]
      (is (:bold r))
      (is (< -0.021 (:tracking r) -0.020) "-1.16 pt over 56 pt")))
  (testing "nested spans become runs over their own text"
    (is (= {:text "a b"
            :runs [{:start 0 :end 3 :size 17.0}
                   {:start 2 :end 3 :size 17.0 :bold true}]}
           (p/runs [:span {} "a " [:span {:weight "bold"} "b"]]))))
  (testing "a first child that is a span: the parent is the base run"
    (is (= {:text "Hole 3 of 18"
            :runs [{:start 0 :end 12 :size 17.0}
                   {:start 0 :end 6 :size 17.0 :bold true}]}
           (p/runs [:span {:size "17408"} [:span {:weight "bold"} "Hole 3"] " of 18"]))))
  (testing "a string is one plain run at the default size"
    (is (= {:text "hi" :runs [{:start 0 :end 2 :size 17.0}]} (p/runs "hi"))))
  (testing "Pango's style tags style their text"
    (is (= {:text "bisu"
            :runs [{:start 0 :end 4 :size 17.0}
                   {:start 0 :end 1 :size 17.0 :bold true}
                   {:start 1 :end 2 :size 17.0 :italic true}
                   {:start 2 :end 3 :size 17.0 :strike true}
                   {:start 3 :end 4 :size 17.0 :underline true}]}
           (p/runs [:span {} [:b "b"] [:i "i"] [:s "s"] [:u "u"]]))))
  (testing "styles nest; at one range the inner run, which has them all, is first"
    (is (= {:start 0 :end 1 :size 17.0 :bold true :italic true}
           (first (:runs (p/runs [:b [:i "x"]]))))))
  (testing "a span's style, strikethrough and underline"
    (let [r #(first (:runs (p/runs [:span % "x"])))]
      (is (:italic (r {:style "italic"})))
      (is (:italic (r {:style "oblique"})))
      (is (not (:italic (r {:style "normal"}))))
      (is (:strike (r {:strikethrough "true"})))
      (is (not (:strike (r {:strikethrough "false"}))))
      (is (:underline (r {:underline "single"})))
      (is (:underline (r {:underline "true"})) "glimmer-uikit's word")
      (is (not (:underline (r {:underline "none"}))))))
  (testing "an element that styles nothing keeps its text, and is named"
    (is (= {:text "ab" :runs [{:start 0 :end 2 :size 17.0} {:start 1 :end 2 :size 17.0}] :plain #{:tt}}
           (p/runs [:span {} "a" [:tt "b"]]))))
  (testing "offsets count UTF-16 units, as Android's spans do"
    (is (= [2 3] ((juxt :start :end) (second (:runs (p/runs [:span {} "😀" [:span {:weight "bold"} "x"]])))))
        "an emoji is two units")))

(deftest bold-and-italic-are-a-typeface-style
  (is (= [0 1 2 3] (map p/typeface [{} {:bold true} {:italic true} {:bold true :italic true}]))))

(deftest xalign-is-gravity
  (is (= :start (p/side 0)))
  (is (= :center (p/side 0.5)))
  (is (= :end (p/side 1)))
  (is (= :center (p/side nil))))

(deftest the-stack-aligns-to-its-last-aligned-child
  (is (= :fill (p/stack-align :vbox [{:halign :start} {} {:halign :fill}])))
  (is (= :center (p/stack-align :vbox [{} {}])))
  (is (= :center (p/stack-align :hbox [{:halign :fill}])) "an hbox reads :valign"))

(deftest layout-params
  (testing "in a vbox: :fill fills the width, :vexpand takes the slack"
    (is (= {:w :match :h :wrap :weight 0 :gravity :center-h}
           (p/layout :vbox :fill {})) "fill gives match; gravity is ignored by match")
    (is (= {:w :wrap :h :wrap :weight 0 :gravity :center-h} (p/layout :vbox :center {})))
    (is (= {:w :match :h 0 :weight 1 :gravity :center-h} (p/layout :vbox :fill {:vexpand true})))
    (is (= {:w 56 :h 56 :weight 0 :gravity :center-h} (p/layout :vbox :fill {:width 56 :height 56}))))
  (testing "in an hbox: :hexpand takes the width"
    (is (= {:w 0 :h :wrap :weight 1 :gravity :center-v} (p/layout :hbox :center {:hexpand true}))))
  (testing "in layers, and at the root"
    (is (= {:w :match :h :match :weight 0 :gravity :none} (p/layout :layers :center {})))
    (is (= {:w :match :h :wrap :weight 0 :gravity :none} (p/layout :root :center {})))
    (is (= {:w :match :h :match :weight 0 :gravity :none} (p/layout :root :center {:vfill true})))
    (is (= {:w :match :h :match :weight 0 :gravity :none} (p/layout :root :center {:full-bleed true})))))

(deftest gradient-stops
  (is (= [[0x8C003424 0.0] [0x1A006747 0.42] [0xBF002D1F 1.0]]
         (p/stops [["#003424" 0.55 0.0] ["#006747" 0.1 0.42] ["#002d1f" 0.75 1.0]]))))
