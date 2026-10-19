package io.github.wayneshao.rayneo.ime;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;

/** One keyboard tree, laid out at one-eye width. No dependency on the host's renderer. */
public final class KeyboardSurface extends FrameLayout {
    private final Paint pointer = new Paint(Paint.ANTI_ALIAS_FLAG);
    private float pointerX, pointerY;
    private boolean pointerVisible;
    private int touchEye;
    public KeyboardSurface(Context context) {
        super(context);
        setClipChildren(true);
        // This small window uses a software canvas; the app/player keep their existing rendering.
        setLayerType(View.LAYER_TYPE_SOFTWARE, null);
        pointer.setColor(Color.WHITE);
    }
    public int eyeWidth() { return Math.max(1, getWidth() / 2); }
    public void cursor(float x, float y, boolean visible) {
        pointerX = x; pointerY = y; pointerVisible = visible; invalidate();
    }
    @Override protected void onMeasure(int widthSpec, int heightSpec) {
        int width = MeasureSpec.getSize(widthSpec), height = MeasureSpec.getSize(heightSpec);
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(width / 2, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        setMeasuredDimension(width, height);
    }
    @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
        for (int i = 0; i < getChildCount(); i++) getChildAt(i).layout(0, 0, eyeWidth(), getHeight());
    }
    @Override protected void dispatchDraw(Canvas canvas) {
        for (int eye = 0; eye < 2; eye++) {
            int saved = canvas.save(); canvas.translate(eye * eyeWidth(), 0); canvas.clipRect(0, 0, eyeWidth(), getHeight());
            for (int i = 0; i < getChildCount(); i++) drawChild(canvas, getChildAt(i), getDrawingTime());
            if (pointerVisible) {
                pointer.setColor(Color.BLACK); canvas.drawCircle(pointerX, pointerY, 7, pointer);
                pointer.setColor(Color.WHITE); canvas.drawCircle(pointerX, pointerY, 5, pointer);
            }
            canvas.restoreToCount(saved);
        }
    }
    @Override public boolean dispatchTouchEvent(MotionEvent event) {
        if (event.getActionMasked() == MotionEvent.ACTION_DOWN) touchEye = event.getX() >= eyeWidth() ? 1 : 0;
        MotionEvent copy = MotionEvent.obtain(event);
        try { copy.offsetLocation(-touchEye * eyeWidth(), 0); return super.dispatchTouchEvent(copy); }
        finally { copy.recycle(); }
    }
    @Override public boolean dispatchGenericMotionEvent(MotionEvent event) {
        MotionEvent copy = MotionEvent.obtain(event);
        try { if (event.getX() >= eyeWidth()) copy.offsetLocation(-eyeWidth(), 0); return super.dispatchGenericMotionEvent(copy); }
        finally { copy.recycle(); }
    }
}
