package io.github.wayneshao.rayneo.ime;

import android.os.Handler;
import android.os.Looper;
import android.view.InputDevice;
import android.view.MotionEvent;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowLooper;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class KeyboardGestureTest {
    int starts, stops, cancels, clears;
    boolean available = true, acceptVoice = true;
    KeyboardGesture gesture;
    @Before public void setup() {
        gesture = new KeyboardGesture(new Handler(Looper.getMainLooper()), new KeyboardGesture.Actions() {
            public boolean available() { return available; }
            public boolean startVoice() { starts++; return acceptVoice; }
            public void stopVoice() { stops++; }
            public void cancelVoice() { cancels++; }
            public void clearText() { clears++; }
        }, 640, 480);
    }
    private boolean event(int action, int[] ids, float[] xs, float[] ys) {
        MotionEvent.PointerProperties[] properties = new MotionEvent.PointerProperties[ids.length];
        MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[ids.length];
        for (int i = 0; i < ids.length; i++) {
            properties[i] = new MotionEvent.PointerProperties(); properties[i].id = ids[i]; properties[i].toolType = MotionEvent.TOOL_TYPE_FINGER;
            coords[i] = new MotionEvent.PointerCoords(); coords[i].x = xs[i]; coords[i].y = ys[i]; coords[i].pressure = 1; coords[i].size = 1;
        }
        MotionEvent event = MotionEvent.obtain(0, android.os.SystemClock.uptimeMillis(), action, ids.length, properties, coords, 0, 0, 1, 1, 7, 0, InputDevice.SOURCE_TOUCHPAD, 0);
        try { return gesture.event(event); } finally { event.recycle(); }
    }
    private void twoDown() {
        assertFalse(event(MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{200}, new float[]{300}));
        assertTrue(event(MotionEvent.ACTION_POINTER_DOWN | (1 << 8), new int[]{3, 8}, new float[]{200, 230}, new float[]{300, 300}));
    }
    private void upSwipe() { assertTrue(event(MotionEvent.ACTION_MOVE, new int[]{8, 3}, new float[]{230, 200}, new float[]{210, 210})); }
    private void hold() { org.robolectric.Shadows.shadowOf(Looper.getMainLooper()).idleFor(400, java.util.concurrent.TimeUnit.MILLISECONDS); }
    private void release() {
        assertTrue(event(MotionEvent.ACTION_POINTER_UP, new int[]{3, 8}, new float[]{200, 230}, new float[]{300, 300}));
        assertTrue(event(MotionEvent.ACTION_UP, new int[]{8}, new float[]{230}, new float[]{300}));
    }
    @Test public void holdStartsWithoutFurtherMotionAndFirstReleaseStopsOnce() {
        twoDown(); hold(); assertEquals(1, starts); release(); assertEquals(1, stops); assertEquals(0, clears); assertEquals(0, cancels);
    }
    @Test public void directUpClearsOnceAndNeverStartsVoice() {
        twoDown(); upSwipe(); upSwipe(); hold(); release(); assertEquals(1, clears); assertEquals(0, starts); assertEquals(0, stops);
    }
    @Test public void heldUpCancelsVoiceOnlyPreservingExistingText() {
        twoDown(); hold(); upSwipe(); release(); assertEquals(1, starts); assertEquals(1, cancels); assertEquals(0, stops); assertEquals(0, clears);
    }
    @Test public void failedVoiceStartNeverTurnsHeldUpIntoClearAll() {
        acceptVoice = false; twoDown(); hold(); upSwipe(); release(); assertEquals(0, clears); assertEquals(0, stops);
    }
    @Test public void oneMovingFingerDoesNotClearText() {
        twoDown(); event(MotionEvent.ACTION_MOVE, new int[]{3, 8}, new float[]{200, 230}, new float[]{200, 300});
        hold(); release(); assertEquals(0, clears); assertEquals(0, starts);
    }
    @Test public void sidewaysOrDownwardMovementDoesNotStartVoiceOrClear() {
        twoDown(); event(MotionEvent.ACTION_MOVE, new int[]{3, 8}, new float[]{280, 310}, new float[]{300, 300});
        hold(); release(); assertEquals(0, starts); assertEquals(0, clears);
    }
    @Test public void movingBeforeHoldThenReturningDoesNotRearmHold() {
        twoDown(); event(MotionEvent.ACTION_MOVE, new int[]{3, 8}, new float[]{220, 250}, new float[]{300, 300});
        event(MotionEvent.ACTION_MOVE, new int[]{3, 8}, new float[]{200, 230}, new float[]{300, 300}); hold(); release(); assertEquals(0, starts);
    }
    @Test public void thirdFingerCancelsAndDrainsRemainingTouches() {
        twoDown(); hold(); assertTrue(event(MotionEvent.ACTION_POINTER_DOWN | (2 << 8), new int[]{3, 8, 9}, new float[]{200, 230, 250}, new float[]{300, 300, 300}));
        upSwipe(); release(); assertEquals(1, cancels); assertEquals(0, stops); assertEquals(0, clears);
    }
    @Test public void focusLossCancelsRecordingAndSuppressesRemainder() {
        twoDown(); hold(); gesture.cancel(); upSwipe(); release(); assertEquals(1, cancels); assertEquals(0, clears); assertEquals(0, stops);
    }
    @Test public void unavailableWindowNeverStartsOrClears() {
        twoDown(); available = false; hold(); upSwipe(); release(); assertEquals(0, starts); assertEquals(0, clears);
    }
    @Test public void shortTwoFingerTapHasNoActionAndSingleFingerStillPasses() {
        twoDown(); release(); hold(); assertEquals(0, starts); assertEquals(0, clears);
        assertFalse(event(MotionEvent.ACTION_DOWN, new int[]{3}, new float[]{200}, new float[]{300}));
        assertFalse(event(MotionEvent.ACTION_UP, new int[]{3}, new float[]{200}, new float[]{300}));
    }
    @Test public void cancelEventCancelsAndNewSingleSequencePasses() {
        twoDown(); hold(); assertTrue(event(MotionEvent.ACTION_CANCEL, new int[]{3, 8}, new float[]{200, 230}, new float[]{300, 300}));
        assertEquals(1, cancels); assertFalse(event(MotionEvent.ACTION_DOWN, new int[]{7}, new float[]{200}, new float[]{300}));
    }
    @Test public void mouseHoverResumesAfterMultiTouchWithoutAnExtraClick() {
        twoDown(); release();
        assertTrue(event(MotionEvent.ACTION_UP, new int[]{8}, new float[]{230}, new float[]{300}));
        assertFalse(event(MotionEvent.ACTION_HOVER_ENTER, new int[]{8}, new float[]{230}, new float[]{300}));
        assertFalse(event(MotionEvent.ACTION_HOVER_MOVE, new int[]{8}, new float[]{240}, new float[]{300}));
    }
}
