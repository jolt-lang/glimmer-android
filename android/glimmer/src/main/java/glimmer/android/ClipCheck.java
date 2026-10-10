// Debug builds only: after each layout, log any text that does not fit
// its view, so a script can read clipping from logcat.
package glimmer.android;

import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

final class ClipCheck implements ViewTreeObserver.OnGlobalLayoutListener {
  private final ViewGroup root;

  private ClipCheck(ViewGroup root) { this.root = root; }

  static void watch(ViewGroup root) {
    root.getViewTreeObserver().addOnGlobalLayoutListener(new ClipCheck(root));
  }

  @Override
  public void onGlobalLayout() { walk(root); }

  private void walk(View v) {
    if (v instanceof ViewGroup) {
      ViewGroup g = (ViewGroup) v;
      for (int i = 0; i < g.getChildCount(); i++) walk(g.getChildAt(i));
    }
    if (v instanceof TextView && clipped((TextView) v)) {
      Log.w("glimmer-android", "clipped: \"" + ((TextView) v).getText() + "\"");
    }
  }

  private static boolean clipped(TextView t) {
    Layout l = t.getLayout();
    if (l == null) return false;
    int w = t.getWidth() - t.getCompoundPaddingLeft() - t.getCompoundPaddingRight();
    int h = t.getHeight() - t.getCompoundPaddingTop() - t.getCompoundPaddingBottom();
    if (l.getHeight() > h) return true;
    for (int i = 0; i < l.getLineCount(); i++) {
      if (l.getLineWidth(i) > w || l.getEllipsisCount(i) > 0) return true;
    }
    return false;
  }
}
