// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.util.concurrent.atomic.AtomicLong

/**
 * Bounded, memory-only playback acquisition trace for release-device diagnosis.
 *
 * Records only typed enums and numbers. URLs, query strings, headers, bodies,
 * identity values, episode ids/titles and arbitrary exception messages are
 * never stored. [writeTo] is exposed through Activity.dump so `dumpsys activity`
 * can retrieve the last requests when logcat is unavailable.
 */
object PlaybackTrace {
    const val CAPACITY = 192

    enum class Route { NONE, SHORTPLAY, PHOENIX, PRELOAD, TAB, LEGACY, FALLBACK, REGISTER, ALERT, REGISTER_KEY, MOBILE }
    enum class Event { ROOT_START, ROOT_COMPLETE, ROOT_FAIL, START, RESPONSE, END, FAIL, CODE, PARSER, RETRY, RESULT, IDENTITY }
    enum class Parser {
        NONE, SUCCESS, RAW_BLANK, DATA_EMPTY, VIDEO_MODEL_EMPTY, LIST_EMPTY, MODEL_INVALID, INCOMPATIBLE,
        TAB_RAW_BLANK, TAB_DATA_EMPTY, TAB_LIST_EMPTY, NO_FALLBACK_API,
    }
    enum class Failure {
        NONE, TIMEOUT, CANCEL, NETWORK, HTTP, MODEL, BUDGET, DEADLINE, EMPTY,
        DEVICE_REJECTED, IDENTITY_REJECTED, UNKNOWN,
    }
    enum class Identity { NONE, ACTIVE, CANDIDATE, REGISTER_START, REGISTER_FAILED, COMMITTED, COOLDOWN, EMPTY_THRESHOLD }
    enum class Source { NONE, SAVED, DEFAULT }
    enum class Trigger { NONE, DEVICE_REJECTED, EMPTY_THRESHOLD, COOLDOWN, IDENTITY_CHANGED, REGISTER_FAILED }

    internal data class Record(
        val seq: Long,
        val root: Long,
        val event: Event,
        val route: Route = Route.NONE,
        val httpStatus: Int = 0,
        val contentLength: Long = -1L,
        val bytes: Long = -1L,
        val eof: Boolean = false,
        val code: Int = Int.MIN_VALUE,
        val parser: Parser = Parser.NONE,
        val failure: Failure = Failure.NONE,
        val elapsedMs: Long = -1L,
        val remainingMs: Long = -1L,
        val budgetRequests: Int = -1,
        val budgetRetries: Int = -1,
        val attempt: Int = 1,
        val identity: Identity = Identity.NONE,
        val source: Source = Source.NONE,
        val trigger: Trigger = Trigger.NONE,
        val emptyPoints: Int = -1,
        val cooldownMs: Long = -1L,
        val retryAllowed: Boolean = false,
    )

    private class Scope(val root: Long, val startedAt: Long) {
        var requestStartedAt: Long = startedAt
        var attempt: Int = 1
        var lastStatus: Int = 0
        var lastContentLength: Long = -1L
        var route: Route = Route.NONE
    }

    private val lock = Any()
    private val entries = arrayOfNulls<Record>(CAPACITY)
    private var head = 0
    private var size = 0
    private var dropped = 0L
    private val ordinal = AtomicLong(0)
    private val sequence = AtomicLong(0)
    private val scope = ThreadLocal<Scope?>()

    internal fun nextOrdinal(): Long = ordinal.incrementAndGet()

    internal fun <T> root(rootOrdinal: Long, block: () -> T): T {
        val previous = scope.get()
        val current = Scope(rootOrdinal, monotonic())
        scope.set(current)
        add(current, Event.ROOT_START)
        try {
            val result = block()
            add(current, Event.ROOT_COMPLETE, parser = Parser.SUCCESS, elapsedMs = monotonic() - current.startedAt)
            return result
        } catch (error: Throwable) {
            add(current, Event.ROOT_FAIL, failure = failureOf(error), parser = parserOf(error), elapsedMs = monotonic() - current.startedAt)
            throw error
        } finally {
            if (previous == null) scope.remove() else scope.set(previous)
        }
    }

    internal fun <T> withAttempt(number: Int, block: () -> T): T {
        val current = scope.get() ?: return block()
        val previous = current.attempt
        current.attempt = number
        return try { block() } finally { current.attempt = previous }
    }

    internal fun requestStart(route: Route) {
        val current = scope.get() ?: return
        current.route = route
        current.requestStartedAt = monotonic()
        current.lastStatus = 0
        current.lastContentLength = -1L
        add(current, Event.START, route)
    }

    internal fun response(status: Int, contentLength: Long) {
        val current = scope.get() ?: return
        current.lastStatus = status
        current.lastContentLength = contentLength
        add(current, Event.RESPONSE, current.route, httpStatus = status, contentLength = contentLength)
    }

    internal fun requestEnd(bytes: Long, eof: Boolean) {
        val current = scope.get() ?: return
        add(current, Event.END, current.route, httpStatus = current.lastStatus,
            contentLength = current.lastContentLength, bytes = bytes, eof = eof)
    }

    internal fun requestFail(failure: Failure) {
        val current = scope.get() ?: return
        add(current, Event.FAIL, current.route, httpStatus = current.lastStatus,
            contentLength = current.lastContentLength, failure = failure)
    }

    internal fun businessCode(route: Route, code: Int) {
        withScope { add(it, Event.CODE, route, code = code) }
    }

    internal fun parser(route: Route, parser: Parser) {
        withScope { add(it, Event.PARSER, route, parser = parser) }
    }

    internal fun blank(route: Route) {
        withScope { add(it, Event.PARSER, route, parser = Parser.RAW_BLANK) }
    }

    internal fun retry(route: Route, attempt: Int, failure: Failure, allowed: Boolean) {
        withScope { current ->
            val previous = current.attempt
            current.attempt = attempt
            try { add(current, Event.RETRY, route, failure = failure, retryAllowed = allowed) }
            finally { current.attempt = previous }
        }
    }

    internal fun failure(route: Route, failure: Failure, parser: Parser = Parser.NONE) {
        withScope { add(it, Event.FAIL, route, failure = failure, parser = parser) }
    }

    internal fun result(route: Route, parser: Parser, failure: Failure) {
        withScope { add(it, Event.RESULT, route, parser = parser, failure = failure) }
    }

    /** Records the failure swallowed while switching from registered routes to legacy. */
    internal fun registeredFailure(error: Throwable) {
        withScope { add(it, Event.FAIL, Route.NONE, failure = failureOf(error), parser = parserOf(error)) }
    }

    internal fun identity(
        phase: Identity,
        source: Source = Source.NONE,
        trigger: Trigger = Trigger.NONE,
        emptyPoints: Int = -1,
        cooldownMs: Long = -1L,
        failure: Failure = Failure.NONE,
    ) {
        withScope {
            add(it, Event.IDENTITY, identity = phase, source = source, trigger = trigger,
                emptyPoints = emptyPoints, cooldownMs = cooldownMs, failure = failure)
        }
    }

    internal fun snapshot(): List<Record> = synchronized(lock) {
        List(size) { entries[(head + it) % CAPACITY]!! }
    }

    internal fun dropped(): Long = synchronized(lock) { dropped }

    internal fun reset() {
        synchronized(lock) {
            entries.fill(null)
            head = 0
            size = 0
            dropped = 0L
        }
        sequence.set(0L)
        ordinal.set(0L)
        scope.remove()
    }

    /** Called by Activity.dump; stdout-independent and safe for release builds. */
    fun writeTo(writer: java.io.PrintWriter) {
        val rows: List<Record>
        val lost: Long
        synchronized(lock) {
            rows = List(size) { entries[(head + it) % CAPACITY]!! }
            lost = dropped
        }
        writer.println("playback-trace v1 cap=$CAPACITY rows=${rows.size} dropped=$lost")
        rows.forEach { writer.println(format(it)) }
        writer.flush()
    }

    internal fun failureOf(error: Throwable): Failure {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        return when {
            causes.any { it is PlaybackBudgetExceededException } -> Failure.BUDGET
            causes.any { it is PlaybackDeadlineException } -> Failure.DEADLINE
            causes.any { it is EmptyPlaybackException } -> Failure.EMPTY
            causes.any { it is DeviceIdentityRejectedException } -> Failure.IDENTITY_REJECTED
            causes.any { it is DeviceRejectedException } -> Failure.DEVICE_REJECTED
            causes.any { it is PlaybackHttpException } -> Failure.HTTP
            causes.any { it is PlaybackModelException || it is org.json.JSONException } -> Failure.MODEL
            causes.any { it is NoCompatibleStreamException } -> Failure.MODEL
            causes.any { it is java.net.SocketTimeoutException } -> Failure.TIMEOUT
            causes.any { it is java.io.InterruptedIOException || it is java.util.concurrent.CancellationException } -> Failure.CANCEL
            causes.any { it is java.io.IOException } -> Failure.NETWORK
            else -> Failure.UNKNOWN
        }
    }

    internal fun parserOf(error: Throwable): Parser {
        val causes = generateSequence(error) { it.cause }.take(12).toList()
        causes.filterIsInstance<EmptyPlaybackException>().firstOrNull()?.let { return parserOf(it.reason) }
        return when {
            causes.any { it is NoCompatibleStreamException } -> Parser.INCOMPATIBLE
            causes.any { it is PlaybackModelException || it is org.json.JSONException } -> Parser.MODEL_INVALID
            else -> Parser.NONE
        }
    }

    internal fun parserOf(reason: EmptyPlaybackReason): Parser = when (reason) {
        EmptyPlaybackReason.RAW_BLANK -> Parser.RAW_BLANK
        EmptyPlaybackReason.DATA_EMPTY -> Parser.DATA_EMPTY
        EmptyPlaybackReason.VIDEO_MODEL_EMPTY -> Parser.VIDEO_MODEL_EMPTY
        EmptyPlaybackReason.LIST_EMPTY -> Parser.LIST_EMPTY
        EmptyPlaybackReason.TAB_RAW_BLANK -> Parser.TAB_RAW_BLANK
        EmptyPlaybackReason.TAB_DATA_EMPTY -> Parser.TAB_DATA_EMPTY
        EmptyPlaybackReason.TAB_LIST_EMPTY -> Parser.TAB_LIST_EMPTY
        EmptyPlaybackReason.NO_FALLBACK_API -> Parser.NO_FALLBACK_API
    }

    internal fun routeOf(routeId: Int): Route = when (routeId) {
        PlaybackRoutes.SHORTPLAY -> Route.SHORTPLAY
        PlaybackRoutes.PHOENIX -> Route.PHOENIX
        PlaybackRoutes.PRELOAD -> Route.PRELOAD
        PlaybackRoutes.TAB -> Route.TAB
        else -> Route.NONE
    }

    private inline fun withScope(block: (Scope) -> Unit) {
        val current = scope.get() ?: return
        block(current)
    }

    private fun add(
        current: Scope,
        event: Event,
        route: Route = Route.NONE,
        httpStatus: Int = 0,
        contentLength: Long = -1L,
        bytes: Long = -1L,
        eof: Boolean = false,
        code: Int = Int.MIN_VALUE,
        parser: Parser = Parser.NONE,
        failure: Failure = Failure.NONE,
        elapsedMs: Long = if (event == Event.ROOT_START || event == Event.ROOT_COMPLETE || event == Event.ROOT_FAIL) -1L
            else (monotonic() - current.requestStartedAt).coerceAtLeast(0),
        identity: Identity = Identity.NONE,
        source: Source = Source.NONE,
        trigger: Trigger = Trigger.NONE,
        emptyPoints: Int = -1,
        cooldownMs: Long = -1L,
        retryAllowed: Boolean = false,
    ) {
        val budget = PlaybackRequestBudget.current.get()?.traceSnapshot()
        val record = Record(
            seq = sequence.incrementAndGet(),
            root = current.root,
            event = event,
            route = route,
            httpStatus = httpStatus,
            contentLength = contentLength,
            bytes = bytes,
            eof = eof,
            code = code,
            parser = parser,
            failure = failure,
            elapsedMs = elapsedMs,
            remainingMs = RequestScope.remainingMillis() ?: -1L,
            budgetRequests = budget?.requests ?: -1,
            budgetRetries = budget?.retries ?: -1,
            attempt = current.attempt,
            identity = identity,
            source = source,
            trigger = trigger,
            emptyPoints = emptyPoints,
            cooldownMs = cooldownMs,
            retryAllowed = retryAllowed,
        )
        synchronized(lock) {
            if (size < CAPACITY) {
                entries[(head + size) % CAPACITY] = record
                size++
            } else {
                entries[head] = record
                head = (head + 1) % CAPACITY
                dropped++
            }
        }
    }

    private fun format(row: Record): String = buildString {
        append("root=").append(row.root)
        append(" seq=").append(row.seq)
        append(" ev=").append(row.event.name)
        append(" rt=").append(row.route.name)
        append(" http=").append(row.httpStatus)
        append(" cl=").append(row.contentLength)
        append(" bytes=").append(row.bytes)
        append(" eof=").append(row.eof)
        append(" code=").append(if (row.code == Int.MIN_VALUE) "-" else row.code.toString())
        append(" parser=").append(row.parser.name)
        append(" fail=").append(row.failure.name)
        append(" ms=").append(row.elapsedMs)
        append(" rem=").append(row.remainingMs)
        append(" req=").append(row.budgetRequests)
        append(" retry=").append(row.budgetRetries)
        append(" att=").append(row.attempt)
        append(" id=").append(row.identity.name)
        append(" src=").append(row.source.name)
        append(" trig=").append(row.trigger.name)
        append(" pts=").append(row.emptyPoints)
        append(" cd=").append(row.cooldownMs)
        append(" allow=").append(row.retryAllowed)
    }

    private fun monotonic(): Long = System.nanoTime() / 1_000_000
}
