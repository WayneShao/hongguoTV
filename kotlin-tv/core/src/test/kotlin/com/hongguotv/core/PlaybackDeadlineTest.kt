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
        // 注入等待接缝后断言真实传入的等待时长，避免用宽松墙钟阈值掩盖固定 100ms 的缺陷。
        val store = Store(PlayerDevice("123", "456", "old", "", "").toJson())
        val waits = mutableListOf<Long>()
        val source = PlayerDeviceSource(
            OkHttpClient(),
            store = { store },
            register = { PlayerDevice("789", "987", "new", "", "") },
            monotonicMillis = { 0L },
            awaitLock = { lock, wait -> waits += wait; lock.lock(); true },
        )
        val result = RequestScope.withDeadline(40) {
            source.recover<String> { device ->
                if (device.deviceId == "123") throw DeviceRejectedException("old") else "candidate"
            }
        }
        assertEquals("candidate", result)
        assertEquals(1, waits.size)
        assertTrue("等待必须裁剪到剩余截止时间，实际 ${waits.single()}ms", waits.single() <= 40)
        assertTrue(waits.single() > 0)
    }

    @Test fun exhaustedDeadlineDuringLockWaitNeverRegistersOrFallsBack() {
        val store = Store(PlayerDevice("123", "456", "old", "", "").toJson())
        val waits = mutableListOf<Long>()
        var registrations = 0
        val source = PlayerDeviceSource(
            OkHttpClient(),
            store = { store },
            register = { registrations++; PlayerDevice("789", "987", "new", "", "") },
            monotonicMillis = { 0L },
            awaitLock = { _, wait -> waits += wait; Thread.sleep(wait); false },
        )
        val failure = try {
            RequestScope.withDeadline(40) { source.recover<String> { throw DeviceRejectedException("old") } }
            null
        } catch (error: Throwable) { error }
        assertTrue("锁等待超时必须终止，实际 $failure", failure is PlaybackDeadlineException)
        assertEquals(0, registrations)
        assertTrue(waits.isNotEmpty())
        assertTrue("每次等待都应裁剪到剩余截止时间，实际 $waits", waits.all { it <= 40 })
    }

    @Test fun canceledAuxiliaryRegistrationStopsKeyRetriesAndSleep() {
        val scope = RequestScope()
        val delays = mutableListOf<Long>()
        var keyRequests = 0
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val request = chain.request()
            when {
                request.url.encodedPath.contains("device_register") ->
                    Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                        .body("""{"device_id":"789","install_id":"987"}""".toResponseBody()).build()
                request.url.encodedPath.contains("app_alert_check") -> {
                    // 取消可能只表现为普通 IOException，线程不会中断。
                    scope.cancel()
                    throw IOException("Canceled")
                }
                else -> { keyRequests++; throw IOException("意外请求 ${request.url}") }
            }
        }.build()
        val registrar = DeviceRegistrar(sleep = { delays += it })
        val failure = try {
            scope.run { registrar.register(http) }
            null
        } catch (error: Throwable) { error }
        assertNotNull(failure)
        assertTrue("取消必须作为终止条件抛出，实际 $failure", PlaybackFailure.terminal(failure!!))
        assertEquals(0, keyRequests)
        assertTrue("取消后不得进入重试等待", delays.isEmpty())
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
