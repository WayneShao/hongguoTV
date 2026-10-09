package com.hongguotv.nativeapp.rayneo;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static com.hongguotv.nativeapp.rayneo.TwoFingerTap.Result.*;

public final class TwoFingerTapTest {
    private TwoFingerTap tap() { return new TwoFingerTap(12, 500); }
    private TwoFingerTap.Result event(TwoFingerTap t, int action, int index, long time, int... ids) {
        return t.handle(action, index, ids, new float[ids.length], new float[ids.length], time);
    }
    private void start(TwoFingerTap t) {
        assertEquals(PASS, event(t, 0, 0, 0, 7));
        assertEquals(CONSUMED, event(t, 5, 1, 20, 7, 9));
    }
    @Test public void completesBothLiftOrdersExactlyOnce() {
        for (int lifted : new int[]{0, 1}) {
            TwoFingerTap t = tap(); start(t);
            assertEquals(CONSUMED, event(t, 6, lifted, 40, 7, 9));
            int remaining = lifted == 0 ? 9 : 7;
            assertEquals(MENU, event(t, 1, 0, 60, remaining));
            assertEquals(CONSUMED, event(t, 1, 0, 61, remaining));
            assertEquals(CONSUMED, event(t, 12, 0, 62, remaining));
        }
    }
    @Test public void pointerIndexOrderMayChange() {
        TwoFingerTap t = tap();
        event(t, 0, 0, 0, 7);
        assertEquals(CONSUMED, event(t, 5, 0, 20, 9, 7));
        assertEquals(CONSUMED, event(t, 2, 0, 30, 7, 9));
        assertEquals(CONSUMED, event(t, 6, 0, 40, 9, 7));
        assertEquals(MENU, event(t, 1, 0, 50, 7));
    }
    @Test public void priorSingleMotionDisqualifiesTwoFingerTap() {
        TwoFingerTap t = tap(); event(t, 0, 0, 0, 7);
        assertEquals(PASS, t.handle(2, 0, new int[]{7}, new float[]{40}, new float[]{0}, 10));
        event(t, 5, 1, 20, 7, 9); event(t, 6, 1, 30, 7, 9);
        assertEquals(CONSUMED, event(t, 1, 0, 40, 7));
    }
    @Test public void movementOfEitherPointerDisqualifiesEvenAfterReturning() {
        for (int moved = 0; moved < 2; moved++) {
            TwoFingerTap t = tap(); start(t);
            float[] xs = {0, 0}; xs[moved] = 13;
            assertEquals(CONSUMED, t.handle(2, 0, new int[]{7, 9}, xs, new float[]{0, 0}, 30));
            event(t, 6, 1, 40, 7, 9);
            assertEquals(CONSUMED, event(t, 1, 0, 50, 7));
        }
    }
    @Test public void thirdPointerPoisonsRemainderUntilFreshDown() {
        TwoFingerTap t = tap(); start(t);
        assertEquals(CONSUMED, event(t, 5, 2, 25, 7, 9, 11));
        event(t, 6, 2, 30, 7, 9, 11); event(t, 6, 1, 40, 7, 9);
        assertEquals(CONSUMED, event(t, 1, 0, 50, 7));
        start(t); event(t, 6, 0, 40, 7, 9);
        assertEquals(MENU, event(t, 1, 0, 50, 9));
    }
    @Test public void cancelSuppressesResidualAndUnclaimedSingle() {
        TwoFingerTap t = tap(); start(t);
        assertEquals(CONSUMED, event(t, 3, 0, 30, 7, 9));
        assertEquals(CONSUMED, event(t, 1, 0, 40, 7));
        event(t, 0, 0, 50, 7); t.cancel();
        assertEquals(CONSUMED, event(t, 1, 0, 60, 7));
    }
    @Test public void ordinarySingleSequencePassesThrough() {
        TwoFingerTap t = tap();
        assertEquals(PASS, event(t, 0, 0, 0, 7));
        assertEquals(PASS, event(t, 2, 0, 10, 7));
        assertEquals(PASS, event(t, 1, 0, 20, 7));
    }
    @Test public void invalidIdentityAndMissingPointerUpNeverEmitMenu() {
        for (int mode = 0; mode < 3; mode++) {
            TwoFingerTap t = tap(); start(t);
            if (mode == 0) event(t, 2, 0, 30, 7, 11);
            if (mode == 1) event(t, 6, 1, 30, 7, 7);
            if (mode != 2) event(t, 6, 1, 40, 7, 9);
            assertEquals(CONSUMED, event(t, 1, 0, 50, 7));
        }
    }
    @Test public void longOrBackwardsTimeNeverEmitsMenu() {
        for (long time : new long[]{501, -1}) {
            TwoFingerTap t = tap(); start(t); event(t, 6, 1, 40, 7, 9);
            assertEquals(CONSUMED, event(t, 1, 0, time, 7));
        }
    }
    @Test public void malformedOrOutOfOrderInputIsConsumed() {
        TwoFingerTap t = tap();
        assertEquals(CONSUMED, event(t, 5, 1, 20, 7, 9));
        assertEquals(CONSUMED, event(t, 1, 0, 30, 7));
        event(t, 0, 0, 40, 7);
        assertEquals(CONSUMED, t.handle(5, 1, new int[]{7, 9}, new float[]{0}, new float[]{0, 0}, 50));
    }
    @Test public void eachPointerUsesOwnOriginNotFirstFingerCoordinates() {
        TwoFingerTap t = tap();
        t.handle(0, 0, new int[]{7}, new float[]{100}, new float[]{200}, 0);
        assertEquals(CONSUMED, t.handle(5, 0, new int[]{9, 7}, new float[]{900, 100}, new float[]{800, 200}, 20));
        assertEquals(CONSUMED, t.handle(6, 1, new int[]{9, 7}, new float[]{910, 110}, new float[]{805, 205}, 40));
        assertEquals(MENU, t.handle(1, 0, new int[]{9}, new float[]{910}, new float[]{805}, 60));
    }
    @Test public void remainingFingerMotionStillInvalidatesTap() {
        TwoFingerTap t = tap(); start(t); event(t, 6, 1, 40, 7, 9);
        assertEquals(CONSUMED, t.handle(2, 0, new int[]{7}, new float[]{20}, new float[]{0}, 50));
        assertEquals(CONSUMED, event(t, 1, 0, 60, 7));
    }
    @Test public void claimingPairCancelsPreviouslyPendingSingleConfirmation() {
        TwoFingerTap t = tap();
        GestureCore single = new GestureCore(12, 300, 500);
        single.down(0, 0, 0); single.up(0, 0, 30);
        event(t, 0, 0, 50, 7); single.down(0, 0, 50);
        assertEquals(CONSUMED, event(t, 5, 1, 70, 7, 9));
        single.cancel(); // Window integration cancels only the one-finger recognizer on claim.
        event(t, 6, 1, 90, 7, 9);
        assertEquals(MENU, event(t, 1, 0, 110, 7));
        assertEquals(GestureCore.Action.NONE, single.up(0, 0, 110));
        assertEquals(GestureCore.Action.NONE, single.confirm(1000));
    }
}
