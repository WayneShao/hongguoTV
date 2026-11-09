// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** 每个匿名身份独立记忆播放线路健康度；只保留时间戳，不保存身份或媒体信息。 */
internal class PlaybackRoutes {
    private data class Health(var success: Long = Long.MIN_VALUE, var blockedUntil: Long = 0L, var empty: Boolean = false)
    private val health = POST_ROUTES.associateWith { Health() }

    /** 本轮计划。complete 表示本次确实探测了全部 POST 线路，可据此判断完整空轮次。 */
    data class Plan(val routes: List<Int>, val complete: Boolean)

    /** 可用线路按最近成功排序；全部冷却时只探测最早可恢复的一条，避免连续撞空。 */
    @Synchronized
    fun plan(now: Long): Plan {
        val available = POST_ROUTES.filter { health.getValue(it).blockedUntil <= now }
            .sortedByDescending { health.getValue(it).success }
        if (available.isNotEmpty()) return Plan(available, available.size == POST_ROUTES.size)
        // 冷却中的线路不可用并不等于空响应；这种半轮探测不能算作完整空轮次。
        val recovering = POST_ROUTES.minByOrNull { health.getValue(it).blockedUntil } ?: POST_ROUTES.first()
        return Plan(listOf(recovering), false)
    }

    @Synchronized
    fun success(route: Int, now: Long) {
        health[route]?.apply { success = now; blockedUntil = 0; empty = false }
    }

    /** 空响应冷却更久；传输失败只短暂降级。取消、超时与终止错误不得调用此方法。 */
    @Synchronized
    fun failed(route: Int, now: Long, empty: Boolean) {
        health[route]?.apply {
            blockedUntil = now + if(empty) EMPTY_COOLDOWN_MILLIS else FAILURE_COOLDOWN_MILLIS
            this.empty = empty
        }
    }

    companion object {
        /** 0 短剧 POST，1 凤凰 POST；2 标签页 GET 仅作一次性兜底，不参与排序。 */
        const val SHORTPLAY = 0
        const val PHOENIX = 1
        const val TAB = 2
        val POST_ROUTES = listOf(SHORTPLAY, PHOENIX)
        const val EMPTY_COOLDOWN_MILLIS = 30_000L
        const val FAILURE_COOLDOWN_MILLIS = 5_000L
    }
}
