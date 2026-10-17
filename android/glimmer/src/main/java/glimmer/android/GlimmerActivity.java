// glimmer-android's Activity: loads the C half and the app's Jolt library,
// applies the insets, and calls native. It decides nothing.
package glimmer.android;

import android.app.Activity;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.widget.FrameLayout;

public class GlimmerActivity extends Activity {
  private boolean backTaken;
  private Object backCallback;   // an OnBackInvokedCallback on API 33 and up

  @Override
  protected void onCreate(Bundle state) {
    super.onCreate(state);
    FrameLayout content = new FrameLayout(this);
    // Targeting API 35 or later draws behind the bars; the app applies the insets.
    content.setOnApplyWindowInsetsListener((v, insets) -> {
      nativeInsets(insets.getSystemWindowInsetLeft(), insets.getSystemWindowInsetTop(),
                   insets.getSystemWindowInsetRight(), insets.getSystemWindowInsetBottom());
      return insets;
    });
    setContentView(content);
    Bundle meta = meta();
    String lib = meta.getString("glimmer.library");
    System.loadLibrary("glimmer_android");
    System.loadLibrary(lib);
    boolean debuggable = (getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0;
    if (debuggable) {
      ClipCheck.watch(content);
    }
    nativeStart(content, "lib" + lib + ".so", meta.getString("glimmer.entry"),
                getResources().getDisplayMetrics().density,
                getFilesDir().getAbsolutePath(), versionName(), debuggable);
  }

  private String versionName() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (PackageManager.NameNotFoundException e) {
      return null;
    }
  }

  // Android's moment to save: "if you can't find a better time", onStop.
  @Override
  protected void onStop() {
    super.onStop();
    nativeBackground();
  }

  @Override
  protected void onDestroy() {
    nativeStop();
    super.onDestroy();
  }

  private Bundle meta() {
    try {
      ActivityInfo info = getPackageManager().getActivityInfo(
          getComponentName(), PackageManager.GET_META_DATA);
      return info.metaData;
    } catch (PackageManager.NameNotFoundException e) {
      throw new IllegalStateException("glimmer-android: no meta-data for this Activity", e);
    }
  }

  // Called by the C half: the screen showing does, or does not, take Back.
  void setBackTaken(boolean taken) {
    if (taken == backTaken) return;
    backTaken = taken;
    if (Build.VERSION.SDK_INT >= 33) {
      if (taken) backCallback = Back33.register(this);
      else if (backCallback != null) {
        Back33.unregister(this, backCallback);
        backCallback = null;
      }
    }
  }

  // Below API 33 Back comes here; Android 12's default when nothing takes it.
  @Override
  public void onBackPressed() {
    if (backTaken) nativeBack();
    else super.onBackPressed();
  }

  private native void nativeStart(FrameLayout content, String lib, String entry, float density,
                                  String files, String version, boolean debuggable);
  private native void nativeStop();
  private native void nativeBackground();
  static native void nativeEvent(int id, int kind);
  static native void nativeInsets(int left, int top, int right, int bottom);
  static native void nativeBack();

  // API 33 and up only: the one class that names android.window types,
  // so an API 28 device never loads or verifies them.
  @android.annotation.TargetApi(33)
  static final class Back33 {
    static Object register(Activity a) {
      android.window.OnBackInvokedCallback cb = GlimmerActivity::nativeBack;
      a.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
          android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
      return cb;
    }

    static void unregister(Activity a, Object cb) {
      a.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(
          (android.window.OnBackInvokedCallback) cb);
    }
  }
}
