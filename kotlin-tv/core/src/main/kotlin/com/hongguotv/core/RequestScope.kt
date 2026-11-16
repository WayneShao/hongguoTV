// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import okhttp3.Call
import okhttp3.Response
import java.io.IOException

/** Cancels only the work owned by one navigation/playback intent, including body reads. */
class RequestScope {
    private var cancelled = false
    private val calls = mutableSetOf<Call>()

    @Synchronized fun cancel() {
        cancelled = true
        calls.forEach { it.cancel() }
        calls.clear()
    }

    @Synchronized private fun attach(call: Call) {
        if (cancelled || Thread.currentThread().isInterrupted) {
            call.cancel()
            throw IOException("请求已取消")
        }
        calls += call
    }

    @Synchronized private fun detach(call: Call) { calls -= call }

    fun <T> run(action: () -> T): T {
        val previous = current.get()
        current.set(this)
        try {
            synchronized(this) { if (cancelled) throw IOException("请求已取消") }
            return action()
        } finally {
            if (previous == null) current.remove() else current.set(previous)
        }
    }

    @Synchronized private fun checkActive() { if(cancelled) throw java.io.InterruptedIOException("请求已取消") }
    companion object {
        private val current = ThreadLocal<RequestScope>()
        /** 每次获取播放地址时设置的绝对截止点（单调时钟毫秒），不随请求次数延长。 */
        private val deadline = ThreadLocal<Long>()
        private fun monotonic() = System.nanoTime() / 1_000_000
        fun ensureActive() {
            if(Thread.currentThread().isInterrupted) throw java.io.InterruptedIOException("请求已取消")
            current.get()?.checkActive()
            deadline.get()?.let { if(monotonic() >= it) throw PlaybackDeadlineException() }
        }
        /** 为本次获取设置独立的截止时间；嵌套调用结束后恢复上层截止点。 */
        fun <T> withDeadline(millis: Long, action: () -> T): T {
            val previous = deadline.get()
            // 嵌套期限只能收紧不能放宽：子预算/可选补查绝不能替父级延长总截止点。
            val requested = monotonic() + millis.coerceAtLeast(0)
            deadline.set(if(previous == null) requested else minOf(previous, requested))
            try {
                val result = action()
                // 正文读取可能在上限前结束，但随后的解析/选流仍不得越过绝对截止时间。
                ensureActive()
                return result
            } finally { if(previous == null) deadline.remove() else deadline.set(previous) }
        }
        /** 剩余毫秒数；未设置截止点时返回 null。 */
        fun remainingMillis(): Long? = deadline.get()?.let { (it - monotonic()).coerceAtLeast(0) }
        /**
         * 已有可播结果后的可选补查：内层期限严格短于当前剩余时间并预留返回开销，
         * 绝不延长外层截止点；剩余不足时直接跳过而不是发起徒劳请求。
         */
        fun <T> withOptionalLimit(capMillis: Long, reserveMillis: Long = OPTIONAL_RETURN_RESERVE_MILLIS, action: () -> T): T {
            // 已取消或父级总截止点已过时，一次可选请求都不该发起。
            ensureActive()
            val remaining = remainingMillis() ?: return withDeadline(capMillis, action)
            if (remaining <= reserveMillis + OPTIONAL_MIN_WORK_MILLIS) throw PlaybackDeadlineException()
            return withDeadline(minOf(capMillis, remaining - reserveMillis).coerceAtLeast(0), action)
        }
        /** 可注入的等待，自动裁剪到剩余截止时间并在返回后复查取消与超时。 */
        fun sleepWithinDeadline(millis: Long, sleeper: (Long) -> Unit = Thread::sleep) {
            val remaining = remainingMillis()
            val wait = if(remaining == null) millis else minOf(millis, remaining)
            if(wait > 0) try { sleeper(wait) } catch(interrupted: InterruptedException) {
                Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消")
            }
            ensureActive()
        }
        fun <T> execute(call: Call, consume: (Response) -> T): T {
            val owner = current.get()
            owner?.attach(call)
            try {
                // 单个请求不得越过本次获取的总截止时间，包含响应体读取。
                remainingMillis()?.let { remaining ->
                    if(remaining <= 0) throw PlaybackDeadlineException()
                    call.timeout().timeout(remaining, java.util.concurrent.TimeUnit.MILLISECONDS)
                }
                return call.execute().use(consume)
            } finally { owner?.detach(call) }
        }
        /** 可选补查的内层上限，短于 8 秒总预算；仅用于已有可播结果后的额外探测。 */
        internal const val OPTIONAL_LOOKUP_TIMEOUT_MILLIS = 3_000L
        /** 为返回已有结果预留的时间；可选补查不得占用。 */
        internal const val OPTIONAL_RETURN_RESERVE_MILLIS = 800L
        /** 剩余时间不足以完成一次有意义的可选请求时直接跳过。 */
        private const val OPTIONAL_MIN_WORK_MILLIS = 300L
    }
}
