// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

private const val EPISODE = "8600000001"

private fun storedIdentity() =
    PlayerDevice("700000000000000001", "700000000000000002", "cdid-1", "openudid-1", "").toJson()

/** Meta-only row: fresh v5 replies leave root dims/bitrate empty and carry them in video_meta. */
private fun metaTier(height: Int, codec: String = "h264", bitrate: Int = 1000 + height): JSONObject =
    JSONObject()
        .put("main_url", "https://cdn/$height.mp4")
        .put("video_meta", JSONObject().put("vwidth", height + 1000).put("vheight", height)
            .put("bitrate", bitrate).put("codec_type", codec).put("duration", 159.708))

private fun v5Tiers(): Array<JSONObject> = arrayOf(
    metaTier(1080, "h264", 9000),
    metaTier(720, "h265", 5000),
    metaTier(480, "h264", 3000),
    metaTier(360, "h264", 2000),
    metaTier(240, "h264", 1000),
)

private fun mobileReply(id: String, vararg tiers: JSONObject): String {
    val list = JSONObject()
    tiers.forEachIndexed { index, tier -> list.put("video_$index", tier) }
    val model = JSONObject().put("video_duration", 159.708).put("video_list", list)
    return JSONObject().put("code", 0)
        .put("data", JSONObject().put("1004", JSONObject().put(id, JSONObject().put("video_model", model))))
        .toString()
}

/** Path-typed fake upstream: registration, v5 model, and everything else are counted separately. */
private class FakeServer {
    val registrations = AtomicInteger()
    val models = AtomicInteger()
    val others = AtomicInteger()
    @Volatile var registerCode = 200
    @Volatile var registerBody = """{"device_id":800000000000000001,"install_id":800000000000000002}"""
    @Volatile var modelCode = 200
    @Volatile var modelBody = mobileReply(EPISODE, *v5Tiers())
    @Volatile var modelDelayMillis = 0L

    val client: OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val path = request.url.encodedPath
        when {
            path.contains("device_register") -> {
                registrations.incrementAndGet()
                respond(request, registerCode, registerBody)
            }
            path.contains("multi_video_model/player/v1") -> {
                models.incrementAndGet()
                if (modelDelayMillis > 0) Thread.sleep(modelDelayMillis)
                respond(request, modelCode, modelBody)
            }
            else -> {
                others.incrementAndGet()
                respond(request, 404, "{}")
            }
        }
    }.build()

    private fun respond(request: Request, code: Int, body: String): Response =
        Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code).message("M")
            .body(body.toResponseBody("application/json".toMediaType())).build()
}

/**
 * Production wiring contract for the v5 mobile path: first-use registration under the shared lock,
 * success-only persistence, reuse after restart, conservative cooldown, two-slot budget reservation
 * and the routing switch that makes the v5 model the primary acquisition path.
 */
class MobileV5IntegrationTest {
    private val episode = EPISODE

    @Before fun clearMobileStore() {
        ContentRepository.mobileDeviceStore = null
        PlaybackTrace.reset()
    }

    @After fun resetMobileStore() {
        ContentRepository.mobileDeviceStore = null
        ContentRepository.deviceStore = null
        PlaybackTrace.reset()
    }

    private class MemoryStore(initial: String? = null) : PlayerDeviceStore {
        @Volatile private var value: String? = initial
        override fun load(): String? = value
        override fun save(value: String?) { this.value = value }
    }

    @Test fun firstUseRegistersValidatesAndPersists() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        val repo = ContentRepository(server.client)
        val budget = PlaybackRequestBudget()

        val info = repo.stream(episode, 480, budget)

        assertEquals(480, info.selectedQuality)
        assertEquals("480P", info.quality)
        assertEquals(listOf(1080, 720, 480, 360, 240), info.availableQualities)
        assertEquals("https://cdn/480.mp4", info.url)
        assertEquals(1, server.registrations.get())
        assertEquals(1, server.models.get())
        assertEquals(0, server.others.get())
        // registration + candidate validation share the same quota
        assertEquals(2, budget.traceSnapshot().requests)
        val saved = store.load()
        assertNotNull(saved)
        assertEquals("800000000000000001", PlayerDevice.fromJson(saved!!)!!.deviceId)
        val trace = PlaybackTrace.snapshot()
        assertTrue(trace.any { it.route == PlaybackTrace.Route.MOBILE })
        assertTrue(trace.any { it.identity == PlaybackTrace.Identity.COMMITTED })
    }

    @Test fun restartReusesPersistedIdentityWithoutRegistering() {
        val store = MemoryStore(storedIdentity())
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        val repo = ContentRepository(server.client)

        val info = repo.stream(episode, 480, PlaybackRequestBudget())

        assertEquals(480, info.selectedQuality)
        assertEquals(0, server.registrations.get())
        assertEquals(1, server.models.get())
        assertEquals(storedIdentity(), store.load())
    }

    @Test fun insufficientBudgetNeverSpendsTheRegistrationAlone() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        val repo = ContentRepository(server.client)
        val budget = PlaybackRequestBudget(maxRequests = 1, maxRetries = 0)

        try {
            repo.stream(episode, 480, budget)
            fail("expected budget rejection")
        } catch (expected: PlaybackBudgetExceededException) {
            assertNotNull(expected)
        }
        assertEquals(0, server.registrations.get())
        assertEquals(0, server.models.get())
        assertEquals(0, budget.traceSnapshot().requests)
        assertNull(store.load())
    }

    @Test fun sharedBudgetReservesRegistrationPlusValidation() {
        val budget = PlaybackRequestBudget(maxRequests = 2)
        assertTrue(budget.canReserve(2))
        budget.request()
        assertTrue(!budget.canReserve(2))
        assertTrue(budget.canReserve(1))
        budget.request()
        assertTrue(!budget.canReserve(1))
    }

    @Test fun cancelledScopeNeverRegistersOrPersists() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        val repo = ContentRepository(server.client)
        val scope = RequestScope()
        scope.cancel()

        try {
            scope.run { repo.stream(episode, 480, PlaybackRequestBudget()) }
            fail("expected cancellation")
        } catch (expected: IOException) {
            assertTrue(expected is java.io.InterruptedIOException || expected.message!!.contains("取消"))
        }
        assertEquals(0, server.registrations.get())
        assertEquals(0, server.models.get())
        assertNull(store.load())
    }

    @Test fun expiredBudgetBeforeLockNeverStartsRegistrationOrWritesTheSlot() {
        val store = MemoryStore()
        var registrations = 0
        val source = PlayerDeviceSource(
            OkHttpClient(),
            store = { store },
            register = { registrations++; PlayerDevice("800000000000000001", "800000000000000002", "", "", "") },
            requireRegisteredDevice = true,
            awaitLock = { _, _ -> Thread.sleep(1); false },
        )
        val budget = PlaybackRequestBudget(maxRequests = 8, maxRetries = 0, timeoutMillis = 40)

        try {
            budget.run { source.recover { device -> device } }
            fail("expected deadline")
        } catch (expected: PlaybackDeadlineException) {
            assertNotNull(expected)
        }
        assertEquals(0, registrations)
        assertNull(store.load())
    }

    @Test fun cancelledWaitNeverStartsRegistrationOrWritesTheSlot() {
        val store = MemoryStore()
        var registrations = 0
        val source = PlayerDeviceSource(
            OkHttpClient(),
            store = { store },
            register = { registrations++; PlayerDevice("800000000000000001", "800000000000000002", "", "", "") },
            requireRegisteredDevice = true,
            awaitLock = { _, _ -> throw java.io.InterruptedIOException("请求已取消") },
        )

        try {
            PlaybackRequestBudget().run { source.recover { device -> device } }
            fail("expected cancellation")
        } catch (expected: java.io.InterruptedIOException) {
            assertEquals("请求已取消", expected.message)
        }
        assertEquals(0, registrations)
        assertNull(store.load())
    }

    @Test fun failedValidationDoesNotPersistAndCoolsDown() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        server.modelCode = 500
        val repo = ContentRepository(server.client)

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected model failure")
        } catch (expected: PlaybackHttpException) {
            assertEquals(500, expected.status)
        }
        assertNull(store.load())
        assertEquals(1, server.registrations.get())
        val modelsAfterFailure = server.models.get()

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected cooldown")
        } catch (expected: EmptyPlaybackException) {
            assertTrue(expected.message!!.contains("cooldown"))
        }
        assertEquals(1, server.registrations.get())
        assertEquals(modelsAfterFailure, server.models.get())
    }

    @Test fun failedRegistrationDoesNotPersistAndCoolsDown() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        server.registerBody = """{"device_id":0,"install_id":0}"""
        val repo = ContentRepository(server.client)

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected registration failure")
        } catch (expected: IOException) {
            assertNotNull(expected)
        }
        assertNull(store.load())
        assertEquals(1, server.registrations.get())
        assertEquals(0, server.models.get())

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected cooldown")
        } catch (expected: EmptyPlaybackException) {
            assertTrue(expected.message!!.contains("cooldown"))
        }
        assertEquals(1, server.registrations.get())
    }

    @Test fun concurrentFirstUseRegistersExactlyOnce() {
        val store = MemoryStore()
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        server.modelDelayMillis = 30
        val repo = ContentRepository(server.client)
        val start = CountDownLatch(1)
        val done = CountDownLatch(2)
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val results = Collections.synchronizedList(mutableListOf<StreamInfo>())
        val executor = Executors.newFixedThreadPool(2)
        repeat(2) {
            executor.submit {
                try {
                    start.await()
                    results.add(repo.stream(episode, 480, PlaybackRequestBudget()))
                } catch (error: Throwable) {
                    failures.add(error)
                } finally {
                    done.countDown()
                }
            }
        }
        start.countDown()
        assertTrue(done.await(10, TimeUnit.SECONDS))
        executor.shutdownNow()

        assertTrue(failures.toString(), failures.isEmpty())
        assertEquals(2, results.size)
        assertEquals(1, server.registrations.get())
        assertNotNull(store.load())
    }

    @Test fun mobileStoreRoutesStreamToTheV5ModelInsteadOfLegacyRoutes() {
        val mobile = MemoryStore(storedIdentity())
        ContentRepository.mobileDeviceStore = mobile
        ContentRepository.deviceStore = MemoryStore(storedIdentity())
        try {
            val server = FakeServer()
            val repo = ContentRepository(server.client)
            val info = repo.stream(episode, 480, PlaybackRequestBudget())
            assertEquals(480, info.selectedQuality)
            assertEquals(1, server.models.get())
            assertEquals(0, server.others.get())
            assertEquals(0, server.registrations.get())
        } finally {
            ContentRepository.deviceStore = null
        }
    }

    @Test fun absentMobileStoreNeverCallsTheV5Model() {
        ContentRepository.mobileDeviceStore = null
        val server = FakeServer()
        val repo = ContentRepository(server.client)
        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
        } catch (_: IOException) {
            // legacy path is allowed to fail; it just must not touch the v5 mobile contract
        }
        assertEquals(0, server.models.get())
        assertEquals(0, server.registrations.get())
    }

    @Test fun incompatibleRowsAreNotReportedAsEmpty() {
        val store = MemoryStore(storedIdentity())
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        server.modelBody = mobileReply(episode, metaTier(1080, "bytevc2"), metaTier(720, "bytevc2"))
        val repo = ContentRepository(server.client)

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected incompatible model")
        } catch (expected: NoCompatibleStreamException) {
            assertNotNull(expected)
        }
        assertEquals(0, server.registrations.get())
    }

    @Test fun missingEpisodeEntryIsAnEmptyResult() {
        val store = MemoryStore(storedIdentity())
        ContentRepository.mobileDeviceStore = store
        val server = FakeServer()
        server.modelBody = mobileReply("999999")
        val repo = ContentRepository(server.client)

        try {
            repo.stream(episode, 480, PlaybackRequestBudget())
            fail("expected empty model entry")
        } catch (expected: EmptyPlaybackException) {
            assertTrue(expected.message!!.contains("entry=missing"))
        }
        assertEquals(0, server.registrations.get())
    }
}
