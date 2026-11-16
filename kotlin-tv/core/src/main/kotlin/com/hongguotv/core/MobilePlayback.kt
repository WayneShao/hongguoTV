// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/** One raw v5 mobile rendition row: metadata survives even when the row is not locally playable. */
internal data class MobileRendition(
    val variant: PlayerVariant?,
    val width: Long,
    val height: Long,
    val bitrate: Long,
    val codec: String,
    val durationSeconds: Double,
)

/** v5 mobile model reply: local variants plus the unranked renditions they came from. */
internal data class MobileModel(
    val variants: List<PlayerVariant>,
    val renditions: List<MobileRendition>,
    val bodyBytes: Int,
    val status: Int,
    val code: Int,
    val dataKeys: Int,
    val dataIsMap1004: Boolean,
)

/**
 * Android v5 playback contract (aid 8662, gzip JSON body, device_level 1).
 *
 * Bounded helper only: it performs one model request and never selects a quality or switches the
 * production route. Signing is delegated to the frozen [Signer] v5 API, so this file owns transport
 * shape (query, gzip body, headers) and JSON extraction, nothing else.
 */
internal object MobilePlayback {
    const val MODEL_URL = "https://api5-normal-sinfonlinea.fqnovel.com/novel/player/multi_video_model/player/v1"
    private const val MAX_BODY = 2 * 1024 * 1024L
    private const val UA = "com.phoenix.read/73932 (Linux; U; Android 12; zh_CN_#Hans; PGT-AN10; Build/V417IR;tt-ok/3.12.13.20)"
    private val signer = Signer(mobileV5 = true)

    /** Exact v5 query; `_rticket` is replaced by the signed ticket during signing. */
    fun query(device: Map<String, String>, now: Long): Map<String, String> = linkedMapOf(
        "aid" to "8662", "app_name" to "novelread", "version_code" to "74332",
        "version_name" to "7.4.3.32", "channel" to "vivo_8662_64", "device_platform" to "android",
        "device_type" to "PGT-AN10", "os_version" to "12", "update_version_code" to "74332",
        "iid" to device.getValue("iid"), "cdid" to (device["cdid"] ?: device.getValue("iid")),
        "device_id" to device.getValue("device_id"), "_rticket" to now.toString(),
    )

    /** Gzip JSON body bound to exactly one episode id. */
    fun body(id: String): ByteArray {
        require(id.matches(Regex("[0-9]{1,30}")))
        val payload = JSONObject(
            """{"biz_param":{"detail_page_version":0,"device_level":1,"disable_digg_stat":false,"disable_video_relate_book":false,"need_all_video_definition":true,"need_mp4_align":false,"use_os_player":false,"use_server_dns":false,"video_platform":1024},"mixed_video_id_map":{"1004":["$id"]},"dr_scene":"player"}"""
        )
        val output = ByteArrayOutputStream()
        GZIPOutputStream(output).use { it.write(payload.toString().toByteArray(Charsets.UTF_8)) }
        return output.toByteArray()
    }

    fun fetch(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        budget: PlaybackRequestBudget? = null,
        now: Long = System.currentTimeMillis(),
    ): MobileModel {
        require(id.matches(Regex("[0-9]{1,30}")))
        PlaybackTrace.requestStart(PlaybackTrace.Route.MOBILE)
        try {
            return fetchBody(id, device, http, budget, now)
        } catch (error: Exception) {
            PlaybackTrace.failure(PlaybackTrace.Route.MOBILE, PlaybackTrace.failureOf(error), PlaybackTrace.parserOf(error))
            PlaybackTrace.result(PlaybackTrace.Route.MOBILE, PlaybackTrace.parserOf(error), PlaybackTrace.failureOf(error))
            throw error
        }
    }

    private fun fetchBody(
        id: String,
        device: Map<String, String>,
        http: OkHttpClient,
        budget: PlaybackRequestBudget?,
        now: Long,
    ): MobileModel {
        RequestScope.ensureActive()
        val bytes = body(id)
        val signed = signer.signPlayback(MODEL_URL, query(device, now), bytes, now)
        val request = Request.Builder().url(signed.url)
            .header("User-Agent", UA).header("Accept", "application/json")
            .header("Content-Encoding", "gzip")
            .header("x-ss-stub", MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02X".format(it) })
        signed.headers.forEach { (name, value) -> request.header(name, value) }
        RequestScope.ensureActive()
        request.post(bytes.toRequestBody("application/json; charset=utf-8".toMediaType()))
        budget?.request()
        val transport = http.newBuilder().cookieJar(CookieJar.NO_COOKIES).build()
        return RequestScope.execute(transport.newCall(request.build())) { response ->
            PlaybackTrace.response(response.code, response.body?.contentLength() ?: -1L)
            if (!response.isSuccessful) {
                // Explicit identity rejections keep their existing recovery semantics; other HTTP
                // failures stay generic so callers do not rotate identities on transient errors.
                if (response.code == 401 || response.code == 403) throw DeviceRejectedException("播放请求被拒绝（HTTP ${response.code}）")
                throw PlaybackHttpException(response.code)
            }
            val body = response.body ?: throw PlaybackModelException("v5 mobile model body missing")
            if (body.contentLength() > MAX_BODY) throw IOException("v5 mobile model too large")
            val source = body.source()
            source.request(MAX_BODY + 1)
            if (source.buffer.size > MAX_BODY) throw IOException("v5 mobile model too large")
            val text = source.readUtf8()
            if (text.isBlank()) {
                PlaybackTrace.parser(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.RAW_BLANK)
                throw EmptyPlaybackException(detail = "stage=mobile-model blank status=${response.code} bytes=0")
            }
            val root = try { JSONObject(text) } catch (_: Exception) {
                PlaybackTrace.parser(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.MODEL_INVALID)
                throw PlaybackModelException("v5 mobile model not JSON")
            }
            val size = text.toByteArray(Charsets.UTF_8).size
            PlaybackTrace.requestEnd(size.toLong(), true)
            val code = root.optInt("code", -1)
            PlaybackTrace.businessCode(PlaybackTrace.Route.MOBILE, code)
            // Established business-level identity rejections, matching the legacy route set.
            if (code == 10001 || code == 10002 || code == 110001) throw DeviceRejectedException("播放接口拒绝该设备")
            if (code != 0) throw PlaybackModelException("v5 mobile model code=$code status=${response.code} bytes=$size")
            val data = root.optJSONObject("data")
                ?: throw PlaybackModelException("v5 mobile model data=missing status=${response.code} bytes=$size")
            val nested = data.optJSONObject("1004")
            val entries = nested ?: data
            val model = StreamModels.models(entries, id, allowDirect = false).firstOrNull()
                ?: run {
                    PlaybackTrace.parser(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.MODEL_INVALID)
                    throw EmptyPlaybackException(detail = "stage=mobile-model entry=missing map=${nested != null} " +
                        "keys=${entries.length()} code=$code status=${response.code} bytes=$size")
                }
            val rows = model.opt("video_list")
            val seed = StreamModels.keySeed(model)
            val raw = when (rows) {
                is JSONObject -> rows.keys().asSequence().mapNotNull { rows.optJSONObject(it) }.toList()
                is JSONArray -> (0 until rows.length()).mapNotNull { rows.optJSONObject(it) }
                else -> emptyList()
            }
            if (raw.isEmpty()) {
                PlaybackTrace.parser(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.LIST_EMPTY)
                throw EmptyPlaybackException(reason = EmptyPlaybackReason.LIST_EMPTY, detail = "stage=mobile-model")
            }
            val variants = StreamModels.listRows(rows as? JSONObject, seed) + StreamModels.arrayRows(rows as? JSONArray)
            val modelDuration = model.optDouble("video_duration", model.optDouble("duration", 0.0))
            // Every raw row is reported, including bytevc2 or malformed rows that yield no local variant.
            val renditions = raw.map { entry ->
                val info = mergedEntry(entry)
                MobileRendition(
                    StreamModels.decryptEntry(entry, seed),
                    StreamModels.int64FromAny(info, "vwidth"),
                    StreamModels.int64FromAny(info, "vheight"),
                    StreamModels.int64FromAny(info, "bitrate").takeIf { it != 0L }
                        ?: StreamModels.int64FromAny(info, "real_bitrate"),
                    listOf(info.optString("codec_type"), info.optString("gear_des_key"))
                        .firstOrNull { it.isNotBlank() }.orEmpty(),
                    info.optDouble("duration", modelDuration),
                )
            }
            RequestScope.ensureActive()
            PlaybackTrace.parser(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.SUCCESS)
            PlaybackTrace.result(PlaybackTrace.Route.MOBILE, PlaybackTrace.Parser.SUCCESS, PlaybackTrace.Failure.NONE)
            MobileModel(variants, renditions, size, response.code, code, entries.length(), nested != null)
        }
    }

    /** Rows keep dims/bitrate/codec in `video_meta`; merge it exactly like [StreamModels.decryptEntry]. */
    private fun mergedEntry(entry: JSONObject): JSONObject {
        val info = JSONObject(entry.toString())
        entry.optJSONObject("video_meta")?.let { meta ->
            for (name in meta.keys()) {
                if (!info.has(name) || info.isNull(name) || info.optString(name).isBlank()) info.put(name, meta.get(name))
            }
        }
        return info
    }
}
