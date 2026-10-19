package io.github.wayneshao.rayneo.ime;

import android.text.Editable;
import android.text.InputType;
import android.text.Selection;
import android.text.method.PasswordTransformationMethod;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import java.lang.ref.WeakReference;

/** Edits the original buffer: original filters, spans and watchers keep working. */
public final class NativeEditor implements EditorTarget {
    private final WeakReference<EditText> reference;
    public NativeEditor(EditText editor) { reference = new WeakReference<>(editor); }
    public View owner() { return reference.get(); }
    public boolean valid() { EditText e = reference.get(); return e != null && e.isAttachedToWindow() && e.isEnabled() && e.isShown(); }
    public boolean password() {
        EditText e = reference.get();
        if (e == null) return false;
        int kind = e.getInputType() & InputType.TYPE_MASK_VARIATION;
        return e.getTransformationMethod() instanceof PasswordTransformationMethod
                || kind == InputType.TYPE_TEXT_VARIATION_PASSWORD || kind == InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
                || kind == InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                || ((e.getInputType() & InputType.TYPE_MASK_CLASS) == InputType.TYPE_CLASS_NUMBER && kind == InputType.TYPE_NUMBER_VARIATION_PASSWORD);
    }
    public boolean preferAscii() {
        EditText e = reference.get(); if (e == null) return true;
        int kind = e.getInputType() & InputType.TYPE_MASK_CLASS;
        int variation = e.getInputType() & InputType.TYPE_MASK_VARIATION;
        return password() || kind == InputType.TYPE_CLASS_NUMBER || kind == InputType.TYPE_CLASS_PHONE
                || variation == InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS || variation == InputType.TYPE_TEXT_VARIATION_URI;
    }
    public String preview() {
        EditText e = reference.get(); if (e == null) return "";
        String text = e.getText().toString();
        if (password()) return text.isEmpty() ? "" : "••••••";
        int cursor = clamp(e.getSelectionEnd(), text.length());
        return text.substring(0, cursor) + "│" + text.substring(cursor);
    }
    private static int clamp(int value, int length) { return value < 0 ? length : Math.min(value, length); }
    public void commit(String value) {
        EditText e = reference.get(); if (e == null) return;
        Editable text = e.getText(); int a = clamp(e.getSelectionStart(), text.length()), b = clamp(e.getSelectionEnd(), text.length());
        // Editable performs InputFilter processing and preserves original listeners.
        text.replace(Math.min(a, b), Math.max(a, b), value);
    }
    public void delete() {
        EditText e = reference.get(); if (e == null) return;
        Editable text = e.getText(); int a = clamp(e.getSelectionStart(), text.length()), b = clamp(e.getSelectionEnd(), text.length());
        if (a != b) text.delete(Math.min(a, b), Math.max(a, b));
        else if (a > 0) text.delete(Character.offsetByCodePoints(text, a, -1), a);
    }
    public void move(int direction) {
        EditText e = reference.get(); if (e == null) return;
        Editable text = e.getText(); int cursor = clamp(e.getSelectionEnd(), text.length());
        if (direction < 0 && cursor > 0) cursor = Character.offsetByCodePoints(text, cursor, -1);
        if (direction > 0 && cursor < text.length()) cursor = Character.offsetByCodePoints(text, cursor, 1);
        Selection.setSelection(text, cursor);
    }
    public void submit() {
        EditText e = reference.get(); if (e == null) return;
        int action = e.getImeOptions() & EditorInfo.IME_MASK_ACTION;
        if (action == EditorInfo.IME_ACTION_NONE || action == EditorInfo.IME_ACTION_UNSPECIFIED) {
            if ((e.getInputType() & InputType.TYPE_TEXT_FLAG_MULTI_LINE) != 0) commit("\n");
            else e.onEditorAction(EditorInfo.IME_ACTION_DONE);
        } else e.onEditorAction(action); // Never additionally inject ENTER: that can submit twice.
    }
}
