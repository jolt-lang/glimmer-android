(ns glimmer-android.demo
  "glimmer-android's test screens: a label a tap changes and a label
  made to clip, then, after Next, every tag once. The state lives in
  atoms, so it outlives the Activity."
  (:require [glimmer.ratom :as r]
            [glimmer-android.core :as ga]
            [jolt.ffi :as ffi]))

(defonce taps (r/atom 0))
(defonce showing (r/atom :taps))     ; :taps or :vocabulary
(defonce ticked (r/atom false))      ; the vocabulary's checkbutton

(defn taps-screen []
  [:vbox {}
   [:label {:label (str "Tapped " @taps " times")}]
   [:button {:label "Tap" :on-click #(swap! taps inc)}]
   [:label {:label "This label is far too long for its box" :width 60 :height 20}]
   [:button {:label "Next" :on-click #(do (reset! showing :vocabulary) (ga/take-back! true))}]])

(def ^:private days
  "Four fixed days for :label-date, in milliseconds, at 16:00 UTC (noon
  EDT), so no zone from UTC-12 to UTC+7 moves the day: Monday 5 to
  Thursday 8 October 2026. Four 48 dp buttons overflow the 160 dp scroll."
  [1791216000000 1791302400000 1791388800000 1791475200000])

(defn- white
  "Pango markup: `s` in white, `size` in 1024ths of a point, bold if asked."
  [s size bold?]
  [:span (cond-> {:size (str size) :foreground "#ffffff"} bold? (assoc :weight "bold")) s])

(defn vocabulary []
  [:layers {:full-bleed true}
   [:image {:src "demo.png"}]
   [:gradient {:stops [["#000000" 0.0 0.0] ["#000000" 0.35 0.5] ["#000000" 0.7 1.0]]}]
   [:vbox {:safe true :spacing 12 :margin 16}
    [:label {:markup (white "Vocabulary" 28672 true) :xalign 0}]
    [:hbox {:spacing 12 :halign :fill}
     [:label {:markup (white "A box to tick" 17408 false) :hexpand true :xalign 0}]
     [:checkbutton {:active @ticked :on-toggled #(swap! ticked not)
                    :description "Example mistake"
                    :background (if @ticked "#a5d6a7" "#ffffff") :foreground "#1b5e20"
                    :symbol 24 :width 44 :height 44 :radius 8}]]
    (into [:scroll {:spacing 8 :height 160}]
          (for [[d word] (map vector days ["Monday" "Tuesday" "Wednesday" "Thursday"])]
            [:button {:label-date d :label (str " · " word)
                      :background "#ffffff" :foreground "#1b5e20" :font-size 17 :font-weight :bold
                      :radius 8 :padding 16 :xalign 0 :height 48 :halign :fill}]))
    [:button {:label "Disabled" :sensitive false
              :foreground "#ffffff" :border [1.5 "#ffffff" 0.85] :radius 8
              :font-size 17 :height 48}]
    [:label {:label "" :vexpand true}]]])

(defn screen []
  (if (= :vocabulary @showing) [vocabulary] [taps-screen]))

(defn- main
  "The entry, once per Activity. The vocabulary takes Back, back to the
  first screen; the first screen leaves Back to Android. A throw is
  printed: one that escaped into C would end the process."
  []
  (try (ga/back! (fn [] (reset! showing :taps) (ga/take-back! false)))
       (ga/mount! [screen])
       (ga/take-back! (= :vocabulary @showing))   ; after a rotation, as before it
       (catch :default e
         (println "glimmer-demo: start threw:" (ex-message e)))))

(ffi/export! "glimmer_demo_main" main [] :void :collect-safe)
