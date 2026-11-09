package com.hongguotv.core

import okhttp3.Call
import okhttp3.Callback
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Timeout
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.TimeUnit

class PlaybackDeadlineTest {
    private class FakeCall : Call {
        var started = false
        override fun request() = Request.Builder().url("https://example.invalid/video").build()
        override fun execute(): Response {
            started = true
            return Response.Builder().request(request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body("data".toResponseBody()).build()
        }
        override fun enqueue(responseCallback: Callback) = error("not used")
        override fun cancel() {}
        override fun isExecuted() = started
        override fun isCanceled() = false
        override fun timeout() = Timeout.NONE
        override fun clone(): Call = FakeCall()
    }

    @Test fun expiredDeadlineIsTerminalAndAlwaysCleared() {
        assertThrows(PlaybackDeadlineException::class.java) {
            RequestScope.withDeadline(0) {
                assertThrows(PlaybackDeadlineException::class.java) { RequestScope.ensureActive() }
            }
        }
        RequestScope.ensureActive()
        assertNull(RequestScope.remainingMillis())
        assertTrue(PlaybackFailure.terminal(PlaybackDeadlineException()))
    }

    @Test fun normalReturnAfterDeadlineIsRejected() {
        // 请求体读取可能刚好在上限前结束，解析结果也不能越过绝对截止时间返回。
        assertThrows(PlaybackDeadlineException::class.java) {
            RequestScope.withDeadline(0) { "parsed model" }
        }
        RequestScope.ensureActive()
        assertNull(RequestScope.remainingMillis())
    }

    @Test fun lockWaitIsClippedToRemainingDeadline() {
        val store = Store(PlayerDevice("123", "456", "old", "", "").toJson())
        val fresh = PlayerDevice("789", "987", "new", "", "")
        val candidateEntered = java.util.concurrent.CountDownLatch(1)
        val releaseCandidate = java.util.concurrent.CountDownLatch(1)
        var now = 0L
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { fresh }, monotonicMillis = { now })
        val pool = java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val owner = pool.submit<String> {
                source.recover { device ->
                    if (device.deviceId == "123") throw DeviceRejectedException("old")
                    candidateEntered.countDown(); releaseCandidate.await(); "candidate"
                }
            }
            assertTrue(candidateEntered.await(2, TimeUnit.SECONDS))
            val started = System.nanoTime()
            val failure = pool.submit<Throwable?> {
                try {
                    RequestScope.withDeadline(120) { source.recover<String> { throw DeviceRejectedException("old") } }
                    null
                } catch (error: Throwable) { error }
            }.get(3, TimeUnit.SECONDS)
            val elapsed = (System.nanoTime() - started) / 1_000_000
            assertTrue("锁等待必须裁剪到剩余截止时间，实际 $failure", failure is PlaybackDeadlineException)
            assertTrue("等待不应越过截止时间太多，实际 ${elapsed}ms", elapsed < 1_000)
            releaseCandidate.countDown()
            assertEquals("candidate", owner.get(2, TimeUnit.SECONDS))
        } finally { releaseCandidate.countDown(); pool.shutdownNow() }
    }

    private class Store(private val value: String) : PlayerDeviceStore {
        override fun load() = value
        override fun save(value: String?) {}
    }

    @Test fun deadlineStopsCallBeforeItStarts() {
        val call = FakeCall()
        assertThrows(PlaybackDeadlineException::class.java) {
            RequestScope.withDeadline(0) {
                RequestScope.execute(call) { it.body!!.string() }
            }
        }
        assertFalse(call.started)
    }

    @Test fun budgetSetsFreshDeadlinePerAcquisitionAndRetainsCounters() {
        assertNull(RequestScope.remainingMillis())
        val budget = PlaybackRequestBudget(3, 2)
        budget.run {
            val remaining = RequestScope.remainingMillis()
            assertNotNull(remaining)
            assertTrue(remaining!! in 1..PlaybackRequestBudget.ACQUISITION_TIMEOUT_MILLIS)
            budget.request(); budget.request()
        }
        assertNull(RequestScope.remainingMillis())
        // 同一预算再次获取时重新计时，但请求计数继续累计。
        budget.run {
            assertNotNull(RequestScope.remainingMillis())
            budget.request()
            assertThrows(PlaybackBudgetExceededException::class.java) { budget.request() }
        }
    }

    @Test fun retrySleepIsClippedToRemainingDeadline() {
        val delays = mutableListOf<Long>()
        assertThrows(PlaybackDeadlineException::class.java) {
            RequestScope.withDeadline(60) {
                PlaybackReadRetry { millis -> delays += millis; Thread.sleep(millis) }.read { "" }
            }
        }
        assertEquals(1, delays.size)
        assertTrue("等待应裁剪到剩余时间", delays.single() in 1..60)
    }

    @Test fun retrySleepThrowsCancellationWhenInterrupted() {
        val thread = Thread.currentThread()
        assertThrows(IOException::class.java) {
            RequestScope.sleepWithinDeadline(700) { thread.interrupt() }
        }
        assertTrue(Thread.interrupted())
    }
}
