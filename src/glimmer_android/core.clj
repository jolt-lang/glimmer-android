(ns glimmer-android.core
  "The glimmer backend over Android Views. Jolt runs on the UI thread, so
  work runs inline. C calls the two exports below; an app's own export
  calls mount!."
  (:require [glimmer.backend :as backend]
            [glimmer.core :as g]
            [glimmer-android.ffi :as f]
            [glimmer-android.widget :as w]
            [jolt.ffi :as ffi]))

(def ^:private root
  "The id C gives the Activity's content view."
  0)

(defn- after
  "The index that puts `child` just after `sibling` among `parent`'s
  children, counted without `child`: 0 when `sibling` is nil. A sibling
  not among them is logged, and `child` goes at the end."
  [parent child sibling]
  (if (nil? sibling)
    0
    (let [others (vec (remove #{child} (get-in @w/tree [parent :children])))
          i      (first (keep-indexed (fn [i c] (when (= c sibling) i)) others))]
      (if i
        (inc i)
        (do (println "glimmer-android: reorder: no sibling" sibling "under" parent "; at the end")
            (count others))))))

(def android-backend
  "The backend map glimmer calls."
 {:name           :android
  :create!        w/create!
  :apply-props!   (fn [tag id props] (w/apply-props! tag id props))
  :append-child!  (fn [_ parent child] (w/append! parent child))
  :remove-child!  (fn [_ parent child] (w/remove! parent child))
  :replace-child! (fn [_ parent old new] (w/replace! parent old new))
  :reorder-child! (fn [_ parent child sibling] (w/move! parent child (after parent child sibling)))
  :schedule       (fn [work] (work))})

(backend/register! android-backend)

(defonce ^:private mounted (atom nil))

(defn mount!
  "Mount `hiccup` into the Activity's content view. A throw is printed:
  one that escaped into C would end the process."
  [hiccup]
  (try (reset! mounted (g/mount root :root hiccup))
       (println "glimmer-android: mounted")
       (catch :default e
         (println "glimmer-android: mount threw:" (ex-message e)))))

(defonce ^:private back-handler (atom nil))
(defonce ^:private back-taken (atom nil))

(defn back!
  "Run `f` when the system sends Back while this app takes it."
  [f]
  (reset! back-handler f))

(defn take-back!
  "Say whether the screen showing takes Back. Android shows its own way
  out, and animates to it, only when the app does not."
  [taken?]
  (let [t (boolean taken?)]
    (when (not= t @back-taken)
      (f/set-back t)                  ; throws on failure, so the flag stays unset
      (reset! back-taken t))))

(defn reset-back!
  "Forget whether Back is taken: the next take-back! calls C."
  []
  (reset! back-taken nil))

(defn stop!
  "The Activity is going: unmount, so no watcher renders into dead Views.
  The screen is forgotten even if unmounting throws."
  []
  (try
    (when-let [m @mounted]
      (g/unmount! m root :root))
    (finally
      (reset! mounted nil)
      (reset! w/handlers {})
      (w/reset-tree!)
      (f/reset-released!)
      (reset-back!)))
  (println "glimmer-android: stopped"))

(defn- event
  "A tap from C. A handler that throws is printed, not passed to C."
  [id kind]
  (try (w/event! id kind)
       (catch :default e
         (println "glimmer-android: a handler threw:" (ex-message e)))))

(defn- back
  "A Back from C. A handler that throws is printed, not passed to C."
  []
  (try (when-let [f @back-handler] (f))
       (catch :default e
         (println "glimmer-android: the Back handler threw:" (ex-message e)))))

(defn files-dir
  "The app's private files directory, or nil."
  []
  (not-empty (f/files-dir)))

(defn version
  "The app's version name, or nil."
  []
  (not-empty (f/version)))

(defonce ^:private background (atom nil))

(defn on-background!
  "Run `f` when the Activity stops. A later call replaces `f`."
  [f]
  (reset! background f))

(defn open-url!
  "Open `url` with the app Android chooses. Nothing to open it is printed,
  never thrown."
  [url]
  (when-not (f/open-url url)
    (println "glimmer-android: nothing opened" url)))

(defn- on-stop
  "The Activity stopped. A throw is printed, not passed to C."
  []
  (try (when-let [f @background] (f))
       (catch :default e
         (println "glimmer-android: the background handler threw:" (ex-message e)))))

(ffi/export! "glimmer_android_background" on-stop [] :void :collect-safe)
(ffi/export! "glimmer_android_event" event [:int :int] :void :collect-safe)
(ffi/export! "glimmer_android_back" back [] :void :collect-safe)
(ffi/export! "glimmer_android_stop"
             (fn [] (try (stop!)
                         (catch :default e (println "glimmer-android: stop threw:" (ex-message e)))))
             [] :void :collect-safe)
