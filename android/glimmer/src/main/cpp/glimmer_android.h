/* State and helpers glimmer_android.c shares with glimmer_more.c and glimmer_fetch.c. */
#ifndef GLIMMER_ANDROID_H
#define GLIMMER_ANDROID_H
#include <jni.h>
#include <android/log.h>

#define TAG "glimmer-android"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define EXPORT __attribute__((visibility("default")))
#define CAP 4096
enum { KIND_VBOX = 1, KIND_LABEL = 2, KIND_BUTTON = 3, KIND_HBOX = 4, KIND_LAYERS = 5,
       KIND_SCROLL = 6, KIND_CHECK = 7, KIND_IMAGE = 8, KIND_GRADIENT = 9 };

extern jobject g_activity;
extern jobject g_root;
extern jobject g_views[CAP];
extern int g_next_id;
extern float g_density;
extern void (*ga_jolt_fetched)(int, int, const char *, const char *);

JNIEnv *ga_env(void);
int ga_failed(JNIEnv *e, const char *call);
jclass ga_class(JNIEnv *e, const char *name);
jobject ga_view(int id);
void ga_more_teardown(JNIEnv *e);
void ga_more_release(int id);
jobject create_more(JNIEnv *e, int kind);
jobject container(int id);
int ga_fetch_cache(JNIEnv *e);
#endif
