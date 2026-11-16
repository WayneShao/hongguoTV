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

/**
 * 已有可播结果后的可选线路不得把一次成功降级成失败：内层期限/预算耗尽时
 * 只能交出已校验的行，且取消与总截止点仍然优先。
 */
class PlaybackResultProtectionTest {
    private class FakeStore(private val value: String) : PlayerDeviceStore {
        val saved = mutableListOf<String?>()
        override fun load(): String? = value
        override fun save(stored: String?) { saved += stored }
    }

    private companion object {
        const val deviceJson = """{"device_id":"7000000000001234567","install_id":"7000000000007654321","cdid":"c-uuid","openudid":"0123456789abcdef","secret_key":""}"""
        const val SHORTPLAY_PATH = "/novel/player/video_model/v1/"
        const val PHOENIX_PATH = "/novel/player/multi_video_model/v1/"
        const val PRELOAD_PATH = "/novel/player/multi_video_model/preload/v1/"
        /** 非请求清晰度，迫使线路在拿到行之后继续看下一条线路。 */
        const val FALLBACK_ONLY = """{"code":0,"data":{"video_model":{"video_list":{"v":{"vheight":480,"codec_type":"h264","main_url":"https://example.com/480"}}}}}"""
        const val FALLBACK_HOST = "fallback.example.com"
        /** 0 号线路先给出可播的 480P 行，再带一个能补出 720P 的 fallback_api。 */
        const val INLINE_480_WITH_FALLBACK = """{"code":0,"data":{"video_model":{"video_list":{"v":{"vheight":480,"codec_type":"h264","main_url":"https://example.com/480"}},"fallback_api":{"fallback_api":"https://fallback.example.com/play"}}}}"""
        /** 没有任何内联行，只有 fallback_api：此时补查是必需路径而不是可选路径。 */
        const val NO_INLINE_WITH_FALLBACK = """{"code":0,"data":{"video_model":{"video_list":{},"fallback_api":{"fallback_api":"https://fallback.example.com/play"}}}}"""
        const val FALLBACK_720 = """{"video_info":{"data":{"video_list":{"v":{"vheight":720,"codec_type":"h264","main_url":"https://example.com/720"}}}}}"""
        const val EMPTY_DATA = """{"code":0,"data":{}}"""
    }

    @After fun clearStore() { ContentRepository.deviceStore = null }

    private fun repository(respond: (Request) -> String): Pair<ContentRepository, FakeStore> {
        val store = FakeStore(deviceJson)
        ContentRepository.deviceStore = store
        return ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body(respond(chain.request()).toResponseBody()).build()
        }.build()) to store
    }

    @Test fun optionalRouteIsSkippedWhenTooLittleTimeRemains() {
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += request.url.encodedPath
            FALLBACK_ONLY
        }
        // 900ms 总预算低于「返回预留 800ms + 最小工作 300ms」，可选补查必须整段跳过，
        // 不发一次注定超时的请求。
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 900))
        assertEquals("https://example.com/480", result.url)
        assertEquals("480P", result.quality)
        assertEquals(listOf(SHORTPLAY_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun optionalRouteDeadlineKeepsValidatedRowsAndSkipsLegacyFallback() {
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += request.url.encodedPath
            when {
                request.url.encodedPath == SHORTPLAY_PATH -> FALLBACK_ONLY
                // 可选线路的内层期限到点，等价于执行层抛出的截止异常。
                else -> throw PlaybackDeadlineException()
            }
        }
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000))
        assertEquals("https://example.com/480", result.url)
        // 内层期限到点即结束本轮：不再探测后续线路，更不得退化为 legacy 回退或注册新身份。
        assertEquals(listOf(SHORTPLAY_PATH, PHOENIX_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun optionalRouteBudgetExhaustionKeepsValidatedRows() {
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += request.url.encodedPath
            FALLBACK_ONLY
        }
        // 首条线路已经用掉唯一一次请求：可选线路不得再发请求，也不得吞掉结果。
        val result = repo.stream("123", 720, PlaybackRequestBudget(1, 0, 4_000))
        assertEquals("https://example.com/480", result.url)
        assertEquals(listOf(SHORTPLAY_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun cancellationDuringOptionalRouteNeverReturnsStaleRows() {
        val scope = RequestScope()
        val (repo, _) = repository { request ->
            when (request.url.encodedPath) {
                SHORTPLAY_PATH -> FALLBACK_ONLY
                else -> { scope.cancel(); "" }
            }
        }
        val failure = try {
            scope.run { repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000)) }
            null
        } catch (error: Throwable) { error }
        assertNotNull("取消后不得返回旧行", failure)
        assertTrue("取消必须作为终止条件抛出，实际 $failure", PlaybackFailure.terminal(failure!!))
    }

    @Test fun optionalRouteKeepsRowsWhenOptionalFetchTimesOutOnWire() {
        // 传输层超时也只是可选线路的失败：已校验的行仍然可用，且不得归为空身份轮换。
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += request.url.encodedPath
            when (request.url.encodedPath) {
                SHORTPLAY_PATH -> FALLBACK_ONLY
                else -> throw java.net.SocketTimeoutException("optional route slow")
            }
        }
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000))
        assertEquals("https://example.com/480", result.url)
        assertEquals(listOf(SHORTPLAY_PATH, PHOENIX_PATH, PRELOAD_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun inlineRowSkipsOptionalFallbackWhenNoReturnTimeRemains() {
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += if (request.url.host == FALLBACK_HOST) "fallback" else request.url.encodedPath
            if (request.url.host == FALLBACK_HOST) FALLBACK_720 else INLINE_480_WITH_FALLBACK
        }
        // 900ms 总预算低于「返回预留 + 最小工作」：同一次尝试里补清晰度的 fallback_api
        // 也属于可选补查，必须整段跳过，直接交出已解析并校验过的 480P 行。
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 900))
        assertEquals("https://example.com/480", result.url)
        assertEquals("480P", result.quality)
        assertEquals(listOf(SHORTPLAY_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun inlineRowSurvivesExhaustedBudgetWithoutOptionalFallback() {
        val paths = mutableListOf<String>()
        val (repo, store) = repository { request ->
            paths += if (request.url.host == FALLBACK_HOST) "fallback" else request.url.encodedPath
            if (request.url.host == FALLBACK_HOST) FALLBACK_720 else INLINE_480_WITH_FALLBACK
        }
        // 唯一一次请求预算已被 0 号线路用掉：fallback_api 不得再发一次必败请求，直接交出 480P。
        val result = repo.stream("123", 720, PlaybackRequestBudget(1, 0, 4_000))
        assertEquals("https://example.com/480", result.url)
        assertEquals(listOf(SHORTPLAY_PATH), paths)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun fallbackDeadlineKeepsInlineRowsBeforeOuterDeadline() {
        var childRemaining: Long? = null
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) {
                // 补清晰度必须跑在「短于外层」的真实子截止点下，而不是被无限放行。
                childRemaining = RequestScope.remainingMillis()
                Thread.sleep(1_500)
                FALLBACK_720
            } else INLINE_480_WITH_FALLBACK
        }
        val started = System.nanoTime()
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 2_000))
        val elapsed = (System.nanoTime() - started) / 1_000_000
        assertEquals("https://example.com/480", result.url)
        assertEquals("480P", result.quality)
        assertTrue("补查必须短于外层总预算，实际 ${childRemaining}ms", childRemaining != null && childRemaining!! in 1..1_200)
        assertTrue("外层截止点仍有效时必须及时返回，实际 ${elapsed}ms", elapsed < 2_000)
        assertNull("子截止点必须恢复", RequestScope.remainingMillis())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun rowsParsedAfterOuterDeadlineAreNeverReturned() {
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) { Thread.sleep(1_800); FALLBACK_720 } else INLINE_480_WITH_FALLBACK
        }
        // 补查超时后又把响应体解析完：此刻外层总截止点已过，任何行都不得被当作有效结果返回。
        val failure = try { repo.stream("123", 720, PlaybackRequestBudget(8, 0, 1_400)); null } catch (error: Throwable) { error }
        assertTrue("外层截止点已过时不得交出任何行，实际 $failure", failure is PlaybackDeadlineException)
        assertNull(RequestScope.remainingMillis())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun fallbackIsStillRequiredWithoutInlineRows() {
        val fallbackCalls = mutableListOf<String>()
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) { fallbackCalls += request.url.toString(); FALLBACK_720 }
            else NO_INLINE_WITH_FALLBACK
        }
        // 没有内联行时补查就是唯一来源，必须照常发起；不能因为可选化而丢掉整线路。
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000))
        assertEquals("https://example.com/720", result.url)
        assertEquals(1, fallbackCalls.size)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun timelyFallbackImprovesQuality() {
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) FALLBACK_720 else INLINE_480_WITH_FALLBACK
        }
        // 预算与时间都充足时，补查仍应把 480P 提升到请求的 720P。
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000))
        assertEquals("https://example.com/720", result.url)
        assertEquals("720P", result.quality)
        assertTrue(result.availableQualities.contains(720))
        assertTrue(store.saved.isEmpty())
    }

    @Test fun requiredFallbackStillRunsInsideSmallRemainingBudget() {
        var fallbackCalls = 0
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) { fallbackCalls++; FALLBACK_720 }
            else NO_INLINE_WITH_FALLBACK
        }
        // 没有可播行时，剩余时间再少也不能用“跳过可选补查”的规则跳过唯一取流来源。
        val result = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 900))
        assertEquals("https://example.com/720", result.url)
        assertEquals(1, fallbackCalls)
        assertTrue(store.saved.isEmpty())
    }

    @Test fun optionalFallbackTimeoutDoesNotCoolTheRouteAsAnEmptyResult() {
        val paths = mutableListOf<String>()
        var slowFallback = true
        val (repo, store) = repository { request ->
            if (request.url.host == FALLBACK_HOST) { paths += "fallback"; if (slowFallback) Thread.sleep(900); FALLBACK_720 }
            else { paths += request.url.encodedPath; if (request.url.encodedPath == SHORTPLAY_PATH) INLINE_480_WITH_FALLBACK else EMPTY_DATA }
        }
        // 第一次：补查耗尽子期限，只交出 480P。
        assertEquals("https://example.com/480", repo.stream("123", 720, PlaybackRequestBudget(8, 0, 1_400)).url)
        assertEquals(listOf(SHORTPLAY_PATH, "fallback"), paths)
        paths.clear(); slowFallback = false
        // 超时不是空响应：0 号线路不得因此进入冷却被跳过，第二次仍应从它开始并补到 720P。
        val improved = repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000))
        assertEquals("https://example.com/720", improved.url)
        assertEquals("可选补查超时不得冷却线路，实际 $paths", SHORTPLAY_PATH, paths.first())
        assertTrue(store.saved.isEmpty())
    }

    @Test fun cancellationDuringOptionalFallbackNeverReturnsInlineRows() {
        val scope = RequestScope()
        val (repo, _) = repository { request ->
            if (request.url.host == FALLBACK_HOST) { scope.cancel(); FALLBACK_720 } else INLINE_480_WITH_FALLBACK
        }
        val failure = try { scope.run { repo.stream("123", 720, PlaybackRequestBudget(8, 0, 4_000)) }; null }
            catch (error: Throwable) { error }
        assertNotNull("取消后不得返回已解析的行", failure)
        assertTrue("取消必须保持终止语义，实际 $failure", PlaybackFailure.terminal(failure!!))
    }
}
