// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/**
 * One foreground buffering interval for diagnostics. Callers pass monotonic milliseconds and the
 * current episode ordinal; no media/account identifier is stored or printed.
 */
internal class BufferingDiagnostics(private val emit: (String)->Unit) {
    /** Fixed end reasons; no free-form text reaches the log. */
    internal enum class Stop(val label: String) {
        READY("就绪"),PAUSED("暂停"),BACKGROUND("后台"),SWITCH("切集"),ERROR("错误"),CLOSED("关闭")
    }
    private var episode=0
    private var since: Long?=null
    private var bufferedAhead=0L

    /** Feed existing Player.Listener state; duplicate buffering callbacks never open another interval. */
    fun state(ordinal: Int,now: Long,buffering: Boolean,eligible: Boolean,bufferedAheadMs: Long) {
        bufferedAhead=bufferedAheadMs.coerceAtLeast(0)
        if(ordinal!=episode) { finish(now,Stop.SWITCH);episode=ordinal }
        if(!eligible) { finish(now,Stop.PAUSED);return }
        if(buffering) {
            if(since==null) { since=now;emit(line("开始 · 已缓存 $bufferedAhead ms")) }
        } else finish(now,Stop.READY)
    }

    /** A seamless queue transition changes the episode before the next listener callback arrives. */
    fun switchEpisode(ordinal: Int,now: Long) {
        if(ordinal!=episode) { finish(now,Stop.SWITCH);episode=ordinal }
    }

    /** Error, teardown and background paths end the interval and forget the ordinal. */
    fun close(now: Long,stop: Stop=Stop.CLOSED) {
        finish(now,stop);episode=0
    }

    private fun finish(now: Long,stop: Stop) {
        val started=since ?: return
        since=null
        emit(line("结束 · 耗时 ${(now-started).coerceAtLeast(0)} ms · 已缓存 $bufferedAhead ms · 原因 ${stop.label}"))
    }
    private fun line(detail: String)="缓冲诊断：第 $episode 集 $detail"
}
