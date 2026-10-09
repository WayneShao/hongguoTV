package com.hongguotv.nativeapp.rayneo;

/** Recognizes a completed two-finger tap without depending on the Android runtime. */
public final class TwoFingerTap {
    public enum Result { PASS, CONSUMED, MENU }
    // Android MotionEvent actionMasked values, deliberately no Android imports.
    private static final int DOWN = 0, UP = 1, MOVE = 2, CANCEL = 3, POINTER_DOWN = 5, POINTER_UP = 6;
    private static final int IDLE = 0, SINGLE = 1, PAIR = 2, REMAINING = 3, SUPPRESSED = 4;
    private final float slop;
    private final long maxDurationMs;
    private int state = IDLE;
    private int firstId, secondId, remainingId;
    private float firstX, firstY, secondX, secondY;
    private long started, lastTime;
    private boolean eligible;

    public TwoFingerTap(float slop, long maxDurationMs) {
        if (!Float.isFinite(slop) || slop < 0 || maxDurationMs < 0) throw new IllegalArgumentException();
        this.slop = slop; this.maxDurationMs = maxDurationMs;
    }

    /** Feed every raw event. PASS leaves one-finger recognition to the caller. */
    public Result handle(int action, int actionIndex, int[] ids, float[] xs, float[] ys, long time) {
        if (action == CANCEL) return suppress();
        if (!wellFormed(actionIndex, ids, xs, ys)) return suppress();
        if (action == DOWN) {
            if (ids.length != 1 || actionIndex != 0) return suppress();
            state = SINGLE; firstId = ids[0]; firstX = xs[0]; firstY = ys[0];
            started = lastTime = time; eligible = true;
            return Result.PASS;
        }
        if (state == SUPPRESSED) return Result.CONSUMED;
        if (state == IDLE) {
            if (action == POINTER_DOWN || action == POINTER_UP || ids.length > 1) return suppress();
            return Result.PASS;
        }
        if (time < lastTime) return suppress();
        lastTime = time;
        if (time - started > maxDurationMs) eligible = false;

        if (state == SINGLE) {
            if (action == POINTER_DOWN) {
                if (ids.length != 2 || ids[actionIndex] == firstId) return suppress();
                int first = indexOf(ids, firstId);
                if (first < 0) return suppress();
                track(firstId, xs[first], ys[first]);
                secondId = ids[actionIndex]; secondX = xs[actionIndex]; secondY = ys[actionIndex];
                state = PAIR;
                return Result.CONSUMED;
            }
            if (ids.length != 1 || ids[0] != firstId || action == POINTER_UP) return suppress();
            if (action == MOVE || action == UP) track(firstId, xs[0], ys[0]);
            if (action == UP) state = IDLE;
            return Result.PASS;
        }
        if (state == PAIR) {
            if (ids.length != 2 || indexOf(ids, firstId) < 0 || indexOf(ids, secondId) < 0
                    || action == POINTER_DOWN || action == UP) return suppress();
            for (int i = 0; i < 2; i++) track(ids[i], xs[i], ys[i]);
            if (action == POINTER_UP) {
                remainingId = ids[actionIndex] == firstId ? secondId : firstId;
                state = REMAINING;
            }
            return Result.CONSUMED;
        }
        // One finger remains after a valid POINTER_UP. It still belongs to this gesture.
        if (ids.length != 1 || ids[0] != remainingId || action == POINTER_DOWN || action == POINTER_UP) return suppress();
        track(ids[0], xs[0], ys[0]);
        if (action == UP) {
            boolean menu = eligible;
            suppress(); // Do not let duplicated UP/button-release become a single click.
            return menu ? Result.MENU : Result.CONSUMED;
        }
        return Result.CONSUMED;
    }

    private void track(int id, float x, float y) {
        float originX = id == firstId ? firstX : secondX;
        float originY = id == firstId ? firstY : secondY;
        if (Math.max(Math.abs(x - originX), Math.abs(y - originY)) > slop) eligible = false;
    }
    private static int indexOf(int[] ids, int wanted) {
        for (int i = 0; i < ids.length; i++) if (ids[i] == wanted) return i;
        return -1;
    }
    private static boolean wellFormed(int index, int[] ids, float[] xs, float[] ys) {
        if (ids == null || xs == null || ys == null || ids.length == 0 || ids.length > 2
                || xs.length != ids.length || ys.length != ids.length || index < 0 || index >= ids.length) return false;
        for (int i = 0; i < ids.length; i++) {
            if (ids[i] < 0 || !Float.isFinite(xs[i]) || !Float.isFinite(ys[i])) return false;
            for (int j = 0; j < i; j++) if (ids[j] == ids[i]) return false;
        }
        return true;
    }
    private Result suppress() { state = SUPPRESSED; eligible = false; return Result.CONSUMED; }
    /** Lifecycle cancellation suppresses residual events until the next fresh ACTION_DOWN. */
    public void cancel() { suppress(); }
}
