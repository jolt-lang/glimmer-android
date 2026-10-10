(ns glimmer-android.ffi
  "The C half's functions, bound by name from libglimmer_android.so.
  Each answers 0 when its JNI call failed. C has logged the failure;
  `checked` refuses the 0 and names the call."
  (:refer-clojure :exclude [remove replace])
  (:require [jolt.ffi :as ffi]))

(ffi/defcfn ga-create*          "ga_create"          [:int] :int)
(ffi/defcfn ga-set-text*        "ga_set_text"        [:int :string] :int)
(ffi/defcfn ga-set-size*        "ga_set_size"        [:int :int :int] :int)
(ffi/defcfn ga-set-on-click*    "ga_set_on_click"    [:int :int] :int)
(ffi/defcfn ga-append*          "ga_append"          [:int :int] :int)
(ffi/defcfn ga-remove*          "ga_remove"          [:int :int] :int)
(ffi/defcfn ga-replace*         "ga_replace"         [:int :int :int] :int)
(ffi/defcfn ga-release*         "ga_release"         [:int] :int)
(ffi/defcfn ga-text-style*      "ga_text_style"      [:int :double :int :long :int :double] :int)
(ffi/defcfn ga-runs-begin*      "ga_runs_begin"      [:int :string] :int)
(ffi/defcfn ga-run*             "ga_run"             [:int :int :long :int :double :int :int] :int)
(ffi/defcfn ga-runs-end*        "ga_runs_end"        [:int] :int)
(ffi/defcfn ga-set-gravity*     "ga_set_gravity"     [:int :int] :int)
(ffi/defcfn ga-set-lines*       "ga_set_lines"       [:int :int] :int)
(ffi/defcfn ga-set-box*         "ga_set_box"         [:int :long :double :double :long] :int)
(ffi/defcfn ga-plain*           "ga_plain"           [:int] :int)
(ffi/defcfn ga-set-alpha*       "ga_set_alpha"       [:int :double] :int)
(ffi/defcfn ga-set-enabled*     "ga_set_enabled"     [:int :int] :int)
(ffi/defcfn ga-set-padding*     "ga_set_padding"     [:int :double :double :double :double] :int)
(ffi/defcfn ga-set-safe*        "ga_set_safe"        [:int :int] :int)
(ffi/defcfn ga-set-full-bleed*  "ga_set_full_bleed"  [:int] :int)
(ffi/defcfn ga-set-layout*      "ga_set_layout"      [:int :int :int :double :int] :int)
(ffi/defcfn ga-set-spacing*     "ga_set_spacing"     [:int :double] :int)
(ffi/defcfn ga-set-checked*     "ga_set_checked"     [:int :int] :int)
(ffi/defcfn ga-set-description* "ga_set_description" [:int :string] :int)
(ffi/defcfn ga-set-image*       "ga_set_image"       [:int :string] :int)
(ffi/defcfn ga-stop*            "ga_stop"            [:long :double] :int)
(ffi/defcfn ga-set-gradient*    "ga_set_gradient"    [:int] :int)
(ffi/defcfn ga-set-translation-y* "ga_set_translation_y" [:int :double] :int)
(ffi/defcfn ga-move*            "ga_move"            [:int :int :int] :int)
(ffi/defcfn ga-format-date*     "ga_format_date"     [:long] :string)
(ffi/defcfn ga-set-back*        "ga_set_back"        [:int] :int)
(ffi/defcfn ga-set-min-height*  "ga_set_min_height"  [:int :double] :int)
(ffi/defcfn ga-files-dir*       "ga_files_dir"       [] :string)
(ffi/defcfn ga-version*         "ga_version"         [] :string)
(ffi/defcfn ga-open-url*        "ga_open_url"        [:string] :int)

(def signatures
  "Each call's argument types, as the bindings above declare them."
  {"ga_create" [:int]
   "ga_set_text" [:int :string]
   "ga_set_size" [:int :int :int]
   "ga_set_on_click" [:int :int]
   "ga_append" [:int :int]
   "ga_remove" [:int :int]
   "ga_replace" [:int :int :int]
   "ga_release" [:int]
   "ga_text_style" [:int :double :int :long :int :double]
   "ga_runs_begin" [:int :string]
   "ga_run" [:int :int :long :int :double :int :int]
   "ga_runs_end" [:int]
   "ga_set_gravity" [:int :int]
   "ga_set_lines" [:int :int]
   "ga_set_box" [:int :long :double :double :long]
   "ga_plain" [:int]
   "ga_set_alpha" [:int :double]
   "ga_set_enabled" [:int :int]
   "ga_set_padding" [:int :double :double :double :double]
   "ga_set_safe" [:int :int]
   "ga_set_full_bleed" [:int]
   "ga_set_layout" [:int :int :int :double :int]
   "ga_set_spacing" [:int :double]
   "ga_set_checked" [:int :int]
   "ga_set_description" [:int :string]
   "ga_set_image" [:int :string]
   "ga_stop" [:long :double]
   "ga_set_gradient" [:int]
   "ga_set_translation_y" [:int :double]
   "ga_move" [:int :int :int]
   "ga_format_date" [:long]
   "ga_set_back" [:int]
   "ga_set_min_height" [:int :double]
   "ga_files_dir" []
   "ga_version" []
   "ga_open_url" [:string]})

(defonce released (atom #{}))   ; ids Clojure has released

(defn reset-released!
  "Forget the released ids: the Activity is going, and C's table with it."
  []
  (reset! released #{}))

(defn checked
  "`n` when it is positive. Otherwise throw, naming `call`."
  [call n]
  (if (pos? n)
    n
    (throw (ex-info (str "glimmer-android: " call " failed; logcat says why") {:call call}))))

(defn- coerce
  "`x` as type `t`. A boolean is 1 or 0."
  [t x]
  (case t
    (:int :long) (cond (true? x) 1 (false? x) 0 :else (long x))
    :double      (double x)
    :string      (str x)))

(defn- call
  "Refuse any of `ids` that is released, coerce `args` to the types of
  `nm`, call `f` with them, and refuse a 0."
  [nm f ids & args]
  (doseq [id ids]
    (when (contains? @released id)
      (throw (ex-info (str "glimmer-android: " nm " on released view " id) {:call nm :id id}))))
  (checked nm (apply f (map coerce (signatures nm) args))))

(defn create       [kind]           (call "ga_create" ga-create* [] kind))
(defn set-text     [id s]           (call "ga_set_text" ga-set-text* [id] id s))
(defn set-size     [id w h]         (call "ga_set_size" ga-set-size* [id] id w h))
(defn set-on-click [id on?]         (call "ga_set_on_click" ga-set-on-click* [id] id on?))
(defn append       [parent child]   (call "ga_append" ga-append* [parent child] parent child))
(defn remove       [parent child]   (call "ga_remove" ga-remove* [parent child] parent child))
(defn replace      [parent old new] (call "ga_replace" ga-replace* [parent old new] parent old new))

(defn release
  "Free view `id`'s reference in C. Any later call on it is refused."
  [id]
  (let [n (call "ga_release" ga-release* [id] id)]
    (swap! released conj id)
    n))

(defn text-style
  "Size in sp, an ARGB colour or nil for the view's own, a Typeface style
  (1 bold, 2 italic, 3 both), tracking in em."
  [id size color style tracking]
  (call "ga_text_style" ga-text-style* [id] id size (some? color) (or color 0) style tracking))

(defn runs-begin   [id text]        (call "ga_runs_begin" ga-runs-begin* [id] id text))
(defn run
  "A span over [start, end): an ARGB colour or 0, a Typeface style or 0,
  a size relative to the base or 0, an underline, a strikethrough."
  [start end color style relative underline? strike?]
  (call "ga_run" ga-run* [] start end color style relative underline? strike?))
(defn runs-end     [id]             (call "ga_runs_end" ga-runs-end* [id] id))
(defn set-gravity  [id gravity]     (call "ga_set_gravity" ga-set-gravity* [id] id gravity))
(defn set-lines    [id n]           (call "ga_set_lines" ga-set-lines* [id] id n))
(defn set-box      [id color radius stroke stroke-color]
  (call "ga_set_box" ga-set-box* [id] id color radius stroke stroke-color))
(defn plain        [id]             (call "ga_plain" ga-plain* [id] id))
(defn set-alpha    [id a]           (call "ga_set_alpha" ga-set-alpha* [id] id a))
(defn set-enabled  [id on?]         (call "ga_set_enabled" ga-set-enabled* [id] id on?))
(defn set-padding  [id l t r b]     (call "ga_set_padding" ga-set-padding* [id] id l t r b))
(defn set-safe     [id on?]         (call "ga_set_safe" ga-set-safe* [id] id on?))
(defn set-full-bleed [on?]          (call "ga_set_full_bleed" ga-set-full-bleed* [] on?))
(defn set-layout   [id w h weight gravity]
  (call "ga_set_layout" ga-set-layout* [id] id w h weight gravity))
(defn set-spacing  [id dp]          (call "ga_set_spacing" ga-set-spacing* [id] id dp))
(defn set-checked  [id on?]         (call "ga_set_checked" ga-set-checked* [id] id on?))
(defn set-description [id s]        (call "ga_set_description" ga-set-description* [id] id s))
(defn set-image    [id name]        (call "ga_set_image" ga-set-image* [id] id name))
(defn stop         [color at]       (call "ga_stop" ga-stop* [] color at))
(defn set-gradient [id]             (call "ga_set_gradient" ga-set-gradient* [id] id))
(defn set-translation-y [id dp]     (call "ga_set_translation_y" ga-set-translation-y* [id] id dp))
(defn move         [parent child index]
  (call "ga_move" ga-move* [parent child] parent child index))
(defn set-back     [on?]            (call "ga_set_back" ga-set-back* [] on?))
(defn set-min-height [id dp]        (call "ga_set_min_height" ga-set-min-height* [id] id dp))

(defn format-date
  "`ms` as a date in the device's medium style; empty when C failed."
  [ms]
  (ga-format-date* (long ms)))

(defn files-dir
  "The app's private files directory; empty when Java had none."
  []
  (ga-files-dir*))

(defn version
  "The app's version name; empty when Java had none."
  []
  (ga-version*))

(defn open-url
  "Ask Android to open `url`: true when an app took it, false when none
  could. Never throws."
  [url]
  (= 1 (ga-open-url* (str url))))
