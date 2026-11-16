// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Display only allowlisted categories, never exception messages, addresses or identity values. */
object PlaybackDiagnostics {
    /** Coarse caller-known phase; only qualifies timeout wording, never overrides typed failures. */
    enum class Stage { PREPARATION, PREFETCH_WAIT, TRANSPORT, DEADLINE }

    fun line(error: Throwable,model: String,os: String,api: Int,version: String,decoding: Boolean,attempts: Int,stage: Stage?=null): String {
        val causes=generateSequence(error) { it.cause }.take(12).toList()
        val category=when {
            causes.any { it is NoCompatibleStreamException } -> "资源不兼容"
            causes.any { it is PlaybackBudgetExceededException } -> "请求预算耗尽"
            causes.any { it is PlaybackModelException } -> "播放模型异常"
            causes.any { it is DeviceRejectedException } -> "设备请求被拒绝"
            decoding -> "解码失败"
            causes.any { it is PlaybackDeadlineException } -> "截止时间超时"
            causes.any { it.isTimeoutMarker() } -> timeoutLabel(stage)
            causes.any { it is java.net.UnknownHostException } -> "地址解析失败"
            causes.any { it is java.io.IOException } -> "网络请求失败"
            else -> "播放失败"
        }
        fun field(value: String)=value.filter { it.isLetterOrDigit() || it in " ._-" }.take(32).ifBlank { "未知" }
        return "诊断：$category · ${field(model)} · Android ${field(os)} / API $api · ${field(version)} · 已自动重试 ${attempts.coerceIn(0,3)}/3"
    }

    /** Fixed prefetch-wait notice; callers never interpolate request details. */
    fun timeoutLine(stage: Stage): String="诊断：${timeoutLabel(stage)}"

    private fun timeoutLabel(stage: Stage?): String=when(stage) {
        Stage.PREPARATION -> "准备超时"
        Stage.PREFETCH_WAIT -> "等待预加载超时"
        Stage.TRANSPORT -> "传输超时"
        Stage.DEADLINE -> "截止时间超时"
        null -> "网络超时"
    }
    private fun Throwable.isTimeoutMarker()=this is java.net.SocketTimeoutException ||
        (this is java.io.InterruptedIOException && message?.trim()?.equals("timeout",ignoreCase=true)==true)
}
