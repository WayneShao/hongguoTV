package com.hongguotv.nativeapp.rayneo

import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.EditText
import io.github.wayneshao.rayneo.ime.EmbeddedInput
import io.github.wayneshao.rayneo.ime.NativeEditor

/** Only the native search editor requests a soft keyboard in this host. */
object SearchKeyboard {
    fun bind(editor: EditText) {
        editor.setOnClickListener { open(editor) }
        editor.setOnKeyListener { _, code, event ->
            if (code in listOf(KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER, KeyEvent.KEYCODE_BUTTON_A)) {
                if (event.action == KeyEvent.ACTION_UP && !event.isCanceled) open(editor)
                true
            } else false
        }
        var x = 0f; var y = 0f
        val slop = ViewConfiguration.get(editor.context).scaledTouchSlop
        editor.setOnTouchListener { _, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { x = event.x; y = event.y }
                MotionEvent.ACTION_UP -> if (kotlin.math.abs(event.x-x) <= slop && kotlin.math.abs(event.y-y) <= slop) {
                    editor.post { if (editor.isAttachedToWindow) open(editor) }
                }
            }
            false // EditText retains selection and cursor positioning.
        }
        editor.showSoftInputOnFocus = false
    }
    private fun open(editor: EditText) {
        editor.requestFocus()
        val shown = EmbeddedInput.show(NativeEditor(editor)) { dialog, _ ->
            dialog.window?.let(RayNeoInput::install) // Module already owns stereo rendering.
        }
        Log.i("EmbeddedSearchInput", "open shown=$shown")
    }
    fun close(editor: android.view.View?) { editor?.let(EmbeddedInput::close) }
}
