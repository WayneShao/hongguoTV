// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.io.PrintWriter
import java.io.StringWriter
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class PlaybackTraceTest {
    @Before fun reset() {
        PlaybackTrace.reset()
        ContentRepository.deviceStore = null
    }

    @After fun cleanup() {
        ContentRepository.deviceStore = null
    }

    @Test fun ringKeepsOnlyNewestBoundedEntries() {
        PlaybackTrace.root(1) {
            repeat(PlaybackTrace.CAPACITY + 40) { PlaybackTrace.identity(PlaybackTrace.Identity.ACTIVE) }
        }
        val rows = PlaybackTrace.snapshot()
        assertEquals(PlaybackTrace.CAPACITY, rows.size)
        assertTrue(PlaybackTrace.dropped() > 0)
        assertEquals(rows.map { it.seq }, rows.map { it.seq }.sorted())
        assertEquals(rows.last().seq, rows.maxOf { it.seq })
    }

    @Test fun concurrentRootsKeepIndependentOrdinalsAndRestoreScope() {
        val gate = CountDownLatch(1)
        val done = CountDownLatch(2)
        repeat(2) { index ->
            Thread {
                gate.await()
                PlaybackTrace.root(100L + index) {
                    PlaybackTrace.identity(PlaybackTrace.Identity.ACTIVE,
                        source = if (index == 0) PlaybackTrace.Source.SAVED else PlaybackTrace.Source.DEFAULT)
                    repeat(20) { PlaybackTrace.identity(PlaybackTrace.Identity.ACTIVE) }
                }
                done.countDown()
            }.start()
        }
        gate.countDown()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        val rows = PlaybackTrace.snapshot()
        repeat(2) { index ->
            val mine = rows.filter { it.root == 100L + index }
            assertEquals(21, mine.count { it.event == PlaybackTrace.Event.IDENTITY })
            assertEquals(1, mine.count { it.event == PlaybackTrace.Event.ROOT_COMPLETE })
        }
        // A root() call has no lasting thread-local scope.
        PlaybackTrace.identity(PlaybackTrace.Identity.ACTIVE)
        assertEquals(46, PlaybackTrace.snapshot().size)
    }

    @Test fun reusedBudgetCarriesOneSharedOrdinal() {
        val budget = PlaybackRequestBudget(8, 0)
        val ordinal = budget.traceOrdinal
        assertEquals(ordinal, budget.traceOrdinal)
        PlaybackTrace.root(ordinal) { PlaybackTrace.identity(PlaybackTrace.Identity.ACTIVE) }
        PlaybackTrace.root(budget.traceOrdinal) { PlaybackTrace.identity(PlaybackTrace.Identity.CANDIDATE) }
        assertEquals(setOf(ordinal), PlaybackTrace.snapshot().map { it.root }.toSet())
    }

    @Test fun dumpContainsNoUrlsIdentifiersOrExceptionMessages() {
        PlaybackTrace.root(7) {
            PlaybackTrace.requestStart(PlaybackTrace.Route.LEGACY)
            PlaybackTrace.requestFail(PlaybackTrace.Failure.NETWORK)
            PlaybackTrace.failure(PlaybackTrace.Route.LEGACY, PlaybackTrace.Failure.MODEL,
                PlaybackTrace.Parser.MODEL_INVALID)
        }
        val text = StringWriter().also { PlaybackTrace.writeTo(PrintWriter(it)) }.toString()
        assertTrue(text.contains("ev=FAIL"))
        assertTrue(text.contains("fail=NETWORK"))
        listOf("https://", "iid=", "_rticket", "device_id", "video_id", "7000000000001234567",
            "secret-token", "api3-normal", "novel/player").forEach {
            assertFalse("trace leaked $it", text.contains(it))
        }
    }

    @Test fun failureAndParserEnumsAreSafeAndStable() {
        assertEquals(PlaybackTrace.Failure.TIMEOUT, PlaybackTrace.failureOf(java.net.SocketTimeoutException()))
        assertEquals(PlaybackTrace.Failure.CANCEL,
            PlaybackTrace.failureOf(java.io.InterruptedIOException("请求已取消")))
        assertEquals(PlaybackTrace.Failure.NETWORK, PlaybackTrace.failureOf(UnknownHostException()))
        assertEquals(PlaybackTrace.Failure.HTTP, PlaybackTrace.failureOf(PlaybackHttpException(503)))
        assertEquals(PlaybackTrace.Failure.MODEL,
            PlaybackTrace.failureOf(PlaybackModelException("raw server message")))
        assertEquals(PlaybackTrace.Failure.BUDGET, PlaybackTrace.failureOf(PlaybackBudgetExceededException()))
        assertEquals(PlaybackTrace.Failure.DEADLINE, PlaybackTrace.failureOf(PlaybackDeadlineException()))
        assertEquals(PlaybackTrace.Failure.EMPTY,
            PlaybackTrace.failureOf(EmptyPlaybackException(reason = EmptyPlaybackReason.NO_FALLBACK_API)))
        assertEquals(PlaybackTrace.Parser.NO_FALLBACK_API,
            PlaybackTrace.parserOf(EmptyPlaybackException(reason = EmptyPlaybackReason.NO_FALLBACK_API)))
        assertEquals(PlaybackTrace.Parser.INCOMPATIBLE,
            PlaybackTrace.parserOf(NoCompatibleStreamException()))
    }

    @Test fun emptyLegacyBodyRecordsStatusEofAndPreservesRequestBudget() {
        val seen = mutableListOf<String>()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            seen += chain.request().url.encodedPath
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(200).message("OK").body("".toResponseBody()).build()
        }.build()
        val budget = PlaybackRequestBudget(8, 2)
        val repository = ContentRepository(client)
        assertThrows(Exception::class.java) { repository.stream("123", 480, budget) }

        // The three read attempts are still counted exactly as before instrumentation.
        assertEquals(3, budget.traceSnapshot().requests)
        assertEquals(2, budget.traceSnapshot().retries)
        assertEquals(3, seen.size)

        val rows = PlaybackTrace.snapshot()
        assertEquals(setOf(budget.traceOrdinal), rows.map { it.root }.toSet())
        val responses = rows.filter { it.event == PlaybackTrace.Event.RESPONSE }
        assertEquals(3, responses.size)
        assertTrue(responses.all { it.route == PlaybackTrace.Route.LEGACY })
        assertTrue(responses.all { it.httpStatus == 200 && it.contentLength == 0L })
        val ends = rows.filter { it.event == PlaybackTrace.Event.END }
        assertTrue(ends.isNotEmpty())
        assertTrue(ends.all { it.bytes == 0L && it.eof })
        assertEquals(3, rows.count { it.event == PlaybackTrace.Event.PARSER && it.parser == PlaybackTrace.Parser.RAW_BLANK })
        assertEquals(2, rows.count { it.event == PlaybackTrace.Event.RETRY })
        assertTrue(rows.any { it.event == PlaybackTrace.Event.ROOT_FAIL && it.failure == PlaybackTrace.Failure.EMPTY })
    }

    @Test fun httpFailureStillRecordsStatusCodeEvenWithoutBody() {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                .code(404).message("Not Found").body("not json".toResponseBody()).build()
        }.build()
        val budget = PlaybackRequestBudget(8, 0)
        val repository = ContentRepository(client)
        assertThrows(Exception::class.java) { repository.stream("123", 480, budget) }

        val rows = PlaybackTrace.snapshot()
        val response = rows.first { it.event == PlaybackTrace.Event.RESPONSE }
        assertEquals(404, response.httpStatus)
        assertTrue(rows.any { it.event == PlaybackTrace.Event.FAIL && it.failure == PlaybackTrace.Failure.HTTP })
        assertEquals(1, budget.traceSnapshot().requests)
        val text = StringWriter().also { PlaybackTrace.writeTo(PrintWriter(it)) }.toString()
        assertFalse(text.contains("not json"))
        assertFalse(text.contains("https://"))
    }
}
