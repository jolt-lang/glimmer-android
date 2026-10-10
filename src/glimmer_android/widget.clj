(ns glimmer-android.widget
  "Hiccup tags into Android Views, through the C half. A view is an id
  in C's table; `tree` keeps each one's tag, parent, children and props."
  (:require [glimmer-android.ffi :as f]
            [glimmer-android.props :as p]))

(defonce tree (atom {0 {:tag :root :children []}}))   ; id -> {:tag :parent :children :props}
(defonce handlers (atom {}))   ; id -> the tap handler of the latest render
(defonce warned (atom #{}))    ; [tag prop] pairs already named

(defn reset-tree!
  "Forget every view but the root: the Activity is going."
  []
  (reset! tree {0 {:tag :root :children []}}))

(def ^:private kinds
  "The tag, and the C half's number for the View it makes."
  {:vbox 1 :label 2 :button 3 :hbox 4 :layers 5 :scroll 6 :checkbutton 7 :image 8 :gradient 9})

(def ^:private layout-keys
  "Props a parent reads to lay its children out."
  #{:width :height :hexpand :vexpand :halign :valign :vfill :full-bleed})

(def ^:private handler-keys
  "The prop that holds a tag's tap handler."
  {:button :on-click :checkbutton :on-toggled})

(def ^:private bits
  "Android's Gravity bits for p/layout's words."
  {:start 0x800003 :end 0x800005 :center-h 1 :center-v 16 :top 48 :bottom 80 :none -1})

(defn- text-gravity
  "A text's gravity for :xalign: its side, centred vertically."
  [xalign]
  (case (p/side xalign)
    :start  (bit-or 0x800003 16)
    :end    (bit-or 0x800005 16)
    :center 17))

(defn- box!
  "A fill, a radius and a border [width colour alpha] as one drawable."
  [id {:keys [background radius border]}]
  (let [[w colour alpha] border]
    (f/set-box id (p/argb background) (or radius 0) (or w 0) (if colour (p/argb colour (or alpha 1.0)) 0))))

(defn- span
  "ga_run's arguments for what run `r` draws over `under`, the style
  under it, or nil when it draws nothing more."
  [r under]
  (let [new?     #(boolean (and (% r) (not (% under))))
        color    (when (not= (:color r) (:color under)) (:color r))
        style    (p/typeface {:bold (new? :bold) :italic (new? :italic)})
        relative (if (= (:size r) (:size under)) 0 (/ (:size r) (:size under)))]
    (when (or color (pos? style) (pos? relative) (new? :underline) (new? :strike))
      [(:start r) (:end r) (or color 0) style relative (new? :underline) (new? :strike)])))

(defn- warn-plain!
  "Print one line for each markup element in `tags` that styles nothing
  here, once per element."
  [tags]
  (doseq [t tags :when (not (contains? @warned [:markup t]))]
    (swap! warned conj [:markup t])
    (println "glimmer-android: markup element" t "draws its text plain")))

(defn- text!
  "A label's text: its first run is the style, and a later run that
  differs gets a span. The first run's underline and strikethrough are
  spans too, as the style draws no line. A :date is the platform's
  formatting of it, styled by :date-markup's span attributes, as
  glimmer-uikit draws it. On a :label, :date replaces :label and :markup,
  unlike a :button's :label-date, which goes before its :label."
  [id {:keys [markup label date date-markup]}]
  (let [{:keys [text runs plain]} (p/runs (cond date   [:span (or date-markup {}) (f/format-date date)]
                                                markup markup
                                                :else  (str (or label ""))))
        base  (first runs)
        spans (keep identity (cons (span base (dissoc base :underline :strike))
                                   (map #(span % base) (rest runs))))]
    (warn-plain! plain)
    (f/text-style id (:size base) (:color base) (p/typeface base) (or (:tracking base) 0))
    (if (empty? spans)
      (f/set-text id text)
      (do (f/runs-begin id text)
          (doseq [s spans] (apply f/run s))
          (f/runs-end id)))))

(def ^:private common
  "The groups every tag has, first."
  [[[:alpha :sensitive]
    (fn [id p] (f/set-alpha id (* (or (:alpha p) 1.0) (if (false? (:sensitive p)) 0.4 1.0))))]
   [[:center-y] (fn [id p] (f/set-translation-y id (or (:center-y p) 0)))]])

(def ^:private stack
  "A vbox's or hbox's own groups."
  [[[:background] (fn [id p] (f/set-box id (p/argb (:background p)) 0 0 0))]
   [[:margin :margin-top :margin-bottom :margin-left :margin-right]
    (fn [id p] (let [m (or (:margin p) 0) side #(or (% p) m)]
                 (f/set-padding id (side :margin-left) (side :margin-top)
                                (side :margin-right) (side :margin-bottom))))]
   [[:spacing] (fn [id p] (f/set-spacing id (or (:spacing p) 0)))]
   [[:safe] (fn [id p] (f/set-safe id (boolean (:safe p))))]])

(def ^:private groups
  "Each tag's groups, in order: [props setter], where a setter takes the
  id and the props map."
  {:vbox   (into common stack)
   :hbox   (into common stack)
   :scroll (conj common [[:spacing] (fn [id p] (f/set-spacing id (or (:spacing p) 0)))])
   :layers (conj common [[:background] (fn [id p] (f/set-box id (p/argb (:background p)) 0 0 0))])
   ;; :label draws :date and :date-markup, as glimmer-uikit's label-spec
   ;; does, so a title can carry a date. :button takes one as :label-date.
   :label  (into common
                 [[[:label :markup :date :date-markup] text!]
                  [[:xalign] (fn [id p] (f/set-gravity id (text-gravity (:xalign p))))]
                  [[:wrap] (fn [id p] (f/set-lines id (if (:wrap p) 0 1)))]])
   :button (into common
                 [[[:label :label-date]
                   (fn [id p] (f/set-text id (str (some-> (:label-date p) f/format-date) (:label p))))]
                  [[:font-size :font-weight :foreground]
                   (fn [id p] (f/text-style id (or (:font-size p) 15) (some-> (:foreground p) p/argb)
                                            (p/typeface {:bold (= :bold (:font-weight p))}) 0))]
                  [[:background :radius :border] box!]
                  [[:padding] (fn [id p] (let [x (or (:padding p) 0)] (f/set-padding id x 0 x 0)))]
                  [[:sensitive] (fn [id p] (f/set-enabled id (not (false? (:sensitive p)))))]
                  [[:xalign] (fn [id p] (f/set-gravity id (text-gravity (:xalign p))))]])
   :checkbutton (into common
                      [[[:active :symbol :foreground]
                        (fn [id p] (f/set-checked id (boolean (:active p)))
                          (f/set-text id (if (:active p) "✓" ""))
                          (f/text-style id (or (:symbol p) 24) (some-> (:foreground p) p/argb) 0 0))]
                       [[:background :radius] (fn [id p] (box! id (select-keys p [:background :radius])))]
                       [[:description] (fn [id p] (f/set-description id (or (:description p) "")))]])
   ;; no :src, or no :stops, clears the view: "" is C's word for no image,
   ;; and a box of nothing takes the gradient's background away
   :image    (conj common [[:src] (fn [id p] (f/set-image id (or (:src p) "")))])
   :gradient (conj common [[:stops] (fn [id p]
                                      (if-let [stops (seq (:stops p))]
                                        (do (doseq [[c at] (p/stops stops)] (f/stop c at))
                                            (f/set-gradient id))
                                        (f/set-box id 0 0 0 0)))])})

(defn- known
  "The props `tag` reads: its groups', the layout keys, :key and its handler."
  [tag]
  (-> (into #{:key} layout-keys)
      (into (mapcat first (groups tag)))
      (cond-> (handler-keys tag) (conj (handler-keys tag)))))

(defn- warn-unknown!
  "Print one line for each prop `tag` does not read, once per pair."
  [tag props]
  (doseq [k (keys props)
          :when (not (contains? (known tag) k))
          :when (not (contains? @warned [tag k]))]
    (swap! warned conj [tag k])
    (println "glimmer-android: unknown prop" k "on" tag)))

(defn- dimension
  "A size from p/layout as C takes it: dp, or -1 to match, -2 to wrap."
  [x]
  (case x :match -1 :wrap -2 x))

(defn relayout!
  "Set the layout params of each of `parent`'s children, and at the root,
  whether the screen is full-bleed."
  [parent]
  (let [{:keys [tag children]} (get @tree parent)
        props (mapv #(get-in @tree [% :props]) children)
        align (p/stack-align tag props)]
    (doseq [[c cp] (map vector children props)]
      (let [{:keys [w h weight gravity]} (p/layout tag align cp)
            floor? (#{:button :checkbutton} (get-in @tree [c :tag]))]
        (f/set-layout c (dimension w) (dimension (if (and floor? (:height cp)) :wrap h)) weight (bits gravity))
        (when floor? (f/set-min-height c (or (:height cp) 0)))))
    (when (= :root tag)
      (f/set-full-bleed (boolean (some :full-bleed props))))))

(defn- changed?
  "Whether any of `ks` differs between `old` and `new`."
  [ks old new]
  (not= (select-keys old ks) (select-keys new ks)))

(defn- set-handler!
  "Keep `tag`'s handler for `id`. The listener is wired once, at the
  first, and unwired when the handler goes."
  [tag id props]
  (if-let [h (get props (handler-keys tag))]
    (do (when-not (contains? @handlers id) (f/set-on-click id true))
        (swap! handlers assoc id h))
    (when (contains? @handlers id)
      (f/set-on-click id false)
      (swap! handlers dissoc id))))

(defn- apply-groups!
  "Run `tag`'s groups whose props differ between `old` and `props`, or
  every group when `all?`."
  [tag id old props all?]
  (doseq [[ks setter] (groups tag)
          :when (or all? (changed? ks old props))]
    (setter id props)))

(defn apply-props!
  "Re-render view `id` with `props`: the groups that changed, the
  handler, and the parent's layout if a layout prop changed."
  [tag id props]
  (warn-unknown! tag props)
  (let [old (get-in @tree [id :props])]
    (swap! tree assoc-in [id :props] props)
    (apply-groups! tag id old props false)
    (set-handler! tag id props)
    (when-let [parent (get-in @tree [id :parent])]
      (when (changed? (vec layout-keys) old props)
        (relayout! parent)))))

(defn create!
  "Make the View for `tag`, run every group, and answer its id."
  [tag props]
  (let [kind (or (kinds tag)
                 (throw (ex-info (str "glimmer-android: no view for " tag) {:tag tag})))
        id   (f/create kind)]
    (warn-unknown! tag props)
    (swap! tree assoc id {:tag tag :children [] :props props})
    (when (#{:button :checkbutton} tag) (f/plain id))
    (apply-groups! tag id nil props true)
    (set-handler! tag id props)
    id))

(defn event!
  "A tap on view `id`: run its handler, if it has one. A CheckBox has
  already toggled itself, so a checkbutton's box is then put to the
  :active its latest render left: the View follows the app's state,
  never the tap."
  [id _kind]
  (when-let [h (get @handlers id)] (h))
  (when (= :checkbutton (get-in @tree [id :tag]))
    (f/set-checked id (boolean (get-in @tree [id :props :active])))))

(defn forget!
  "View `id` is gone: drop its handler."
  [id]
  (swap! handlers dissoc id))

(defn- release-subtree!
  "Release view `id` and every view under it, children first, forgetting
  each handler and tree entry."
  [id]
  (doseq [c (get-in @tree [id :children])] (release-subtree! c))
  (forget! id)
  (f/release id)
  (swap! tree dissoc id))

(defn append!
  "Add `child` at the end of `parent`'s children."
  [parent child]
  (f/append parent child)
  (swap! tree #(-> % (update-in [parent :children] conj child)
                   (assoc-in [child :parent] parent)))
  (relayout! parent))

(defn remove!
  "Take `child` out of `parent` and release its subtree."
  [parent child]
  (f/remove parent child)
  (swap! tree update-in [parent :children] #(vec (remove #{child} %)))
  (release-subtree! child)
  (relayout! parent))

(defn replace!
  "Put `new` where `old` was among `parent`'s children, and release
  `old`'s subtree."
  [parent old new]
  (f/replace parent old new)
  (swap! tree #(-> % (update-in [parent :children] (fn [cs] (mapv (fn [c] (if (= c old) new c)) cs)))
                   (assoc-in [new :parent] parent)))
  (release-subtree! old)
  (relayout! parent))

(defn move!
  "Move `child` to `index` among `parent`'s children."
  [parent child index]
  (f/move parent child index)
  (swap! tree update-in [parent :children]
         (fn [cs] (let [cs (vec (remove #{child} cs))]
                    (vec (concat (take index cs) [child] (drop index cs))))))
  (relayout! parent))
