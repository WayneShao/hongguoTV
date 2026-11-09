package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class PlaybackRoutesTest {
    @Test fun stableIdsSortByRecentSuccessAndSkipCookingRoutes() {
        val routes = PlaybackRoutes()
        assertEquals(PlaybackRoutes.POST_ROUTES, routes.plan(0))
        routes.success(PlaybackRoutes.PHOENIX, 10)
        assertEquals(listOf(PlaybackRoutes.PHOENIX, PlaybackRoutes.SHORTPLAY), routes.plan(10))
        routes.failed(PlaybackRoutes.PHOENIX, 20, true)
        // 空响应冷却 30 秒：只剩另一条线路，不再连续撞空。
        assertEquals(listOf(PlaybackRoutes.SHORTPLAY), routes.plan(20))
        assertEquals(listOf(PlaybackRoutes.SHORTPLAY), routes.plan(20 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS - 1))
        assertEquals(listOf(PlaybackRoutes.PHOENIX, PlaybackRoutes.SHORTPLAY),
            routes.plan(20 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS))
    }

    @Test fun transportFailureUsesShortCooldownAndAllBlockedProbesEarliest() {
        val routes = PlaybackRoutes()
        routes.failed(PlaybackRoutes.SHORTPLAY, 100, false)
        assertEquals(PlaybackRoutes.FAILURE_COOLDOWN_MILLIS, 5_000L)
        assertEquals(listOf(PlaybackRoutes.PHOENIX), routes.plan(100))
        // 两条都冷却时只探测最早可恢复的一条，避免一次获取内连续失败。
        routes.failed(PlaybackRoutes.PHOENIX, 101, false)
        assertEquals(listOf(PlaybackRoutes.SHORTPLAY), routes.plan(102))
        assertEquals(listOf(PlaybackRoutes.SHORTPLAY, PlaybackRoutes.PHOENIX),
            routes.plan(101 + PlaybackRoutes.FAILURE_COOLDOWN_MILLIS))
    }

    @Test fun successClearsCooldownWithoutChangingOtherRoutes() {
        val routes = PlaybackRoutes()
        routes.failed(PlaybackRoutes.SHORTPLAY, 10, true)
        routes.failed(PlaybackRoutes.PHOENIX, 11, true)
        routes.success(PlaybackRoutes.SHORTPLAY, 12)
        assertEquals(listOf(PlaybackRoutes.SHORTPLAY), routes.plan(12))
        assertEquals(PlaybackRoutes.POST_ROUTES, routes.plan(11 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS))
    }

    @Test fun exhaustedRequiresEveryRouteEmptyCooled() {
        val routes = PlaybackRoutes()
        assertFalse(routes.exhausted(0))
        routes.failed(PlaybackRoutes.SHORTPLAY, 10, true)
        // 一条空、一条仅传输失败，不算完整空轮次。
        routes.failed(PlaybackRoutes.PHOENIX, 10, false)
        assertFalse(routes.exhausted(20))
        routes.failed(PlaybackRoutes.PHOENIX, 20, true)
        assertTrue(routes.exhausted(20))
        // 冷却结束后必须重新探测。
        assertFalse(routes.exhausted(20 + PlaybackRoutes.EMPTY_COOLDOWN_MILLIS))
    }
}
