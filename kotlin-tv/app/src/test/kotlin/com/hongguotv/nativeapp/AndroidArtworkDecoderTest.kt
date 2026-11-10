// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream

/** Real bitmap decoding on the app's minimum supported platform. */
@RunWith(RobolectricTestRunner::class)
class AndroidArtworkDecoderTest {
    @Test
    @Config(sdk = [26])
    fun decodesPngWithinTargetBounds() {
        val png = png(200, 100)
        val decoded = AndroidArtworkDecoder(lowRam = false).decode(png, 50, 100)
        assertNotNull(decoded)
        assertEquals(50, decoded!!.width)
        assertEquals(25, decoded.height)
    }

    private fun png(width: Int, height: Int): ByteArray {
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val out = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out))
        bitmap.recycle()
        return out.toByteArray()
    }
}
