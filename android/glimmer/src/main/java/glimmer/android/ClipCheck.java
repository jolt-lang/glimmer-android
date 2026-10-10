// Debug builds only: once layout has settled, log any text that does not
// fit its view, so a script can read clipping from logcat.
//
// A view counts as clipped only if it is still clipped once layout has
// been quiet for 250 ms. A walk on every pass logged the same clip once
// per pass, several times for one screen, and could log a state that
// lasted only one pass. So each layout pass reschedules a single walk,
// cancelling the one before: one walk per settled state, not one per pass.
package glimmer.android;

import android.text.Layout;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

final class ClipCheck implements ViewTreeObserver.OnGlobalLayoutListener {
  private static final long QUIET_MS = 250;

  private final ViewGroup root;

  // a rebuilt Activity's old root is detached: it has nothing to report
  private final Runnable check = new Runnable() {
    @Override
    public void run() { if (root.isAttachedToWindow()) walk(root); }
  };

  private ClipCheck(ViewGroup root) { this.root = root; }

  static void watch(ViewGroup root) {
    root.getViewTreeObserver().addOnGlobalLayoutListener(new ClipCheck(root));
  }

  @Override
  public void onGlobalLayout() {
    root.removeCallbacks(check);
    root.postDelayed(check, QUIET_MS);
  }

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
