package io.github.wayneshao.rayneo.ime;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import com.feelime.ime.nativeengine.NativeSmoke;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/** Rime is process-global. All native calls and initialization share one serial worker. */
final class PinyinSession {
    interface Listener { void result(String committed, String preedit, String[] candidates, boolean previous, boolean next); void failure(); default void unhandled(int key) { } }
    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> new Thread(r, "embedded-pinyin"));
    private static boolean initialized;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Listener listener;
    private volatile boolean closed;
    private long session;
    private int pending;
    PinyinSession(Context context, Listener listener) {
        this.context = context.getApplicationContext(); this.listener = listener;
        enqueue(() -> { ensure(); update(); });
    }
    void key(int code) { enqueue(() -> {
        ensure();
        if (!NativeSmoke.rimeProcessKey(session, code, 0)) main.post(() -> { if (!closed) listener.unhandled(code); });
        update();
    }); }
    void choose(int index) { enqueue(() -> { ensure(); NativeSmoke.rimeSelectCandidate(session, index); update(); }); }
    boolean pending() { return pending > 0; }
    void close() { closed = true; WORKER.execute(() -> { if (session != 0) NativeSmoke.rimeDestroySession(session); session = 0; }); }
    private void enqueue(Runnable action) {
        pending++;
        WORKER.execute(() -> {
            try { if (!closed) action.run(); }
            catch (Exception | LinkageError error) { main.post(() -> { if (!closed) listener.failure(); }); }
            finally { main.post(() -> pending--); }
        });
    }
    private void ensure() {
        if (!initialized) {
            try {
                File shared = new File(context.getFilesDir(), "embedded-ime/rime-8ed1785");
                copyTree("embedded-ime/rime", shared);
                File user = new File(context.getFilesDir(), "embedded-ime/rime-user");
                File stage = new File(user, "build"); stage.mkdirs();
                if (!NativeSmoke.rimeInitialize(shared.getPath(), user.getPath(), stage.getPath())) throw new IllegalStateException("Rime initialization");
                initialized = true;
            } catch (Exception e) { throw new IllegalStateException(e); }
        }
        if (session == 0) { session = NativeSmoke.rimeCreateSession("luna_pinyin"); if (session == 0) throw new IllegalStateException("Rime session"); }
    }
    private void copyTree(String asset, File target) throws Exception {
        String[] children = context.getAssets().list(asset);
        if (children != null && children.length > 0) {
            target.mkdirs(); for (String child : children) copyTree(asset + "/" + child, new File(target, child));
        } else if (!target.isFile()) {
            target.getParentFile().mkdirs(); File temporary = new File(target.getPath() + ".tmp");
            try (InputStream in = context.getAssets().open(asset); FileOutputStream out = new FileOutputStream(temporary)) {
                byte[] buffer = new byte[65536]; int n; while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                out.getFD().sync();
            }
            if (!temporary.renameTo(target)) throw new IllegalStateException("Dictionary install");
        }
    }
    private void update() {
        try {
            String committed = NativeSmoke.rimeCommit(session);
            JSONObject state = new JSONObject(NativeSmoke.rimeContext(session));
            String preedit = state.optString("preedit");
            String values = state.optString("candidates"); String[] candidates = values.isEmpty() ? new String[0] : values.split("\n");
            boolean previous = state.optInt("pageNo") > 0, next = state.optInt("isLastPage", 1) == 0;
            main.post(() -> { if (!closed) listener.result(committed, preedit, candidates, previous, next); });
        } catch (Exception error) { throw new IllegalStateException(error); }
    }
}
