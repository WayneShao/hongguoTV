// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp.rayneo

import android.app.AlertDialog
import android.app.Dialog
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ListView

/** All AlertDialog callers retain their normal buttons, ListView and dismiss listeners. */
object RayNeoDialogs {
    fun builder(context: Context): AlertDialog.Builder = object : AlertDialog.Builder(context) {
        override fun create(): AlertDialog = super.create().also { dialog ->
            dialog.setOnShowListener { wrap(dialog) }
        }
    }

    fun configure(window: Window) {
        window.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window.decorView.setPadding(0, 0, 0, 0)
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
        window.attributes = window.attributes.apply {
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            gravity = Gravity.CENTER
            x = 0; y = 0; dimAmount = .55f
        }
        window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        RayNeoInput.install(window)
    }

    fun wrap(dialog: Dialog) {
        val window = dialog.window ?: return
        val container = window.decorView.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (container.childCount == 1 && container.getChildAt(0) is RayNeoLayout) {
            configure(window)
            return
        }
        val context = dialog.context
        val focus = window.decorView.findFocus()
        val list = focus as? ListView
        val adapter = list?.adapter
        val row = list?.selectedItemPosition ?: -1
        val panel = FrameLayout(context)
        while (container.childCount > 0) {
            val child = container.getChildAt(0)
            val old = child.layoutParams
            container.removeView(child)
            panel.addView(child, FrameLayout.LayoutParams(old.width, old.height))
        }
        val padding = (12 * context.resources.displayMetrics.density).toInt()
        panel.background = GradientDrawable().apply { setColor(Color.rgb(22, 29, 39)); cornerRadius = padding.toFloat() }
        val logical = FrameLayout(context)
        logical.addView(panel, FrameLayout.LayoutParams(-1, -2, Gravity.CENTER).apply {
            leftMargin = padding; rightMargin = padding; topMargin = padding; bottomMargin = padding
        })
        container.addView(RayNeoLayout(context, logical), ViewGroup.LayoutParams(-1, -1))
        configure(window)
        container.post {
            if (!dialog.isShowing || focus?.isAttachedToWindow != true || !focus.isShown) return@post
            focus.requestFocusFromTouch()
            if (list != null && list.adapter === adapter && adapter != null && row in 0 until adapter.count && adapter.isEnabled(row)) list.setSelection(row)
        }
    }

    class ContentDialog(context: Context) : Dialog(context) {
        override fun setContentView(view: View) {
            super.setContentView(view)
            wrap(this)
        }
        override fun onStart() { super.onStart(); wrap(this) }
    }
}
