/* glimmer-android's C half: JNI on one side, plain C for Jolt on the
 * other. Every function runs on Android's UI thread, which is Jolt's
 * owner thread. */
#include "glimmer_android.h"
#include <dlfcn.h>
#include <pthread.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <unistd.h>



static JavaVM *vm;
jobject g_activity;          /* global: the Activity on the screen */
jobject g_root;              /* global: its content view, id 0 */
jobject g_views[CAP];        /* global refs; the index is the id */
int g_next_id = 1;           /* never reused within one Activity */
float g_density = 1.0f;

static int started;               /* jolt_library_init ran: once per process */
static void (*jolt_event)(int, int);
static void (*jolt_stop)(void);
static void (*jolt_entry)(void);
static void (*jolt_back)(void);
static void (*jolt_background)(void);
static jmethodID m_set_back_taken;   /* the Activity's setBackTaken(Z)V */
static char files_dir[1024];      /* the app's files directory, from Java */
static char app_version[128];     /* its version name, from Java */
static int debuggable;            /* whether it is debuggable, from Java */

static jclass c_linear, c_text, c_button, c_group, c_view, c_click, c_params;
static jmethodID m_linear_new, m_orientation, m_text_new, m_set_text, m_button_new,
    m_add, m_add_at, m_remove, m_index_of, m_on_click, m_click_new, m_params_new,
    m_set_params, m_get_params, m_all_caps;
static jfieldID f_width, f_height;

static int out[2];                /* stdout and stderr, as a pipe to logcat */

static void *pump(void *arg) {
  (void)arg;
  FILE *in = fdopen(out[0], "r");
  char line[1024];
  while (in && fgets(line, sizeof line, in)) {
    line[strcspn(line, "\n")] = 0;
    __android_log_write(ANDROID_LOG_INFO, TAG, line);
  }
  return NULL;
}

JNIEXPORT jint JNI_OnLoad(JavaVM *jvm, void *reserved) {
  (void)reserved;
  vm = jvm;
  setvbuf(stdout, NULL, _IOLBF, 0);
  setvbuf(stderr, NULL, _IONBF, 0);
  if (pipe(out) == 0) {
    dup2(out[1], STDOUT_FILENO);
    dup2(out[1], STDERR_FILENO);
    pthread_t p;
    pthread_create(&p, NULL, pump, NULL);
    pthread_detach(p);
  }
  return JNI_VERSION_1_6;
}

JNIEnv *ga_env(void) {
  JNIEnv *e = NULL;
  if ((*vm)->GetEnv(vm, (void **)&e, JNI_VERSION_1_6) != JNI_OK) return NULL;
  return e;
}

/* A JNI call threw: log it under `call`, clear it, and say so. */
int ga_failed(JNIEnv *e, const char *call) {
  if (!(*e)->ExceptionCheck(e)) return 0;
  (*e)->ExceptionDescribe(e);
  (*e)->ExceptionClear(e);
  LOGE("%s failed", call);
  return 1;
}

jclass ga_class(JNIEnv *e, const char *name) {
  jclass local = (*e)->FindClass(e, name);
  if (!local) { ga_failed(e, name); return NULL; }
  jclass g = (*e)->NewGlobalRef(e, local);
  (*e)->DeleteLocalRef(e, local);
  return g;
}

static int cached;

#define METHOD(m, c, name, sig) \
  do { (m) = (*e)->GetMethodID(e, (c), (name), (sig)); \
       if (!(m)) { ga_failed(e, name); return 0; } } while (0)

/* The classes and method ids, once. A failure stops at once, with the
 * exception logged and cleared, and the next call tries again. */
static int cache(JNIEnv *e) {
  if (cached) return 1;
  c_linear = ga_class(e, "android/widget/LinearLayout");
  c_text   = ga_class(e, "android/widget/TextView");
  c_button = ga_class(e, "android/widget/Button");
  c_group  = ga_class(e, "android/view/ViewGroup");
  c_view   = ga_class(e, "android/view/View");
  c_click  = ga_class(e, "glimmer/android/Click");
  c_params = ga_class(e, "android/view/ViewGroup$LayoutParams");
  if (!c_linear || !c_text || !c_button || !c_group || !c_view || !c_click || !c_params) return 0;
  METHOD(m_linear_new,  c_linear, "<init>", "(Landroid/content/Context;)V");
  METHOD(m_orientation, c_linear, "setOrientation", "(I)V");
  METHOD(m_text_new,    c_text, "<init>", "(Landroid/content/Context;)V");
  METHOD(m_set_text,    c_text, "setText", "(Ljava/lang/CharSequence;)V");
  METHOD(m_all_caps,    c_text, "setAllCaps", "(Z)V");
  METHOD(m_button_new,  c_button, "<init>", "(Landroid/content/Context;)V");
  METHOD(m_add,         c_group, "addView", "(Landroid/view/View;)V");
  METHOD(m_add_at,      c_group, "addView", "(Landroid/view/View;I)V");
  METHOD(m_remove,      c_group, "removeView", "(Landroid/view/View;)V");
  METHOD(m_index_of,    c_group, "indexOfChild", "(Landroid/view/View;)I");
  METHOD(m_on_click,    c_view, "setOnClickListener", "(Landroid/view/View$OnClickListener;)V");
  METHOD(m_set_params,  c_view, "setLayoutParams", "(Landroid/view/ViewGroup$LayoutParams;)V");
  METHOD(m_click_new,   c_click, "<init>", "(I)V");
  METHOD(m_get_params,  c_view, "getLayoutParams", "()Landroid/view/ViewGroup$LayoutParams;");
  METHOD(m_params_new,  c_params, "<init>", "(II)V");
  f_width  = (*e)->GetFieldID(e, c_params, "width", "I");
  f_height = (*e)->GetFieldID(e, c_params, "height", "I");
  if (!f_width || !f_height) { ga_failed(e, "LayoutParams width/height"); return 0; }
  cached = 1;
  return 1;
}

/* The View for `id`: 0 is the g_root; an empty slot is NULL. */
jobject ga_view(int id) {
  if (id == 0) return g_root;
  if (id < 0 || id >= g_next_id) return NULL;
  return g_views[id];
}

EXPORT int ga_create(int kind) {
  JNIEnv *e = ga_env();
  if (!e || !g_activity) { LOGE("ga_create: no Activity"); return 0; }
  if (g_next_id >= CAP) { LOGE("ga_create: the table is full"); return 0; }
  jobject v = NULL;
  switch (kind) {
  case KIND_VBOX:
    v = (*e)->NewObject(e, c_linear, m_linear_new, g_activity);
    if (!ga_failed(e, "ga_create vbox") && v) (*e)->CallVoidMethod(e, v, m_orientation, 1);
    break;
  case KIND_LABEL:  v = (*e)->NewObject(e, c_text, m_text_new, g_activity); break;
  case KIND_BUTTON:
    v = (*e)->NewObject(e, c_button, m_button_new, g_activity);
    /* a label's own case, not the theme's capitals */
    if (!ga_failed(e, "ga_create button") && v) (*e)->CallVoidMethod(e, v, m_all_caps, JNI_FALSE);
    break;
  default: v = create_more(e, kind); break;
  }
  /* setOrientation or setAllCaps threw: the View was made, so free it */
  if (ga_failed(e, "ga_create") || !v) { if (v) (*e)->DeleteLocalRef(e, v); return 0; }
  int id = g_next_id++;
  g_views[id] = (*e)->NewGlobalRef(e, v);
  (*e)->DeleteLocalRef(e, v);
  return id;
}

EXPORT int ga_set_text(int id, const char *s) {
  JNIEnv *e = ga_env();
  jobject v = ga_view(id);
  if (!e || !v || id == 0) { LOGE("ga_set_text: no view %d", id); return 0; }
  jstring js = (*e)->NewStringUTF(e, s ? s : "");
  (*e)->CallVoidMethod(e, v, m_set_text, js);
  (*e)->DeleteLocalRef(e, js);
  return ga_failed(e, "ga_set_text") ? 0 : 1;
}

/* Width and height in dp; -1 fills the parent, -2 wraps the content.
 * A View already in a parent holds that parent's kind of params, which
 * the parent casts to when it measures: those are changed in place. A
 * plain ViewGroup.LayoutParams is made only for a View with none, and
 * addView converts it. */
EXPORT int ga_set_size(int id, int w, int h) {
  JNIEnv *e = ga_env();
  jobject v = ga_view(id);
  if (!e || !v || id == 0) { LOGE("ga_set_size: no view %d", id); return 0; }
  int pw = w < 0 ? w : (int)(w * g_density + 0.5f);
  int ph = h < 0 ? h : (int)(h * g_density + 0.5f);
  jobject p = (*e)->CallObjectMethod(e, v, m_get_params);
  if (ga_failed(e, "ga_set_size get params")) return 0;
  if (p) {
    (*e)->SetIntField(e, p, f_width, pw);
    (*e)->SetIntField(e, p, f_height, ph);
  } else {
    p = (*e)->NewObject(e, c_params, m_params_new, pw, ph);
    if (ga_failed(e, "ga_set_size params") || !p) return 0;
  }
  (*e)->CallVoidMethod(e, v, m_set_params, p);
  (*e)->DeleteLocalRef(e, p);
  return ga_failed(e, "ga_set_size") ? 0 : 1;
}

EXPORT int ga_set_on_click(int id, int on) {
  JNIEnv *e = ga_env();
  jobject v = ga_view(id);
  if (!e || !v || id == 0) { LOGE("ga_set_on_click: no view %d", id); return 0; }
  jobject listener = NULL;
  if (on) {
    listener = (*e)->NewObject(e, c_click, m_click_new, id);
    if (ga_failed(e, "ga_set_on_click listener") || !listener) return 0;
  }
  (*e)->CallVoidMethod(e, v, m_on_click, listener);
  if (listener) (*e)->DeleteLocalRef(e, listener);
  return ga_failed(e, "ga_set_on_click") ? 0 : 1;
}

EXPORT int ga_append(int parent, int child) {
  JNIEnv *e = ga_env();
  jobject p = container(parent), c = ga_view(child);
  if (!e || !p || !c) { LOGE("ga_append: no view %d or %d", parent, child); return 0; }
  (*e)->CallVoidMethod(e, p, m_add, c);
  return ga_failed(e, "ga_append") ? 0 : 1;
}

EXPORT int ga_remove(int parent, int child) {
  JNIEnv *e = ga_env();
  jobject p = container(parent), c = ga_view(child);
  if (!e || !p || !c) { LOGE("ga_remove: no view %d or %d", parent, child); return 0; }
  (*e)->CallVoidMethod(e, p, m_remove, c);
  return ga_failed(e, "ga_remove") ? 0 : 1;
}

/* Put `new_id` where `old_id` was, in the same place among its siblings. */
EXPORT int ga_replace(int parent, int old_id, int new_id) {
  JNIEnv *e = ga_env();
  jobject p = container(parent), o = ga_view(old_id), n = ga_view(new_id);
  if (!e || !p || !o || !n) { LOGE("ga_replace: no view"); return 0; }
  jint at = (*e)->CallIntMethod(e, p, m_index_of, o);
  if (ga_failed(e, "ga_replace index") || at < 0) return 0;
  (*e)->CallVoidMethod(e, p, m_remove, o);
  if (ga_failed(e, "ga_replace remove")) return 0;
  (*e)->CallVoidMethod(e, p, m_add_at, n, at);
  return ga_failed(e, "ga_replace add") ? 0 : 1;
}

/* Free the reference for `id`. The slot stays empty: ids are not reused. */
EXPORT int ga_release(int id) {
  JNIEnv *e = ga_env();
  if (!e || id <= 0 || id >= g_next_id || !g_views[id]) { LOGE("ga_release: no view %d", id); return 0; }
  (*e)->DeleteGlobalRef(e, g_views[id]);
  g_views[id] = NULL;
  ga_more_release(id);
  return 1;
}

static long millis(void) {
  struct timespec t;
  clock_gettime(CLOCK_MONOTONIC, &t);
  return t.tv_sec * 1000L + t.tv_nsec / 1000000L;
}

/* Once per process: open the app's Jolt library, start the runtime, look
 * up the five exports, and hand the thread back (jolt's docs: a thread
 * parked in a run loop must be released). */
static int start_jolt(JNIEnv *e, jstring lib, jstring entry) {
  const char *l = (*e)->GetStringUTFChars(e, lib, NULL);
  const char *n = (*e)->GetStringUTFChars(e, entry, NULL);
  int ok = 0;
  void *h = dlopen(l, RTLD_NOW | RTLD_LOCAL);
  if (!h) { LOGE("dlopen %s: %s", l, dlerror()); goto done; }
  int (*init)(int, char **) = (int (*)(int, char **))dlsym(h, "jolt_library_init");
  void *(*lookup)(const char *) = (void *(*)(const char *))dlsym(h, "jolt_lookup");
  void (*release)(void) = (void (*)(void))dlsym(h, "jolt_library_release_thread");
  if (!init || !lookup || !release) { LOGE("%s has no jolt ABI", l); goto done; }
  long t0 = millis();
  char name[] = "glimmer-android";
  char *argv[] = {name, NULL};
  int rc = init(1, argv);   /* one argument, the name, as a program's main passes */
  LOGI("jolt_library_init=%d in %ld ms, pid %d, thread %d", rc, millis() - t0, getpid(), gettid());
  if (rc != 0) goto done;
  started = 1;              /* the runtime is up: never init again, whatever follows */
  jolt_event = (void (*)(int, int))lookup("glimmer_android_event");
  jolt_stop  = (void (*)(void))lookup("glimmer_android_stop");
  jolt_back  = (void (*)(void))lookup("glimmer_android_back");
  jolt_background = (void (*)(void))lookup("glimmer_android_background");
  jolt_entry = (void (*)(void))lookup(n);
  if (!jolt_event || !jolt_stop || !jolt_back || !jolt_background || !jolt_entry) {
    LOGE("missing export: event %p, stop %p, back %p, background %p, %s %p", (void *)jolt_event,
         (void *)jolt_stop, (void *)jolt_back, (void *)jolt_background, n, (void *)jolt_entry);
    goto done;
  }
  release();
  ok = 1;
done:
  (*e)->ReleaseStringUTFChars(e, lib, l);
  (*e)->ReleaseStringUTFChars(e, entry, n);
  return ok;
}

/* The Activity's screen is going. Jolt unmounts first, so it can still
 * reach its Views; then every reference left is freed and the table
 * starts again. */
static void teardown(JNIEnv *e) {
  if (jolt_stop) jolt_stop();
  for (int i = 1; i < g_next_id; i++) {
    if (g_views[i]) { (*e)->DeleteGlobalRef(e, g_views[i]); g_views[i] = NULL; }
  }
  ga_more_teardown(e);
  g_next_id = 1;
  if (g_root) { (*e)->DeleteGlobalRef(e, g_root); g_root = NULL; }
  if (g_activity) { (*e)->DeleteGlobalRef(e, g_activity); g_activity = NULL; }
}

JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeStart(
    JNIEnv *e, jobject self, jobject content, jstring lib, jstring entry, jfloat dp,
    jstring files, jstring version, jboolean dbg) {
  if (!cache(e)) { LOGE("could not cache the Android classes"); return; }
  /* Each Activity is the same class, so its method is looked up once. */
  if (!m_set_back_taken) {
    jclass ac = (*e)->GetObjectClass(e, self);
    m_set_back_taken = (*e)->GetMethodID(e, ac, "setBackTaken", "(Z)V");
    (*e)->DeleteLocalRef(e, ac);
    if (!m_set_back_taken) { ga_failed(e, "setBackTaken"); return; }
  }
  /* An older Activity not yet destroyed: its screen goes first. */
  if (g_activity) {
    teardown(e);
    LOGI("start before the older Activity stopped; its screen was torn down first");
  }
  g_activity = (*e)->NewGlobalRef(e, self);
  g_root = (*e)->NewGlobalRef(e, content);
  g_density = dp;
  /* Copied before the entry runs, so Clojure can ask for them from it. */
  if (files) {
    const char *s = (*e)->GetStringUTFChars(e, files, NULL);
    if (s) { snprintf(files_dir, sizeof files_dir, "%s", s); (*e)->ReleaseStringUTFChars(e, files, s); }
    else { ga_failed(e, "nativeStart strings"); files_dir[0] = 0; }
  }
  if (version) {
    const char *s = (*e)->GetStringUTFChars(e, version, NULL);
    if (s) { snprintf(app_version, sizeof app_version, "%s", s); (*e)->ReleaseStringUTFChars(e, version, s); }
    else { ga_failed(e, "nativeStart strings"); app_version[0] = 0; }
  }
  debuggable = dbg == JNI_TRUE;
  if (!started && !start_jolt(e, lib, entry)) return;
  if (!jolt_entry) { LOGE("no entry to run; see the lines above"); return; }
  long t0 = millis();
  jolt_entry();
  LOGI("entry ran in %ld ms on thread %d", millis() - t0, gettid());
}

JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeEvent(
    JNIEnv *e, jclass cls, jint id, jint kind) {
  (void)e; (void)cls;
  if (!jolt_event) return;
  long t0 = millis();
  jolt_event(id, kind);
  LOGI("event %d ran in %ld ms", id, millis() - t0);
}

/* Tell the Activity whether the screen showing takes Back. */
EXPORT int ga_set_back(int on) {
  JNIEnv *e = ga_env();
  if (!e || !g_activity || !m_set_back_taken) { LOGE("ga_set_back: no Activity"); return 0; }
  (*e)->CallVoidMethod(e, g_activity, m_set_back_taken, on ? JNI_TRUE : JNI_FALSE);
  return ga_failed(e, "ga_set_back") ? 0 : 1;
}

JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeBack(JNIEnv *e, jclass cls) {
  (void)e; (void)cls;
  LOGI("back");
  if (jolt_back) jolt_back();
}

EXPORT const char *ga_files_dir(void) { return files_dir; }
EXPORT const char *ga_version(void) { return app_version; }
EXPORT int ga_debuggable(void) { return debuggable; }

/* The Activity stopped: the app's moment to save. */
JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeBackground(JNIEnv *e, jobject self) {
  if (!g_activity || !(*e)->IsSameObject(e, self, g_activity)) return;   /* an older Activity */
  LOGI("background");
  if (jolt_background) jolt_background();
}

/* The Activity is going. A stop from an Activity that is no longer the
 * current one is late: nativeStart has already torn its screen down. */
JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeStop(JNIEnv *e, jobject self) {
  if (!g_activity || !(*e)->IsSameObject(e, self, g_activity)) {
    LOGI("stop from an older Activity; ignored");
    return;
  }
  teardown(e);
}
