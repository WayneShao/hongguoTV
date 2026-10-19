package io.github.wayneshao.rayneo.ime;

import android.os.Handler;
import android.view.MotionEvent;

/** Window-local two-finger gesture recognizer; physical-device routing is supplied separately. */
final class KeyboardGesture {
    interface Actions {
        boolean available();
        boolean startVoice();
        void stopVoice();
        void cancelVoice();
        void clearText();
    }
    private static final int IDLE = 0, SINGLE = 1, TWO = 2, DRAIN = 3;
    private final Handler handler;
    private final Actions actions;
    private final float slopX, slopY, swipeY;
    private int state, first = -1, second = -1;
    private float x1, y1, x2, y2;
    private boolean held, recording, released;
    private final Runnable hold = new Runnable() {
        @Override public void run() {
            if (state != TWO || !actions.available()) { cancel(); return; }
            // Classify the sequence as a hold even when permission/model availability rejects recording.
            held = true;
            recording = actions.startVoice();
        }
    };
    KeyboardGesture(Handler handler, Actions actions, float rangeX, float rangeY) {
        this.handler = handler; this.actions = actions;
        slopX = Math.max(8, rangeX * .02f); slopY = Math.max(8, rangeY * .02f);
        swipeY = Math.max(32, rangeY * .12f);
    }
    boolean event(MotionEvent event) {
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) {
            cancel(); state = SINGLE; held = false; released = false;
            first = event.getPointerId(0); second = -1;
            return false;
        }
        if (state == IDLE) return false;
        if (state == DRAIN) {
            if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) released = true;
            if (released && (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE)) { state = IDLE; return false; }
            return true; // Duplicate releases still belong to the completed gesture.
        }
        if (action == MotionEvent.ACTION_CANCEL || (event.getFlags() & 0x20) != 0 || !actions.available()) {
            boolean owned = state == TWO; cancel(); return owned;
        }
        if (event.getPointerCount() > 2) { cancel(); state = DRAIN; released = false; return true; }
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (state != SINGLE || event.getPointerCount() != 2) { cancel(); state = DRAIN; return true; }
            int index = event.findPointerIndex(first);
            if (index < 0) { cancel(); state = DRAIN; return true; }
            second = event.getPointerId(event.getActionIndex());
            if (second == first) { cancel(); state = DRAIN; return true; }
            x1 = event.getX(index); y1 = event.getY(index);
            x2 = event.getX(event.getActionIndex()); y2 = event.getY(event.getActionIndex());
            state = TWO; held = false; released = false; handler.postDelayed(hold, 400);
            return true;
        }
        if (state == SINGLE) { if (action == MotionEvent.ACTION_UP) state = IDLE; return false; }
        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            handler.removeCallbacks(hold);
            if (recording) { recording = false; actions.stopVoice(); }
            state = DRAIN; released = action == MotionEvent.ACTION_UP; return true;
        }
        int a = event.findPointerIndex(first), b = event.findPointerIndex(second);
        if (a < 0 || b < 0 || event.getPointerCount() != 2) { cancel(); return true; }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx1 = event.getX(a) - x1, dx2 = event.getX(b) - x2;
            float dy1 = event.getY(a) - y1, dy2 = event.getY(b) - y2;
            if (dy1 <= -swipeY && dy2 <= -swipeY && Math.abs(dx1) < -dy1 && Math.abs(dx2) < -dy2) {
                handler.removeCallbacks(hold); state = DRAIN; released = false;
                if (held) { if (recording) { recording = false; actions.cancelVoice(); } }
                else actions.clearText();
            } else if (Math.abs(dx1) > slopX || Math.abs(dx2) > slopX || Math.abs(dy1) > slopY || Math.abs(dy2) > slopY) {
                handler.removeCallbacks(hold);
                // Upward motion may still become the cancel/clear gesture; other motion cancels ownership.
                if (dy1 > slopY || dy2 > slopY || Math.abs(dx1) > Math.max(slopX, -dy1) || Math.abs(dx2) > Math.max(slopX, -dy2)) { cancel(); released = false; }
            }
        }
        return true;
    }
    void cancel() {
        handler.removeCallbacks(hold);
        if (recording) { recording = false; actions.cancelVoice(); }
        if (state != IDLE) state = DRAIN;
        released = true;
    }
    boolean ownsContacts() { return state == TWO || (state == DRAIN && !released); }
}
