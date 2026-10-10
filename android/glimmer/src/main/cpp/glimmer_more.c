/* glimmer-android's other views and setters. Each takes plain numbers
 * and strings the Clojure half has already decided, makes one or two JNI
 * calls, logs a failure, and answers 1, or 0 when it failed. */
#include "glimmer_android.h"
#include <stdio.h>
#include <string.h>

#define MATCH -1
#define WRAP  -2

static jobject inner[CAP];    /* a scroll's stack, by the scroll's id */
static int safe[CAP];         /* views padded by the insets */
static float pad[CAP][4];     /* each view's own padding, in dp */
static jobject colors[CAP];   /* a text view's own colours, kept before the first change */
static int full_bleed;        /* the root ignores the insets */
static int insets[4];         /* left, top, right, bottom, in px */
static jobject run_text;      /* the SpannableString being built */

static int ready;
static jclass k_linear, k_frame, k_scroll, k_check, k_image, k_view, k_text, k_compound,
    k_drawable, k_lparams, k_flparams, k_vparams, k_typeface, k_spannable, k_fg_span,
    k_style_span, k_size_span, k_truncate, k_scale, k_orient, k_date_fmt, k_date,
    k_bitmap_fac, k_assets, k_stream, k_build, k_intent, k_uri, k_under_span, k_strike_span;
static jmethodID n_linear, n_frame, n_scroll, n_check, n_image, n_view, n_drawable,
    n_spannable, n_fg_span, n_style_span, n_size_span, n_under_span, n_strike_span, n_date,
    s_orientation, s_divider, s_show_dividers, s_fill_viewport, s_button_drawable,
    s_text_size, s_text_color, s_typeface, s_letter_spacing, s_gravity_text,
    s_gravity_linear, s_max_lines, s_ellipsize, s_break_strategy, s_min_height, s_min_width,
    s_minimum_height, s_minimum_width, s_text, s_set_span, s_color, s_corner, s_stroke,
    s_dsize, s_colors, s_colors_at, s_dorient, s_background, s_alpha, s_enabled,
    s_padding, s_translation_y, s_description, s_checked, s_scale_type, s_bitmap,
    s_get_params, s_set_params, s_add, s_add_at, s_remove, s_assets, s_open, s_close,
    s_decode, s_date_instance, s_format, s_state_animator, s_request_layout,
    s_get_colors, s_set_colors, s_image_drawable, n_intent, s_uri_parse, s_start_activity;
static jfieldID f_w, f_h, f_weight, f_lgravity, f_fgravity, f_sdk;
static jobject o_end, o_center_crop, o_top_bottom;

/* The class references prepare() takes, so a retry after a failure can
 * give back what the failed attempt took. */
static jclass *const class_slots[] = {
    &k_linear, &k_frame, &k_scroll, &k_check, &k_image, &k_view, &k_text, &k_compound,
    &k_drawable, &k_lparams, &k_flparams, &k_vparams, &k_typeface, &k_spannable, &k_fg_span,
    &k_style_span, &k_size_span, &k_truncate, &k_scale, &k_orient, &k_date_fmt, &k_date,
    &k_bitmap_fac, &k_assets, &k_stream, &k_build, &k_intent, &k_uri, &k_under_span,
    &k_strike_span};
static jobject *const object_slots[] = {&o_end, &o_center_crop, &o_top_bottom};

/* Give back the global references a failed prepare() took. */
static void unprepare(JNIEnv *e) {
  for (size_t i = 0; i < sizeof class_slots / sizeof class_slots[0]; i++)
    if (*class_slots[i]) { (*e)->DeleteGlobalRef(e, *class_slots[i]); *class_slots[i] = NULL; }
  for (size_t i = 0; i < sizeof object_slots / sizeof object_slots[0]; i++)
    if (*object_slots[i]) { (*e)->DeleteGlobalRef(e, *object_slots[i]); *object_slots[i] = NULL; }
}

#define M(m, c, name, sig) \
  do { (m) = (*e)->GetMethodID(e, (c), (name), (sig)); if (!(m)) { ga_failed(e, name); return 0; } } while (0)
#define S(m, c, name, sig) \
  do { (m) = (*e)->GetStaticMethodID(e, (c), (name), (sig)); if (!(m)) { ga_failed(e, name); return 0; } } while (0)
#define F(f, c, name, sig) \
  do { (f) = (*e)->GetFieldID(e, (c), (name), (sig)); if (!(f)) { ga_failed(e, name); return 0; } } while (0)

static jobject static_object(JNIEnv *e, jclass c, const char *name, const char *sig) {
  jfieldID f = (*e)->GetStaticFieldID(e, c, name, sig);
  if (!f) { ga_failed(e, name); return NULL; }
  jobject o = (*e)->GetStaticObjectField(e, c, f);
  return o ? (*e)->NewGlobalRef(e, o) : NULL;
}

static int prepare(JNIEnv *e) {
  if (ready) return 1;
  if (!g_activity) { LOGE("prepare: no Activity yet"); return 0; }
  unprepare(e);   /* an earlier attempt that failed partway took some */
  const char *ns[] = {"android/widget/LinearLayout", "android/widget/FrameLayout",
      "android/widget/ScrollView", "android/widget/CheckBox", "android/widget/ImageView",
      "android/view/View", "android/widget/TextView", "android/widget/CompoundButton",
      "android/graphics/drawable/GradientDrawable", "android/widget/LinearLayout$LayoutParams",
      "android/widget/FrameLayout$LayoutParams", "android/view/ViewGroup$LayoutParams",
      "android/graphics/Typeface", "android/text/SpannableString",
      "android/text/style/ForegroundColorSpan", "android/text/style/StyleSpan",
      "android/text/style/RelativeSizeSpan", "android/text/TextUtils$TruncateAt",
      "android/widget/ImageView$ScaleType", "android/graphics/drawable/GradientDrawable$Orientation",
      "java/text/DateFormat", "java/util/Date", "android/graphics/BitmapFactory",
      "android/content/res/AssetManager", "java/io/InputStream", "android/os/Build$VERSION",
      "android/content/Intent", "android/net/Uri", "android/text/style/UnderlineSpan",
      "android/text/style/StrikethroughSpan"};
  for (size_t i = 0; i < sizeof class_slots / sizeof class_slots[0]; i++)
    if (!(*class_slots[i] = ga_class(e, ns[i]))) return 0;
  const char *ctx = "(Landroid/content/Context;)V";
  M(n_linear, k_linear, "<init>", ctx);   M(n_frame, k_frame, "<init>", ctx);
  M(n_scroll, k_scroll, "<init>", ctx);   M(n_check, k_check, "<init>", ctx);
  M(n_image, k_image, "<init>", ctx);     M(n_view, k_view, "<init>", ctx);
  M(n_drawable, k_drawable, "<init>", "()V");
  M(n_spannable, k_spannable, "<init>", "(Ljava/lang/CharSequence;)V");
  M(n_fg_span, k_fg_span, "<init>", "(I)V");
  M(n_style_span, k_style_span, "<init>", "(I)V");
  M(n_size_span, k_size_span, "<init>", "(F)V");
  M(n_under_span, k_under_span, "<init>", "()V");
  M(n_strike_span, k_strike_span, "<init>", "()V");
  M(n_date, k_date, "<init>", "(J)V");
  M(s_orientation, k_linear, "setOrientation", "(I)V");
  M(s_divider, k_linear, "setDividerDrawable", "(Landroid/graphics/drawable/Drawable;)V");
  M(s_show_dividers, k_linear, "setShowDividers", "(I)V");
  M(s_gravity_linear, k_linear, "setGravity", "(I)V");
  M(s_fill_viewport, k_scroll, "setFillViewport", "(Z)V");
  M(s_button_drawable, k_compound, "setButtonDrawable", "(Landroid/graphics/drawable/Drawable;)V");
  M(s_checked, k_compound, "setChecked", "(Z)V");
  M(s_text_size, k_text, "setTextSize", "(IF)V");
  M(s_text_color, k_text, "setTextColor", "(I)V");
  M(s_typeface, k_text, "setTypeface", "(Landroid/graphics/Typeface;I)V");
  M(s_letter_spacing, k_text, "setLetterSpacing", "(F)V");
  M(s_gravity_text, k_text, "setGravity", "(I)V");
  M(s_max_lines, k_text, "setMaxLines", "(I)V");
  M(s_ellipsize, k_text, "setEllipsize", "(Landroid/text/TextUtils$TruncateAt;)V");
  M(s_break_strategy, k_text, "setBreakStrategy", "(I)V");
  M(s_min_height, k_text, "setMinHeight", "(I)V");
  M(s_min_width, k_text, "setMinWidth", "(I)V");
  M(s_text, k_text, "setText", "(Ljava/lang/CharSequence;)V");
  M(s_set_span, k_spannable, "setSpan", "(Ljava/lang/Object;III)V");
  M(s_color, k_drawable, "setColor", "(I)V");
  M(s_corner, k_drawable, "setCornerRadius", "(F)V");
  M(s_stroke, k_drawable, "setStroke", "(II)V");
  M(s_dsize, k_drawable, "setSize", "(II)V");
  M(s_colors, k_drawable, "setColors", "([I)V");
  M(s_dorient, k_drawable, "setOrientation", "(Landroid/graphics/drawable/GradientDrawable$Orientation;)V");
  M(s_background, k_view, "setBackground", "(Landroid/graphics/drawable/Drawable;)V");
  M(s_alpha, k_view, "setAlpha", "(F)V");
  M(s_enabled, k_view, "setEnabled", "(Z)V");
  M(s_padding, k_view, "setPadding", "(IIII)V");
  M(s_translation_y, k_view, "setTranslationY", "(F)V");
  M(s_description, k_view, "setContentDescription", "(Ljava/lang/CharSequence;)V");
  M(s_minimum_height, k_view, "setMinimumHeight", "(I)V");
  M(s_minimum_width, k_view, "setMinimumWidth", "(I)V");
  M(s_state_animator, k_view, "setStateListAnimator", "(Landroid/animation/StateListAnimator;)V");
  M(s_get_params, k_view, "getLayoutParams", "()Landroid/view/ViewGroup$LayoutParams;");
  M(s_set_params, k_view, "setLayoutParams", "(Landroid/view/ViewGroup$LayoutParams;)V");
  M(s_request_layout, k_view, "requestLayout", "()V");
  M(s_scale_type, k_image, "setScaleType", "(Landroid/widget/ImageView$ScaleType;)V");
  M(s_bitmap, k_image, "setImageBitmap", "(Landroid/graphics/Bitmap;)V");
  M(s_image_drawable, k_image, "setImageDrawable", "(Landroid/graphics/drawable/Drawable;)V");
  M(s_open, k_assets, "open", "(Ljava/lang/String;)Ljava/io/InputStream;");
  M(s_close, k_stream, "close", "()V");
  M(s_format, k_date_fmt, "format", "(Ljava/util/Date;)Ljava/lang/String;");
  { jclass g = ga_class(e, "android/view/ViewGroup"); if (!g) return 0;
    /* not M: g is given back on the way out, whatever is found */
    s_add = (*e)->GetMethodID(e, g, "addView", "(Landroid/view/View;)V");
    s_add_at = s_add ? (*e)->GetMethodID(e, g, "addView", "(Landroid/view/View;I)V") : NULL;
    s_remove = s_add_at ? (*e)->GetMethodID(e, g, "removeView", "(Landroid/view/View;)V") : NULL;
    (*e)->DeleteGlobalRef(e, g);
    if (!s_remove) { ga_failed(e, "addView or removeView"); return 0; } }
  { jclass a = (*e)->GetObjectClass(e, g_activity);
    s_assets = (*e)->GetMethodID(e, a, "getAssets", "()Landroid/content/res/AssetManager;");
    s_start_activity = s_assets ? (*e)->GetMethodID(e, a, "startActivity", "(Landroid/content/Intent;)V") : NULL;
    (*e)->DeleteLocalRef(e, a);
    if (!s_assets) { ga_failed(e, "getAssets"); return 0; }
    if (!s_start_activity) { ga_failed(e, "startActivity"); return 0; } }
  M(n_intent, k_intent, "<init>", "(Ljava/lang/String;Landroid/net/Uri;)V");
  S(s_uri_parse, k_uri, "parse", "(Ljava/lang/String;)Landroid/net/Uri;");
  M(s_get_colors, k_text, "getTextColors", "()Landroid/content/res/ColorStateList;");
  M(s_set_colors, k_text, "setTextColor", "(Landroid/content/res/ColorStateList;)V");
  S(s_decode, k_bitmap_fac, "decodeStream", "(Ljava/io/InputStream;)Landroid/graphics/Bitmap;");
  S(s_date_instance, k_date_fmt, "getDateInstance", "(I)Ljava/text/DateFormat;");
  F(f_w, k_vparams, "width", "I");
  F(f_h, k_vparams, "height", "I");
  F(f_weight, k_lparams, "weight", "F");
  F(f_lgravity, k_lparams, "gravity", "I");
  F(f_fgravity, k_flparams, "gravity", "I");
  f_sdk = (*e)->GetStaticFieldID(e, k_build, "SDK_INT", "I");
  if (!f_sdk) { ga_failed(e, "SDK_INT"); return 0; }
  /* setColors(int[], float[]) is API 29; on 28 the colours are spaced evenly */
  if ((*e)->GetStaticIntField(e, k_build, f_sdk) >= 29) {
    s_colors_at = (*e)->GetMethodID(e, k_drawable, "setColors", "([I[F)V");
    if (!s_colors_at) ga_failed(e, "setColors(int[], float[])");
  }
  o_end = static_object(e, k_truncate, "END", "Landroid/text/TextUtils$TruncateAt;");
  o_center_crop = static_object(e, k_scale, "CENTER_CROP", "Landroid/widget/ImageView$ScaleType;");
  o_top_bottom = static_object(e, k_orient, "TOP_BOTTOM", "Landroid/graphics/drawable/GradientDrawable$Orientation;");
  if (!o_end || !o_center_crop || !o_top_bottom) return 0;
  ready = 1;
  return 1;
}

static int px(float dp) { return (int)(dp * g_density + (dp < 0 ? -0.5f : 0.5f)); }

/* The setters below begin the same way: a JNIEnv, the classes, and a
 * live view that is not the root. */
#define LIVE(call)                                                             \
  JNIEnv *e = ga_env();                                                        \
  jobject v = ga_view(id);                                                     \
  if (!e || !prepare(e) || !v || id == 0) { LOGE(call ": no view %d", id); return 0; }

jobject create_more(JNIEnv *e, int kind) {
  if (!prepare(e)) return NULL;
  jobject v = NULL;
  switch (kind) {
  case KIND_HBOX:
    v = (*e)->NewObject(e, k_linear, n_linear, g_activity);
    if (!ga_failed(e, "create hbox") && v) (*e)->CallVoidMethod(e, v, s_orientation, 0);
    break;
  case KIND_LAYERS:   v = (*e)->NewObject(e, k_frame, n_frame, g_activity); break;
  case KIND_GRADIENT: v = (*e)->NewObject(e, k_view, n_view, g_activity); break;
  case KIND_IMAGE:
    v = (*e)->NewObject(e, k_image, n_image, g_activity);
    if (!ga_failed(e, "create image") && v) (*e)->CallVoidMethod(e, v, s_scale_type, o_center_crop);
    break;
  case KIND_CHECK:
    v = (*e)->NewObject(e, k_check, n_check, g_activity);
    if (ga_failed(e, "create check") || !v) return NULL;
    /* each call only when the one before it raised nothing */
    (*e)->CallVoidMethod(e, v, s_button_drawable, NULL);     /* the tile is the box */
    if (!ga_failed(e, "check button drawable")) {
      (*e)->CallVoidMethod(e, v, s_gravity_text, 17);        /* Gravity.CENTER */
      if (!ga_failed(e, "check gravity")) {
        (*e)->CallVoidMethod(e, v, s_min_height, 0);
        if (!ga_failed(e, "check min height")) {
          (*e)->CallVoidMethod(e, v, s_min_width, 0);
          if (!ga_failed(e, "check min width")) return v;
        }
      }
    }
    (*e)->DeleteLocalRef(e, v);
    return NULL;
  case KIND_SCROLL: {
    v = (*e)->NewObject(e, k_scroll, n_scroll, g_activity);
    if (ga_failed(e, "create scroll") || !v) break;
    (*e)->CallVoidMethod(e, v, s_fill_viewport, JNI_TRUE);
    if (ga_failed(e, "scroll fill viewport")) { (*e)->DeleteLocalRef(e, v); return NULL; }
    jobject in = (*e)->NewObject(e, k_linear, n_linear, g_activity);
    if (ga_failed(e, "create scroll stack") || !in) { (*e)->DeleteLocalRef(e, v); return NULL; }
    (*e)->CallVoidMethod(e, in, s_orientation, 1);
    if (!ga_failed(e, "scroll stack orientation")) (*e)->CallVoidMethod(e, v, s_add, in);
    if (ga_failed(e, "scroll add stack")) {
      (*e)->DeleteLocalRef(e, in);
      (*e)->DeleteLocalRef(e, v);
      return NULL;
    }
    inner[g_next_id] = (*e)->NewGlobalRef(e, in);            /* the id v is about to get */
    (*e)->DeleteLocalRef(e, in);
    break;
  }
  default: LOGE("ga_create: no kind %d", kind); return NULL;
  }
  if (ga_failed(e, "create_more")) { if (v) (*e)->DeleteLocalRef(e, v); return NULL; }
  return v;
}

jobject container(int id) {
  if (id > 0 && id < CAP && inner[id]) return inner[id];
  return ga_view(id);
}

/* A label's or button's base style: size in sp; a colour in ARGB when
 * has_color, else the view's own colours back; a Typeface style (1 bold,
 * 2 italic, 3 both); tracking in em. */
EXPORT int ga_text_style(int id, double size, int has_color, long long color, int style, double tracking) {
  LIVE("ga_text_style");
  (*e)->CallVoidMethod(e, v, s_text_size, 2, (jfloat)size);   /* COMPLEX_UNIT_SP */
  if (ga_failed(e, "ga_text_style size")) return 0;
  if (!colors[id]) {
    jobject c = (*e)->CallObjectMethod(e, v, s_get_colors);
    if (ga_failed(e, "ga_text_style colours") || !c) return 0;
    colors[id] = (*e)->NewGlobalRef(e, c);
    (*e)->DeleteLocalRef(e, c);
  }
  if (has_color) (*e)->CallVoidMethod(e, v, s_text_color, (jint)color);
  else (*e)->CallVoidMethod(e, v, s_set_colors, colors[id]);
  if (ga_failed(e, "ga_text_style colour")) return 0;
  (*e)->CallVoidMethod(e, v, s_typeface, NULL, style & 3);
  if (ga_failed(e, "ga_text_style typeface")) return 0;
  (*e)->CallVoidMethod(e, v, s_letter_spacing, (jfloat)tracking);
  return ga_failed(e, "ga_text_style") ? 0 : 1;
}

/* Text with spans: begin with the whole text, add a span per run that
 * differs from the base, end by setting it. */
EXPORT int ga_runs_begin(int id, const char *text) {
  LIVE("ga_runs_begin");
  jstring s = (*e)->NewStringUTF(e, text ? text : "");
  if (ga_failed(e, "ga_runs_begin text") || !s) return 0;
  jobject sp = (*e)->NewObject(e, k_spannable, n_spannable, s);
  (*e)->DeleteLocalRef(e, s);
  if (ga_failed(e, "ga_runs_begin") || !sp) return 0;
  if (run_text) (*e)->DeleteGlobalRef(e, run_text);
  run_text = (*e)->NewGlobalRef(e, sp);
  (*e)->DeleteLocalRef(e, sp);
  return 1;
}

static int add_span(JNIEnv *e, jobject span, int start, int end) {
  if (!span) return 0;
  (*e)->CallVoidMethod(e, run_text, s_set_span, span, start, end, 33);   /* EXCLUSIVE_EXCLUSIVE */
  (*e)->DeleteLocalRef(e, span);
  return !ga_failed(e, "setSpan");
}

/* One run's differences from the base: a colour (0: none), a Typeface
 * style (0: none), a size relative to the base (0: none), an underline
 * and a strikethrough. start and end are UTF-16 offsets. */
EXPORT int ga_run(int start, int end, long long color, int style, double relative, int underline,
                  int strike) {
  JNIEnv *e = ga_env();
  if (!e || !run_text) { LOGE("ga_run: no text begun"); return 0; }
  if (color && !add_span(e, (*e)->NewObject(e, k_fg_span, n_fg_span, (jint)color), start, end)) return 0;
  if ((style & 3) && !add_span(e, (*e)->NewObject(e, k_style_span, n_style_span, style & 3), start, end)) return 0;
  if (relative > 0 && !add_span(e, (*e)->NewObject(e, k_size_span, n_size_span, (jfloat)relative), start, end)) return 0;
  if (underline && !add_span(e, (*e)->NewObject(e, k_under_span, n_under_span), start, end)) return 0;
  if (strike && !add_span(e, (*e)->NewObject(e, k_strike_span, n_strike_span), start, end)) return 0;
  return 1;
}

EXPORT int ga_runs_end(int id) {
  LIVE("ga_runs_end");
  if (!run_text) { LOGE("ga_runs_end: no text begun"); return 0; }
  (*e)->CallVoidMethod(e, v, s_text, run_text);
  (*e)->DeleteGlobalRef(e, run_text);
  run_text = NULL;
  return ga_failed(e, "ga_runs_end") ? 0 : 1;
}

/* Gravity for a text or a stack: Android's Gravity bits. */
EXPORT int ga_set_gravity(int id, int gravity) {
  LIVE("ga_set_gravity");
  if ((*e)->IsInstanceOf(e, v, k_text)) (*e)->CallVoidMethod(e, v, s_gravity_text, gravity);
  else (*e)->CallVoidMethod(e, v, s_gravity_linear, gravity);
  return ga_failed(e, "ga_set_gravity") ? 0 : 1;
}

/* 0: as many lines as it needs; n: at most n, the tail ellipsized. Each
 * line takes as many words as fit (BREAK_STRATEGY_SIMPLE): TextView's
 * default weighs the whole paragraph and can end a line early. */
EXPORT int ga_set_lines(int id, int n) {
  LIVE("ga_set_lines");
  (*e)->CallVoidMethod(e, v, s_max_lines, n > 0 ? n : 0x7fffffff);
  (*e)->CallVoidMethod(e, v, s_ellipsize, n > 0 ? o_end : NULL);
  (*e)->CallVoidMethod(e, v, s_break_strategy, 0);
  return ga_failed(e, "ga_set_lines") ? 0 : 1;
}

/* A rounded fill and a stroke, as one drawable. color 0 and stroke 0
 * remove the background. radius and stroke in dp. */
EXPORT int ga_set_box(int id, long long color, double radius, double stroke, long long stroke_color) {
  LIVE("ga_set_box");
  jobject d = NULL;
  if (color || stroke > 0) {
    d = (*e)->NewObject(e, k_drawable, n_drawable);
    if (ga_failed(e, "ga_set_box drawable") || !d) return 0;
    (*e)->CallVoidMethod(e, d, s_color, (jint)color);
    (*e)->CallVoidMethod(e, d, s_corner, (jfloat)px((float)radius));
    if (stroke > 0) (*e)->CallVoidMethod(e, d, s_stroke, px((float)stroke), (jint)stroke_color);
  }
  (*e)->CallVoidMethod(e, v, s_background, d);
  if (d) (*e)->DeleteLocalRef(e, d);
  return ga_failed(e, "ga_set_box") ? 0 : 1;
}

/* A button's least height in dp: its :height, so text that needs more
 * grows it. TextView's minimum and View's both, as ga_plain clears both. */
EXPORT int ga_set_min_height(int id, double dp) {
  LIVE("ga_set_min_height");
  int h = px((float)dp);
  (*e)->CallVoidMethod(e, v, s_min_height, h);
  if (ga_failed(e, "ga_set_min_height text")) return 0;
  (*e)->CallVoidMethod(e, v, s_minimum_height, h);
  return ga_failed(e, "ga_set_min_height") ? 0 : 1;
}

/* A button's look starts plain: no minimum size, no shadow, no inset. */
EXPORT int ga_plain(int id) {
  LIVE("ga_plain");
  (*e)->CallVoidMethod(e, v, s_min_height, 0);
  if (ga_failed(e, "ga_plain min height")) return 0;
  (*e)->CallVoidMethod(e, v, s_min_width, 0);
  if (ga_failed(e, "ga_plain min width")) return 0;
  (*e)->CallVoidMethod(e, v, s_minimum_height, 0);
  if (ga_failed(e, "ga_plain minimum height")) return 0;
  (*e)->CallVoidMethod(e, v, s_minimum_width, 0);
  if (ga_failed(e, "ga_plain minimum width")) return 0;
  (*e)->CallVoidMethod(e, v, s_state_animator, NULL);
  if (ga_failed(e, "ga_plain animator")) return 0;
  (*e)->CallVoidMethod(e, v, s_padding, 0, 0, 0, 0);
  return ga_failed(e, "ga_plain") ? 0 : 1;
}

EXPORT int ga_set_alpha(int id, double a) {
  LIVE("ga_set_alpha");
  (*e)->CallVoidMethod(e, v, s_alpha, (jfloat)a);
  return ga_failed(e, "ga_set_alpha") ? 0 : 1;
}

EXPORT int ga_set_enabled(int id, int on) {
  LIVE("ga_set_enabled");
  (*e)->CallVoidMethod(e, v, s_enabled, on ? JNI_TRUE : JNI_FALSE);
  return ga_failed(e, "ga_set_enabled") ? 0 : 1;
}

static void apply_padding(JNIEnv *e, jobject v, int l, int t, int r, int b) {
  (*e)->CallVoidMethod(e, v, s_padding, l, t, r, b);
}

/* A view's own padding, and the insets on top when it is safe. */
static void repad(JNIEnv *e, int id, jobject v) {
  int s = safe[id];
  apply_padding(e, v, px(pad[id][0]) + (s ? insets[0] : 0), px(pad[id][1]) + (s ? insets[1] : 0),
                px(pad[id][2]) + (s ? insets[2] : 0), px(pad[id][3]) + (s ? insets[3] : 0));
}

/* Padding in dp, kept so the insets can be added to it later. */
EXPORT int ga_set_padding(int id, double l, double t, double r, double b) {
  LIVE("ga_set_padding");
  pad[id][0] = (float)l; pad[id][1] = (float)t; pad[id][2] = (float)r; pad[id][3] = (float)b;
  repad(e, id, v);
  return ga_failed(e, "ga_set_padding") ? 0 : 1;
}

EXPORT int ga_set_safe(int id, int on) {
  LIVE("ga_set_safe");
  safe[id] = on;
  repad(e, id, v);
  return ga_failed(e, "ga_set_safe") ? 0 : 1;
}

/* The root pads itself by the insets unless it is full-bleed. */
static void pad_root(JNIEnv *e) {
  if (!g_root) return;
  if (full_bleed) apply_padding(e, g_root, 0, 0, 0, 0);
  else apply_padding(e, g_root, insets[0], insets[1], insets[2], insets[3]);
}

EXPORT int ga_set_full_bleed(int on) {
  JNIEnv *e = ga_env();
  if (!e || !prepare(e)) return 0;
  full_bleed = on;
  pad_root(e);
  return ga_failed(e, "ga_set_full_bleed") ? 0 : 1;
}

JNIEXPORT void JNICALL Java_glimmer_android_GlimmerActivity_nativeInsets(
    JNIEnv *e, jclass cls, jint l, jint t, jint r, jint b) {
  (void)cls;
  insets[0] = l; insets[1] = t; insets[2] = r; insets[3] = b;
  LOGI("insets %d %d %d %d", l, t, r, b);
  if (!prepare(e)) return;
  pad_root(e);
  for (int i = 1; i < g_next_id; i++)
    if (safe[i] && g_views[i]) repad(e, i, g_views[i]);
  ga_failed(e, "nativeInsets");
}

/* Layout params in the parent's own kind, changed in place: w and h in
 * dp, MATCH or WRAP; weight; Android's Gravity bits, or -1 for none. */
EXPORT int ga_set_layout(int id, int w, int h, double weight, int gravity) {
  LIVE("ga_set_layout");
  jobject p = (*e)->CallObjectMethod(e, v, s_get_params);
  if (ga_failed(e, "ga_set_layout params") || !p) { LOGE("ga_set_layout: view %d has no parent", id); return 0; }
  (*e)->SetIntField(e, p, f_w, w < 0 ? w : px((float)w));
  (*e)->SetIntField(e, p, f_h, h < 0 ? h : px((float)h));
  if ((*e)->IsInstanceOf(e, p, k_lparams)) {
    (*e)->SetFloatField(e, p, f_weight, (jfloat)weight);
    if (gravity >= 0) (*e)->SetIntField(e, p, f_lgravity, gravity);
  } else if ((*e)->IsInstanceOf(e, p, k_flparams) && gravity >= 0) {
    (*e)->SetIntField(e, p, f_fgravity, gravity);
  }
  (*e)->CallVoidMethod(e, v, s_set_params, p);
  (*e)->DeleteLocalRef(e, p);
  return ga_failed(e, "ga_set_layout") ? 0 : 1;
}

/* The gap between a stack's children, in dp, as a clear divider. */
EXPORT int ga_set_spacing(int id, double dp) {
  JNIEnv *e = ga_env();
  jobject v = container(id);
  if (!e || !prepare(e) || !v || id == 0) { LOGE("ga_set_spacing: no view %d", id); return 0; }
  jobject d = (*e)->NewObject(e, k_drawable, n_drawable);
  if (ga_failed(e, "ga_set_spacing drawable") || !d) return 0;
  int n = px((float)dp);
  (*e)->CallVoidMethod(e, d, s_dsize, n, n);
  (*e)->CallVoidMethod(e, v, s_divider, d);
  (*e)->CallVoidMethod(e, v, s_show_dividers, n > 0 ? 2 : 0);   /* SHOW_DIVIDER_MIDDLE */
  (*e)->DeleteLocalRef(e, d);
  return ga_failed(e, "ga_set_spacing") ? 0 : 1;
}

EXPORT int ga_set_checked(int id, int on) {
  LIVE("ga_set_checked");
  (*e)->CallVoidMethod(e, v, s_checked, on ? JNI_TRUE : JNI_FALSE);
  return ga_failed(e, "ga_set_checked") ? 0 : 1;
}

EXPORT int ga_set_description(int id, const char *s) {
  LIVE("ga_set_description");
  jstring js = s ? (*e)->NewStringUTF(e, s) : NULL;
  if (s && (ga_failed(e, "ga_set_description text") || !js)) return 0;
  (*e)->CallVoidMethod(e, v, s_description, js);
  if (js) (*e)->DeleteLocalRef(e, js);
  return ga_failed(e, "ga_set_description") ? 0 : 1;
}

/* An image from the APK's assets, by file name; "" clears it. Every
 * local reference is given back on every path out. */
EXPORT int ga_set_image(int id, const char *name) {
  LIVE("ga_set_image");
  if (!name || !*name) {
    (*e)->CallVoidMethod(e, v, s_image_drawable, NULL);
    return ga_failed(e, "ga_set_image clear") ? 0 : 1;
  }
  int ok = 0, bad;
  jobject am = NULL, in = NULL, bmp = NULL;
  jstring n = NULL;
  am = (*e)->CallObjectMethod(e, g_activity, s_assets);
  if (ga_failed(e, "ga_set_image assets") || !am) goto out;
  n = (*e)->NewStringUTF(e, name);
  if (ga_failed(e, "ga_set_image name") || !n) goto out;
  in = (*e)->CallObjectMethod(e, am, s_open, n);
  if (ga_failed(e, "ga_set_image open") || !in) { LOGE("no asset %s", name); goto out; }
  bmp = (*e)->CallStaticObjectMethod(e, k_bitmap_fac, s_decode, in);
  bad = ga_failed(e, "ga_set_image decode") || !bmp;
  (*e)->CallVoidMethod(e, in, s_close);
  if (ga_failed(e, "ga_set_image close") || bad) goto out;
  (*e)->CallVoidMethod(e, v, s_bitmap, bmp);
  ok = !ga_failed(e, "ga_set_image");
out:
  if (bmp) (*e)->DeleteLocalRef(e, bmp);
  if (in) (*e)->DeleteLocalRef(e, in);
  if (n) (*e)->DeleteLocalRef(e, n);
  if (am) (*e)->DeleteLocalRef(e, am);
  return ok;
}

/* A top-to-bottom gradient of up to 8 stops: ARGB colours and offsets. */
static jint stop_colors[8];
static jfloat stop_at[8];
static int stop_count;

EXPORT int ga_stop(long long color, double at) {
  if (stop_count >= 8) { LOGE("ga_stop: more than 8 stops"); return 0; }
  stop_colors[stop_count] = (jint)color;
  stop_at[stop_count] = (jfloat)at;
  stop_count++;
  return 1;
}

EXPORT int ga_set_gradient(int id) {
  int n = stop_count;
  stop_count = 0;                 /* whatever happens, the next gradient starts empty */
  LIVE("ga_set_gradient");
  if (n < 2) { LOGE("ga_set_gradient: %d stops", n); return 0; }
  jobject d = (*e)->NewObject(e, k_drawable, n_drawable);
  if (ga_failed(e, "ga_set_gradient drawable") || !d) return 0;
  (*e)->CallVoidMethod(e, d, s_dorient, o_top_bottom);
  jintArray cs = (*e)->NewIntArray(e, n);
  (*e)->SetIntArrayRegion(e, cs, 0, n, stop_colors);
  if (s_colors_at) {
    jfloatArray at = (*e)->NewFloatArray(e, n);
    (*e)->SetFloatArrayRegion(e, at, 0, n, stop_at);
    (*e)->CallVoidMethod(e, d, s_colors_at, cs, at);
    (*e)->DeleteLocalRef(e, at);
  } else {
    (*e)->CallVoidMethod(e, d, s_colors, cs);
  }
  (*e)->CallVoidMethod(e, v, s_background, d);
  (*e)->DeleteLocalRef(e, cs);
  (*e)->DeleteLocalRef(e, d);
  return ga_failed(e, "ga_set_gradient") ? 0 : 1;
}

EXPORT int ga_set_translation_y(int id, double dp) {
  LIVE("ga_set_translation_y");
  (*e)->CallVoidMethod(e, v, s_translation_y, (jfloat)px((float)dp));
  return ga_failed(e, "ga_set_translation_y") ? 0 : 1;
}

/* Move `child` to `index` among `parent`'s children. */
EXPORT int ga_move(int parent, int child, int index) {
  JNIEnv *e = ga_env();
  jobject p = container(parent), c = ga_view(child);
  if (!e || !prepare(e) || !p || !c) { LOGE("ga_move: no view %d or %d", parent, child); return 0; }
  (*e)->CallVoidMethod(e, p, s_remove, c);
  if (ga_failed(e, "ga_move remove")) return 0;
  (*e)->CallVoidMethod(e, p, s_add_at, c, index);
  return ga_failed(e, "ga_move add") ? 0 : 1;
}

/* A date in the medium style of the device's locale. The answer lives
 * until the next call. */
EXPORT const char *ga_format_date(long long millis) {
  static char out[128];
  JNIEnv *e = ga_env();
  out[0] = 0;
  if (!e || !prepare(e)) return out;
  jobject fmt = (*e)->CallStaticObjectMethod(e, k_date_fmt, s_date_instance, 2);   /* MEDIUM */
  if (ga_failed(e, "ga_format_date instance") || !fmt) return out;
  jobject d = (*e)->NewObject(e, k_date, n_date, (jlong)millis);
  if (ga_failed(e, "ga_format_date") || !d) { (*e)->DeleteLocalRef(e, fmt); return out; }
  jstring s = (jstring)(*e)->CallObjectMethod(e, fmt, s_format, d);
  if (!ga_failed(e, "ga_format_date format") && s) {
    const char *c = (*e)->GetStringUTFChars(e, s, NULL);
    snprintf(out, sizeof out, "%s", c);
    (*e)->ReleaseStringUTFChars(e, s, c);
    (*e)->DeleteLocalRef(e, s);
  }
  (*e)->DeleteLocalRef(e, d);
  (*e)->DeleteLocalRef(e, fmt);
  return out;
}

/* Open `url`: ACTION_SENDTO for mailto:, so only email apps answer;
 * ACTION_VIEW otherwise. 1 when an app took it; 0 when none could
 * (ActivityNotFoundException, logged and cleared) or a call failed. */
EXPORT int ga_open_url(const char *url) {
  JNIEnv *e = ga_env();
  if (!e || !prepare(e) || !url) { LOGE("ga_open_url: nothing to open"); return 0; }
  const char *action = strncmp(url, "mailto:", 7) == 0
                         ? "android.intent.action.SENDTO" : "android.intent.action.VIEW";
  jstring ja = (*e)->NewStringUTF(e, action);
  jstring ju = (*e)->NewStringUTF(e, url);
  jobject uri = NULL, intent = NULL;
  int ok = 0;
  if (ga_failed(e, "ga_open_url strings") || !ja || !ju) goto done;
  uri = (*e)->CallStaticObjectMethod(e, k_uri, s_uri_parse, ju);
  if (ga_failed(e, "ga_open_url parse") || !uri) goto done;
  intent = (*e)->NewObject(e, k_intent, n_intent, ja, uri);
  if (ga_failed(e, "ga_open_url intent") || !intent) goto done;
  (*e)->CallVoidMethod(e, g_activity, s_start_activity, intent);
  ok = !ga_failed(e, "ga_open_url start");   /* ActivityNotFoundException lands here */
done:
  if (intent) (*e)->DeleteLocalRef(e, intent);
  if (uri) (*e)->DeleteLocalRef(e, uri);
  if (ju) (*e)->DeleteLocalRef(e, ju);
  if (ja) (*e)->DeleteLocalRef(e, ja);
  return ok;
}

/* Called from teardown: a scroll's stack and the safe flags go with the
 * table. */
void ga_more_teardown(JNIEnv *e) {
  for (int i = 0; i < CAP; i++) {
    if (inner[i]) { (*e)->DeleteGlobalRef(e, inner[i]); inner[i] = NULL; }
    if (colors[i]) { (*e)->DeleteGlobalRef(e, colors[i]); colors[i] = NULL; }
    safe[i] = 0;
    pad[i][0] = pad[i][1] = pad[i][2] = pad[i][3] = 0;
  }
  if (run_text) { (*e)->DeleteGlobalRef(e, run_text); run_text = NULL; }   /* a text begun, never ended */
  stop_count = 0;                                                             /* stops sent, never used */
  full_bleed = 0;
}

void ga_more_release(int id) {
  JNIEnv *e = ga_env();
  if (e && id > 0 && id < CAP && inner[id]) { (*e)->DeleteGlobalRef(e, inner[id]); inner[id] = NULL; }
  if (e && id > 0 && id < CAP && colors[id]) { (*e)->DeleteGlobalRef(e, colors[id]); colors[id] = NULL; }
  if (id > 0 && id < CAP) { safe[id] = 0; pad[id][0] = pad[id][1] = pad[id][2] = pad[id][3] = 0; }
}
