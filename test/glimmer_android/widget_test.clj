(ns glimmer-android.widget-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [glimmer-android.ffi :as f]
            [glimmer-android.widget :as w]))

(def ^:private c-half
  "Every binding the widget layer calls, by its C name."
  [["ga_create" #'f/ga-create*] ["ga_set_text" #'f/ga-set-text*]
   ["ga_set_size" #'f/ga-set-size*] ["ga_set_on_click" #'f/ga-set-on-click*]
   ["ga_append" #'f/ga-append*] ["ga_remove" #'f/ga-remove*]
   ["ga_replace" #'f/ga-replace*] ["ga_release" #'f/ga-release*]
   ["ga_text_style" #'f/ga-text-style*] ["ga_runs_begin" #'f/ga-runs-begin*]
   ["ga_run" #'f/ga-run*] ["ga_runs_end" #'f/ga-runs-end*]
   ["ga_set_gravity" #'f/ga-set-gravity*] ["ga_set_lines" #'f/ga-set-lines*]
   ["ga_set_box" #'f/ga-set-box*] ["ga_plain" #'f/ga-plain*]
   ["ga_set_alpha" #'f/ga-set-alpha*] ["ga_set_enabled" #'f/ga-set-enabled*]
   ["ga_set_padding" #'f/ga-set-padding*] ["ga_set_safe" #'f/ga-set-safe*]
   ["ga_set_full_bleed" #'f/ga-set-full-bleed*] ["ga_set_layout" #'f/ga-set-layout*]
   ["ga_set_spacing" #'f/ga-set-spacing*] ["ga_set_checked" #'f/ga-set-checked*]
   ["ga_set_description" #'f/ga-set-description*] ["ga_set_image" #'f/ga-set-image*]
   ["ga_stop" #'f/ga-stop*] ["ga_set_gradient" #'f/ga-set-gradient*]
   ["ga_set_translation_y" #'f/ga-set-translation-y*] ["ga_move" #'f/ga-move*]
   ["ga_set_min_height" #'f/ga-set-min-height*]])

(def ^:private calls (atom []))

(defn- typed?
  "Whether `x` is what C declares as `t`."
  [t x]
  (case t (:int :long) (int? x) :double (double? x) :string (string? x)))

(defn- recorder
  "A stand-in for C's `nm`: check the types, keep the call, answer."
  [nm answer]
  (fn [& args]
    (let [types (f/signatures nm)]
      (when-not (and (= (count types) (count args)) (every? true? (map typed? types args)))
        (throw (ex-info (str nm " got " (pr-str args) ", not " (pr-str types)) {}))))
    (swap! calls conj (into [nm] args))
    (answer)))

(defn- recording
  "Run `body-fn` with the C half replaced by recorders, from an empty
  screen, and answer the calls. ga_create answers ids from 1."
  [body-fn]
  (let [ids (atom 0)]
    (reset! calls [])
    (reset! w/handlers {})
    (w/reset-tree!)
    (f/reset-released!)
    (with-redefs-fn (into {} (for [[nm v] c-half]
                               [v (recorder nm (if (= nm "ga_create") #(swap! ids inc) (constantly 1)))]))
      body-fn)
    @calls))

(defn- after-create
  "The calls that re-rendering `tag` from `props` to `new-props` makes."
  [tag props new-props]
  (recording (fn [] (let [id (w/create! tag props)]
                      (reset! calls [])
                      (w/apply-props! tag id new-props)))))

(defn- named
  "Only the calls to `nm`."
  [nm cs]
  (filterv #(= nm (first %)) cs))

(deftest each-tag-makes-its-view
  (is (= [["ga_create" 1] ["ga_set_alpha" 1 1.0] ["ga_set_translation_y" 1 0.0]
          ["ga_set_box" 1 0 0.0 0.0 0] ["ga_set_padding" 1 0.0 0.0 0.0 0.0]
          ["ga_set_spacing" 1 0.0] ["ga_set_safe" 1 0]]
         (recording #(w/create! :vbox {}))))
  (is (= [["ga_create" 2] ["ga_set_alpha" 1 1.0] ["ga_set_translation_y" 1 0.0]
          ["ga_text_style" 1 17.0 0 0 0 0.0] ["ga_set_text" 1 "Hello"]
          ["ga_set_gravity" 1 17] ["ga_set_lines" 1 1]]
         (recording #(w/create! :label {:label "Hello"}))))
  (is (= [["ga_create" 3] ["ga_plain" 1] ["ga_set_alpha" 1 1.0] ["ga_set_translation_y" 1 0.0]
          ["ga_set_text" 1 "Tap"] ["ga_text_style" 1 15.0 0 0 0 0.0]
          ["ga_set_box" 1 0 0.0 0.0 0] ["ga_set_padding" 1 0.0 0.0 0.0 0.0]
          ["ga_set_enabled" 1 1] ["ga_set_gravity" 1 17] ["ga_set_on_click" 1 1]]
         (recording #(w/create! :button {:label "Tap" :on-click identity})))))

(deftest each-of-the-nine-tags-makes-its-kind
  (doseq [[tag kind] [[:vbox 1] [:label 2] [:button 3] [:hbox 4] [:layers 5]
                      [:scroll 6] [:checkbutton 7] [:image 8] [:gradient 9]]]
    (is (= ["ga_create" kind] (first (recording #(w/create! tag {})))) (str tag))))

(deftest a-size-is-in-dp-and-a-missing-side-wraps
  (is (= [["ga_append" 1 2] ["ga_set_layout" 2 60 -2 0.0 1]]
         (recording #(let [v (w/create! :vbox {}) l (w/create! :label {:label "x" :width 60})]
                       (reset! calls [])
                       (w/append! v l))))))

(deftest markup-sets-the-style-and-the-text
  (testing "one run: its size, colour, bold and tracking, then the text"
    (is (= [["ga_text_style" 1 56.0 1 0xFFFFFFFF 1 0.0] ["ga_set_text" 1 "Headline"]]
           (after-create :label {} {:markup [:span {:size "57344" :foreground "#ffffff" :weight "bold"} "Headline"]}))))
  (testing "no colour: 0 and 0, so the view keeps its own"
    (is (= [["ga_text_style" 1 34.0 0 0 0 0.0] ["ga_set_text" 1 "R"]]
           (after-create :label {} {:markup [:span {:size "34816"} "R"]}))))
  (testing "two runs that differ: the first is the style, the second a span"
    (is (= [["ga_text_style" 1 17.0 0 0 0 0.0] ["ga_runs_begin" 1 "a b"]
            ["ga_run" 2 3 0xFFFF0000 1 0.0] ["ga_runs_end" 1]]
           (after-create :label {} {:markup [:span {} "a " [:span {:weight "bold" :foreground "#ff0000"} "b"]]})))))

(deftest a-label-is-one-line-unless-it-wraps
  (is (= [["ga_set_lines" 1 1]] (named "ga_set_lines" (recording #(w/create! :label {:label "x"})))))
  (is (= [["ga_set_lines" 1 0]] (named "ga_set_lines" (recording #(w/create! :label {:label "x" :wrap true}))))))

(deftest xalign-is-gravity
  (is (= [["ga_set_gravity" 1 0x800013]] (after-create :label {} {:xalign 0})))
  (is (= [["ga_set_gravity" 1 17]] (after-create :label {:xalign 0} {:xalign 0.5}))))

(deftest a-button-puts-the-date-before-its-label
  (with-redefs [f/format-date (constantly "Oct 6, 2026")]
    (is (= [["ga_set_text" 1 "Oct 6, 2026 · Resume"]]
           (after-create :button {:label "Tap"} {:label " · Resume" :label-date 1759766400000})))))

(deftest a-label-draws-a-date-styled-by-its-markup
  (with-redefs [f/format-date (constantly "Oct 6, 2026")]
    (testing "the date, formatted by the platform, styled as a span"
      (is (= [["ga_text_style" 1 24.0 1 0xFFFFFFFF 1 0.0] ["ga_set_text" 1 "Oct 6, 2026"]]
             (after-create :label {:label "x"}
                           {:date 1759766400000
                            :date-markup {:size "24576" :foreground "#FFFFFF" :weight "bold"}}))))
    (testing "no unknown-prop line for either"
      (reset! w/warned #{})   ; warn-unknown! prints a pair once per run
      (is (not (str/includes? (with-out-str (after-create :label {} {:date 1759766400000 :date-markup {}}))
                              "unknown prop"))))))

(deftest an-insensitive-button-is-disabled-and-dimmed
  (is (= [["ga_set_alpha" 1 0.4] ["ga_set_enabled" 1 0]]
         (after-create :button {} {:sensitive false}))))

(deftest a-buttons-box-is-one-call
  (is (= [["ga_set_box" 1 0xFF006747 12.0 1.5 0xD9FFFFFF]]
         (after-create :button {} {:background "#006747" :radius 12 :border [1.5 "#ffffff" 0.85]}))))

(deftest a-checkbutton-ticks-and-describes
  (is (= [["ga_set_checked" 1 1] ["ga_set_text" 1 "✓"] ["ga_text_style" 1 24.0 0 0 0 0.0]]
         (after-create :checkbutton {} {:active true})))
  (is (= [["ga_set_description" 1 "Hole 3 fairway"]]
         (after-create :checkbutton {} {:description "Hole 3 fairway"}))))

(deftest a-tap-leaves-the-box-as-the-app-has-it
  (testing "a handler that leaves :active alone: the box is put back"
    (is (= [["ga_set_checked" 1 0]]
           (recording #(let [id (w/create! :checkbutton {:active false :on-toggled (fn [])})]
                         (reset! calls [])
                         (w/event! id 0))))))
  (testing "a handler that re-renders with :active true: the box stays ticked"
    (is (= [["ga_set_checked" 1 1] ["ga_set_text" 1 "✓"] ["ga_text_style" 1 24.0 0 0 0 0.0]
            ["ga_set_checked" 1 1]]
           (recording #(let [id (w/create! :checkbutton {:active false})]
                         (w/apply-props! :checkbutton id
                                         {:active false
                                          :on-toggled (fn [] (w/apply-props! :checkbutton id
                                                                             {:active true :on-toggled (fn [])}))})
                         (reset! calls [])
                         (w/event! id 0)))))))

(deftest nested-markup-keeps-the-parent-as-the-base
  (is (= [["ga_text_style" 1 17.0 0 0 0 0.0] ["ga_runs_begin" 1 "Hole 3 of 18"]
          ["ga_run" 0 6 0 1 0.0] ["ga_runs_end" 1]]
         (after-create :label {} {:markup [:span {:size "17408"} [:span {:weight "bold"} "Hole 3"] " of 18"]}))
      "\" of 18\" is plain: only the first six units get a bold span"))

(deftest a-prop-that-goes-away-is-reset
  (is (= [["ga_set_lines" 1 1]] (after-create :label {:label "x" :wrap true} {:label "x"})))
  (is (= [["ga_set_box" 1 0 0.0 0.0 0]] (after-create :button {:background "#006747"} {})))
  (is (= [["ga_set_image" 1 ""]] (after-create :image {:src "splash.jpg"} {})) "no :src clears the image")
  (is (= [["ga_set_box" 1 0 0.0 0.0 0]]
         (after-create :gradient {:stops [["#000000" 0.0 0.0] ["#000000" 1.0 1.0]]} {}))
      "no :stops takes the gradient away"))

(deftest spacing-and-margins-are-doubles
  (is (= [["ga_set_spacing" 1 12.0]] (after-create :vbox {} {:spacing 12})))
  (is (= [["ga_set_padding" 1 24.0 24.0 24.0 24.0]] (after-create :vbox {} {:margin 24})))
  (is (= [["ga_set_padding" 1 36.0 36.0 36.0 8.0]] (after-create :vbox {} {:margin 36 :margin-bottom 8}))))

(deftest center-y-is-a-translation
  (is (= [["ga_set_translation_y" 1 -84.0]] (after-create :vbox {} {:center-y -84}))))

(deftest a-gradient-is-its-stops
  (is (= [["ga_create" 9] ["ga_set_alpha" 1 1.0] ["ga_set_translation_y" 1 0.0]
          ["ga_stop" 0x8C003424 0.0] ["ga_stop" 0xBF002D1F 1.0] ["ga_set_gradient" 1]]
         (recording #(w/create! :gradient {:stops [["#003424" 0.55 0.0] ["#002d1f" 0.75 1.0]]})))))

(deftest an-image-is-its-src
  (is (= [["ga_create" 8] ["ga_set_alpha" 1 1.0] ["ga_set_translation_y" 1 0.0]
          ["ga_set_image" 1 "splash.jpg"]]
         (recording #(w/create! :image {:src "splash.jpg"})))))

(deftest a-buttons-height-is-its-least
  (testing "a button wraps, with its :height as the floor"
    (is (= [["ga_append" 1 2] ["ga_set_layout" 2 -1 -2 0.0 1] ["ga_set_min_height" 2 56.0]]
           (recording #(let [v (w/create! :vbox {}) b (w/create! :button {:label "Rounds" :height 56 :halign :fill})]
                         (reset! calls [])
                         (w/append! v b))))))
  (testing "and a checkbutton"
    (is (= [["ga_append" 1 2] ["ga_set_layout" 2 56 -2 0.0 16] ["ga_set_min_height" 2 56.0]]
           (recording #(let [h (w/create! :hbox {}) b (w/create! :checkbutton {:width 56 :height 56})]
                         (reset! calls [])
                         (w/append! h b))))))
  (testing "a label's :height is exact"
    (is (= [["ga_append" 1 2] ["ga_set_layout" 2 -2 20 0.0 1]]
           (recording #(let [v (w/create! :vbox {}) l (w/create! :label {:label "x" :height 20})]
                         (reset! calls [])
                         (w/append! v l)))))))

(deftest a-fill-child-matches-the-width
  (is (= [["ga_append" 1 2] ["ga_set_layout" 2 -1 -2 0.0 1]]
         (recording #(let [v (w/create! :vbox {}) l (w/create! :label {:halign :fill})]
                       (reset! calls [])
                       (w/append! v l))))))

(deftest a-layout-prop-changed-lays-the-parent-out-again
  (is (= [["ga_set_layout" 2 -2 0 1.0 1]]
         (recording #(let [v (w/create! :vbox {}) l (w/create! :label {})]
                       (w/append! v l)
                       (reset! calls [])
                       (w/apply-props! :label l {:vexpand true}))))))

(deftest the-roots-child-says-whether-it-is-full-bleed
  (is (= [["ga_append" 0 1] ["ga_set_layout" 1 -1 -1 0.0 -1] ["ga_set_full_bleed" 1]]
         (recording #(let [l (w/create! :layers {:full-bleed true})]
                       (reset! calls [])
                       (w/append! 0 l))))))

(deftest removing-a-view-releases-its-subtree-children-first
  (let [cs (recording #(let [v (w/create! :vbox {}) l (w/create! :button {:on-click identity})]
                         (w/append! 0 v)
                         (w/append! v l)
                         (reset! calls [])
                         (w/remove! 0 v)))]
    (is (= [["ga_remove" 0 1] ["ga_release" 2] ["ga_release" 1] ["ga_set_full_bleed" 0]] cs))
    (is (= {0 {:tag :root :children []}} @w/tree))
    (is (empty? @w/handlers))
    (is (= #{1 2} @f/released))))

(deftest replacing-a-view-keeps-its-place
  (let [cs (recording #(let [v (w/create! :vbox {}) a (w/create! :label {}) b (w/create! :label {})
                             c (w/create! :button {})]
                         (w/append! v a)
                         (w/append! v b)
                         (reset! calls [])
                         (w/replace! v a c)))]
    (is (= [["ga_replace" 1 2 4] ["ga_release" 2]
            ["ga_set_layout" 4 -2 -2 0.0 1] ["ga_set_min_height" 4 0.0]
            ["ga_set_layout" 3 -2 -2 0.0 1]] cs))
    (is (= [4 3] (get-in @w/tree [1 :children])))
    (is (= 1 (get-in @w/tree [4 :parent])))))

(deftest a-move-reorders-the-children
  (let [cs (recording #(let [v (w/create! :vbox {}) a (w/create! :label {}) b (w/create! :label {})]
                         (w/append! v a)
                         (w/append! v b)
                         (reset! calls [])
                         (w/move! v b 0)))]
    (is (= [["ga_move" 1 3 0] ["ga_set_layout" 3 -2 -2 0.0 1] ["ga_set_layout" 2 -2 -2 0.0 1]] cs))
    (is (= [3 2] (get-in @w/tree [1 :children])))))

(deftest a-tap-runs-the-handler-of-the-moment
  (let [said (atom [])]
    (recording (fn []
                 (let [id (w/create! :button {:label "Tap" :on-click #(swap! said conj :first)})]
                   (w/apply-props! :button id {:label "Tap" :on-click #(swap! said conj :second)})
                   (w/event! id 0))))
    (is (= [:second] @said) "the handler re-applied, not the one at create")))

(deftest a-handler-taken-away-stops-the-listener
  (let [calls (recording (fn [] (let [id (w/create! :button {:label "Tap" :on-click identity})]
                                  (w/apply-props! :button id {:label "Tap"}))))]
    (is (= ["ga_set_on_click" 1 0] (last calls)))
    (is (empty? @w/handlers))))

(deftest an-unknown-prop-is-named-once
  (reset! w/warned #{})
  (let [out (with-out-str (recording #(do (w/create! :label {:label "a" :colour "red"})
                                          (w/create! :label {:label "b" :colour "red"}))))]
    (is (= 1 (count (re-seq #"unknown prop" out))))
    (is (str/includes? out ":colour") "the prop")
    (is (str/includes? out ":label") "the tag")))

(deftest a-tag-with-no-view-says-so
  (is (= "glimmer-android: no view for :canvas"
         (try (recording #(w/create! :canvas {})) nil
              (catch :default e (ex-message e))))))
