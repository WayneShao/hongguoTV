// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.graphics.Bitmap
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.util.concurrent.atomic.AtomicInteger

/**
 * A stored signed fqnovelpic URL may fail with HTTP/IO errors, may already be cached as
 * HEIC bytes, or may decode to null on Android 7/8. Recovery must try the known byteimg
 * JPEG exactly once, must not cache bytes until they decode, and must heal the old cache
 * entry so reopening does not repeat the failed fetch.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class ArtworkFetcherTest {
    @get:Rule val temp = TemporaryFolder()

    private val hash = "033873814a4b74d4365620010d16548e"
    private val source = "https://p3-reading-sign.fqnovelpic.com/novel-pic/$hash~tplv-x:400:0.heic?x-signature=old"
    private val fallback = "https://p3-novel.byteimg.com/novel-pic/$hash~tplv-shrink:640:0.image"

    @Test fun httpFailureTriesCompatibleFallbackExactlyOnce() {
        val requests = mutableListOf<Request>()
        val cache = cache()
        val fetcher = fetcher(cache, { request ->
            requests += request
            if (request.url.host == "p3-reading-sign.fqnovelpic.com") response(request, 403)
            else response(request, JPEG)
        })
        assertNotNull(fetcher.fetch(source, 64, 64))
        assertEquals(listOf(source, fallback), requests.map { it.url.toString() })
        assertArrayEquals(JPEG, cache.get(source))
        assertArrayEquals(JPEG, cache.get(fallback))
    }

    @Test fun ioFailureAlsoTriesFallback() {
        val requests = mutableListOf<Request>()
        val cache = cache()
        val fetcher = fetcher(cache, { request ->
            requests += request
            if (request.url.host == "p3-reading-sign.fqnovelpic.com") throw IOException("offline")
            else response(request, JPEG)
        })
        assertNotNull(fetcher.fetch(source, 64, 64))
        assertEquals(listOf(source, fallback), requests.map { it.url.toString() })
    }

    @Test fun unsupportedCachedHeicIsReplacedAndReopenUsesNoNetwork() {
        val cache = cache()
        cache.put(source, HEIC)
        val requests = mutableListOf<Request>()
        val first = fetcher(cache, { request ->
            requests += request
            if (request.url.host.endsWith("byteimg.com")) response(request, JPEG) else response(request, 403)
        })
        assertNotNull(first.fetch(source, 64, 64))
        assertEquals(listOf(fallback), requests.map { it.url.toString() })
        assertArrayEquals(JPEG, cache.get(source))
        assertArrayEquals(JPEG, cache.get(fallback))
        assertTrue(!cache.get(source)!!.contentEquals(HEIC))

        val reopened = mutableListOf<Request>()
        val second = fetcher(cache, { request ->
            reopened += request
            throw IOException("network must not be used")
        })
        assertNotNull(second.fetch(source, 64, 64))
        assertTrue(reopened.isEmpty())
    }

    @Test fun cachedHeicIsRemovedWhenFallbackAlsoFails() {
        val cache = cache()
        cache.put(source, HEIC)
        val requests = mutableListOf<Request>()
        val fetcher = fetcher(cache, { request -> requests += request; response(request, 403) })
        assertNull(fetcher.fetch(source, 64, 64))
        assertEquals(listOf(fallback), requests.map { it.url.toString() })
        assertNull(cache.get(source))
        assertNull(cache.get(fallback))
    }

    @Test fun downloadedBytesAreNotCachedUntilDecodeSucceeds() {
        val cache = cache()
        val fetcher = fetcher(cache, { request ->
            if (request.url.host.endsWith("byteimg.com")) response(request, HEIC) else response(request, HEIC)
        })
        assertNull(fetcher.fetch(source, 64, 64))
        assertNull(cache.get(source))
        assertNull(cache.get(fallback))
    }

    @Test fun unrelatedDecodeFailureDoesNotCacheOrFallback() {
        val cache = cache()
        val requests = mutableListOf<Request>()
        val fetcher = fetcher(cache, { request -> requests += request; response(request, HEIC) })
        assertNull(fetcher.fetch("https://example.com/cover.jpg", 64, 64))
        assertEquals(1, requests.size)
        assertNull(cache.get("https://example.com/cover.jpg"))
    }

    @Test fun lookalikeStoredUrlGetsNoFallback() {
        val lookalike = "https://evil.example/fqnovelpic.com/novel-pic/$hash~tplv-x:400:0.heic"
        val requests = mutableListOf<Request>()
        val fetcher = fetcher(cache(), { request -> requests += request; response(request, HEIC) })
        assertNull(fetcher.fetch(lookalike, 64, 64))
        assertEquals(listOf(lookalike), requests.map { it.url.toString() })
    }

    @Test fun redirectCannotTurnALookalikeIntoAFallbackCandidate() {
        val lookalike = "https://evil.example/fqnovelpic.com/novel-pic/$hash~tplv-x:400:0.heic"
        val valid = "https://p3-reading-sign.fqnovelpic.com/novel-pic/$hash~tplv-x:400:0.heic"
        val requests = mutableListOf<Request>()
        val fetcher = fetcher(cache(), { request ->
            requests += request
            if (request.url.toString() == lookalike) Response.Builder().request(request)
                .protocol(Protocol.HTTP_1_1).code(302).message("Found")
                .header("Location", valid).body("".toResponseBody(null)).build()
            else response(request, HEIC)
        })
        assertNull(fetcher.fetch(lookalike, 64, 64))
        assertTrue(requests.none { it.url.host.endsWith("byteimg.com") })
    }

    @Test fun loopbackRedirectIsNotFollowedAndCannotFeedBytesOrCache() {
        val destinationHits = AtomicInteger()
        LoopbackHttpServer { _ ->
            destinationHits.incrementAndGet()
            http(200, "OK", emptyList(), JPEG)
        }.use { destination ->
            LoopbackHttpServer { _ ->
                http(
                    302,
                    "Found",
                    listOf("Location" to "http://127.0.0.1:${destination.port}/cover.jpg"),
                    JPEG,
                )
            }.use { origin ->
                val url = "http://127.0.0.1:${origin.port}/cover.jpg"
                val cache = cache()
                val fetcher = ArtworkFetcher(
                    cache,
                    OkHttpClient.Builder().proxy(Proxy.NO_PROXY).build(),
                    MarkerDecoder(),
                )
                assertNull(fetcher.fetch(url, 64, 64))
                assertEquals(0, destinationHits.get())
                assertNull(cache.get(url))
            }
        }
    }

    @Test fun redirectFromKnownSourceStillReachesExactlyOneCompatibleFallback() {
        val requests = mutableListOf<Request>()
        val cache = cache()
        val fetcher = fetcher(cache, { request ->
            requests += request
            if (request.url.host == "p3-reading-sign.fqnovelpic.com") {
                Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(302).message("Found")
                    .header("Location", fallback).body(JPEG.toResponseBody(null)).build()
            } else {
                response(request, JPEG)
            }
        })
        assertNotNull(fetcher.fetch(source, 64, 64))
        assertEquals(listOf(source, fallback), requests.map { it.url.toString() })
        assertArrayEquals(JPEG, cache.get(source))
        assertArrayEquals(JPEG, cache.get(fallback))
    }

    @Test fun cancellationBeforeRequestDoesNotTouchTheNetwork() {
        val requests = mutableListOf<Request>()
        val fetcher = fetcher(cache(), { request -> requests += request; response(request, JPEG) })
        assertNull(fetcher.fetch(source, 64, 64, cancelled = { true }))
        assertTrue(requests.isEmpty())
    }

    @Test fun cancellationDuringDecodePreventsFallbackWork() {
        val requests = mutableListOf<Request>()
        var cancelled = false
        val decoder = object : ArtworkDecoder {
            override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
                cancelled = true
                return null
            }
        }
        val fetcher = fetcher(cache(), { request ->
            requests += request
            if (request.url.host.endsWith("byteimg.com")) response(request, JPEG) else response(request, HEIC)
        }, decoder)
        assertNull(fetcher.fetch(source, 64, 64, cancelled = { cancelled }))
        assertEquals(listOf(source), requests.map { it.url.toString() })
    }

    @Test fun cancellationAfterSuccessfulPrimaryDecodeRecyclesAndSkipsCache() {
        val cache = cache()
        val requests = mutableListOf<Request>()
        var cancelled = false
        var decoded: Bitmap? = null
        val decoder = object : ArtworkDecoder {
            override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? {
                if (!bytes.startsWith(JPEG)) return null
                return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).also {
                    decoded = it
                    cancelled = true
                }
            }
        }
        val fetcher = fetcher(cache, { request -> requests += request; response(request, JPEG) }, decoder)
        assertNull(fetcher.fetch(source, 64, 64, cancelled = { cancelled }))
        assertEquals(listOf(source), requests.map { it.url.toString() })
        assertTrue(decoded?.isRecycled == true)
        assertNull(cache.get(source))
        assertNull(cache.get(fallback))
    }

    @Test fun poisonedCanonicalByteimgCacheIsRefreshedOnceThenReopenIsNetworkFree() {
        val cache = cache()
        cache.put(fallback, HEIC)
        val requests = mutableListOf<Request>()
        val first = fetcher(cache, { request -> requests += request; response(request, JPEG) })
        assertNotNull(first.fetch(fallback, 64, 64))
        assertEquals(listOf(fallback), requests.map { it.url.toString() })
        assertArrayEquals(JPEG, cache.get(fallback))

        val reopened = mutableListOf<Request>()
        val second = fetcher(cache, { request -> reopened += request; throw IOException("network must not be used") })
        assertNotNull(second.fetch(fallback, 64, 64))
        assertTrue(reopened.isEmpty())
    }

    @Test fun poisonedSourceAndFallbackCachesHealInOneFetch() {
        val cache = cache()
        cache.put(source, HEIC)
        cache.put(fallback, HEIC)
        val requests = mutableListOf<Request>()
        val first = fetcher(cache, { request ->
            requests += request
            if (request.url.host.endsWith("byteimg.com")) response(request, JPEG) else response(request, HEIC)
        })
        assertNotNull(first.fetch(source, 64, 64))
        assertEquals(listOf(fallback), requests.map { it.url.toString() })
        assertArrayEquals(JPEG, cache.get(source))
        assertArrayEquals(JPEG, cache.get(fallback))

        val reopened = mutableListOf<Request>()
        val second = fetcher(cache, { request -> reopened += request; throw IOException("network must not be used") })
        assertNotNull(second.fetch(source, 64, 64))
        assertTrue(reopened.isEmpty())
    }

    @Test fun fourMegabyteBoundIsRetained() {
        val exact = JPEG.copyOf(4 * 1024 * 1024).also { JPEG.copyInto(it) }
        val over = JPEG.copyOf(4 * 1024 * 1024 + 1).also { JPEG.copyInto(it) }

        val cache = cache()
        val ok = fetcher(cache, { request -> response(request, exact) })
        assertNotNull(ok.fetch("https://example.com/exact.jpg", 64, 64))
        assertArrayEquals(exact, cache.get("https://example.com/exact.jpg"))

        val oversized = fetcher(cache(), { request -> response(request, over) })
        assertNull(oversized.fetch("https://example.com/over.jpg", 64, 64))
    }

    private fun cache() = ArtworkCache(File(temp.newFolder(), "artwork-v1"))

    private fun fetcher(
        cache: ArtworkCache,
        respond: (Request) -> Response,
        decoder: ArtworkDecoder = MarkerDecoder(),
    ) = ArtworkFetcher(cache, OkHttpClient.Builder().addInterceptor { chain ->
        respond(chain.request())
    }.build(), decoder)

    private fun response(request: Request, bytes: ByteArray, code: Int = 200): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
            .code(code).message(if (code == 200) "OK" else "Error")
            .body(bytes.toResponseBody(null)).build()

    private fun response(request: Request, code: Int): Response = response(request, ByteArray(0), code)

    private fun String.toRequest(): Request = Request.Builder().url(this).build()

    private fun http(code: Int, reason: String, headers: List<Pair<String, String>>, body: ByteArray): ByteArray {
        val head = buildString {
            append("HTTP/1.1 $code $reason\r\n")
            headers.forEach { (name, value) -> append("$name: $value\r\n") }
            append("Content-Length: ${body.size}\r\nConnection: close\r\n\r\n")
        }
        return head.toByteArray(Charsets.ISO_8859_1) + body
    }

    /** Minimal loopback HTTP/1.1 responder; only the request line is inspected. */
    private class LoopbackHttpServer(private val respond: (String) -> ByteArray) : Closeable {
        private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))
        val port: Int get() = socket.localPort
        private val thread = Thread {
            while (!socket.isClosed) {
                try {
                    socket.accept().use { connection ->
                        val reader = connection.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                        val requestLine = reader.readLine() ?: return@use
                        while (true) {
                            val header = reader.readLine() ?: break
                            if (header.isEmpty()) break
                        }
                        connection.getOutputStream().apply {
                            write(respond(requestLine))
                            flush()
                        }
                    }
                } catch (_: IOException) {
                    if (socket.isClosed) break
                }
            }
        }.apply {
            isDaemon = true
            name = "artwork-loopback"
            start()
        }

        override fun close() {
            runCatching { socket.close() }
            thread.join(2000)
        }
    }

    private class MarkerDecoder : ArtworkDecoder {
        override fun decode(bytes: ByteArray, width: Int, height: Int): Bitmap? =
            if (bytes.startsWith(JPEG)) Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888) else null
    }

    companion object {
        private val JPEG = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0x01)
        private val HEIC = "HEIC-not-decodable-on-api-21".toByteArray()

        private fun ByteArray.startsWith(prefix: ByteArray) =
            size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
    }
}
