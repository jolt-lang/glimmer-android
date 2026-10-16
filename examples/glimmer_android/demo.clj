(ns glimmer-android.demo
  "glimmer-android's test screens: a label a tap changes and a label
  made to clip; after Next, every tag once; after Passages, wrapped text
  in a scroll and styled markup. The state lives in atoms, so it
  outlives the Activity."
  (:require [glimmer.ratom :as r]
            [glimmer-android.core :as ga]
            [jolt.ffi :as ffi]))

(defonce taps (r/atom 0))
(defonce showing (r/atom :taps))     ; :taps, :vocabulary, :passages or :fetches
(defonce ticked (r/atom false))      ; the vocabulary's checkbutton

(defn taps-screen []
  [:vbox {}
   [:label {:label (str "Tapped " @taps " times")}]
   [:button {:label "Tap" :on-click #(swap! taps inc)}]
   [:label {:label "This label is far too long for its box" :width 60 :height 20}]
   [:button {:label "Next" :on-click #(do (reset! showing :vocabulary) (ga/take-back! true))}]
   [:button {:label "Fetches" :on-click #(do (reset! showing :fetches) (ga/take-back! true))}]])

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
    [:button {:label "Passages" :on-click #(reset! showing :passages)
              :foreground "#ffffff" :border [1.5 "#ffffff" 0.85] :radius 8
              :font-size 17 :height 48}]
    [:label {:label "" :vexpand true}]]])

(def ^:private passages
  "An app's help text: six passages, together taller than the screen."
  [["TRACKING A HOLE" "Each hole shows the five mistakes with a box on the right. Tap a box to tick it, tap again to untick. A clean hole has nothing ticked, which is the goal. The arrows at the top move to the previous or next hole; ticks are saved as you go."]
   ["THE SUMMARY" "Tap Summary at any point for the count of each mistake, the total, and how many holes were clean so far. Back to Round returns you to the hole you were on."]
   ["ENDING A ROUND" "End Round keeps the round on your phone and shows it, with a practice tip or two when it ran nine holes or more. Done returns to the start screen. Nothing is lost: every ended round is kept."]
   ["YOUR ROUNDS" "Tap Rounds on the start screen for every round you have kept, newest first. Tap one to read it back, or to delete it. All Time shows your rates per 18 holes across everything, beside your last five rounds."]
   ["PUTTING THE PHONE AWAY" "Lock it, pocket it, play four holes: when you come back the app is where you left it. If Android has closed the app in the meantime, the start screen offers Continue with the hole you were on."]
   ["NAMING THE COURSE" "After Start, the app offers to find the course you are standing on. On the first tee, tap Find Courses Near Me and allow location once; pick your course from the five nearest, or tap No Course. The course is kept with the round."]])

(defn passages-screen
  "Wrapped labels in a scroll, most of them below the first screen, built
  as the app's help screen was: each must wrap at its view's width."
  []
  [:vbox {:vfill true :margin 24 :spacing 16}
   [:label {:markup [:span {:size "28672" :weight "bold"} "Passages"] :xalign 0 :halign :fill}]
   [:label {:markup [:span {} "Styled: " [:b "bold"] ", " [:i "italic"] ", "
                     [:s "struck"] ", " [:u "underlined"]]
            :xalign 0 :halign :fill}]
   (into [:scroll {:vexpand true :spacing 0 :halign :fill}]
         (for [[title body] passages]
           [:vbox {:spacing 8 :halign :fill :margin 0 :margin-top 12}
            [:label {:markup [:span {:size "17408" :weight "bold"} title] :xalign 0 :halign :fill}]
            [:label {:markup [:span {:size "17408"} body] :xalign 0 :wrap true :halign :fill}]]))])

(def ^:private server
  "The proof's local server, scripts/fetch-server, as the emulator reaches the Mac."
  "http://10.0.2.2:8765")

(def ^:private proof-timeouts
  "Short, so the proof's three timeouts each take seconds: connect 2,
  read 3, total 6."
  {:connect 2000 :read 3000 :total 6000})

(defn- summary
  "An answer in one line: the status, the body's length and its start, or the error."
  [{:keys [status body error]}]
  (if error
    (str "error " error)
    (str status " " (count body) " chars " (pr-str (subs body 0 (min 40 (count body)))))))

(defn- ask!
  "Fetch `url` as the case named `label`, and print the answer with the
  time it took. Answers the handle."
  [label url]
  (let [t0 (System/currentTimeMillis)]
    (ga/fetch! url {:headers {"X-Glimmer" "demo"} :timeout-ms proof-timeouts}
               (fn [a]
                 (println "glimmer-demo: fetch" label "->" (summary a)
                          "in" (- (System/currentTimeMillis) t0) "ms")))))

(defonce ^:private slow (atom nil))   ; the handle of the slow fetch, until it is cancelled

(defn fetches-screen
  "One button per case the proof checks. Port 8766 stands for a server
  that never accepts: scripts/fetch-server fills its backlog. The slow fetch is started by one
  button and cancelled by another, tapped a second later: by then the
  worker has its connection, so the cancel's disconnect has a socket to
  close, off the UI thread."
  []
  (into [:vbox {:spacing 8 :margin 16}]
        (concat
         (for [[label url] [["ok" (str server "/ok")] ["missing" (str server "/missing")]
                            ["connect" "http://10.0.2.2:8766/"] ["silent" (str server "/silent")]
                            ["trickle" (str server "/trickle")] ["big" (str server "/big")]]]
           [:button {:label (str "Fetch " label) :on-click (fn [] (ask! label url))}])
         [[:button {:label "Fetch slow"
                    :on-click #(do (reset! slow (ask! "slow" (str server "/slow")))
                                   (println "glimmer-demo: fetch slow started"))}]
          [:button {:label "Cancel slow"
                    :on-click #(do (ga/cancel-fetch! @slow)
                                   (reset! slow nil)
                                   (println "glimmer-demo: fetch slow cancelled"))}]
          [:button {:label "Cancel nothing"
                    :on-click #(println "glimmer-demo: cancel with nothing pending ->"
                                        (pr-str (ga/cancel-fetch! -1)))}]])))

(defn screen []
  (case @showing
    :vocabulary [vocabulary]
    :passages   [passages-screen]
    :fetches    [fetches-screen]
    [taps-screen]))

(defn- main
  "The entry, once per Activity. The vocabulary, the passages and the fetches take
  Back, back to the first screen; the first screen leaves Back to Android. A throw is
  printed: one that escaped into C would end the process."
  []
  (try (println "glimmer-demo: debuggable?" (ga/debuggable?))
       (ga/back! (fn [] (reset! showing :taps) (ga/take-back! false)))
       (ga/mount! [screen])
       (ga/take-back! (not= :taps @showing))   ; after a rotation, as before it
       (catch :default e
         (println "glimmer-demo: start threw:" (ex-message e)))))

(ffi/export! "glimmer_demo_main" main [] :void :collect-safe)
