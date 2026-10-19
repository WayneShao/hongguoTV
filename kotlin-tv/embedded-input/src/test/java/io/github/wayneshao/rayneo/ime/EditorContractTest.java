package io.github.wayneshao.rayneo.ime;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=28, qualifiers="w1280dp-h480dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class EditorContractTest {
    private Activity activity() { return Robolectric.buildActivity(Activity.class).setup().visible().get(); }
    @Test public void editsOriginalBufferWithFiltersSelectionAndWatchers() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field);
        field.setText("abXYef"); field.setSelection(2, 4); field.setFilters(new InputFilter[]{new InputFilter.LengthFilter(7)});
        int[] changed = {0}; field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) { changed[0]++; }
        });
        Editable original = field.getText(); new NativeEditor(field).commit("12345");
        assertSame(original, field.getText()); assertEquals("ab123ef", original.toString()); assertEquals(1, changed[0]);
    }
    @Test public void deletionAndMovementDoNotSplitSupplementaryCharacters() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field);
        field.setText("A\uD83D\uDE00B"); field.setSelection(3); NativeEditor editor = new NativeEditor(field);
        editor.move(-1); assertEquals(1, field.getSelectionStart()); editor.move(1); assertEquals(3, field.getSelectionStart());
        editor.delete(); assertEquals("AB", field.getText().toString());
    }
    @Test public void searchCallsOriginalActionOnceWithoutEnterKey() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field); field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        int[] actions = {0}, keys = {0}; field.setOnEditorActionListener((v, action, event) -> { assertEquals(EditorInfo.IME_ACTION_SEARCH, action); actions[0]++; return true; });
        field.setOnKeyListener((v, code, event) -> { keys[0]++; return false; });
        new NativeEditor(field).submit(); assertEquals(1, actions[0]); assertEquals(0, keys[0]);
    }
    @Test public void passwordPreviewNeverExposesTextAndDetachedOwnerIsInvalid() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD); field.setText("private");
        NativeEditor editor = new NativeEditor(field); assertTrue(editor.password()); assertTrue(editor.preferAscii()); assertFalse(editor.preview().contains("private"));
        a.setContentView(new View(a)); assertFalse(editor.valid());
    }
    @Test public void unspecifiedSingleLineSearchStillDispatchesOriginalActionOnce() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field); field.setSingleLine(true);
        assertEquals(EditorInfo.IME_ACTION_UNSPECIFIED, field.getImeOptions() & EditorInfo.IME_MASK_ACTION);
        int[] actions = {0}, keys = {0}; field.setOnEditorActionListener((v, action, event) -> { actions[0]++; return true; });
        field.setOnKeyListener((v, code, event) -> { keys[0]++; return false; });
        new NativeEditor(field).submit(); assertEquals(1, actions[0]); assertEquals(0, keys[0]);
    }
    @Test public void unspecifiedMultilineInsertsNewlineWithoutSubmitting() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        int[] actions = {0}; field.setOnEditorActionListener((v, action, event) -> { actions[0]++; return true; });
        new NativeEditor(field).submit(); assertEquals("\n", field.getText().toString()); assertEquals(0, actions[0]);
    }
    @Test public void clearRemovesWholeTextRegardlessOfSelectionAndPreservesOriginalWatcher() {
        Activity a = activity(); EditText field = new EditText(a); a.setContentView(field);
        field.setText("before selected after"); field.setSelection(7, 9); Editable buffer = field.getText(); int[] changes = {0};
        field.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
            public void onTextChanged(CharSequence s, int start, int before, int count) { }
            public void afterTextChanged(Editable text) { changes[0]++; }
        });
        new NativeEditor(field).clear(); assertSame(buffer, field.getText()); assertEquals("", field.getText().toString());
        assertEquals(0, field.getSelectionStart()); assertEquals(1, changes[0]);
    }
    @Test public void stereoSurfaceUsesOneMeasuredContentTree() {
        Activity a = activity(); KeyboardSurface surface = new KeyboardSurface(a); Button child = new Button(a); surface.addView(child);
        surface.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY));
        surface.layout(0, 0, 1280, 480); assertEquals(1, surface.getChildCount()); assertEquals(640, child.getMeasuredWidth()); assertEquals(480, child.getMeasuredHeight());
    }
    @Test public void rightEyeTouchIsCopiedAndBusinessClickRunsOnlyOnce() {
        Activity a = activity(); KeyboardSurface surface = new KeyboardSurface(a); Button child = new Button(a); int[] clicks = {0}; child.setOnClickListener(v -> clicks[0]++); surface.addView(child); a.setContentView(surface);
        surface.measure(View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(480, View.MeasureSpec.EXACTLY)); surface.layout(0, 0, 1280, 480);
        MotionEvent down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 800, 100, 0), up = MotionEvent.obtain(0, 40, MotionEvent.ACTION_UP, 800, 100, 0);
        surface.dispatchTouchEvent(down); surface.dispatchTouchEvent(up); org.robolectric.shadows.ShadowLooper.idleMainLooper();
        assertEquals(800f, down.getX(), 0); assertEquals(800f, up.getX(), 0); assertEquals(1, clicks[0]); down.recycle(); up.recycle();
    }
}
