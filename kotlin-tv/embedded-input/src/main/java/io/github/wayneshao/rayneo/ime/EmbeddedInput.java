package io.github.wayneshao.rayneo.ime;

import android.app.Dialog;
import android.content.Context;
import android.view.View;
import java.lang.ref.WeakReference;
import java.util.WeakHashMap;

/** Public entry point. The module owns its window and stereo rendering; hosts only bridge input. */
public final class EmbeddedInput {
    public interface Host {
        void attachInput(Dialog dialog, KeyboardSurface surface);
    }
    private static final WeakHashMap<View, WeakReference<KeyboardDialog>> WINDOWS = new WeakHashMap<>();
    private EmbeddedInput() { }
    public static boolean show(EditorTarget target, Host host) {
        View owner = target.owner();
        if (owner == null || !target.valid()) return false;
        View root = owner.getRootView();
        WeakReference<KeyboardDialog> ref = WINDOWS.get(root);
        KeyboardDialog old = ref == null ? null : ref.get();
        if (old != null && old.isShowing()) {
            if (old.owns(target)) return true;
            old.dismiss();
        }
        KeyboardDialog dialog = new KeyboardDialog(owner.getContext(), target, host);
        WINDOWS.put(root, new WeakReference<>(dialog));
        dialog.show();
        return true;
    }
    public static void close(View owner) {
        WeakReference<KeyboardDialog> ref = WINDOWS.get(owner.getRootView());
        KeyboardDialog dialog = ref == null ? null : ref.get();
        if (dialog != null && dialog.owns(owner)) dialog.dismiss();
    }
    public static void close(EditorTarget target) {
        View owner = target.owner(); if (owner == null) return;
        WeakReference<KeyboardDialog> ref = WINDOWS.get(owner.getRootView());
        KeyboardDialog dialog = ref == null ? null : ref.get();
        if (dialog != null && dialog.owns(target)) dialog.dismiss();
    }
}
