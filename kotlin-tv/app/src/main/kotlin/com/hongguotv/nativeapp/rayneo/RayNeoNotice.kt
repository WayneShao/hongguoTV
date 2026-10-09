// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.rayneo

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.FrameLayout
import android.widget.TextView

class RayNeoNotice(private val container: FrameLayout) {
    private val handler = Handler(Looper.getMainLooper())
    private var current: TextView? = null
    private val clear = Runnable { current?.let(container::removeView); current = null }

    fun show(message: String, duration: Int = 0) {
        close()
        val dp = container.resources.displayMetrics.density
        val view = TextView(container.context).apply {
            text = message; textSize = 16f; setTextColor(Color.WHITE)
            setPadding((16*dp).toInt(), (10*dp).toInt(), (16*dp).toInt(), (10*dp).toInt())
            background = GradientDrawable().apply { setColor(Color.rgb(46, 53, 65)); cornerRadius = 8*dp }
            gravity = Gravity.CENTER
            isFocusable = false
        }
        current = view
        container.addView(view, FrameLayout.LayoutParams(-2, -2, Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            leftMargin = (16*dp).toInt(); rightMargin = (16*dp).toInt(); bottomMargin = (18*dp).toInt()
        })
        handler.postDelayed(clear, if (duration == 1) 3500 else 2000)
    }

    fun close() { handler.removeCallbacks(clear); clear.run() }
}
