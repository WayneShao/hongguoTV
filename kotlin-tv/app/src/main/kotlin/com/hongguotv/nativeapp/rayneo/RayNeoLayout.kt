// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.rayneo

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout

/** One live logical tree. Only ordinary UI pixels are copied, never video frames. */
class RayNeoLayout(context: Context, val content: View) : ViewGroup(context) {
    private var bitmap: Bitmap? = null
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var dirty = true
    private var drawingSnapshot = false
    private var touchOffset = 0f

    init {
        setWillNotDraw(false)
        clipChildren = true
        addView(content)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        content.measure(MeasureSpec.makeMeasureSpec(w / 2, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        content.layout(0, 0, width / 2, height)
        if (changed) dirty = true
    }

    override fun onDescendantInvalidated(child: View, target: View) {
        dirty = true
        super.onDescendantInvalidated(child, target)
        if (!drawingSnapshot) invalidate()
    }

    override fun invalidateChildInParent(location: IntArray?, dirtyRect: Rect?): android.view.ViewParent? {
        dirty = true
        return super.invalidateChildInParent(location, dirtyRect)
    }

    override fun dispatchDraw(canvas: Canvas) {
        val eye = width / 2
        if (eye <= 0 || height <= 0) return
        var frame = bitmap
        if (frame == null || frame.width != eye || frame.height != height) {
            // Display lists may retain the previous bitmap until the next frame: let GC release it.
            frame = Bitmap.createBitmap(eye, height, Bitmap.Config.ARGB_8888)
            bitmap = frame
            dirty = true
        }
        if (dirty) {
            dirty = false
            drawingSnapshot = true
            try {
                val snapshot = Canvas(frame)
                snapshot.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
                super.dispatchDraw(snapshot)
            } finally { drawingSnapshot = false }
        }
        canvas.drawBitmap(frame, 0f, 0f, paint)
        canvas.drawBitmap(frame, eye.toFloat(), 0f, paint)
        if (dirty) postInvalidateOnAnimation()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) touchOffset = if (event.x >= width / 2) (width / 2).toFloat() else 0f
        val logical = MotionEvent.obtain(event)
        logical.offsetLocation(-touchOffset, 0f)
        return try { super.dispatchTouchEvent(logical) } finally { logical.recycle() }
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        val logical = MotionEvent.obtain(event)
        logical.offsetLocation(if (event.x >= width / 2) -(width / 2).toFloat() else 0f, 0f)
        return try { super.dispatchGenericMotionEvent(logical) } finally { logical.recycle() }
    }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); dirty = true; invalidate() }
    override fun onDetachedFromWindow() { bitmap = null; super.onDetachedFromWindow() }
}

object RayNeoDisplay {
    fun eyeWidth(context: Context): Int = context.resources.displayMetrics.widthPixels / 2
    fun columns(context: Context): Int = (eyeWidth(context) / context.resources.displayMetrics.density / 150).toInt().coerceIn(2, 4)
}
