(ns glimmer-android.props
  "Hiccup props into the numbers and words the C half takes. Pure, so
  the host tests them."
  (:require [clojure.string :as str]))

(defn argb
  "#rrggbb or #rgb, and an alpha from 0 to 1, as one ARGB number. nil is
  transparent."
  ([hex] (argb hex 1.0))
  ([hex alpha]
   (if-not hex
     0
     (let [h  (subs hex 1)
           h  (if (= 3 (count h)) (apply str (mapcat #(list % %) h)) h)
           a  (long (Math/round (* 255.0 (double alpha))))]
       (+ (* a 0x1000000) (Long/parseLong h 16))))))

(defn pango-pt
  "A Pango size or spacing, in 1024ths of a point, as points."
  [s]
  (/ (Long/parseLong (str s)) 1024.0))

(def ^:private default-size 17.0)

(def ^:private tag-styles
  "The style each Pango element's name gives, as glimmer-uikit reads it.
  Another element draws its text plain."
  {:span {} :b {:bold true} :i {:italic true} :s {:strike true} :u {:underline true}})

(defn- style
  "The run style element `tag` and its attributes `a` give, over `base`."
  [base tag a]
  (cond-> (merge base (tag-styles tag))
    (:size a)           (assoc :size (pango-pt (:size a)))
    (:foreground a)     (assoc :color (argb (:foreground a)))
    (= "bold" (:weight a)) (assoc :bold true)
    (some-> (:font_family a) (str/includes? "Bold")) (assoc :bold true)
    (#{"italic" "oblique"} (:style a)) (assoc :italic true)
    (= "true" (:strikethrough a)) (assoc :strike true)
    (and (:underline a) (not (#{"none" "false"} (:underline a)))) (assoc :underline true)
    (:letter_spacing a) (assoc :spacing-pt (pango-pt (:letter_spacing a)))))

(defn typeface
  "A run's bold and italic as Android's Typeface style: 1 bold, 2 italic,
  3 both."
  [{:keys [bold italic]}]
  (+ (if bold 1 0) (if italic 2 0)))

(defn- finish
  "A run as C takes it: tracking in em, no working keys."
  [{:keys [spacing-pt size] :as r}]
  (cond-> (dissoc r :spacing-pt)
    spacing-pt (assoc :tracking (/ spacing-pt size))))

(defn- utf16-length
  "How many UTF-16 units `s` takes: Android's spans count those, and a
  character past U+FFFF takes two."
  [s]
  (reduce + (map #(if (> (int %) 0xFFFF) 2 1) s)))

(defn runs
  "Pango hiccup, or a string, as {:text s :runs [{:start :end :size …}]}.
  A run covers its element's own text and its children's. Offsets are
  UTF-16 units. :plain, when present, holds the elements whose names
  style nothing here."
  [markup]
  (let [text (StringBuilder.) at (atom 0) out (atom []) plain (atom #{})]
    (letfn [(walk [node st]
              (cond
                (string? node) (do (.append text node) (swap! at + (utf16-length node)))
                (vector? node)
                (let [[tag a & kids] node
                      [a kids] (if (map? a) [a kids] [{} (cons a kids)])
                      st (style st tag a)
                      start @at]
                  (when-not (contains? tag-styles tag) (swap! plain conj tag))
                  (doseq [k kids] (walk k st))
                  (swap! out conj (finish (assoc st :start start :end @at))))
                :else nil))]
      (walk (if (string? markup) [:span {} markup] markup) {:size default-size})
      (cond-> {:text (str text)
               ;; a parent run is added after its children; at one start the
               ;; longer run, the parent, comes first and is the base
               :runs (vec (sort-by (juxt :start (comp - :end))
                                   (map #(select-keys % [:start :end :size :color :bold :italic
                                                         :underline :strike :tracking])
                                        @out)))}
        (seq @plain) (assoc :plain @plain)))))

(defn side
  "iOS's reading of :xalign: below 0.34 the start, above 0.66 the end."
  [x]
  (cond (nil? x) :center (<= x 0.34) :start (>= x 0.66) :end :else :center))

(defn stack-align
  "A stack's one cross alignment: the last child's that sets one. A
  vertical stack reads :halign, an hbox :valign."
  [tag children-props]
  (let [k (if (= :hbox tag) :valign :halign)]
    (or (some k (reverse children-props)) :center)))

(defn layout
  "The layout params a child with `props` gets in a parent of `ptag`
  aligned `align`: {:w :h :weight :gravity}, sizes in dp or :match/:wrap."
  [ptag align props]
  (let [{:keys [width height hexpand vexpand vfill full-bleed]} props]
    (case ptag
      (:vbox :scroll)
      {:w (or width (if (= :fill align) :match :wrap))
       :h (or height (if vexpand 0 :wrap))
       :weight (if (and vexpand (not height)) 1 0)
       :gravity (case align :start :start :end :end :center-h)}
      :hbox
      {:w (or width (if hexpand 0 :wrap))
       :h (or height (if (= :fill align) :match :wrap))
       :weight (if (and hexpand (not width)) 1 0)
       :gravity (case align :top :top :bottom :bottom :center-v)}
      :layers
      {:w (or width :match) :h (or height :match) :weight 0 :gravity :none}
      :root
      {:w :match :h (if (or vfill full-bleed) :match :wrap) :weight 0 :gravity :none})))

(defn stops
  "Gradient stops [hex alpha location] as [argb location]."
  [stops]
  (mapv (fn [[hex alpha at]] [(argb hex alpha) (double at)]) stops))
