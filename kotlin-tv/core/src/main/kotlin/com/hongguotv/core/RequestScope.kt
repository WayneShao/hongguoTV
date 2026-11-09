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
            deadline.set(monotonic() + millis.coerceAtLeast(0))
            try {
                val result = action()
                // 正文读取可能在上限前结束，但随后的解析/选流仍不得越过绝对截止时间。
                ensureActive()
                return result
            } finally { if(previous == null) deadline.remove() else deadline.set(previous) }
        }
        /** 剩余毫秒数；未设置截止点时返回 null。 */
        fun remainingMillis(): Long? = deadline.get()?.let { (it - monotonic()).coerceAtLeast(0) }
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
    }
}
