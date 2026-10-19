package io.github.wayneshao.rayneo.ime;

import android.view.View;

/** App-independent editing contract. Calls run on the UI thread. */
public interface EditorTarget {
    View owner();
    default Object identity() { return owner(); }
    boolean valid();
    boolean password();
    boolean preferAscii();
    String preview();
    void commit(String text);
    void delete();
    void move(int direction);
    void submit();
    default void observe(Runnable changed) { }
}
