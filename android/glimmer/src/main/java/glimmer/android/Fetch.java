// One GET off the UI thread, answered on it. Only the UI thread touches
// `live`, `done` and `timer`: C calls start and cancel there, and every
// answer is posted there. The worker calls nothing native.
package glimmer.android;

import android.os.Handler;
import android.os.Looper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

final class Fetch {
  static final int MAX = 1 << 20;   // 1 MiB
  private static final ExecutorService pool = Executors.newCachedThreadPool();
  private static final Handler main = new Handler(Looper.getMainLooper());
  private static final Map<Integer, Fetch> live = new HashMap<>();

  private final int id;
  private volatile HttpURLConnection conn;
  private boolean done;
  private Runnable timer;

  private Fetch(int id) { this.id = id; }

  // From C, on the UI thread. The total limit is a timer of its own:
  // HttpURLConnection has none, and disconnect() does not unblock a DNS
  // lookup, so the timer answers rather than waiting for the worker.
  static boolean start(int id, String url, String[] headers, int connectMs, int readMs,
                       int totalMs) {
    if (live.containsKey(id)) return false;
    Fetch f = new Fetch(id);
    f.timer = () -> { if (!f.done) { f.disconnectLater(); f.deliver(0, null, "timeout"); } };
    live.put(id, f);
    main.postDelayed(f.timer, totalMs);
    try {
      pool.execute(() -> f.run(url, headers, connectMs, readMs));
    } catch (RuntimeException e) {   // the pool refused it
      live.remove(id);
      main.removeCallbacks(f.timer);
      return false;
    }
    return true;
  }

  // From C, on the UI thread. Closing an HTTPS socket does I/O, which
  // Android can refuse on the UI thread, so the disconnect is the pool's.
  static void cancel(int id) {
    Fetch f = live.remove(id);
    if (f == null) return;
    f.done = true;
    main.removeCallbacks(f.timer);
    f.disconnectLater();
  }

  private void disconnectLater() {
    HttpURLConnection c = conn;
    if (c != null) pool.execute(c::disconnect);
  }

  private static final class TooBig extends IOException {}

  private void run(String url, String[] headers, int connectMs, int readMs) {
    int status = 0;
    byte[] body = null;
    String error = null;
    HttpURLConnection c = null;
    try {
      c = (HttpURLConnection) new URL(url).openConnection();
      conn = c;
      c.setConnectTimeout(connectMs);
      c.setReadTimeout(readMs);
      c.setUseCaches(false);
      for (int i = 0; i + 1 < headers.length; i += 2) c.setRequestProperty(headers[i], headers[i + 1]);
      status = c.getResponseCode();
      // any status but 2xx has its body on the error stream, which may be null
      InputStream in = status >= 200 && status < 300 ? c.getInputStream() : c.getErrorStream();
      body = in == null ? new byte[0] : read(in);
    } catch (TooBig e) {
      error = "body over 1 MiB";
    } catch (IOException | RuntimeException e) {
      error = e.getClass().getSimpleName() + ": " + e.getMessage();
    } finally {
      if (c != null) c.disconnect();   // the worker's thread, never the UI's
    }
    final int s = status;
    final byte[] b = body;
    final String er = error;
    main.post(() -> deliver(s, b, er));
  }

  private static byte[] read(InputStream in) throws IOException {
    try (InputStream i = in) {
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      for (int n; (n = i.read(buf)) != -1; ) {
        out.write(buf, 0, n);
        if (out.size() > MAX) throw new TooBig();
      }
      return out.toByteArray();
    }
  }

  // On the UI thread. The first of the worker's answer, the timer and a
  // cancel wins; whatever comes after is dropped here, and Clojure drops
  // an id it no longer holds besides.
  private void deliver(int status, byte[] body, String error) {
    if (done) return;
    done = true;
    live.remove(id);
    main.removeCallbacks(timer);
    nativeFetched(id, status, body, error);
  }

  // The body crosses as bytes: JNI's GetStringUTFChars gives modified
  // UTF-8, which writes a character outside the BMP as a surrogate pair.
  static native void nativeFetched(int id, int status, byte[] body, String error);
}
