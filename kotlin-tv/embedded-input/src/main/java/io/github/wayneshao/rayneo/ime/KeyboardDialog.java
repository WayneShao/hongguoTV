package io.github.wayneshao.rayneo.ime;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Point;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;

/** Fixed button instances keep native focus stable while composition/candidate text changes. */
final class KeyboardDialog extends Dialog implements View.OnAttachStateChangeListener {
    private final EditorTarget target;
    private final EmbeddedInput.Host host;
    private PinyinSession pinyin;
    private TextView preview, status;
    private final Button[] candidates = new Button[5];
    private final Button[] letters = new Button[26];
    private Button modeButton, caseButton, voiceButton;
    private String[] words = new String[0];
    private String composition = "";
    private boolean ascii, symbols, upper, closed, ready;
    private int symbolPage;
    private OfflineVoice voice;
    private int voiceGeneration, pinyinGeneration;
    private boolean heldVoice;
    private String heldResult;
    private KeyboardGesture gestures;
    private int gestureDevice = Integer.MIN_VALUE;
    private boolean haveEvent, lastConsumed;
    private long lastEventSignature;
    private long lastMultiTime = Long.MIN_VALUE;
    private KeyboardSurface surface;
    private final String alphabet = "qwertyuiopasdfghjklzxcvbnm";
    private final String symbolKeys = "1234567890@#%&*()-+=_.,!?/:;'\"[]{}\\<>$^`~|";

    KeyboardDialog(Context context, EditorTarget target, EmbeddedInput.Host host) {
        super(context, android.R.style.Theme_Material_NoActionBar);
        this.target = target; this.host = host; ascii = target.preferAscii();
    }
    boolean owns(View owner) { return target.owner() == owner; }
    boolean owns(EditorTarget other) { return target.identity() == other.identity(); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        Window window = getWindow();
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM | WindowManager.LayoutParams.FLAG_FULLSCREEN);
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        window.setBackgroundDrawable(new ColorDrawable(Color.BLACK));
        window.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        surface = new KeyboardSurface(getContext());
        LinearLayout panel = new LinearLayout(getContext()); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(12, 8, 12, 8); panel.setBackgroundColor(0xff111820);
        surface.addView(panel, new android.widget.FrameLayout.LayoutParams(-1, -1));
        preview = text(23); preview.setSingleLine(true); preview.setEllipsize(TextUtils.TruncateAt.START); panel.addView(preview, new LinearLayout.LayoutParams(-1, 38));
        status = text(18); status.setSingleLine(true); status.setEllipsize(TextUtils.TruncateAt.END); status.setText("拼音词库正在准备…"); panel.addView(status, new LinearLayout.LayoutParams(-1, 32));
        LinearLayout candidateRow = row(panel);
        button(candidateRow, "‹", () -> page(-1), .55f);
        for (int i = 0; i < candidates.length; i++) { final int index = i; candidates[i] = button(candidateRow, "·", () -> choose(index), 1.3f); }
        button(candidateRow, "›", () -> page(1), .55f);
        int at = 0;
        for (int count : new int[]{10, 9, 7}) {
            LinearLayout line = row(panel);
            for (int i = 0; i < count; i++) {
                final int index = at++; letters[index] = button(line, alphabet.substring(index, index + 1), () -> type(index), 1);
            }
        }
        LinearLayout edits = row(panel);
        caseButton = button(edits, "大写", () -> { if (symbols) symbolPage = (symbolPage + 1) % 2; else upper = !upper; labels(); }, 1);
        button(edits, "←", () -> edit(() -> target.move(-1)), 1);
        button(edits, "空格", () -> { if (!ascii && !symbols && ready) pinyin.key(0x20); else edit(() -> target.commit(" ")); }, 2);
        button(edits, "→", () -> edit(() -> target.move(1)), 1);
        button(edits, "删除", () -> { if (pinyin != null && (!composition.isEmpty() || pinyin.pending())) pinyin.key(0xff08); else edit(target::delete); }, 1.5f);
        LinearLayout controls = row(panel);
        modeButton = button(controls, "英文", () -> changeMode(false), 1);
        button(controls, "123 / 符号", () -> changeMode(true), 1.6f);
        voiceButton = button(controls, "语音", this::voice, 1);
        button(controls, "收起", this::dismiss, 1);
        button(controls, "完成 / 搜索", this::submit, 1.8f);
        setContentView(surface);
        labels(); refreshPreview();
    }
    @Override public void show() {
        super.show();
        Point size = new Point(); ((WindowManager) getContext().getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay().getSize(size);
        getWindow().setLayout(size.x, size.y);
        if (host != null) host.attachInput(this, surface);
        target.owner().addOnAttachStateChangeListener(this);
        target.observe(() -> { if (live()) refreshPreview(); });
        letters[0].requestFocusFromTouch();
        startPinyin();
    }
    private void startPinyin() {
        final int generation = ++pinyinGeneration;
        if (pinyin != null) pinyin.close();
        ready = false;
        pinyin = new PinyinSession(getContext(), new PinyinSession.Listener() {
            @Override public void result(String committed, String preedit, String[] values, boolean previous, boolean next) {
                if (generation != pinyinGeneration || !live()) return;
                ready = true;
                if (!committed.isEmpty()) target.commit(committed);
                composition = preedit; words = values;
                for (int i = 0; i < candidates.length; i++) candidates[i].setText(i < words.length ? words[i] : "·");
                if (voice == null || !voice.active()) status.setText(preedit.isEmpty() ? (ascii ? "英文直接输入" : "拼音输入 · 选择候选词上屏") : preedit);
                refreshPreview();
            }
            @Override public void failure() { if (generation == pinyinGeneration && live()) { ready = false; status.setText("拼音加载失败，可先用英文或语音，重新打开可重试"); } }
            @Override public void unhandled(int key) {
                if (generation != pinyinGeneration || !live()) return;
                if (key == 0x20) target.commit(" "); else if (key == 0xff08) target.delete();
                refreshPreview();
            }
        });
    }
    private TextView text(int size) { TextView view = new TextView(getContext()); view.setTextColor(Color.WHITE); view.setTextSize(TypedValue.COMPLEX_UNIT_PX, size); view.setGravity(Gravity.CENTER_VERTICAL); return view; }
    private LinearLayout row(LinearLayout parent) { LinearLayout row = new LinearLayout(getContext()); row.setGravity(Gravity.CENTER); parent.addView(row, new LinearLayout.LayoutParams(-1, 0, 1)); return row; }
    private Button button(LinearLayout row, String label, Runnable action, float weight) {
        Button button = new Button(getContext()); button.setText(label); button.setAllCaps(false); button.setTextColor(Color.WHITE);
        button.setTextSize(TypedValue.COMPLEX_UNIT_PX, 20); button.setPadding(1, 0, 1, 0); button.setMinWidth(0); button.setMinimumWidth(0); button.setMinHeight(0); button.setMinimumHeight(0); button.setSingleLine(true);
        button.setFocusable(true);
        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, keyBackground(0xff286494, 0xffa5dbff));
        background.addState(new int[]{android.R.attr.state_pressed}, keyBackground(0xff286494, Color.WHITE));
        background.addState(new int[]{}, keyBackground(0xff263340, 0xff465666)); button.setBackground(background);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, -1, weight); params.setMargins(3, 3, 3, 3); row.addView(button, params);
        button.setOnClickListener(v -> { if (live()) action.run(); }); return button;
    }
    private GradientDrawable keyBackground(int fill, int stroke) { GradientDrawable bg = new GradientDrawable(); bg.setColor(fill); bg.setCornerRadius(6); bg.setStroke(2, stroke); return bg; }
    private boolean live() { if (closed) return false; if (!target.valid()) { dismiss(); return false; } return true; }
    private void refreshPreview() { preview.setText(target.preview()); }
    private void edit(Runnable action) {
        if (waiting()) return;
        if (!composition.isEmpty()) { status.setText("请先选字，或删除正在输入的拼音"); return; }
        action.run(); refreshPreview();
    }
    private void type(int index) {
        if (voice != null && voice.active()) { status.setText("请先结束语音输入"); return; }
        if (symbols) edit(() -> target.commit(symbol(index)));
        else if (ascii) edit(() -> target.commit(upper ? alphabet.substring(index, index + 1).toUpperCase(java.util.Locale.ROOT) : alphabet.substring(index, index + 1)));
        else if (ready) pinyin.key(alphabet.charAt(index));
    }
    private void choose(int index) { if (!waiting() && ready && index < words.length) pinyin.choose(index); }
    private void page(int direction) { if (!waiting() && ready && !composition.isEmpty()) pinyin.key(direction < 0 ? 0xff55 : 0xff56); }
    private void changeMode(boolean symbol) {
        if (waiting()) return;
        if (!composition.isEmpty()) { status.setText("请先选字，或删除正在输入的拼音"); return; }
        if (symbol) symbols = !symbols; else { symbols = false; ascii = !ascii; }
        labels();
    }
    private void labels() {
        for (int i = 0; i < letters.length; i++) letters[i].setText(symbols ? symbol(i)
                : upper && ascii ? alphabet.substring(i, i + 1).toUpperCase(java.util.Locale.ROOT) : alphabet.substring(i, i + 1));
        modeButton.setText(ascii ? "中文" : "英文"); caseButton.setText(symbols ? "更多" : upper ? "小写" : "大写");
    }
    private String symbol(int index) { int at = (symbolPage * 26 + index) % symbolKeys.length(); return symbolKeys.substring(at, at + 1); }
    private boolean waiting() { if (ready && pinyin != null && pinyin.pending()) { status.setText("正在更新拼音，请稍候"); return true; } return false; }
    private void submit() {
        if (waiting()) return;
        if (!composition.isEmpty()) { status.setText("请先选择候选词，再完成输入"); return; }
        if (voice != null && voice.active()) { voice.stop(); return; }
        target.submit(); dismiss();
    }
    private void voice() {
        if (voice != null && voice.active()) { voice.stop(); return; }
        startVoice(false);
    }
    private boolean startVoice(final boolean holdToTalk) {
        if (voice != null && voice.active()) return false;
        if (waiting()) return false;
        if (target.password()) { status.setText("密码输入不启用语音"); return false; }
        if (!composition.isEmpty()) { status.setText("请先选择候选词"); return false; }
        if (!OfflineVoice.permission(getContext())) { status.setText("请允许麦克风权限，然后重新开始语音"); return false; }
        final int generation = ++voiceGeneration;
        heldVoice = holdToTalk; heldResult = null;
        voice = new OfflineVoice(getContext(), new OfflineVoice.Listener() {
            public void status(String message) {
                if (generation == voiceGeneration && live()) status.setText(heldVoice && heldResult != null
                        ? "识别完成 · 松手填入，上划取消"
                        : holdToTalk && message.startsWith("正在听") ? "正在录音 · 松手识别，上划取消（最长 20 秒）" : message);
            }
            public void result(String text) {
                if (generation != voiceGeneration || !live()) return;
                if (heldVoice) heldResult = text;
                else { target.commit(text); refreshPreview(); }
            }
            public void stopped() { if (generation == voiceGeneration && live()) voiceButton.setText("语音"); }
        });
        voiceButton.setText("结束"); voice.start();
        return voice.active();
    }
    private void finishHeldVoice() {
        heldVoice = false;
        if (voice != null) voice.stop();
        if (heldResult != null && live()) {
            String result = heldResult; heldResult = null; target.commit(result); refreshPreview(); status.setText("语音已填入");
        }
    }
    private void cancelVoice() {
        voiceGeneration++;
        heldVoice = false; heldResult = null;
        if (voice != null) voice.cancel();
        voice = null;
        if (voiceButton != null) voiceButton.setText("语音");
    }
    void clearText() {
        if (!live()) return;
        cancelVoice();
        composition = ""; words = new String[0];
        for (Button candidate : candidates) candidate.setText("·");
        startPinyin();
        target.clear(); refreshPreview(); status.setText("已清空");
    }
    boolean physicalMotion(MotionEvent event) {
        if (closed || event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || !"cyttsp5_mt".equals(device.getName())) return false;
        if (!isShowing() || !getWindow().getDecorView().hasWindowFocus() || !target.valid()) {
            if (gestures != null) gestures.cancel();
            return false;
        }
        if (gestureDevice != event.getDeviceId()) {
            if (gestures != null) gestures.cancel();
            gestureDevice = event.getDeviceId(); haveEvent = false;
            InputDevice.MotionRange x = device.getMotionRange(MotionEvent.AXIS_X), y = device.getMotionRange(MotionEvent.AXIS_Y);
            gestures = new KeyboardGesture(new Handler(Looper.getMainLooper()), new KeyboardGesture.Actions() {
                public boolean available() { return !closed && isShowing() && getWindow().getDecorView().hasWindowFocus() && target.valid(); }
                public boolean startVoice() { return KeyboardDialog.this.startVoice(true); }
                public void stopVoice() { finishHeldVoice(); }
                public void cancelVoice() {
                    KeyboardDialog.this.cancelVoice();
                    if (!closed) status.setText("本次语音已取消，原文字保留");
                }
                public void clearText() { KeyboardDialog.this.clearText(); }
            }, x == null ? 640 : x.getRange(), y == null ? 480 : y.getRange());
        }
        long signature = event.getDownTime(); signature = signature * 31 + event.getEventTime(); signature = signature * 31 + event.getAction();
        for (int i = 0; i < event.getPointerCount(); i++) {
            signature = signature * 31 + event.getPointerId(i);
            signature = signature * 31 + Float.floatToIntBits(event.getX(i)); signature = signature * 31 + Float.floatToIntBits(event.getY(i));
        }
        if (haveEvent && signature == lastEventSignature) return lastConsumed;
        haveEvent = true; lastEventSignature = signature;
        lastConsumed = gestures.event(event);
        if (lastConsumed) lastMultiTime = android.os.SystemClock.uptimeMillis();
        return lastConsumed;
    }
    boolean physicalKey(android.view.KeyEvent event) {
        if (closed || !isShowing() || !getWindow().getDecorView().hasWindowFocus() || event == null) return false;
        InputDevice device = event.getDevice();
        if (device == null || !"cyttsp5_mt".equals(device.getName())) return false;
        int code = event.getKeyCode();
        if (code != android.view.KeyEvent.KEYCODE_MEDIA_NEXT && code != android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) return false;
        return gestures != null && (gestures.ownsContacts() || (lastMultiTime != Long.MIN_VALUE && android.os.SystemClock.uptimeMillis() - lastMultiTime <= 250));
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) { return physicalMotion(event) || super.dispatchTouchEvent(event); }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) { return physicalMotion(event) || super.dispatchGenericMotionEvent(event); }
    @Override public boolean dispatchKeyEvent(android.view.KeyEvent event) { return physicalKey(event) || super.dispatchKeyEvent(event); }
    @Override public void onWindowFocusChanged(boolean focused) {
        super.onWindowFocusChanged(focused);
        if (!focused) { if (gestures != null) gestures.cancel(); cancelVoice(); }
    }
    @Override public void dismiss() {
        if (!closed) {
            closed = true;
            if (gestures != null) gestures.cancel();
            cancelVoice(); pinyinGeneration++;
            target.observe(null);
            if (pinyin != null) pinyin.close();
            View owner = target.owner(); if (owner != null) owner.removeOnAttachStateChangeListener(this);
        }
        super.dismiss();
    }
    @Override public void onViewAttachedToWindow(View view) { }
    @Override public void onViewDetachedFromWindow(View view) { dismiss(); }
}
