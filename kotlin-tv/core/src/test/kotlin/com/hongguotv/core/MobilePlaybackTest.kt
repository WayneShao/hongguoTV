// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPInputStream

/** Contract tests for the bounded v5 mobile model transport, plus an opt-in live control. */
class MobilePlaybackTest {
    private class Captured {
        lateinit var request: Request
        lateinit var body: ByteArray
        var gunzipped: String = ""
    }

    private fun client(response: String, captured: Captured? = null, code: Int = 200): OkHttpClient =
        OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            if (captured != null) {
                captured.request = request
                val buffer = okio.Buffer()
                request.body?.writeTo(buffer)
                captured.body = buffer.readByteArray()
                captured.gunzipped = gunzip(captured.body)
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("M")
                .body(response.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun gunzip(bytes: ByteArray): String =
        GZIPInputStream(ByteArrayInputStream(bytes)).use { it.readBytes().toString(Charsets.UTF_8) }

    private fun md5Hex(bytes: ByteArray) =
        MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) }

    private fun device() = mapOf("iid" to "i-1", "cdid" to "c-1", "device_id" to "7000000000001234567")

    private fun tier(height: Int, width: Int, codec: String, url: String, duration: Double = 0.0) = JSONObject()
        .put("vheight", height).put("vwidth", width).put("codec_type", codec)
        .put("bitrate", 1000 + height).put("main_url", url).put("duration", duration)

    private fun model(vararg tiers: JSONObject) = JSONObject().put("video_list", JSONObject().apply {
        tiers.forEachIndexed { index, value -> put("video_$index", value) }
    })

    private fun reply(id: String, model: JSONObject, nested: Boolean = true) = JSONObject()
        .put("code", 0)
        .put("data", if (nested) JSONObject().put("1004", JSONObject().put(id, JSONObject().put("video_model", model)))
        else JSONObject().put("video_model", model))
        .toString()

    @Test fun gzipBodyQueryAndHashFollowTheV5Contract() {
        val captured = Captured()
        val id = "1234567890"
        MobilePlayback.fetch(id, device(), client(reply(id, model(tier(720, 1280, "h264", "https://cdn/x.mp4"))), captured))

        val url = captured.request.url
        assertEquals("api5-normal-sinfonlinea.fqnovel.com", url.host)
        assertEquals("/novel/player/multi_video_model/player/v1", url.encodedPath)
        assertEquals("8662", url.queryParameter("aid"))
        assertEquals("74332", url.queryParameter("version_code"))
        assertEquals("vivo_8662_64", url.queryParameter("channel"))
        assertEquals("POST", captured.request.method)
        assertEquals("gzip", captured.request.header("Content-Encoding"))
        assertEquals(md5Hex(captured.body), captured.request.header("x-ss-stub"))
        assertNotNull(captured.request.header("x-khronos"))

        val body = JSONObject(captured.gunzipped)
        assertEquals(1, body.getJSONObject("biz_param").getInt("device_level"))
        assertTrue(body.getJSONObject("biz_param").getBoolean("need_all_video_definition"))
        assertEquals(id, body.getJSONObject("mixed_video_id_map").getJSONArray("1004").getString(0))
        assertEquals("player", body.getString("dr_scene"))
        assertFalse(captured.gunzipped.contains("registration"))
    }

    @Test fun everyReturnedTierKeepsItsRawMetadata() {
        val id = "777"
        val answer = reply(id, model(
            tier(360, 640, "h264", "https://cdn/a.mp4", 63.169),
            tier(480, 854, "h265", "https://cdn/b.mp4", 63.169),
            tier(720, 1280, "h264", "https://cdn/c.mp4", 63.0),
            tier(1080, 1920, "bytevc2", "https://cdn/d.mp4", 63.169),
        ))
        val result = MobilePlayback.fetch(id, device(), client(answer))
        assertEquals(4, result.renditions.size)
        assertEquals(3, result.variants.size) // bytevc2 row is reported, not playable
        assertEquals(listOf(360L, 480L, 720L, 1080L), result.renditions.map { it.height })
        assertEquals(listOf("h264", "h265", "h264", "bytevc2"), result.renditions.map { it.codec })
        assertEquals(63.169, result.renditions[0].durationSeconds, 0.0001)
        assertEquals(1000L, result.renditions[0].bitrate - 360L)
        assertEquals(480, StreamModels.pick(result.variants, 480)!!.quality)
    }

    @Test fun flatDataWithoutPerIdEntryIsRejected() {
        val id = "555"
        try {
            MobilePlayback.fetch(id, device(), client(reply(id, model(tier(720, 1280, "h264", "https://cdn/x.mp4")), nested = false)))
            fail("expected exact per-id binding")
        } catch (expected: EmptyPlaybackException) {
            assertNotNull(expected)
        }
    }

    @Test fun wrongEpisodeOnlyReplyIsAnExplicitEmptyResult() {
        val id = "555"
        try {
            MobilePlayback.fetch(id, device(), client(reply("999", model(tier(720, 1280, "h264", "https://cdn/x.mp4")))))
            fail("expected empty")
        } catch (expected: EmptyPlaybackException) {
            assertTrue(expected.message!!.contains("entry=missing"))
        }
    }

    @Test fun oversizedReplyIsRejected() {
        val huge = "{\"code\":0,\"data\":{\"pad\":\"" + "x".repeat(2 * 1024 * 1024) + "\"}}"
        try {
            MobilePlayback.fetch("123", device(), client(huge))
            fail("expected oversize rejection")
        } catch (expected: IOException) {
            assertEquals("v5 mobile model too large", expected.message)
        }
    }

    @Test fun sharedBudgetCountsOneRequestAndStopsAtItsLimit() {
        val budget = PlaybackRequestBudget(maxRequests = 1)
        MobilePlayback.fetch("123", device(), client(reply("123", model(tier(720, 1280, "h264", "https://cdn/x.mp4")))), budget)
        try {
            MobilePlayback.fetch("124", device(), client(reply("124", model(tier(720, 1280, "h264", "https://cdn/x.mp4")))), budget)
            fail("expected budget rejection")
        } catch (expected: PlaybackBudgetExceededException) {
            assertNotNull(expected)
        }
    }

    @Test fun cancelledScopeAbortsBeforeAnyRequest() {
        var calls = 0
        val scope = RequestScope()
        val http = OkHttpClient.Builder().addInterceptor { chain -> calls++; chain.proceed(chain.request()) }.build()
        scope.cancel()
        try {
            scope.run { MobilePlayback.fetch("123", device(), http) }
            fail("expected cancellation")
        } catch (expected: IOException) {
            assertEquals(0, calls)
        }
    }

    @Test fun rejectedBusinessCodeNeverPublishesTiers() {
        val answer = JSONObject().put("code", 1004).put("data", JSONObject()).toString()
        try {
            MobilePlayback.fetch("123", device(), client(answer))
            fail("expected code rejection")
        } catch (expected: PlaybackModelException) {
            assertTrue(expected.message!!.contains("code=1004"))
        }
    }

    @Test fun explicitIdentityRejectionsReachTheExistingDeviceRecovery() {
        try {
            MobilePlayback.fetch("123", device(), client("{}", code = 401))
            fail("expected 401 rejection")
        } catch (expected: DeviceRejectedException) {
            assertNotNull(expected)
        }
        try {
            MobilePlayback.fetch("123", device(), client("{}", code = 403))
            fail("expected 403 rejection")
        } catch (expected: DeviceRejectedException) {
            assertNotNull(expected)
        }
        try {
            MobilePlayback.fetch("123", device(), client(JSONObject().put("code", 110001).toString()))
            fail("expected business identity rejection")
        } catch (expected: DeviceRejectedException) {
            assertNotNull(expected)
        }
    }

    @Test fun otherTransportFailuresStayGenericHttpErrors() {
        try {
            MobilePlayback.fetch("123", device(), client("{}", code = 503))
            fail("expected server failure")
        } catch (expected: PlaybackHttpException) {
            assertEquals(503, expected.status)
        }
    }

    @Test fun metaOnlyRenditionsMergeValuesAndChoose480() {
        val id = "888"
        fun metaTier(height: Int, codec: String, bitrate: Long) = JSONObject()
            .put("main_url", "https://cdn/$height.mp4")
            .put("video_meta", JSONObject().put("vwidth", height + 1000).put("vheight", height)
                .put("bitrate", bitrate).put("codec_type", codec).put("duration", 159.708))
        val model = JSONObject().put("video_duration", 159.708).put("video_list", JSONObject().apply {
            put("video_0", metaTier(1080, "h264", 9000))
            put("video_1", metaTier(720, "h265", 5000))
            put("video_2", metaTier(480, "h264", 3000))
            put("video_3", metaTier(360, "h264", 2000))
            put("video_4", metaTier(240, "bytevc2", 1000))
        })
        val result = MobilePlayback.fetch(id, device(), client(reply(id, model)))
        assertEquals(listOf(1080L, 720L, 480L, 360L, 240L), result.renditions.map { it.height })
        assertTrue(result.renditions.all { it.width > 0 && it.bitrate > 0 })
        assertEquals(listOf(9000L, 5000L, 3000L, 2000L, 1000L), result.renditions.map { it.bitrate })
        assertEquals(159.708, result.renditions.first().durationSeconds, 0.0001)
        assertEquals(4, result.variants.size)
        assertEquals(480, StreamModels.pick(result.variants, 480)!!.quality)
        assertEquals(1080, StreamModels.pick(result.variants, 1080)!!.quality)
    }

    /** Bounded per-phase request counters: classification only, never bodies or identity. */
    private class PhaseCalls {
        var catalog = 0
        var detail = 0
        var model = 0
        var media = 0
        val total get() = catalog + detail + model + media
    }

    /** Fixed phase buckets; anything that is not catalog/detail/model is counted as media. */
    private fun phaseOf(request: Request): String {
        val path = request.url.encodedPath
        return when {
            path.contains("/api/search/result") -> "catalog-search"
            path.contains("/detail") -> "detail"
            path.contains("multi_video_model") -> "model"
            else -> "media"
        }
    }

    /**
     * Opt-in live control over the two original failed episodes. Only the public web catalog is used
     * to resolve ids; model requests use the v5 contract. Failures are reported as a fixed phase plus
     * a typed status, never as free-form exception text.
     */
    @Test fun liveFailedEpisodesV5() {
        if (System.getenv("HONGGUO_LIVE_V5") != "1") return
        val calls = PhaseCalls()
        val http = OkHttpClient.Builder()
            .addInterceptor { chain ->
                val request = chain.request()
                when (phaseOf(request)) {
                    "catalog-search" -> calls.catalog++
                    "detail" -> calls.detail++
                    "model" -> calls.model++
                    else -> calls.media++
                }
                chain.proceed(request)
            }
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS).callTimeout(10, TimeUnit.SECONDS).build()
        val repo = ContentRepository(http)
        val out = JSONArray()
        val targets = listOf(Triple("tv", "末世轮回：我以尸躯化神明", 1), Triple("x3", "万妖图录传第十三季", 8))
        for ((name, title, index) in targets) {
            val row = JSONObject().put("target", name)
            var phase = "catalog-search"
            try {
                val page = repo.websiteSearch(title, 1)
                val exact = page.items.firstOrNull { it.title == title }
                val relaxed = exact ?: page.items.firstOrNull { normalize(it.title) == normalize(title) }
                row.put("search", JSONObject().put("found", relaxed != null)
                    .put("match", if (exact != null) "exact" else if (relaxed != null) "normalized" else "none")
                    .put("items", page.items.size))
                if (relaxed == null) { out.put(row); println("V5_LIVE $row"); continue }
                phase = "detail"
                val episodes = repo.detail(relaxed.id).episodes
                row.put("detail", JSONObject().put("episodeCount", episodes.size).put("index", index))
                if (index >= episodes.size) { out.put(row); println("V5_LIVE $row"); continue }
                phase = "model"
                val model = MobilePlayback.fetch(episodes[index], VendorConstants.device, http,
                    PlaybackRequestBudget(maxRequests = 1, maxRetries = 0, timeoutMillis = 10_000L))
                val tiers = JSONArray()
                model.renditions.sortedBy { it.height }.forEach { rendition ->
                    tiers.put(JSONObject().put("q", rendition.variant?.quality ?: -1).put("w", rendition.width)
                        .put("h", rendition.height).put("bitrate", rendition.bitrate)
                        .put("codec", rendition.codec).put("duration", rendition.durationSeconds))
                }
                row.put("model", JSONObject().put("status", model.status).put("code", model.code)
                    .put("dataKeys", model.dataKeys).put("map1004", model.dataIsMap1004).put("bytes", model.bodyBytes)
                    .put("tierCount", tiers.length()).put("tiers", tiers)
                    .put("picked480", StreamModels.pick(model.variants, 480)?.quality ?: -1))
            } catch (error: Exception) {
                val failure = JSONObject().put("phase", phase).put("typed", error.javaClass.simpleName)
                if (error is PlaybackHttpException) failure.put("httpStatus", error.status)
                if (error is EmptyPlaybackException) {
                    failure.put("emptyReason", error.reason.code).put("completeRound", error.completeRound)
                }
                row.put("failure", failure)
            }
            out.put(row)
            println("V5_LIVE $row")
        }
        val summary = JSONObject().put("live", out).put("calls", JSONObject()
            .put("total", calls.total).put("catalog-search", calls.catalog).put("detail", calls.detail)
            .put("model", calls.model).put("media", calls.media)).toString()
        System.getenv("HONGGUO_V5_SUMMARY")?.let { File(it).writeText(summary) }
        println("V5_LIVE_SUMMARY $summary")
    }

    private fun normalize(value: String) = value.replace(" ", "").replace("：", ":").replace("（", "(").replace("）", ")")
}
