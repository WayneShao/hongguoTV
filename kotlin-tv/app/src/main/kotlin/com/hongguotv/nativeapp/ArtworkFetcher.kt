// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import com.hongguotv.core.CoverArt
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream

/**
 * Fetches and decodes a public cover, retrying once on the known-compatible byteimg URL.
 *
 * A failure to download the source must also reach the fallback (old signed URLs expire),
 * and downloaded bytes are cached only after they decode. On fallback success the bytes
 * are stored under both URLs so reopening an already-healed row needs no network.
 *
 * Redirects are refused: a forged or expired URL must not silently become a different
 * resource, and only the explicit byteimg fallback below may replace the requested cover.
 *
 * Bytes read from the old build's pre-decode cache may still be HEIC. They are dropped and
 * the same candidate is fetched once more, so a poisoned entry heals in the same visit.
 * A known source skips that retry and proceeds straight to its compatible URL, which is the
 * only candidate that can decode on old TVs.
 */
internal class ArtworkFetcher(
    private val cache: ArtworkCache,
    http: OkHttpClient,
    private val decoder: ArtworkDecoder,
    private val maxBytes: Int = 4 * 1024 * 1024,
) {
    // Clone keeps the caller's transport configuration, connection pool and dispatcher while
    // refusing automatic redirects. Following a redirect before this check would let a 302
    // body or a lookalike host feed bytes and cache entries the app never requested.
    private val client = http.newBuilder().followRedirects(false).followSslRedirects(false).build()

    fun fetch(
        url: String,
        width: Int,
        height: Int,
        cancelled: () -> Boolean = { false },
        onCall: (Call?) -> Unit = {},
    ): Bitmap? {
        val compatible = CoverArt.fallbackUrl(url)
        // A known source whose cached bytes no longer decode goes straight to the compatible
        // JPEG; re-downloading the signed source would repeat the same HEIC failure.
        val bitmap = attempt(url, width, height, cancelled, onCall, retryBadCache = compatible == null)
        if (bitmap != null || cancelled()) return bitmap
        val fallback = compatible ?: return null
        return attempt(fallback, width, height, cancelled, onCall, retryBadCache = true, healUnder = url)
    }

    private fun attempt(
        url: String,
        width: Int,
        height: Int,
        cancelled: () -> Boolean,
        onCall: (Call?) -> Unit,
        retryBadCache: Boolean,
        healUnder: String? = null,
    ): Bitmap? {
        val cached = cache.get(url)
        if (cached != null) {
            val bitmap = decode(cached, width, height, cancelled)
            if (bitmap != null) {
                if (healUnder != null) cache.put(healUnder, cached)
                return bitmap
            }
            // Cancelled during decode: keep the cached bytes for the next visit.
            if (cancelled()) return null
            // The old build cached bytes before decode; a HEIC row must not poison the cache.
            cache.remove(url)
            if (!retryBadCache) return null
        }
        val bytes = download(url, cancelled, onCall) ?: return null
        val bitmap = decode(bytes, width, height, cancelled) ?: return null
        cache.put(url, bytes)
        if (healUnder != null) cache.put(healUnder, bytes)
        return bitmap
    }

    private fun decode(bytes: ByteArray, width: Int, height: Int, cancelled: () -> Boolean): Bitmap? {
        val bitmap = decoder.decode(bytes, width, height) ?: return null
        // The view may have left the screen while the decoder was running; never hand a
        // bitmap back to a cancelled request or write its bytes to the cache.
        if (cancelled()) {
            bitmap.recycle()
            return null
        }
        return bitmap
    }

    private fun download(url: String, cancelled: () -> Boolean, onCall: (Call?) -> Unit): ByteArray? {
        if (cancelled()) return null
        return try {
            val call = client.newCall(Request.Builder().url(url).build())
            onCall(call)
            if (cancelled()) { call.cancel(); return null }
            call.execute().use { response ->
                val body = response.body ?: return null
                if (!response.isSuccessful || body.contentLength() > maxBytes) return null
                val out = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                body.byteStream().use { input ->
                    while (!cancelled()) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (out.size() + count > maxBytes) return null
                        out.write(buffer, 0, count)
                    }
                }
                if (cancelled()) return null
                out.toByteArray()
            }
        } catch (_: Exception) {
            // HTTP/IO failure, an expired signed URL or a malformed stored value.
            null
        }
    }
}
