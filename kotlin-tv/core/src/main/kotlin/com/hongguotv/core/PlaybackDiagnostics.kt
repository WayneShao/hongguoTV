// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Display only allowlisted categories, never exception messages, addresses or identity values. */
object PlaybackDiagnostics {
    fun line(error: Throwable,model: String,os: String,api: Int,version: String,decoding: Boolean,attempts: Int): String {
        val causes=generateSequence(error) { it.cause }.take(12).toList()
        val category=when {
            causes.any { it is NoCompatibleStreamException } -> "资源不兼容"
            causes.any { it is PlaybackBudgetExceededException } -> "请求预算耗尽"
            causes.any { it is PlaybackModelException } -> "播放模型异常"
            causes.any { it is DeviceRejectedException } -> "设备请求被拒绝"
            decoding -> "解码失败"
            causes.any { it is java.net.SocketTimeoutException } -> "网络超时"
            causes.any { it is java.net.UnknownHostException } -> "地址解析失败"
            causes.any { it is java.io.IOException } -> "网络请求失败"
            else -> "播放失败"
        }
        fun field(value: String)=value.filter { it.isLetterOrDigit() || it in " ._-" }.take(32).ifBlank { "未知" }
        return "诊断：$category · ${field(model)} · Android ${field(os)} / API $api · ${field(version)} · 已自动重试 ${attempts.coerceIn(0,3)}/3"
    }
}
