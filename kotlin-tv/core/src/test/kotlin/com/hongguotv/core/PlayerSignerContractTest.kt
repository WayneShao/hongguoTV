// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.junit.Assert.*
import org.junit.Test

/**
 * Playback/registration x-argus composition contract, pinned to upstream
 * novel-sound cc6eb588 rust upstream/client.rs (playback_sign fills field 16 with
 * the query `iid`; device registration leaves field 16 empty).
 *
 * Synthetic fixtures only: no real credentials, device ids or captured traffic.
 */
class PlayerSignerContractTest {

    // Synthetic request shaped like the fanqie batch_full read endpoint.
    private val body = """{"items":[{"book_id":"7561234567890123456","chapter_id":1}]}""".toByteArray()

    private fun syntheticRequest(): Request = Request.Builder()
        .url(
            "https://api5-normal-sinfonlineb.fqnovel.com/reading/reader/batch_full/v" +
                "?aid=1967&device_id=dev_synthetic_0001&iid=7561234567890123456" +
                "&version_name=6.5.1.32&version_code=65132&novel_version=65132&platform=android",
        )
        .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
        .build()

    /** Length-delimited bean fields (13/14/5/16) from a decoded 551 blob. */
    private fun fields(bean: ByteArray): Map<Int, ByteArray> {
        val out = mutableMapOf<Int, ByteArray>()
        var at = 0
        fun varint(): Long {
            var value = 0L
            var shift = 0
            while (true) {
                val b = bean[at++].toInt() and 255
                value = value or ((b and 127).toLong() shl shift)
                shift += 7
                if (b and 128 == 0) return value
            }
        }
        while (at < bean.size) {
            // decrypt551 returns the PKCS#7-aligned bean; stop at the inner padding tail.
            val pad = bean[bean.size - 1].toInt() and 255
            if (pad in 1..16 && at + pad == bean.size && (at until bean.size).all { (bean[it].toInt() and 255) == pad }) break
            val key = varint()
            val field = (key ushr 3).toInt()
            when ((key and 7).toInt()) {
                0 -> varint()
                1 -> at += 8
                2 -> {
                    val len = varint().toInt()
                    out[field] = bean.copyOfRange(at, at + len)
                    at += len
                }
                5 -> at += 4
                else -> error("unexpected wire type in synthetic bean")
            }
        }
        return out
    }

    private fun decodedArgus(headers: Map<String, String>): Map<Int, ByteArray> =
        fields(requireNonNull("x-argus", Argus.decrypt551(headers["x-argus"]!!)))

    private fun requireNonNull(what: String, value: ByteArray?): ByteArray {
        assertNotNull("$what decodes back to a bean", value)
        return value!!
    }

    @Test fun playbackHeadersPutQueryIidInArgusField16() {
        val request = syntheticRequest()
        val query = request.url.encodedQuery!!
        val headers = PlayerSigner.headers(query, body, 1_700_000_000)
        val bean = decodedArgus(headers)

        assertEquals("x-ss-stub is MD5 of the body", digest("MD5", body).hex(), headers["x-ss-stub"])
        // field 16 is the playback install/iid slot: upstream playback_sign sets it to query iid.
        assertEquals("7561234567890123456", bean[16]!!.decodeToString())
        assertEquals("dev_synthetic_0001", bean[5]!!.decodeToString())
        // field 14 must cover the exact encoded query that is actually sent.
        assertArrayEquals(sm3(query.toByteArray()).copyOf(6), bean[14])
        // field 13 must cover the body stub (MD5 of the sent body).
        assertArrayEquals(sm3(hex(digest("MD5", body).hex())).copyOf(6), bean[13])
        assertNotNull(Argus.recover551Field3(headers["x-argus"]!!))
    }

    @Test fun registrationHeadersKeepArgusField16EmptyAndCarryGorgon() {
        val request = syntheticRequest()
        val query = request.url.encodedQuery!!
        val headers = PlayerSigner.registrationHeaders(query, body, 1_700_000_000)
        val bean = decodedArgus(headers)

        // Registration keeps the upstream default empty field 16 even though the query carries iid.
        assertEquals("", bean[16]!!.decodeToString())
        assertEquals("dev_synthetic_0001", bean[5]!!.decodeToString())
        assertArrayEquals(sm3(query.toByteArray()).copyOf(6), bean[14])
        assertArrayEquals(sm3(hex(digest("MD5", body).hex())).copyOf(6), bean[13])
        val gorgon = headers["x-gorgon"]
        assertNotNull("registration keeps the legacy x-gorgon", gorgon)
        assertTrue("x-gorgon is non-empty", gorgon!!.isNotEmpty())
        assertNotNull(headers["x-ladon"])
        assertNotNull(Argus.recover551Field3(headers["x-argus"]!!))
    }
}
