/* glimmer-android's fetch: C between Clojure and glimmer.android.Fetch.
 * ga_fetch and ga_cancel_fetch run on the UI thread; so does
 * nativeFetched, which Fetch posts there. */
#include "glimmer_android.h"
#include <stdlib.h>

static jclass c_fetch, c_string;
static jmethodID m_start, m_cancel;

/* From cache(), not prepare(): a fetch needs no Activity. */
int ga_fetch_cache(JNIEnv *e) {
  c_fetch  = ga_class(e, "glimmer/android/Fetch");
  c_string = ga_class(e, "java/lang/String");
  if (!c_fetch || !c_string) return 0;
  m_start  = (*e)->GetStaticMethodID(e, c_fetch, "start", "(ILjava/lang/String;[Ljava/lang/String;III)Z");
  if (!m_start) { ga_failed(e, "Fetch.start"); return 0; }
  m_cancel = (*e)->GetStaticMethodID(e, c_fetch, "cancel", "(I)V");
  if (!m_cancel) { ga_failed(e, "Fetch.cancel"); return 0; }
  return 1;
}

/* Start fetch `id`. `headers` is a NULL-terminated array of names and
 * values, alternating. 1 when Java took it; 0, logged, when not. */
EXPORT int ga_fetch(int id, const char *url, const char **headers, int connect_ms, int read_ms,
                    int total_ms) {
  JNIEnv *e = ga_env();
  if (!e || !c_fetch) { LOGE("ga_fetch: no Fetch class"); return 0; }
  int n = 0;
  while (headers && headers[n]) n++;
  if (n % 2) { LOGE("ga_fetch: %d header strings, not pairs", n); return 0; }
  jobjectArray hs = (*e)->NewObjectArray(e, n, c_string, NULL);
  if (ga_failed(e, "ga_fetch headers") || !hs) return 0;
  for (int i = 0; i < n; i++) {
    jstring s = (*e)->NewStringUTF(e, headers[i]);
    if (ga_failed(e, "ga_fetch header") || !s) { (*e)->DeleteLocalRef(e, hs); return 0; }
    (*e)->SetObjectArrayElement(e, hs, i, s);
    (*e)->DeleteLocalRef(e, s);
  }
  jstring ju = (*e)->NewStringUTF(e, url ? url : "");
  jboolean ok = ju ? (*e)->CallStaticBooleanMethod(e, c_fetch, m_start, id, ju, hs, connect_ms,
                                                   read_ms, total_ms)
                   : JNI_FALSE;
  if (ju) (*e)->DeleteLocalRef(e, ju);
  (*e)->DeleteLocalRef(e, hs);
  if (ga_failed(e, "ga_fetch") || !ok) { LOGE("ga_fetch: %s not started", url ? url : ""); return 0; }
  return 1;
}

EXPORT int ga_cancel_fetch(int id) {
  JNIEnv *e = ga_env();
  if (!e || !c_fetch) { LOGE("ga_cancel_fetch: no Fetch class"); return 0; }
  (*e)->CallStaticVoidMethod(e, c_fetch, m_cancel, id);
  return ga_failed(e, "ga_cancel_fetch") ? 0 : 1;
}

/* A fetch's answer, on the UI thread. The body's bytes are copied and
 * NUL-terminated for jolt, which copies the string in turn, so both are
 * freed when the call returns. A body holding a NUL byte ends there. */
JNIEXPORT void JNICALL Java_glimmer_android_Fetch_nativeFetched(
    JNIEnv *e, jclass cls, jint id, jint status, jbyteArray body, jstring error) {
  (void)cls;
  if (!ga_jolt_fetched) { LOGE("fetch %d answered, but the app has no glimmer_android_fetched", id); return; }
  char *b = NULL;
  if (body) {
    jsize n = (*e)->GetArrayLength(e, body);
    b = malloc((size_t)n + 1);
    if (!b) {   /* still answered once: an error rather than nothing */
      LOGE("fetch %d: no memory for %d bytes", id, n);
      ga_jolt_fetched(id, 0, NULL, "no memory for the body");
      return;
    }
    (*e)->GetByteArrayRegion(e, body, 0, n, (jbyte *)b);
    b[n] = 0;
  }
  const char *er = error ? (*e)->GetStringUTFChars(e, error, NULL) : NULL;
  ga_jolt_fetched(id, status, b, er);
  if (er) (*e)->ReleaseStringUTFChars(e, error, er);
  free(b);
}
