package com.hongguotv.nativeapp.rayneo;

import org.junit.Test;

/** Platform-independent regression suite, also executable through its main entry point. */
public final class GestureCoreTest {
    @Test public void gestureBehavior() { main(new String[0]); }
    static void eq(Object expected, Object actual) {
        if (!expected.equals(actual)) throw new AssertionError("expected " + expected + ", got " + actual);
    }
    static GestureCore core() { return new GestureCore(12, 300, 500); }
    public static void main(String[] args) {
        eq(1, GestureCore.source("cyttsp5_mt"));
        eq(-1, GestureCore.source("cyttsp6_mt"));
        eq(-1, GestureCore.source("CapSense Touch"));
        eq(0, GestureCore.source("USB Mouse"));
        eq(0, GestureCore.source("cyttsp5_mt fake"));
        GestureCore g = core();
        g.down(0, 0, 0); eq(GestureCore.Action.NONE, g.up(0, 0, 40));
        eq(GestureCore.Action.NONE, g.confirm(339));
        eq(GestureCore.Action.CENTER, g.confirm(340));
        eq(GestureCore.Action.NONE, g.confirm(999));
        g.down(0, 0, 1000); g.up(0, 0, 1040); g.down(0, 0, 1100);
        eq(GestureCore.Action.BACK, g.up(0, 0, 1140));
        eq(GestureCore.Action.NONE, g.confirm(2000));
        g.down(0, 0, 2000); eq(GestureCore.Action.MENU, g.up(0, 0, 2600));
        float[][] ends = {{50, 2}, {-50, 2}, {2, 50}, {2, -50}};
        GestureCore.Action[] directions = {GestureCore.Action.RIGHT, GestureCore.Action.LEFT, GestureCore.Action.DOWN, GestureCore.Action.UP};
        for (int i = 0; i < ends.length; i++) {
            g.down(0, 0, 3000); g.move(ends[i][0], ends[i][1]);
            eq(directions[i], g.up(ends[i][0], ends[i][1], 3100));
            eq(GestureCore.Action.NONE, g.up(ends[i][0], ends[i][1], 3100));
        }
        g.down(0, 0, 4000); g.up(0, 0, 4040); g.cancel();
        eq(GestureCore.Action.NONE, g.confirm(5000));
        g.down(0, 0, 5000); g.cancel(); eq(GestureCore.Action.NONE, g.up(0, 0, 5100));
        g.down(0, 0, 6000); g.move(100, 0); eq(GestureCore.Action.NONE, g.up(0, 0, 6100));
        eq(GestureCore.Action.NONE, g.confirm(7000));
        System.out.println("GestureCore behavioral checks passed");
    }
}
