// SPDX-License-Identifier: GPL-3.0-only
// Adapted from the pinned upstream Android registration flow
// (N3urda/hongguoTV 216fd3ea, feature 18c37c2a; GPL-3.0-only) and validated by the
// bounded live registration control: one anonymous aid 8662 device_register accepted,
// after which the v5 mobile model returned a full playable model.
package com.hongguotv.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.UUID
import java.util.zip.GZIPOutputStream
import okhttp3.CookieJar
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Bounded anonymous Android v5 device registration (`POST /service/2/device_register/`).
 *
 * Contract:
 * * exactly one physical request per [register] call, no retries, no follow-up ALERT/registerkey
 *   requests and no sleep/settle loop;
 * * the pinned aid 8662 profile is sent with a STRING `os_version` and the fixed query string;
 * * `cdid` and `openudid` are generated once per call and are the same values in the encrypted
 *   body and in the returned [PlayerDevice];
 * * nothing is persisted (no cookie jar, no token/cookie store, no account identity). The caller
 *   validates the returned identity against a real model response before saving it;
 * * the body read is capped at [MAX_RESPONSE_BYTES] and cancellation/deadline stays terminal:
 *   [RequestScope.ensureActive] and [RequestScope.execute] are used so an outer
 *   [PlaybackRequestBudget] deadline (8 s by default) covers the request and the body.
 */
internal class MobileDeviceRegistrar(
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Registers one anonymous device and returns it with the generated `cdid`/`openudid`.
     * `secretKey` stays empty: the legacy aid 1967 registerkey/ALERT calls are intentionally absent.
     */
    fun register(http: OkHttpClient): PlayerDevice {
        RequestScope.ensureActive()
        val now = clock()
        val cdid = UUID.randomUUID().toString()
        val openudid = UUID.randomUUID().toString().replace("-", "").take(16)
        val body = payload(cdid, openudid, now)
        val request = Request.Builder().url(URL).apply {
            headers(body, now).forEach { (name, value) -> header(name, value) }
        }.post(body.toRequestBody("application/octet-stream;tt-data=a".toMediaType())).build()

        val transport = http.newBuilder()
            .cookieJar(CookieJar.NO_COOKIES)
            .retryOnConnectionFailure(false)
            .build()
        PlaybackRequestBudget.current.get()?.request()
        RequestScope.ensureActive()

        PlaybackTrace.requestStart(PlaybackTrace.Route.REGISTER)
        val raw = try {
            RequestScope.execute(transport.newCall(request)) { response ->
                PlaybackTrace.response(response.code, response.body?.contentLength() ?: -1L)
                if (!response.isSuccessful) throw IOException("设备注册请求失败（HTTP ${response.code}）")
                val replyBody = response.body ?: throw IOException("设备注册响应为空")
                if (replyBody.contentLength() > MAX_RESPONSE_BYTES) throw IOException("设备注册响应过大")
                val source = replyBody.source()
                source.request(MAX_RESPONSE_BYTES + 1L)
                if (source.buffer.size > MAX_RESPONSE_BYTES.toLong()) throw IOException("设备注册响应过大")
                val bytes = source.buffer.readByteArray()
                PlaybackTrace.requestEnd(bytes.size.toLong(), true)
                bytes
            }
        } catch (error: Throwable) {
            PlaybackTrace.requestFail(PlaybackTrace.failureOf(error))
            throw error
        }
        val device = parseIdentity(raw.toString(Charsets.UTF_8), cdid, openudid)
            ?: throw IOException("设备注册响应异常")
        RequestScope.ensureActive()
        return device
    }

    /** Pinned request body: `ttEncrypt(gzip(JSON))`, anonymous aid 8662, STRING `os_version`. */
    internal fun payload(cdid: String, openudid: String, now: Long): ByteArray {
        val header = org.json.JSONObject()
            .put("display_name", "红果免费短剧")
            .put("update_version_code", 73932)
            .put("manifest_version_code", 73932)
            .put("app_version_minor", "7.3.9.32")
            .put("aid", 8662)
            .put("channel", "update_64")
            .put("package", "com.phoenix.read")
            .put("app_version", "7.3.9.32")
            .put("version_code", 73932)
            .put("sdk_version", "3.7.0-rc.34-fanqie-xiaoshuo")
            .put("sdk_target_version", 29)
            .put("git_hash", "3a88f4e")
            .put("os", "Android")
            // Must stay a JSON string: the endpoint rejects a numeric os_version.
            .put("os_version", "16")
            .put("os_api", 36)
            .put("device_model", "25053RT47C")
            .put("device_brand", "Redmi")
            .put("device_manufacturer", "Redmi")
            .put("cpu_abi", "arm64-v8a")
            .put("release_build", "b5e187f")
            .put("density_dpi", 520)
            .put("display_density", "mdpi")
            .put("resolution", "1280x2772")
            .put("language", "zh")
            .put("timezone", 8)
            .put("access", "wifi")
            .put("not_request_sender", 0)
            .put("carrier", "CHINA MOBILE")
            .put("mcc_mnc", "46000")
            .put("rom", "eng.root.20231116.160340")
            .put("rom_version", "W528JS release-keys")
            .put("cdid", cdid)
            .put("sig_hash", "56a962410c494bbaf0b58dba20cae56f")
            .put("openudid", openudid)
            .put("clientudid", UUID.randomUUID().toString())
            .put("ipv6_list", org.json.JSONArray())
            .put("region", "CN")
            .put("tz_name", "Asia/Shanghai")
            .put("tz_offset", 28800)
            .put("sim_region", "cn")
            .put("sim_serial_number", org.json.JSONArray())
            .put("oaid_may_support", false)
            .put("req_id", UUID.randomUUID().toString())
            .put("device_platform", "android")
            .put(
                "custom",
                org.json.JSONObject()
                    .put("account_region_src", "fallback")
                    .put("host_bit", 64)
                    .put("account_region", "cn")
                    .put("dragon_device_type", "phone"),
            )
            .put("pre_installed_channel", "")
            .put("apk_first_install_time", now)
            .put("is_system_app", 0)
            .put("sdk_flavor", "china")
            .put("guest_mode", 0)
        val json = org.json.JSONObject()
            .put("magic_tag", "ss_app_log")
            .put("header", header)
            .put("_gen_time", now)
        return ttEncrypt(gzip(json.toString().toByteArray(Charsets.UTF_8)))
    }

    /** Pinned headers. No signature headers, no manual `accept-encoding` (OkHttp must gunzip). */
    internal fun headers(body: ByteArray, now: Long): Map<String, String> = linkedMapOf(
        "User-Agent" to VendorConstants.APP_UA,
        "Content-Type" to "application/octet-stream;tt-data=a",
        "log-encode-type" to "gzip",
        "x-ss-dp" to "8662",
        "x-ss-req-ticket" to now.toString(),
        "x-vc-bdturing-sdk-version" to "4.0.3.cn",
        "lc" to "101",
        "sdk-version" to "2",
        "passport-sdk-version" to "5051452",
        "x-neptune" to "-8|50:51:59:30:40:47:49:39",
        "x-ss-stub" to digest("MD5", body).hex().uppercase(),
    )

    /**
     * Parses the registration reply. Strict: both ids must be positive decimal strings, so a
     * `device_id=0` rejection or a non-numeric placeholder never becomes a usable identity.
     */
    internal fun parseIdentity(raw: String, cdid: String, openudid: String): PlayerDevice? {
        val json = runCatching { org.json.JSONObject(raw) }.getOrNull() ?: return null
        fun text(name: String) = json.opt(name)?.takeIf { it != org.json.JSONObject.NULL }
            ?.toString()?.takeIf { it.isNotBlank() }
        val deviceId = text("device_id_str") ?: text("device_id").orEmpty()
        val installId = text("install_id_str") ?: text("install_id").orEmpty()
        if (!deviceId.matches(POSITIVE_ID) || !installId.matches(POSITIVE_ID)) return null
        return PlayerDevice(deviceId, installId, cdid, openudid, "")
    }

    /** ttEncrypt: MAGIC || rnd[32] || AES-128-CBC(SHA512(SHA512(rnd)+salt)[0..16], iv=[16..32], PKCS7(SHA512(data)+data)). */
    internal fun ttEncrypt(data: ByteArray): ByteArray {
        val rnd = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }
        val derived = digest("SHA-512", digest("SHA-512", rnd) + KDF_SALT)
        val body = digest("SHA-512", data) + data
        val pad = 16 - body.size % 16
        val padded = body + ByteArray(pad) { pad.toByte() }
        val encrypted = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                javax.crypto.spec.IvParameterSpec(derived.copyOfRange(16, 32)),
            )
            doFinal(padded)
        }
        return MAGIC + rnd + encrypted
    }

    private fun gzip(data: ByteArray): ByteArray =
        ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(data) } }.toByteArray()

    companion object {
        const val MAX_RESPONSE_BYTES = 64 * 1024
        internal const val URL =
            "https://log5-applog-lf.fqnovel.com/service/2/device_register/" +
                "?tt_data=a&cronet_version=8d40f833_2026-03-03&ttnet_version=4.2.243.31-douyin" +
                "&use_store_region_cookie=1"
        private val MAGIC = byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00)
        private val KDF_SALT = hex(
            "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
                "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
        )
        private val POSITIVE_ID = Regex("[1-9][0-9]{0,24}")
    }
}
