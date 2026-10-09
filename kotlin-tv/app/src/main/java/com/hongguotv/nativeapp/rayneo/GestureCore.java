package com.hongguotv.nativeapp.rayneo;

/** Platform-independent touchpad recognizer; timestamps are monotonic milliseconds. */
public final class GestureCore {
    public enum Action { NONE, CENTER, BACK, MENU, LEFT, RIGHT, UP, DOWN }
    private final float slop;
    private final long doubleMs, longMs;
    private float x, y;
    private long started, released;
    private boolean active, moved, pending, second;
    public GestureCore(float slop, long doubleMs, long longMs) {
        this.slop = slop; this.doubleMs = doubleMs; this.longMs = longMs;
    }
    /** 1 = right temple, -1 = suppressed temple, 0 = normal external input. */
    public static int source(String name) {
        if ("cyttsp5_mt".equals(name)) return 1;
        return "cyttsp6_mt".equals(name) || (name != null && name.startsWith("CapSense ")) ? -1 : 0;
    }
    public void down(float x, float y, long time) {
        if (active) return;
        second = pending && time >= released && time - released <= doubleMs;
        pending = false; active = true; moved = false;
        this.x = x; this.y = y; started = time;
    }
    public void move(float x, float y) {
        if (active) moved |= Math.max(Math.abs(x - this.x), Math.abs(y - this.y)) > slop;
    }
    public Action up(float x, float y, long time) {
        if (!active) return Action.NONE;
        move(x, y); active = false;
        float dx = x - this.x, dy = y - this.y;
        if (moved) {
            second = false;
            if (Math.max(Math.abs(dx), Math.abs(dy)) <= slop) return Action.NONE;
            return Math.abs(dx) >= Math.abs(dy) ? (dx > 0 ? Action.RIGHT : Action.LEFT) : (dy > 0 ? Action.DOWN : Action.UP);
        }
        if (time - started >= longMs) { second = false; return Action.MENU; }
        if (second) { second = false; return Action.BACK; }
        pending = true; released = time; return Action.NONE;
    }
    public Action confirm(long time) {
        if (!pending || time - released < doubleMs) return Action.NONE;
        pending = false; return Action.CENTER;
    }
    public boolean pending() { return pending; }
    public void cancel() { active = moved = pending = second = false; }
}
