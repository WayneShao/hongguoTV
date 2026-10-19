package com.hongguotv.nativeapp.rayneo;

import android.app.Activity;
import android.app.Dialog;
import android.os.SystemClock;
import android.view.*;
import android.widget.Button;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE, sdk=28)
@LooperMode(LooperMode.Mode.PAUSED)
public class WindowDoubleTapTest {
    private void focus(Window window) {
        Object root=ReflectionHelpers.callInstanceMethod(window.getDecorView(), "getViewRootImpl");
        ((ShadowViewRootImpl) org.robolectric.shadow.api.Shadow.extract(root)).callWindowFocusChanged(true); ShadowLooper.idleMainLooper(); assertTrue(window.getDecorView().hasWindowFocus());
    }
    private void tap(Window window, float x, long start) {
        for (int action : new int[]{MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
            MotionEvent e=MotionEvent.obtain(start,start+action*30,action,x,100,0);
            Shadows.shadowOf(e).setDevice(ShadowInputDevice.makeInputDeviceNamed("cyttsp5_mt"));
            window.getCallback().dispatchTouchEvent(e); e.recycle();
        }
    }
    private void check(boolean keyboard) {
        ShadowLog.stream=System.out; RayNeoInput.INSTANCE.traceForTest(true);
        Activity a=Robolectric.buildActivity(Activity.class).setup().visible().get();
        final int[] backs={0};
        Dialog d=new Dialog(a) { @Override public void onBackPressed() { backs[0]++; dismiss(); } };
        Button button=new Button(a); button.setText("focus target"); d.setContentView(button); d.show();
        ShadowLooper.idleMainLooper(); focus(d.getWindow());
        if(keyboard) RayNeoInput.INSTANCE.installKeyboard(d); else RayNeoInput.INSTANCE.install(d.getWindow());
        button.requestFocusFromTouch(); long t=SystemClock.uptimeMillis();
        tap(d.getWindow(),841,t);
        // Reproduce the real sequence: focused button is cleared before second DOWN.
        button.clearFocus();
        android.view.ViewTreeObserver.OnGlobalFocusChangeListener listener = ReflectionHelpers.getField(d.getWindow().getCallback(), "focusListener");
        listener.onGlobalFocusChanged(button, null);
        tap(d.getWindow(),238,t+150);
        ShadowLooper.idleMainLooper(500,java.util.concurrent.TimeUnit.MILLISECONDS);
        System.out.println(RayNeoInput.INSTANCE.traceForTest(false));
        assertEquals("one BACK despite lost focus and different landing position",1,backs[0]);
        assertFalse(d.isShowing()); a.finish();
    }
    @Test public void keyboardDoubleTapSurvivesFocusLoss() { check(true); }
    @Test public void ordinaryWindowDoubleTapSurvivesFocusLoss() { check(false); }
}



