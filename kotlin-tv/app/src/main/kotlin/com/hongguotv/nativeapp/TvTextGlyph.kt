// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.widget.TextView

/**
 * Draws the play and completion symbols as shapes because some TV/X3 system fonts do not contain
 * "▶" and "✓" and show a placeholder box instead.
 *
 * The colour is read from the owning text view on every draw, so the symbol keeps the same colour
 * as the episode number and follows any focus tint applied to that text colour.
 */
internal class TvTextGlyph(
    private val owner: TextView,
    val kind: Kind,
    private val size: Int
) : Drawable() {
    enum class Kind { PLAY, CHECK }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeJoin = Paint.Join.ROUND
        strokeCap = Paint.Cap.ROUND
    }
    private val path = Path()
    private var alpha = 255
    private var colorFilter: ColorFilter? = null

    /** Colour of the next draw; mirrors the owner's current text colour. */
    val color: Int get() = owner.currentTextColor

    override fun getIntrinsicWidth() = size
    override fun getIntrinsicHeight() = size

    override fun isStateful() = true
    override fun onStateChange(state: IntArray) = true

    override fun draw(canvas: Canvas) {
        val area = bounds
        val width = area.width().toFloat()
        val height = area.height().toFloat()
        if (width <= 0f || height <= 0f) return
        paint.color = color
        paint.alpha = alpha
        paint.colorFilter = colorFilter
        path.reset()
        when (kind) {
            Kind.PLAY -> {
                // Rounded triangle: fill plus a round-joined stroke of the same colour.
                paint.style = Paint.Style.FILL_AND_STROKE
                paint.strokeWidth = width * .16f
                path.moveTo(area.left + width * .24f, area.top + height * .16f)
                path.lineTo(area.left + width * .84f, area.top + height * .5f)
                path.lineTo(area.left + width * .24f, area.top + height * .84f)
                path.close()
            }
            Kind.CHECK -> {
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = width * .14f
                path.moveTo(area.left + width * .16f, area.top + height * .54f)
                path.lineTo(area.left + width * .42f, area.top + height * .78f)
                path.lineTo(area.left + width * .86f, area.top + height * .26f)
            }
        }
        canvas.drawPath(path, paint)
    }

    override fun setAlpha(alpha: Int) {
        this.alpha = alpha
        paint.alpha = alpha
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        this.colorFilter = colorFilter
        paint.colorFilter = colorFilter
        invalidateSelf()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    companion object {
        /** Adds the symbol before the text, sized to the text. */
        fun prefix(view: TextView, kind: Kind): TvTextGlyph {
            val glyph = glyph(view, kind)
            view.setCompoundDrawablesWithIntrinsicBounds(glyph, null, null, null)
            view.compoundDrawablePadding = padding(view)
            return glyph
        }

        /** Adds the symbol after the text, keeping the old "12✓" completion order. */
        fun suffix(view: TextView, kind: Kind): TvTextGlyph {
            val glyph = glyph(view, kind)
            view.setCompoundDrawablesWithIntrinsicBounds(null, null, glyph, null)
            view.compoundDrawablePadding = padding(view)
            return glyph
        }

        /** Removes a symbol added by [prefix] or [suffix]. */
        fun clear(view: TextView) {
            view.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null)
            view.compoundDrawablePadding = 0
        }

        private fun glyph(view: TextView, kind: Kind) =
            TvTextGlyph(view, kind, (view.textSize * .85f).toInt().coerceAtLeast(1))

        private fun padding(view: TextView) = (view.textSize * .3f).toInt().coerceAtLeast(1)
    }
}
