// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

/**
 * 现场证据：首次获取把同一条已注册线路的空白重试当成了主要手段，7/8 次请求都花在
 * SHORTPLAY 空白重试 + 三条已注册线路 + TAB + LEGACY 上，第二次获取只剩 1 次，
 * emptyPoints 停在 3，候选身份永远拿不到验证机会。
 *
 * 这里的断言固定新的分配规则：已注册 POST 线路不重复花同线路空白重试，同一预算里
 * LEGACY 已确定性为空后不再重复，且预算不足时不注册无法验证的候选。
 */
class PlaybackRecoveryAllocationTest {
    private companion object {
        const val deviceJson = """{"device_id":"7000000000001234567","install_id":"7000000000007654321","cdid":"c-uuid","openudid":"0123456789abcdef","secret_key":""}"""
        const val candidateDeviceId = "7890000000000000001"
        const val candidateInstallId = "9870000000000000001"
        const val SHORTPLAY_PATH = "/novel/player/video_model/v1/"
        const val PHOENIX_PATH = "/novel/player/multi_video_model/v1/"
        const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1/"
    }

    private class FakeStore(var value: String? = deviceJson) : PlayerDeviceStore {
        val saved = mutableListOf<String?>()
        override fun load(): String? = value
        override fun save(stored: String?) { saved += stored; value = stored }
    }

    @After fun clearStore() { ContentRepository.deviceStore = null }

    private fun repository(store: FakeStore, respond: (Request) -> String): ContentRepository {
        ContentRepository.deviceStore = store
        return ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build(), routeClock = { 0L })
    }

    /** 分类只看路径/查询，不把 URL 内容带进断言。 */
    private fun kind(request: Request): String = when {
        request.url.encodedPath.contains("device_register") -> "REGISTER"
        request.url.encodedPath.contains("app_alert_check") -> "ALERT"
        request.url.encodedPath.contains("registerkey") -> "KEY"
        request.url.encodedPath.contains("video_tab") -> "TAB"
        request.url.host.endsWith("fqnovel.com") && request.url.queryParameter("_rticket") != null -> "LEGACY"
        request.url.encodedPath == SHORTPLAY_PATH -> "SHORTPLAY"
        request.url.encodedPath == PHOENIX_PATH -> "PHOENIX"
        request.url.encodedPath == PRELOAD_PATH -> "PRELOAD"
        else -> "OTHER"
    }

    private val successModel = """{"code":0,"data":{"123":{"video_model":{"video_list":{"v":{"vheight":720,"codec_type":"h264","main_url":"https://example.com/candidate"}}}}}}"""

    /** 用主密钥加密注册返回的 key，避免 registerkey 重试等待拖慢测试（不改变被测行为）。 */
    private fun encryptedRegisterKey(deviceId: String): String {
        val iv = ByteArray(16) { 7 }
        val cipher = javax.crypto.Cipher.getInstance("AES/CBC/NoPadding").apply {
            init(
                javax.crypto.Cipher.ENCRYPT_MODE,
                javax.crypto.spec.SecretKeySpec(hex("ac25c67ddd8f38c1b37a2348828e222e"), "AES"),
                javax.crypto.spec.IvParameterSpec(iv),
            )
        }
        val encrypted = cipher.doFinal(DeviceRegistrar.pkcs7Pad(hex(DeviceRegistrar.reverseHex(deviceId))))
        return (iv + encrypted).base64()
    }

    private fun responder(
        mode: String,
        kinds: MutableList<String>,
        candidateSucceeds: Boolean = true,
        onRequest: (Request) -> Unit = {},
    ): (Request) -> String = { request ->
        val kind = kind(request)
        kinds += kind
        onRequest(request)
        when {
            kind == "REGISTER" -> """{"device_id":"$candidateDeviceId","install_id":"$candidateInstallId"}"""
            kind == "KEY" -> """{"data":{"key":"${encryptedRegisterKey(candidateDeviceId)}"}}"""
            kind == "ALERT" -> "{}"
            request.url.host.endsWith("fqnovel.com") || kind == "TAB" -> {
                val candidate = request.url.queryParameter("device_id") == candidateDeviceId
                when {
                    // 候选身份在首次验证时就应该拿到可播结果，否则本修复就只是把预算换了个地方烧。
                    candidate && mode == "blank" && candidateSucceeds -> successModel
                    kind == "TAB" -> ""
                    mode == "blank" -> ""
                    else -> """{"code":0,"data":{}}"""
                }
            }
            else -> "{}"
        }
    }

    @Test fun allBlankFirstAndSecondAcquisitionInOneBudgetLeaveRoomForValidatedCandidate() {
        val store = FakeStore()
        val kinds = mutableListOf<String>()
        var registrations = 0
        var candidateRequest: Int? = null
        val repo = repository(store, responder("blank", kinds) { request ->
            if (kind(request) == "REGISTER") registrations++
            if (kind(request) == "SHORTPLAY" && request.url.queryParameter("device_id") == candidateDeviceId)
                candidateRequest = PlaybackRequestBudget.current.get()?.traceSnapshot()?.requests
        })
        val budget = PlaybackRequestBudget() // 线上默认 8 请求 / 2 重试 / 8 秒

        // 首次获取：三条已注册线路 + TAB + LEGACY，各一次；不再用空白重试烧掉其余预算。
        assertThrows(EmptyPlaybackException::class.java) { repo.stream("123", 720, budget) }
        assertEquals(5, budget.traceSnapshot().requests)
        assertEquals(0, budget.traceSnapshot().retries)
        assertEquals(0, registrations)
        assertEquals(listOf("SHORTPLAY", "PHOENIX", "PRELOAD", "TAB", "LEGACY"), kinds)

        // 第二次获取复用同一预算：冷却半轮只探测一条线路，LEGACY 已确定性为空不再重复。
        assertThrows(EmptyPlaybackException::class.java) { repo.stream("123", 720, budget) }
        assertEquals(6, budget.traceSnapshot().requests)
        assertEquals(0, registrations)

        // 第三次获取：半轮空把累计分推到 4，注册候选后第 8 次请求即完成验证并提交。
        assertEquals("https://example.com/candidate", repo.stream("123", 720, budget).url)
        assertEquals(8, budget.traceSnapshot().requests)
        assertEquals(8, candidateRequest)
        assertEquals(1, registrations)
        assertEquals(candidateDeviceId, PlayerDevice.fromJson(store.value!!)!!.deviceId)
    }

    @Test fun dataEmptyFlowStaysFiniteWithoutRegisteringOrPersisting() {
        val store = FakeStore()
        val kinds = mutableListOf<String>()
        var registrations = 0
        val repo = repository(store, responder("data-empty", kinds) { request ->
            if (kind(request) == "REGISTER") registrations++
        })
        val budget = PlaybackRequestBudget()

        assertThrows(IOException::class.java) { repo.stream("123", 720, budget) }
        assertEquals(5, budget.traceSnapshot().requests)
        assertEquals(1, kinds.count { it == "TAB" })
        assertEquals(0, registrations)

        repeat(2) { assertThrows(IOException::class.java) { repo.stream("123", 720, budget) } }
        // 同一预算最多 8 次请求，TAB 一次性兜底不重复，也没有“空轮”被当成可持久化的健康身份。
        assertEquals(8, budget.traceSnapshot().requests)
        assertEquals(1, kinds.count { it == "TAB" })
        assertEquals(0, registrations)
        assertTrue(store.saved.isEmpty())
        assertEquals(deviceJson, store.value)
    }

    @Test fun repeatedBlankFailuresAcrossBudgetsRegisterAtMostOnceAndNeverPersistUnhealthyIdentity() {
        val store = FakeStore()
        val kinds = mutableListOf<String>()
        var registrations = 0
        val repo = repository(store, responder("blank", kinds, candidateSucceeds = false) { request ->
            if (kind(request) == "REGISTER") registrations++
        })

        val attempts = (1..5).map {
            val budget = PlaybackRequestBudget()
            val failure = try { repo.stream("123", 720, budget); null } catch (error: Exception) { error }
            assertNotNull("重复失败必须以异常结束", failure)
            budget.traceSnapshot().requests to budget.traceSnapshot().retries
        }

        // 每次获取都不越过 8 请求 / 2 重试；首次只花 5 次，第二次冷却半轮只花 2 次。
        attempts.forEach { (requests, retries) ->
            assertTrue("requests=$requests", requests <= 8)
            assertTrue("retries=$retries", retries <= 2)
        }
        assertEquals(listOf(5 to 0, 2 to 0), attempts.take(2))
        // 候选验证失败时不登记为健康身份，也不覆盖已保存身份；冷却期内不再反复注册。
        assertEquals(1, registrations)
        assertTrue(store.saved.isEmpty())
        assertEquals(deviceJson, store.value)
    }

    @Test fun cancellationDuringRecoveryAllocationStopsWithoutRegisteringOrPersisting() {
        val store = FakeStore()
        val scope = RequestScope()
        val kinds = mutableListOf<String>()
        var registrations = 0
        val budget = PlaybackRequestBudget()
        val repo = repository(store, responder("blank", kinds) { request ->
            if (kind(request) == "REGISTER") registrations++
            // 首个已注册线路返回后取消：后续线路、TAB、LEGACY 与注册都不得继续。
            if (kind(request) == "SHORTPLAY") scope.cancel()
        })

        assertThrows(IOException::class.java) { scope.run { repo.stream("123", 720, budget) } }
        assertEquals(1, budget.traceSnapshot().requests)
        assertEquals(listOf("SHORTPLAY"), kinds)
        assertEquals(0, registrations)
        assertTrue(store.saved.isEmpty())
        assertEquals(deviceJson, store.value)
    }

    @Test fun transportRetriesKeepTheirAllowanceOnRegisteredRoutes() {
        val store = FakeStore()
        val kinds = mutableListOf<String>()
        val repo = repository(store) { request ->
            kinds += kind(request)
            throw java.net.SocketTimeoutException("offline")
        }
        val budget = PlaybackRequestBudget(8, 2)

        assertThrows(IOException::class.java) { repo.stream("123", 720, budget) }
        // 传输故障仍按原语义重试：前两条线路各重试一次，重试额度用完后其余线路只探一次。
        assertEquals(6, budget.traceSnapshot().requests)
        assertEquals(2, budget.traceSnapshot().retries)
        assertEquals(2, kinds.count { it == "SHORTPLAY" })
        assertEquals(2, kinds.count { it == "PHOENIX" })
        assertEquals(1, kinds.count { it == "PRELOAD" })
        assertEquals(1, kinds.count { it == "LEGACY" })
        assertEquals(0, kinds.count { it == "TAB" })
        assertTrue(store.saved.isEmpty())
    }
}
