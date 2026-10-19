package io.github.wayneshao.rayneo.ime;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.text.InputType;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import org.junit.Test;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=28, qualifiers="w1280dp-h480dp-land-mdpi",
        instrumentedPackages={"io.github.wayneshao.rayneo.ime"}, shadows={KeyboardWindowTest.NoNativeEngine.class, KeyboardWindowTest.FakeVoice.class, KeyboardWindowTest.WindowCreation.class})
@LooperMode(LooperMode.Mode.PAUSED)
@org.robolectric.annotation.internal.DoNotInstrument
public class KeyboardWindowTest {
    static PinyinSession.Listener lastPinyin;
    static OfflineVoice.Listener lastVoice;
    @Before public void resetCallbacks() { lastPinyin = null; lastVoice = null; WindowCreation.nextFailure = null; }
    @Implements(className="android.view.WindowManagerImpl")
    @org.robolectric.annotation.internal.DoNotInstrument
    public static class WindowCreation extends org.robolectric.shadows.ShadowWindowManagerImpl {
        static RuntimeException nextFailure;
        @Implementation @Override public void addView(View view, ViewGroup.LayoutParams params) {
            if (nextFailure != null) { RuntimeException failure = nextFailure; nextFailure = null; throw failure; }
            super.addView(view, params);
        }
    }
    @Implements(value=PinyinSession.class, isInAndroidSdk=false)
    @org.robolectric.annotation.internal.DoNotInstrument
    public static class NoNativeEngine {
        @Implementation public void __constructor__(Context context, PinyinSession.Listener listener) { lastPinyin = listener; }
        @Implementation public void close() { }
        @Implementation public boolean pending() { return false; }
    }
    @Implements(value=OfflineVoice.class, isInAndroidSdk=false)
    @org.robolectric.annotation.internal.DoNotInstrument
    public static class FakeVoice {
        boolean active;
        @Implementation public void __constructor__(Context context, OfflineVoice.Listener listener) { lastVoice = listener; }
        @Implementation public static boolean permission(Context context) { return true; }
        @Implementation public void start() { active = true; }
        @Implementation public void stop() { active = false; }
        @Implementation public void cancel() { active = false; }
        @Implementation public boolean active() { return active; }
    }
    private EditText editor() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().visible().get();
        EditText field = new EditText(a); field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS); a.setContentView(field);
        return field;
    }
    private Button find(View view, String text) {
        if (view instanceof Button && ((Button) view).getText().toString().equals(text)) return (Button) view;
        if (view instanceof ViewGroup) for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) { Button b = find(((ViewGroup) view).getChildAt(i), text); if (b != null) return b; }
        return null;
    }
    @Test public void actualKeyboardButtonAcceptsFirstMouseClickWithoutFocusOnlyTap() {
        EditText field = editor(); EmbeddedInput.show(new NativeEditor(field), null); ShadowLooper.idleMainLooper();
        Dialog dialog = ShadowDialog.getLatestDialog();
        try {
            Button button = find(dialog.getWindow().getDecorView(), "w"); assertNotNull(button); assertFalse(button.isFocusableInTouchMode());
            MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 15, 15, 0), up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 15, 15, 0);
            button.dispatchTouchEvent(down); button.dispatchTouchEvent(up); ShadowLooper.idleMainLooper();
            assertEquals("w", field.getText().toString()); down.recycle(); up.recycle();
        } finally { dialog.dismiss(); }
    }
    @Test public void staleLogicalEditorCannotReuseOrCloseNewSessionOnSameView() {
        EditText field = editor(); EditorTarget first = session(field), second = session(field);
        EmbeddedInput.show(first, null); Dialog old = ShadowDialog.getLatestDialog();
        EmbeddedInput.show(second, null); Dialog current = ShadowDialog.getLatestDialog();
        try {
            assertNotSame(old, current); assertFalse(old.isShowing()); assertTrue(current.isShowing());
            EmbeddedInput.close(first); assertTrue(current.isShowing());
            EmbeddedInput.close(second); assertFalse(current.isShowing());
        } finally { current.dismiss(); old.dismiss(); }
    }
    @Test public void clearingOriginalFieldDropsLatePinyinAndVoiceResults() {
        EditText field = editor(); field.setText("existing text"); field.setSelection(4);
        EmbeddedInput.show(new NativeEditor(field), null); KeyboardDialog dialog = (KeyboardDialog) ShadowDialog.getLatestDialog();
        try {
            assertTrue(org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "startVoice", org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, false)));
            OfflineVoice.Listener oldVoice = lastVoice; PinyinSession.Listener oldPinyin = lastPinyin;
            dialog.clearText(); assertEquals("", field.getText().toString());
            oldVoice.result("late voice"); oldPinyin.result("late candidate", "pin", new String[]{"candidate"}, false, false);
            assertEquals("", field.getText().toString()); assertEquals(0, field.getSelectionStart()); assertTrue(dialog.isShowing());
        } finally { dialog.dismiss(); }
    }
    @Test public void cancellingHeldVoiceRetainsOriginalTextEvenIfRecognitionCompletedEarly() {
        EditText field = editor(); field.setText("before"); field.setSelection(6);
        EmbeddedInput.show(new NativeEditor(field), null); KeyboardDialog dialog = (KeyboardDialog) ShadowDialog.getLatestDialog();
        try {
            assertTrue(org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "startVoice", org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true)));
            OfflineVoice.Listener oldVoice = lastVoice; oldVoice.result("spoken");
            assertEquals("before", field.getText().toString());
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "cancelVoice"); oldVoice.result("late");
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "finishHeldVoice");
            assertEquals("before", field.getText().toString());
        } finally { dialog.dismiss(); }
    }
    @Test public void releasingHeldVoiceCommitsBufferedResultOnce() {
        EditText field = editor(); field.setText("before"); field.setSelection(6);
        EmbeddedInput.show(new NativeEditor(field), null); KeyboardDialog dialog = (KeyboardDialog) ShadowDialog.getLatestDialog();
        try {
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "startVoice", org.robolectric.util.ReflectionHelpers.ClassParameter.from(boolean.class, true));
            lastVoice.result("spoken"); assertEquals("before", field.getText().toString());
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "finishHeldVoice");
            org.robolectric.util.ReflectionHelpers.callInstanceMethod(dialog, "finishHeldVoice");
            assertEquals("beforespoken", field.getText().toString());
        } finally { dialog.dismiss(); }
    }
    @Test public void normalAppDialogIsOutsideKeyboardGestureScope() {
        EditText field = editor(); Dialog ordinary = new Dialog(field.getContext());
        MotionEvent event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 100, 100, 0);
        try { assertFalse(EmbeddedInput.motion(ordinary, event)); } finally { event.recycle(); ordinary.dismiss(); }
    }

    private void advance(long millis) { org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(millis, java.util.concurrent.TimeUnit.MILLISECONDS); }
    private void sendMedia(Dialog dialog) {
        android.view.KeyEvent key = new android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS);
        org.robolectric.Shadows.shadowOf(key).setDevice(temple());
        dialog.getWindow().getCallback().dispatchKeyEvent(key);
    }
    private void sendMulti(Dialog dialog, int action, int count) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[count]; MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[count];
        for (int i = 0; i < count; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = i; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = 100 + i * 30; coords[i].y = 300;
        }
        MotionEvent event = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, count, properties, coords, 0, 0, 1, 1, 7, 0, android.view.InputDevice.SOURCE_TOUCHPAD, 0);
        org.robolectric.Shadows.shadowOf(event).setDevice(temple());
        try { dialog.getWindow().getCallback().dispatchTouchEvent(event); } finally { event.recycle(); }
    }
    private android.view.InputDevice temple() {
        android.view.InputDevice device = org.robolectric.shadows.ShadowInputDevice.makeInputDeviceNamed("cyttsp5_mt");
        org.robolectric.util.ReflectionHelpers.setField(device, "mMotionRanges", new java.util.ArrayList<android.view.InputDevice.MotionRange>());
        return device;
    }
    @Test public void userBackNotifiesOriginalPreImeWithoutSubmitting() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().visible().get();
        final int[] back = {0, 0}, submits = {0};
        EditText field = new EditText(a) {
            @Override public boolean onKeyPreIme(int code, android.view.KeyEvent event) {
                if (code == android.view.KeyEvent.KEYCODE_BACK) { back[event.getAction()]++; return true; }
                return super.onKeyPreIme(code, event);
            }
        };
        a.setContentView(field);
        field.setOnEditorActionListener((v, action, event) -> { submits[0]++; return true; });
        assertTrue(EmbeddedInput.show(new NativeEditor(field), null));
        Dialog dialog = ShadowDialog.getLatestDialog(); dialog.onBackPressed(); dialog.onBackPressed();
        assertFalse(dialog.isShowing()); assertArrayEquals(new int[]{1, 1}, back); assertEquals(0, submits[0]);
    }
    @Test public void finishingHostIsRejected() {
        EditText field = editor(); ((Activity) field.getContext()).finish();
        assertFalse(EmbeddedInput.show(new NativeEditor(field), null));
    }
    @Test public void failedWindowCanReopen() {
        EditText field = editor();
        assertFalse(EmbeddedInput.show(new NativeEditor(field), (dialog, surface) -> {
            throw new android.view.WindowManager.BadTokenException("fixture");
        }));
        assertTrue(EmbeddedInput.show(new NativeEditor(field), null));
        Dialog dialog = ShadowDialog.getLatestDialog(); assertTrue(dialog.isShowing()); dialog.dismiss();
    }
    @Test public void rejectionInsideWindowManagerBeforeShowCompletesIsCleanedUp() {
        EditText field = editor(); int[] callbacks = {0};
        for (RuntimeException failure : new RuntimeException[]{new android.view.WindowManager.BadTokenException("addView"),
                new android.view.WindowManager.InvalidDisplayException("addView")}) {
            WindowCreation.nextFailure = failure;
            assertFalse(EmbeddedInput.show(tracked(field, callbacks), null)); assertFalse(EmbeddedInput.isShowing(field));
            assertEquals(0, callbacks[0]); assertNull(lastPinyin);
            assertTrue(EmbeddedInput.show(new NativeEditor(field), null)); assertTrue(EmbeddedInput.isShowing(field));
            EmbeddedInput.closeWindow(field); lastPinyin = null;
        }
    }
    private void focusWindow(View view, boolean focused) {
        ShadowLooper.idleMainLooper();
        Object root = org.robolectric.util.ReflectionHelpers.callInstanceMethod(view, "getViewRootImpl");
        org.robolectric.shadows.ShadowViewRootImpl shadow = org.robolectric.shadow.api.Shadow.extract(root);
        shadow.callWindowFocusChanged(focused); ShadowLooper.idleMainLooper();
    }






    @Test public void hideByPeerClosesOnlyThatWindowAndNeverCallsCancelOrSubmit() {
        EditText first = editor(), otherWindow = editor(); int[] callbacks = {0};
        EditorTarget target = tracked(first, callbacks);
        EmbeddedInput.show(target, null); Dialog firstDialog = ShadowDialog.getLatestDialog();
        EmbeddedInput.show(new NativeEditor(otherWindow), null); Dialog secondDialog = ShadowDialog.getLatestDialog();
        try {
            View peer = ((Activity) first.getContext()).getWindow().getDecorView();
            EmbeddedInput.closeWindow(peer); assertFalse(firstDialog.isShowing()); assertTrue(secondDialog.isShowing());
            assertEquals(0, callbacks[0]);
        } finally { firstDialog.dismiss(); secondDialog.dismiss(); }
    }
    @Test public void replacementDetachAndCreationFailureNeverCancelOriginalEditor() {
        EditText field = editor(); int[] callbacks = {0}; EditorTarget target = tracked(field, callbacks);
        EmbeddedInput.show(target, null); EmbeddedInput.show(session(field), null);
        EmbeddedInput.close(target); assertTrue(EmbeddedInput.isShowing(field)); EmbeddedInput.closeWindow(field);
        assertFalse(EmbeddedInput.show(target, (d, s) -> { throw new android.view.WindowManager.InvalidDisplayException("fixture"); }));
        EmbeddedInput.show(target, null); ((Activity) field.getContext()).setContentView(new View(field.getContext()));
        assertFalse(ShadowDialog.getLatestDialog().isShowing()); assertEquals(0, callbacks[0]);
    }
    @Test public void collapseIsUserCancelExactlyOnce() {
        EditText field = editor(); int[] callbacks = {0}; EmbeddedInput.show(tracked(field, callbacks), null);
        Dialog dialog = ShadowDialog.getLatestDialog(); find(dialog.getWindow().getDecorView(), "收起").performClick();
        dialog.onBackPressed(); assertFalse(dialog.isShowing()); assertEquals(1, callbacks[0]);
    }
    @Test public void failedSessionCleanupDoesNotEraseAReentrantReplacement() {
        EditText field = editor(); int[] callbacks = {0};
        assertFalse(EmbeddedInput.show(tracked(field, callbacks), (d, s) -> {
            EmbeddedInput.show(session(field), null);
            throw new android.view.WindowManager.BadTokenException("fixture after replacement");
        }));
        try { assertTrue(EmbeddedInput.isShowing(field)); assertEquals(0, callbacks[0]); }
        finally { EmbeddedInput.closeWindow(field); }
    }
    private EditorTarget tracked(EditText field, int[] callbacks) {
        return new NativeEditorTarget(field, callbacks);
    }
    private static final class NativeEditorTarget implements EditorTarget {
        final EditText field; final int[] callbacks;
        NativeEditorTarget(EditText field, int[] callbacks) { this.field = field; this.callbacks = callbacks; }
        public View owner() { return field; }
        public Object identity() { return this; }
        public boolean valid() { return field.isAttachedToWindow(); }
        public boolean password() { return false; }
        public boolean preferAscii() { return true; }
        public String preview() { return ""; }
        public void commit(String text) { }
        public void delete() { }
        public void clear() { }
        public void move(int direction) { }
        public void submit() { callbacks[0] += 100; }
        public void cancel() { callbacks[0]++; }
    }
    private EditorTarget session(EditText field) {
        return new EditorTarget() {
            public View owner() { return field; }
            public Object identity() { return this; }
            public boolean valid() { return field.isAttachedToWindow(); }
            public boolean password() { return false; }
            public boolean preferAscii() { return true; }
            public String preview() { return ""; }
            public void commit(String text) { }
            public void delete() { }
            public void clear() { }
            public void move(int direction) { }
            public void submit() { }
        };
    }
}
