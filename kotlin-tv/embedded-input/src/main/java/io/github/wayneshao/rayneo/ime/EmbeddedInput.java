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
    /** Hosts that consume physical events first must call this before their normal gesture routing. */
    public static boolean motion(Dialog dialog, android.view.MotionEvent event) {
        return dialog instanceof KeyboardDialog && ((KeyboardDialog) dialog).physicalMotion(event);
    }
    public static boolean key(Dialog dialog, android.view.KeyEvent event) {
        return dialog instanceof KeyboardDialog && ((KeyboardDialog) dialog).physicalKey(event);
    }
    public static boolean show(EditorTarget target, Host host) {
        if (android.os.Build.VERSION.SDK_INT < 23) return false;
        View owner = target.owner();
        if (owner == null || !target.valid()) return false;
        Context context = owner.getContext();
        while (context instanceof android.content.ContextWrapper && !(context instanceof android.app.Activity)) {
            Context base = ((android.content.ContextWrapper) context).getBaseContext();
            if (base == context) break;
            context = base;
        }
        if (!(context instanceof android.app.Activity)) return false;
        android.app.Activity activity = (android.app.Activity) context;
        if (activity.isFinishing() || activity.isDestroyed()) return false;
        View root = owner.getRootView();
        WeakReference<KeyboardDialog> ref = WINDOWS.get(root);
        KeyboardDialog old = ref == null ? null : ref.get();
        if (old != null && old.isShowing()) {
            if (old.owns(target)) return true;
            old.dismiss();
        }
        KeyboardDialog dialog = new KeyboardDialog(owner.getContext(), target, host);
        WINDOWS.put(root, new WeakReference<>(dialog));
        try {
            dialog.show();
            return true;
        } catch (android.view.WindowManager.BadTokenException | android.view.WindowManager.InvalidDisplayException unavailable) {
            WeakReference<KeyboardDialog> current = WINDOWS.get(root);
            if (current != null && current.get() == dialog) WINDOWS.remove(root);
            dialog.dismiss();
            return false;
        }
    }
    public static boolean isShowing(View owner) {
        WeakReference<KeyboardDialog> ref = WINDOWS.get(owner.getRootView());
        KeyboardDialog dialog = ref == null ? null : ref.get();
        return dialog != null && dialog.isShowing() && dialog.owns(owner);
    }
    /** A host hide request applies to the editor in the requesting window, not another window. */
    public static void closeWindow(View view) {
        WeakReference<KeyboardDialog> ref = WINDOWS.get(view.getRootView());
        KeyboardDialog dialog = ref == null ? null : ref.get();
        if (dialog != null) dialog.dismiss();
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
