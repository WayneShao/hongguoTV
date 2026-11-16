// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.OkHttpClient.Builder
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Contract tests for the bounded anonymous v5 device registrar. No test performs real HTTP. */
class MobileDeviceRegistrarTest {
    private val salt = hex(
        "4dd4c2e6b83162090e52b3c7a6733ba41cb2462b829ab58a196b39db57177524" +
            "f49baf7f08e8d68d26a72e37c1a95a2f1f05a51892aef2949732b62a38aadd58"
    )

    private class Captured {
        lateinit var request: Request
        lateinit var body: ByteArray
        var calls = 0
    }

    private fun client(reply: String, code: Int = 200, captured: Captured = Captured()): OkHttpClient =
        Builder().addInterceptor { chain ->
            val request = chain.request()
            captured.calls++
            captured.request = request
            val buffer = okio.Buffer()
            request.body?.writeTo(buffer)
            captured.body = buffer.readByteArray()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("M")
                .body(reply.toResponseBody("application/json".toMediaType())).build()
        }.build()

    private fun registrar(now: Long = 1_700_000_000_000L) = MobileDeviceRegistrar { now }

    private fun sha512(value: ByteArray) =
        MessageDigest.getInstance("SHA-512").digest(value)

    private fun md5Hex(value: ByteArray) =
        MessageDigest.getInstance("MD5").digest(value)
            .joinToString("") { "%02X".format(it.toInt() and 255) }

    /** Decrypts the wire body with the pinned ttEncrypt layout and verifies the SHA-512 envelope. */
    private fun decrypt(wire: ByteArray): JSONObject {
        assertArrayEquals(byteArrayOf(0x74, 0x63, 0x05, 0x10, 0x00, 0x00), wire.copyOfRange(0, 6))
        val rnd = wire.copyOfRange(6, 38)
        val encrypted = wire.copyOfRange(38, wire.size)
        assertEquals(0, encrypted.size % 16)
        val derived = sha512(sha512(rnd) + salt)
        val padded = Cipher.getInstance("AES/CBC/NoPadding").run {
            init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(derived.copyOfRange(0, 16), "AES"),
                IvParameterSpec(derived.copyOfRange(16, 32)),
            )
            doFinal(encrypted)
        }
        val pad = padded.last().toInt() and 255
        assertTrue(pad in 1..16)
        assertTrue(padded.takeLast(pad).all { (it.toInt() and 255) == pad })
        val plain = padded.copyOfRange(0, padded.size - pad)
        val data = plain.copyOfRange(64, plain.size)
        assertArrayEquals(plain.copyOfRange(0, 64), sha512(data))
        val json = GZIPInputStream(ByteArrayInputStream(data)).use { it.readBytes().toString(Charsets.UTF_8) }
        return JSONObject(json)
    }

    private fun reply(deviceId: Any = 7000000000001234567L, installId: Any = 7000000000001234568L) =
        JSONObject().put("device_id", deviceId).put("install_id", installId)
            .put("new_user", 1).put("device_token", "ignored").toString()

    @Test fun onePinnedRequestReturnsTheSameGeneratedIdentityItSent() {
        val captured = Captured()
        val now = 1_700_000_000_000L
        val device = registrar(now).register(client(reply(), captured = captured))
        assertEquals(1, captured.calls)

        assertEquals("7000000000001234567", device.deviceId)
        assertEquals("7000000000001234568", device.installId)
        assertEquals("", device.secretKey)
        assertTrue(device.cdid.isNotBlank())
        assertTrue(device.openudid.isNotBlank())

        val body = decrypt(captured.body)
        assertEquals("ss_app_log", body.getString("magic_tag"))
        assertEquals(now, body.getLong("_gen_time"))
        val header = body.getJSONObject("header")
        assertEquals(8662, header.getInt("aid"))
        assertEquals(73932, header.getInt("version_code"))
        assertEquals(73932, header.getInt("update_version_code"))
        assertEquals(73932, header.getInt("manifest_version_code"))
        assertEquals("7.3.9.32", header.getString("app_version"))
        assertEquals("7.3.9.32", header.getString("app_version_minor"))
        assertEquals("com.phoenix.read", header.getString("package"))
        assertEquals("update_64", header.getString("channel"))
        assertEquals("25053RT47C", header.getString("device_model"))
        assertEquals(36, header.getInt("os_api"))
        assertEquals(device.cdid, header.getString("cdid"))
        assertEquals(device.openudid, header.getString("openudid"))
        // Pinned contract: os_version must be a JSON string, never a number.
        assertEquals("16", header.get("os_version"))
        assertTrue(header.get("os_version") is String)

        // Remaining fields mirror the accepted registration control run exactly.
        assertEquals("Redmi", header.getString("device_brand"))
        assertEquals("Redmi", header.getString("device_manufacturer"))
        assertEquals("arm64-v8a", header.getString("cpu_abi"))
        assertEquals("1280x2772", header.getString("resolution"))
        assertEquals(520, header.getInt("density_dpi"))
        assertEquals("mdpi", header.getString("display_density"))
        assertEquals("b5e187f", header.getString("release_build"))
        assertEquals("eng.root.20231116.160340", header.getString("rom"))
        assertEquals("W528JS release-keys", header.getString("rom_version"))
        assertEquals(now, header.getLong("apk_first_install_time"))
        assertEquals("phone", header.getJSONObject("custom").getString("dragon_device_type"))
        // Anonymous registration: no pre-existing identity is sent in the body.
        assertFalse(header.has("device_id"))
        assertFalse(header.has("install_id"))
        assertFalse(header.has("iid"))

        val request = captured.request
        assertEquals("POST", request.method)
        assertEquals("log5-applog-lf.fqnovel.com", request.url.host)
        assertEquals("/service/2/device_register/", request.url.encodedPath)
        assertEquals(4, request.url.querySize)
        assertEquals("a", request.url.queryParameter("tt_data"))
        assertEquals("8d40f833_2026-03-03", request.url.queryParameter("cronet_version"))
        assertEquals("4.2.243.31-douyin", request.url.queryParameter("ttnet_version"))
        assertEquals("1", request.url.queryParameter("use_store_region_cookie"))
        assertEquals("application/octet-stream;tt-data=a", request.body?.contentType().toString())
        assertEquals("application/octet-stream;tt-data=a", request.header("Content-Type"))
        assertEquals("gzip", request.header("log-encode-type"))
        assertEquals("8662", request.header("x-ss-dp"))
        assertEquals(now.toString(), request.header("x-ss-req-ticket"))
        assertEquals(md5Hex(captured.body), request.header("x-ss-stub"))
        for (name in listOf("x-khronos", "x-helios", "x-medusa", "x-gorgon", "x-ladon", "x-argus")) {
            assertNull(request.header(name))
        }
    }

    @Test fun generatedIdentityIsStableWithinOneCallAndDiffersAcrossCalls() {
        val first = registrar().register(client(reply()))
        val second = registrar().register(client(reply()))
        assertEquals("", first.secretKey)
        assertEquals("", second.secretKey)
        // One cdid/openudid pair per call: values are reused for the body but not across calls.
        assertTrue(first.cdid != second.cdid || first.openudid != second.openudid)
    }

    @Test fun headerProfileHasNoEncodingOrSignatureAdditions() {
        val body = registrar().payload("c", "o", 1L)
        val headers = registrar().headers(body, 1L).keys.map { it.lowercase() }
        assertFalse(headers.any { it == "accept-encoding" })
        assertFalse(headers.any { it.startsWith("x-khronos") || it.startsWith("x-helios") })
        assertFalse(headers.any { it.startsWith("x-medusa") || it.startsWith("x-gorgon") })
        assertEquals("2", registrar().headers(body, 1L)["sdk-version"])
    }

    @Test fun requestCountIsOneAndSharedBudgetConsumesExactlyOneSlot() {
        val budget = PlaybackRequestBudget()
        val captured = Captured()
        val device = budget.run { registrar().register(client(reply(), captured = captured)) }
        assertEquals("7000000000001234567", device.deviceId)
        assertEquals(1, captured.calls)
        assertEquals(1, budget.traceSnapshot().requests)
        assertEquals(0, budget.traceSnapshot().retries)
    }

    @Test fun exhaustedBudgetFailsBeforeAnyHttp() {
        var calls = 0
        val client = Builder().addInterceptor { calls++; throw AssertionError("no http") }.build()
        val budget = PlaybackRequestBudget(maxRequests = 0)
        assertThrows(PlaybackBudgetExceededException::class.java) {
            budget.run { registrar().register(client) }
        }
        assertEquals(0, calls)
    }

    @Test fun canceledScopeSendsNoHttp() {
        var calls = 0
        val client = Builder().addInterceptor { calls++; throw AssertionError("no http") }.build()
        val scope = RequestScope()
        scope.cancel()
        assertThrows(IOException::class.java) {
            scope.run { registrar().register(client) }
        }
        assertEquals(0, calls)
    }

    @Test fun expiredDeadlineIsTerminalBeforeHttp() {
        var calls = 0
        val client = Builder().addInterceptor { calls++; throw AssertionError("no http") }.build()
        assertThrows(PlaybackDeadlineException::class.java) {
            RequestScope.withDeadline(0) { registrar().register(client) }
        }
        assertEquals(0, calls)
    }

    @Test fun replyOfExactly64KiBIsAcceptedAndOneByteMoreIsRejected() {
        val head = "{\"device_id\":7000000000001234567,\"install_id\":7000000000001234568,\"pad\":\""
        val tail = "\"}"
        val exact = head + "x".repeat(MobileDeviceRegistrar.MAX_RESPONSE_BYTES - head.length - tail.length) + tail
        assertEquals(MobileDeviceRegistrar.MAX_RESPONSE_BYTES, exact.toByteArray().size)
        assertEquals("7000000000001234567", registrar().register(client(exact)).deviceId)

        val oversize = exact + " "
        assertEquals(MobileDeviceRegistrar.MAX_RESPONSE_BYTES + 1, oversize.toByteArray().size)
        assertThrows(IOException::class.java) { registrar().register(client(oversize)) }
    }

    @Test fun nonSuccessStatusIsRejectedWithoutIdentity() {
        assertThrows(IOException::class.java) { registrar().register(client("{}", code = 502)) }
    }

    @Test fun identifiersMustBeStrictPositiveDecimals() {
        for (bad in listOf(
            """{"device_id":0,"install_id":7000000000001234568}""",
            """{"device_id":"0","install_id":"7000000000001234568"}""",
            """{"device_id":"abc","install_id":"7000000000001234568"}""",
            """{"device_id":-1,"install_id":7000000000001234568}""",
            """{"install_id":7000000000001234568}""",
            """{"device_id":7000000000001234567}""",
            """{"device_id":"","install_id":"7000000000001234568"}""",
            """{"device_id":null,"install_id":7000000000001234568}""",
        )) {
            assertThrows(IOException::class.java) { registrar().register(client(bad)) }
        }
        // A JSON null `_str` field falls back to the numeric field instead of poisoning the reply.
        val nullStr = """{"device_id_str":null,"device_id":7000000000001234567,"install_id":7000000000001234568}"""
        assertEquals("7000000000001234567", registrar().register(client(nullStr)).deviceId)
        // `_str` fields win when both forms are present, as issued by the live endpoint.
        val prefixed = """{"device_id":0,"install_id":0,"device_id_str":7000000000001234567,"install_id_str":7000000000001234568}"""
        assertEquals("7000000000001234567", registrar().register(client(prefixed)).deviceId)
    }
}
