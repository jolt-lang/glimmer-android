(ns glimmer-android.fake
  "A fake of glimmer-android's C half, for host tests. It keeps a tree of
  ids, kinds, children and each setter's last arguments, refuses a call
  on an id it never gave out or has released, and checks every call's
  types against f/signatures."
  (:require [clojure.test :refer [is]]
            [glimmer.backend :as backend]
            [glimmer-android.core :as ga]
            [glimmer-android.ffi :as f]
            [glimmer-android.widget :as w]))

(def ^:private bindings
  "Every binding in glimmer-android.ffi, by its C name."
  {"ga_create" #'f/ga-create*                   "ga_set_text" #'f/ga-set-text*
   "ga_set_size" #'f/ga-set-size*               "ga_set_on_click" #'f/ga-set-on-click*
   "ga_append" #'f/ga-append*                   "ga_remove" #'f/ga-remove*
   "ga_replace" #'f/ga-replace*                 "ga_release" #'f/ga-release*
   "ga_text_style" #'f/ga-text-style*           "ga_runs_begin" #'f/ga-runs-begin*
   "ga_run" #'f/ga-run*                         "ga_runs_end" #'f/ga-runs-end*
   "ga_set_gravity" #'f/ga-set-gravity*         "ga_set_lines" #'f/ga-set-lines*
   "ga_set_box" #'f/ga-set-box*                 "ga_plain" #'f/ga-plain*
   "ga_set_alpha" #'f/ga-set-alpha*             "ga_set_enabled" #'f/ga-set-enabled*
   "ga_set_padding" #'f/ga-set-padding*         "ga_set_safe" #'f/ga-set-safe*
   "ga_set_full_bleed" #'f/ga-set-full-bleed*   "ga_set_layout" #'f/ga-set-layout*
   "ga_set_spacing" #'f/ga-set-spacing*         "ga_set_checked" #'f/ga-set-checked*
   "ga_set_description" #'f/ga-set-description* "ga_set_image" #'f/ga-set-image*
   "ga_stop" #'f/ga-stop*                       "ga_set_gradient" #'f/ga-set-gradient*
   "ga_set_translation_y" #'f/ga-set-translation-y* "ga_move" #'f/ga-move*
   "ga_format_date" #'f/ga-format-date*         "ga_set_back" #'f/ga-set-back*
   "ga_set_min_height" #'f/ga-set-min-height*
   "ga_files_dir" #'f/ga-files-dir*             "ga_version" #'f/ga-version*
   "ga_open_url" #'f/ga-open-url*})

(def ^:dynamic *files-dir* "What ga_files_dir answers." "/tmp/fake-files")
(def ^:dynamic *version* "What ga_version answers." "1.0")
(def ^:dynamic *opens?* "Whether ga_open_url finds an app to open the link." true)

(def ^:private state (atom nil))

(defn- fresh
  "C's table with only the root, 0, in it."
  []
  {:views {0 {:kind 0 :children [] :set {}}} :next 0
   :released [] :log [] :calls [] :refused [] :wrong []})

(defn- typed?
  "Whether `x` is what C declares as `t`."
  [t x]
  (case t (:int :long) (int? x) :double (double? x) :string (string? x)))

(defn- ids-named
  "The ids among call `nm`'s `args` that C looks up."
  [nm args]
  (case nm
    ("ga_create" "ga_run" "ga_stop" "ga_set_full_bleed" "ga_format_date" "ga_set_back"
     "ga_files_dir" "ga_version" "ga_open_url") []
    ("ga_append" "ga_remove" "ga_move") (take 2 args)
    "ga_replace" (take 3 args)
    [(first args)]))

(defn- live?
  "Whether C would find `id` in its table."
  [s id]
  (and (contains? (:views s) id) (not (some #{id} (:released s)))))

(defn- kids
  "`s` with `parent`'s children put through `g`."
  [s parent g]
  (update-in s [:views parent :children] #(vec (g %))))

(defn- effect
  "What call `nm` with `args` does to C's table `s`."
  [s nm [a b c :as args]]
  (case nm
    "ga_create"  (let [id (inc (:next s))]
                   (-> s (assoc :next id) (assoc-in [:views id] {:kind a :children [] :set {}})
                       (update :log conj [:create id])))
    "ga_append"  (kids s a #(conj % b))
    "ga_remove"  (kids s a #(remove #{b} %))
    "ga_replace" (kids s a #(map (fn [x] (if (= x b) c x)) %))
    "ga_move"    (kids s a #(let [cs (vec (remove #{b} %))] (concat (take c cs) [b] (drop c cs))))
    "ga_release" (-> s (update :released conj a) (update :log conj [:release a]))
    (if-let [id (first (ids-named nm args))]
      (assoc-in s [:views id :set nm] (vec (rest args)))
      (assoc-in s [:views 0 :set nm] (vec args)))))   ; a call with no view: kept on the root

(defn- stand-in
  "C's `nm`: check the types, keep the call, refuse an id C does not
  have, else change the table and answer as C does."
  [nm]
  (fn [& args]
    (let [types (f/signatures nm)
          call  (into [nm] args)]
      (when-not (and (= (count types) (count args)) (every? true? (map typed? types args)))
        (swap! state update :wrong conj call))
      (swap! state update :calls conj call)
      (if-not (every? #(live? @state %) (ids-named nm args))
        (do (swap! state update :refused conj call) 0)
        (do (swap! state effect nm args)
            (case nm
              "ga_create"      (:next @state)
              "ga_format_date" "Oct 6, 2026"
              "ga_files_dir"   *files-dir*
              "ga_version"     *version*
              "ga_open_url"    (if *opens?* 1 0)
              1))))))

(defn run
  "Run `test-fn` against a fresh fake C half, from an empty screen, and
  assert that every call had C's types. A fixture as it stands."
  [test-fn]
  (backend/register! ga/android-backend)   ; glimmer-uikit may have registered itself since
  (reset! state (fresh))
  (reset! w/handlers {})
  (w/reset-tree!)
  (f/reset-released!)
  (ga/reset-back!)   ; a test never inherits the last one's flag
  (with-redefs-fn (into {} (for [[nm v] bindings] [v (stand-in nm)]))
    (fn [] (try (test-fn)
                (finally (when @@#'ga/mounted (ga/stop!))))))
  (is (empty? (:wrong @state)) "every call had C's types"))

(defn views
  "C's table: id -> {:kind :children :set}, released ids included."
  []
  (:views @state))

(defn children
  "`id`'s children in C, in order."
  [id]
  (get-in @state [:views id :children]))

(defn kind
  "The kind `id` was made as."
  [id]
  (get-in @state [:views id :kind]))

(defn last-set
  "The last arguments, after the id, of call `nm` on `id`."
  [id nm]
  (get-in @state [:views id :set nm]))

(defn text
  "`id`'s text, as ga_set_text last set it."
  [id]
  (first (last-set id "ga_set_text")))

(defn released
  "The ids released, in order."
  []
  (:released @state))

(defn live
  "The ids C holds, but the root."
  []
  (filterv #(and (pos? %) (live? @state %)) (sort (keys (:views @state)))))

(defn first-of-kind
  "The first live id of kind `k`."
  [k]
  (first (filter #(= k (kind %)) (live))))

(defn calls
  "Every call, or only those to `nm`, as [nm & args]."
  ([] (:calls @state))
  ([nm] (filterv #(= nm (first %)) (:calls @state))))

(defn refused
  "The calls C refused."
  []
  (:refused @state))

(defn history
  "[:create id] and [:release id], in the order they happened."
  []
  (:log @state))

(defn tap!
  "A tap on `id`, as C reports one."
  [id]
  (#'ga/event id 0))

(defn back!
  "A Back, as C reports one."
  []
  (#'ga/back))

(defn background!
  "The Activity stopping, as C reports it."
  []
  (#'ga/on-stop))
