# glimmer-android

The **Android** backend for [glimmer](https://github.com/jolt-lang/glimmer), the
reactive Clojure(-like) UI framework for [jolt](https://github.com/jolt-lang/jolt).
Where [glimmer-uikit](https://github.com/jolt-lang/glimmer-uikit) renders hiccup
into iPhone views, glimmer-android renders it into real Android Views
(`LinearLayout`, `TextView`, `Button`, `CheckBox`, `ScrollView`) through a small
C half that makes the JNI calls and a Java Activity that decides nothing.

glimmer owns the reactive core (ratom, component model, reconciler) and knows
nothing about Android. This project supplies the views, the prop and event
wiring and the app loop, and registers them through `glimmer.backend`. The app's
screens are compiled by jolt into an Android shared library, and run on
Android's UI thread.

```clojure
(ns myapp.android
  (:require [glimmer.ratom :as r]
            [glimmer-android.core :as ga]      ; installs the Android backend
            [jolt.ffi :as ffi]))

(defonce taps (r/atom 0))

(defn counter []
  [:vbox {:spacing 12 :margin 16}
   [:label {:label (str "Tapped " @taps " times")}]
   [:button {:label "Tap" :on-click #(swap! taps inc)}]])

;; The Activity calls this export once per Activity; the manifest names it.
(defn- main [] (ga/mount! [counter]))
(ffi/export! "myapp_main" main [] :void :collect-safe)
```

glimmer-android grew inside an app before it was extracted, so its vocabulary is
what real screens needed, and the same hiccup renders on glimmer-uikit, with
one exception: in a label's markup, Pango's style tags (`[:b …]`, `[:i …]`,
`[:s …]`, `[:u …]`) are ignored on Android, which reads only a span's
attributes. For bold on both, write `[:span {:weight "bold"} …]`; see Markup
under [Hiccup reference](#hiccup-reference). Components, reactive state and
reconciliation are documented in glimmer's README. What follows is the
Android-specific part.

## Requirements

- **jolt 0.8.0 or newer.** `jolt.ffi/write` changed its argument order in
  0.8.0, and an older runtime writes to the wrong place rather than failing, so
  `deps.edn` declares the floor. This release was tested on jolt 0.8.19 and
  0.8.20. The host tests and lint need nothing else but clj-kondo, and run on
  Linux too.
- **The jolt target pack for Android arm64.** `jolt pack` builds it once, from
  Chez Scheme v10.4.1, into `~/dev/pack-tarm64le-android-api28` (or wherever
  `PACK` points). It builds on an Apple Silicon Mac only.
- **The Android SDK, with `ANDROID_HOME` pointing at it** (on a Mac, usually
  `~/Library/Android/sdk`). It needs platform-tools, the emulator, the NDK r30
  (30.0.16248370) and CMake 4.1.2: `sdkmanager --install "ndk;30.0.16248370"`
  installs the NDK. The scripts refuse to run without `ANDROID_HOME`, and put
  its `platform-tools` and `emulator` on `PATH` themselves, for `adb` and
  `emulator`.
- For the demo: **a JDK 21 or newer**, which Gradle 9.8 and AGP 9.4 need:
  `JAVA_HOME`'s, or else `java` on `PATH`. `scripts/demo` refuses an older one.
- For the demo: ImageMagick (`magick`) draws its one image, and `scripts/demo`
  fetches Gradle 9.8.0 itself into `~/.cache/glimmer-android` (or `CACHE`),
  checked against Gradle's published SHA-256.
- For the proof: an arm64 API 36 emulator (`Medium_Phone_API_36.0` by default;
  `AVD` names another).

## Running

```sh
jolt test                        # unit and conformance tests, against the fake C half, no device
jolt lint                        # clj-kondo over src, test and examples
jolt pack                        # the jolt target pack, once
jolt demo                        # the demo: its Jolt library, then the APK, with the Java and C
jolt proof DIR                   # the demo on an emulator, a screenshot per step into DIR
AVD=<name> jolt proof DIR        # the same on another emulator
```

`jolt lib` builds a namespace as an Android shared library (`NS`, default
`glimmer-android.demo`; `OUT`, default `build/libglimmer_demo.so`; `ALIAS`,
default `:examples`), then reads it back for three checks: it exports
`jolt_library_init` and `jolt_lookup`, needs only `libc`, `libm`, `libdl` and
`liblog`, and aligns every `LOAD` segment to 16 KB, so it loads on a 16 KB-page
device. `jolt demo` runs it.

`DIR` must be new or empty: a proof never overwrites another's pictures. Besides
a screenshot per step and `logcat.txt`, it keeps `proof.txt`, the proof's own
output, and `commit.txt`, the commit it proved.

### The proofs that have run

The demo proof passed 20 of 20 checks on two arm64 emulators, both API 36, one
with 4 KB pages and one with 16 KB pages, on Friday 2026-10-09 EDT, with the
code of this release.

The checks: the label reads back after an `input tap`; the deliberately clipped
label is logged; the count survives a rotation and a tap after it works;
`jolt_library_init` ran once and the screen mounted once per Activity; the
proof swipes the vocabulary's scroll until its fourth button is wholly in view
and inside it; the checkbutton reads checked after a tap, with the check mark
only a re-render draws; no prop was unknown and no call failed; and Back, taken
by the second screen, returns to the first, while a second Back, which nothing
takes, sends the Activity away.

## Hiccup reference

Elements are `[:tag props? & children]`, as in glimmer-uikit. Nine tags make a
View; the root of the tree mounts into the Activity's content view.

**Containers:** `:vbox` and `:hbox` (a `LinearLayout`), `:layers` (children
fill it edge to edge, back to front), `:scroll` (a `ScrollView` whose children
stack vertically).

**Leaf widgets:** `:label` (a `TextView`), `:button`, `:checkbutton`, `:image`,
`:gradient`.

**Common props (every tag):**

- `:width`/`:height`: dp. A missing side wraps its content, or fills, by the
  parent's alignment.
- `:halign`: `:fill :start :end :center`, read by a vertical stack;
  `:valign`: `:fill :top :bottom :center`, read by an `:hbox`. A stack has one
  cross alignment: the last child's that sets one. Without one, children are
  centred.
- `:hexpand`/`:vexpand`: the child takes the stack's slack along that axis.
- `:alpha`: 0.0–1.0; `:center-y`, a vertical offset in dp.
- `:sensitive`: `false` dims the view to 0.4 of its `:alpha`. A button is also
  disabled.
- `:key`: glimmer's key, for reordering.
- On the root: `:vfill` (the root fills the screen's height, so a `:vexpand`
  child can push a footer down) and `:full-bleed` (the root is not padded by the
  system bars' insets, and draws under them).

**Per-tag props:**

- Vbox, hbox: `:background` (`"#rrggbb"` or `"#rgb"`), `:spacing` (dp),
  `:margin` (all four sides) or `:margin-top`/`:margin-bottom`/`:margin-left`/
  `:margin-right`, and `:safe` (padded by the insets, inside a full-bleed root).
- Scroll: `:spacing`. Layers: `:background`.
- Label: `:label` (or `:markup`, below), `:xalign` (below 0.34 the start, above
  0.66 the end, else the centre, as iOS reads it), `:wrap` (any value wraps;
  without it a label is one line), `:date` (milliseconds, formatted by the
  phone's locale, medium style) with `:date-markup` (span attributes for it).
  On a label, `:date` replaces the text.
- Button: `:label`, `:label-date` (milliseconds, shown as a date before
  `:label`), `:font-size` (sp, default 15), `:font-weight` (`:bold`),
  `:foreground`, `:background`, `:radius`, `:border` (`[width "#rrggbb" alpha]`),
  `:padding` (the text's left and right inset, dp), `:xalign`, and
  `:sensitive` (false disables the button, as well as dimming it).
- Checkbutton: `:active`, `:symbol` (the check mark's size, default 24),
  `:foreground` (its colour), `:background`, `:radius`, and `:description` (the
  accessibility description, which a test can find it by).
- Image: `:src`: a file in the APK's assets.
- Gradient: `:stops`: `[["#rrggbb" alpha location] ...]`, top to bottom.

**A button's or checkbutton's `:height` is its least height.** At the font size
a design was drawn at, nothing moves. When a person sets larger text in
Settings, it grows instead of cutting its text off. Every other tag's
`:height` is exact.

**Markup.** A label's `:markup` takes Pango-style hiccup data, the vocabulary
glimmer-uikit and glimmer-gtk use: `[:span {:foreground "#ffffff" :size
"28672"} "Vocabulary"]`. A span's `:size` (1024ths of a point), `:foreground`,
`:weight "bold"`, `:font_family` (a name containing "Bold" reads as bold) and
`:letter_spacing` are read, and spans nest. The first run is the label's style,
and a later run that differs gets an Android span. Only span attributes are
read: an element's name styles nothing, so `[:b …]`, `[:i …]`, `[:s …]` and
`[:u …]` draw their text plain, where glimmer-uikit styles it. Write bold as
`[:span {:weight "bold"} …]`, which both backends read. Italic, underline and
strikethrough have no Android form yet, by tag or by attribute.

**An unknown prop is ignored, and said out loud.** A prop that is neither common
nor in its tag's list does nothing, and the backend prints one line naming the
tag and the prop, once per pair. A tag outside the nine throws, naming it.

**Events:**

- `:on-click`: a button tapped. `:on-toggled`: a checkbutton tapped. Handlers
  take no arguments.

The listener is wired once, at mount; the handler behind it is the latest
render's, so a reused button never fires the handler of the screen it was built
for. A `CheckBox` toggles itself before any handler runs, so after the handler
the box is put back to the `:active` the latest render left: the View follows
the app's state, never the tap.

**Re-rendering** runs only the setters whose props changed, a prop that went
away included, and a setter given no prop applies glimmer-android's default. So
a reused View looks like a new one with the later render's props.

## The app loop

`glimmer-android.core` (requiring it installs the backend):

- `mount!`: mount hiccup into the Activity's content view. An app's entry
  export calls it once per Activity: the Activity owns the loop, so nothing
  here runs one.
- `back!` names the one function to run on Back; `take-back!` says whether the
  screen showing takes Back. While no screen does, Android's own Back applies,
  with its animation. A rotation's new Activity starts with no callback, so the
  entry says it again after it mounts.
- `on-background!` names one function to run when the Activity stops: the
  moment Android's lifecycle guide gives for saving.
- `files-dir` and `version`: the app's private files directory and version
  name, or nil.
- `open-url!`: open a link with the app Android chooses (`mailto:` reaches only
  email apps). Nothing to open it is printed, never thrown.
- `stop!` and `reset-back!` are internals: used by tests; not part of the app
  API. `stop!` is what C's stop export runs, and `glimmer-android.fake/run`
  calls both between tests.

A throw in a handler, the Back handler, the background handler or a mount is
printed, never passed to C: one that escaped into C would end the process.
Work scheduled by glimmer runs inline, since Jolt runs on the UI thread.

## Using it from an app

### The pin

```clojure
jolt-lang/glimmer-android {:git/url "https://github.com/jolt-lang/glimmer-android"
                           :git/tag "v0.1.0"
                           :git/sha "<sha>"}
```

The sha is the tag's commit: the hash on the `refs/tags/v0.1.0^{}` line that
`git ls-remote https://github.com/jolt-lang/glimmer-android v0.1.0` prints.

Put it in the alias the app's Android build uses, and in its test alias if
tests use the fake. Two aliases that pin different commits do not fail: jolt
takes the last alias's, so keep them equal. glimmer-android pins glimmer v0.1.1.
An app that pins glimmer itself wins, since jolt's deps walk is breadth-first,
so move that pin with this one.

### The Java and C, from jolt's checkout

jolt fetches a git dependency whole, `android/` included, into its gitlibs
directory. So one pin governs the Clojure, the Java and the C, and nothing is
published to a Maven repository. Find the checkout through `-Spath`: the entry
whose parent holds the C half's `CMakeLists.txt`. That matches a `:local/root`
clone too.

```sh
root=$(jolt -A:android -Spath | tr ':' '\n' | while IFS= read -r p; do
         r=$(dirname "$p")
         if [ -f "$r/android/glimmer/src/main/cpp/CMakeLists.txt" ]; then echo "$r"; break; fi
       done)
[ -n "$root" ] || { echo "no glimmer-android under -A:android" >&2; exit 1; }
gradle -PglimmerAndroid="$root" assembleDebug
```

The app's `build.gradle.kts` takes both paths from that property, and refuses
to configure without it:

```kotlin
val glimmer = providers.gradleProperty("glimmerAndroid").orNull
    ?: error("no -PglimmerAndroid: pass glimmer-android's checkout")

android {
    ndkVersion = "30.0.16248370"
    defaultConfig { minSdk = 28; ndk { abiFilters += "arm64-v8a" } }
    sourceSets { getByName("main") { java.srcDirs("$glimmer/android/glimmer/src/main/java") } }
    externalNativeBuild {
        cmake { path = file("$glimmer/android/glimmer/src/main/cpp/CMakeLists.txt"); version = "4.1.2" }
    }
}
```

The app's own Jolt library goes into `src/main/jniLibs/arm64-v8a/`;
`scripts/lib` is the recipe, with `NS`, `OUT` and `ALIAS` set for the app. The
manifest names it and the entry, so no app subclasses the Activity:

```xml
<application android:theme="@android:style/Theme.DeviceDefault.NoActionBar"
             android:enableOnBackInvokedCallback="true">
  <activity android:name="glimmer.android.GlimmerActivity" android:exported="true">
    <meta-data android:name="glimmer.library" android:value="myapp" />   <!-- libmyapp.so -->
    <meta-data android:name="glimmer.entry" android:value="myapp_main" />
    ...
  </activity>
</application>
```

Without `enableOnBackInvokedCallback`, Android 13 and later never call the
callback `take-back!` registers. Without an action bar, a full-bleed screen
reaches the status bar, so `:safe` has a bar to clear. `android/demo/` is a
whole project to copy; it reads the Java and C at `../glimmer` instead of a
property.

### `:jolt/native`

The C half, `libglimmer_android.so`, is declared in this library's `deps.edn`
under `:jolt/native`, as optional, and reaches an app through the pin: the app
declares nothing for it. `defcfn` binds lazily, so the host, where the library
is absent, loads every namespace and only a call needs it. Both `:darwin` and
`:linux` name the Android file, because `jolt build` bakes in the candidates
for the machine it builds on, not the target: a Mac building for Android reads
`:darwin`.

## Testing without a device

`glimmer-android.fake` stands in for every binding of the C half, so an app's
screens mount on the host, in `jolt test` and on Linux CI. It keeps a real tree:
each id's kind, its children in order, and the last arguments of every setter
called on it. A call on an id C never gave out, or one released, answers 0 as C
does, and glimmer-android throws on it. Every call's argument types are checked
against `glimmer-android.ffi/signatures`, so a long where C takes a double fails
here as it would on the phone.

`fake/run` is a fixture as it stands. It starts each test from an empty screen,
stops a screen the test left mounted, and asserts at the end that every call
had C's types.

```clojure
(ns myapp.android-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [glimmer-android.core :as ga]
            [glimmer-android.fake :as fake]
            [myapp.android :as app]))

(use-fixtures :each fake/run)

(deftest a-tap-counts
  (ga/mount! [app/counter])
  (let [[label button] (fake/children (first (fake/children 0)))]
    (fake/tap! button)
    (is (= "Tapped 1 times" (fake/text label)))))
```

What it offers: `children`, `kind` (1 vbox, 2 label, 3 button, 4 hbox, 5 layers,
6 scroll, 7 checkbutton, 8 image, 9 gradient), `text`, `last-set` (a setter's
last arguments, by C name), `views`, `live`, `first-of-kind`, `released`,
`history`, `calls`, `refused`; `tap!`, `back!` and `background!` send what C
would. `*files-dir*`, `*version*` and `*opens?*` decide what `files-dir`,
`version` and `open-url!` see. The fake requires `clojure.test`, which ships
with jolt.

## Proving an app on the emulator

`scripts/emulator-lib` holds the helpers the demo's proof uses, for an app's
own proof to source. It is API: a change to anything below is a CHANGELOG
entry.

- The sourcing script sets `TOOL` (the name its messages print), `AVD`, `PKG`,
  `APK` and `out` (its directory for pictures and logcat) first.
- The library needs `ANDROID_HOME`, and dies naming it when it is unset or not
  a directory. It puts `$ANDROID_HOME/platform-tools` and
  `$ANDROID_HOME/emulator` at the front of the sourcing script's `PATH`.
- The library sets `PORT` (5580) and `SERIAL` (`emulator-5580`), and writes
  `logcat.txt` into `out`, and `emulator.txt`, the emulator's own output, when
  it boots one. Every `adb` call names the emulator by `SERIAL`, so
  another device on adb's default port is never touched.
- It provides `die`, `a`, `on_port`, `boot`, `rotate`, `settled`, `cleanup`,
  `shot`, `dump`, `has`, `bounds`, `centre_of`, `centre`, `wait_for_log` and
  `check`.
- `boot` installs `trap cleanup EXIT`. A caller that replaces that trap MUST
  call `cleanup` from its own.

What they do:

- `boot` boots `$AVD` on the port, or reuses it if it is up. Another AVD on the
  port is given 60 s to leave, perhaps still shutting down, and then refused.
  An `$AVD` that `emulator -list-avds` does not list is refused before a boot,
  with the list. An emulator that exits before it reaches the port, or has not
  reached it in 60 s, is refused, naming `emulator.txt`. It waits for the boot and for user 0 to reach `RUNNING_UNLOCKED`
  (Android force-stops an app installed before then), installs `$APK`, waits
  for WindowManager to apply any stored rotation, turns to portrait, and clears
  logcat with a 16 MB buffer.
- `cleanup` saves logcat, unless the script already did, and turns back to
  portrait. Before it kills an emulator it booted, it waits 5 s and syncs: a
  setting reaches disk up to 2 s after it changes, and `emu kill` flushes
  nothing, so a rotation could outlive the run. An emulator it booted that
  never reached the port is killed by its pid.
- `a` is `adb -s $SERIAL`. `rotate N` turns the display to N (0 portrait, 1
  landscape) and waits until it has. `settled` waits for WindowManager to agree
  with the stored rotation. `on_port` says whether an emulator is on the port.
- `shot NAME` saves `out/NAME.png`. `dump` prints uiautomator's tree; `has TEXT`
  finds a node by its text; `bounds 'name="value"'` prints a node's bounds as
  `x1 y1 x2 y2`; `centre_of` and `centre TEXT` its centre, for `input tap`.
- `wait_for_log PATTERN SECONDS` waits for a `glimmer-android` logcat line.
  `check WHAT CMD...` prints `TOOL: ok — WHAT` or dies.

A debuggable app also gets `ClipCheck`, which logs `clipped: "<text>"` for each
`TextView` whose text does not fit its view, once layout has been quiet for
250 ms, so a proof can read clipping from logcat.

## Architecture

Four namespaces, a C half and a Java half:

- **`glimmer-android.ffi`**: the C half's functions, bound by name with
  `jolt.ffi/defcfn`. Each wrapper goes through one `call`: it refuses an id
  Clojure has released before C sees it, coerces each argument to the declared
  type (jolt's FFI throws when a `:double` gets a long), and refuses a 0 answer
  by name. `signatures` repeats the declared types for the coercion and the
  fake.
- **`glimmer-android.props`**: props into the numbers and words C takes:
  colours as ARGB, Pango sizes as points, markup as runs (offsets in UTF-16
  units, as Android's spans count), alignment as layout params. Pure, so the
  host tests it.
- **`glimmer-android.widget`**: hiccup to Views. A view is an id in C's table;
  `tree` keeps each one's tag, parent, children and latest props. A prop group
  is the props one setter applies together, and owns one Android property.
  Sizes, alignment and expansion are the parent's business: `relayout!` sets
  each child's layout params when a child comes, goes or moves, or a layout
  prop changes. A removal releases the whole subtree.
- **`glimmer-android.core`**: the backend map handed to
  `glimmer.backend/register!`, the app loop and the exports C calls back:
  `glimmer_android_event`, `glimmer_android_back`, `glimmer_android_stop` and
  `glimmer_android_background`.
- **The C half** (`android/glimmer/src/main/cpp/`) keeps one table of JNI
  global references; the index is the id Clojure holds. Each call looks an id
  up, makes one or two JNI calls, and answers 1, or 0 with the exception logged
  and cleared. dp and sp become pixels here. It opens the app's Jolt library
  once per process, starts the runtime on the UI thread, and looks up the
  exports. Ids are never reused within an Activity, and the table holds 4,096;
  a new Activity, a rotation's included, starts a fresh table.
- **The Java half** (`glimmer.android`): `GlimmerActivity` loads the C half and
  the app's library, hands C the system bars' insets, forwards Back while a
  screen takes it, and calls native at create, stop and destroy. It decides
  nothing.

## License

MIT (see `LICENSE`).
