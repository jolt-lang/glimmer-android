// A tap on view `id`, sent to native.
package glimmer.android;

import android.view.View;

final class Click implements View.OnClickListener {
  private final int id;

  Click(int id) { this.id = id; }

  @Override
  public void onClick(View v) { GlimmerActivity.nativeEvent(id, 0); }
}
