package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackRoutesTest {
    private fun plan(routes: List<Int>, complete: Boolean) = PlaybackRoutes.Plan(routes, complete)

    @Test fun stableIdsSortByRecentSuccessAndSkipCoolingRoutes() {
        val routes = PlaybackRoutes()
        assertEquals(plan(PlaybackRoutes.POST_ROUTES, true), routes.plan(0))
        routes.success(PlaybackRoutes.PHOENIX, 10)
        assertEquals(plan(listOf(PlaybackRoutes.PHOENIX, PlaybackRoutes.SHORTPLAY), true), routes.plan(10))
        routes.failed(PlaybackRoutes.PHOENIX, 20, true)
        // 空响应冷却 30 秒：只剩另一条线路，不再连续撞空，且该轮不完整。
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY), false), routes.plan(20))
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY), false),
            routes.plan(20 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS - 1))
        assertEquals(plan(listOf(PlaybackRoutes.PHOENIX, PlaybackRoutes.SHORTPLAY), true),
            routes.plan(20 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS))
    }

    @Test fun transportFailureUsesShortCooldownAndAllBlockedProbesEarliest() {
        val routes = PlaybackRoutes()
        routes.failed(PlaybackRoutes.SHORTPLAY, 100, false)
        assertEquals(PlaybackRoutes.FAILURE_COOLDOWN_MILLIS, 5_000L)
        assertEquals(plan(listOf(PlaybackRoutes.PHOENIX), false), routes.plan(100))
        // 两条都冷却时只探测最早可恢复的一条，避免一次获取内连续失败。
        routes.failed(PlaybackRoutes.PHOENIX, 101, false)
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY), false), routes.plan(102))
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY, PlaybackRoutes.PHOENIX), true),
            routes.plan(101 + PlaybackRoutes.FAILURE_COOLDOWN_MILLIS))
    }

    @Test fun successClearsCooldownWithoutChangingOtherRoutes() {
        val routes = PlaybackRoutes()
        routes.failed(PlaybackRoutes.SHORTPLAY, 10, true)
        routes.failed(PlaybackRoutes.PHOENIX, 11, true)
        routes.success(PlaybackRoutes.SHORTPLAY, 12)
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY), false), routes.plan(12))
        assertEquals(plan(listOf(PlaybackRoutes.SHORTPLAY, PlaybackRoutes.PHOENIX), true),
            routes.plan(11 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS))
    }

    @Test fun cooldownSkippedRoundIsNeverACompleteEmptyRound() {
        val routes = PlaybackRoutes()
        routes.failed(PlaybackRoutes.SHORTPLAY, 10, true)
        routes.failed(PlaybackRoutes.PHOENIX, 10, true)
        // 冷却期间只能探测一条，属于“线路不可用”，不是完整空轮次。
        assertFalse(routes.plan(20).complete)
        assertFalse(routes.plan(10 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS - 1).complete)
        // 冷却结束后两条都重新可探测，才是完整轮次。
        assertTrue(routes.plan(10 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS).complete)
    }
}
