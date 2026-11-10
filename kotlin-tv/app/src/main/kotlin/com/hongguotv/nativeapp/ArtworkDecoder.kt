// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import kotlin.math.max
import kotlin.math.min

/** Decodes bounded cover bytes. Kept separate so recovery can be tested without real HEIC data. */
internal fun interface ArtworkDecoder {
    fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap?
}

internal class AndroidArtworkDecoder(private val lowRam: Boolean) : ArtworkDecoder {
    override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        if (bounds.outWidth !in 1..32768 || bounds.outHeight !in 1..32768) return null
        // Fit the entire source into the measured target. Cropping at decode time cannot be
        // undone by ImageView.FIT_CENTER and used to remove most of a portrait poster.
        val fit = min(1.0, min(width.toDouble() / bounds.outWidth, height.toDouble() / bounds.outHeight))
        val targetWidth = max(1, (bounds.outWidth * fit).toInt())
        val targetHeight = max(1, (bounds.outHeight * fit).toInt())
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= targetWidth && bounds.outHeight / (sample * 2) >= targetHeight) sample *= 2
        if ((bounds.outWidth / sample).toLong() * (bounds.outHeight / sample) > (if (lowRam) 2 else 4) * 1024 * 1024) return null
        val options = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options) ?: return null
        val result = try { Bitmap.createScaledBitmap(decoded, targetWidth, targetHeight, true) }
        catch (problem: Throwable) { decoded.recycle(); throw problem }
        if (result !== decoded) decoded.recycle()
        return result
    }
}
