// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.DiscoveryBrowserState
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 多条件找剧的请求序号：晚到的旧响应不能覆盖用户刚选的结果。 */
class DiscoveryControllerTest {
    private fun item(id: String) = Series(id, "剧$id", tags = "都市")

    private fun filterGroups() =
        listOf(
            DiscoveryGroup("genre", "类型", listOf(DiscoveryFilter("comic_series", "漫剧"))),
            DiscoveryGroup("category_dim_theme", "主题", listOf(DiscoveryFilter("theme_a", "甜宠"))),
        )

    private fun page(ids: List<String>, next: DiscoveryCursor? = null) =
        FilteredDiscoveryPage(ids.map(::item), filterGroups(), next)

    private fun cursor() = DiscoveryCursor(24, "session-1", 2)

    private fun controller() = DiscoveryController(DiscoveryBrowserState().apply { groups = filterGroups() })

    @Test
    fun staleResponseCannotOverwriteANewerSelection() {
        val controller = controller()
        val first = controller.begin()
        assertTrue(controller.change("category_dim_theme", "theme_a"))
        val second = controller.begin()
        assertFalse("旧序号必须被丢弃", controller.succeed(first, page(listOf("1")), null))
        assertTrue(controller.items.isEmpty())
        assertTrue(controller.loading)
        assertTrue(controller.succeed(second, page(listOf("2")), null))
        assertEquals(listOf("2"), controller.items.map { it.id })
        assertFalse(controller.loading)
    }

    @Test
    fun staleFailureCannotHideANewerRequest() {
        val controller = controller()
        val first = controller.begin()
        controller.change("category_dim_theme", "theme_a")
        val second = controller.begin()
        assertFalse(controller.fail(first, "旧请求失败"))
        assertEquals("", controller.problem)
        assertTrue(controller.fail(second, "无法连接内容网站"))
        assertEquals("无法连接内容网站", controller.problem)
        assertFalse(controller.loading)
    }

    @Test
    fun genreChoiceClearsOtherConditionsAndRestartsTheList() {
        val controller = controller()
        controller.state.selection =
            DiscoverySelection(mapOf("genre" to "real_series", "category_dim_theme" to "theme_a"))
        controller.state.items = listOf(item("1"))
        controller.state.next = cursor()
        controller.state.loaded = true
        val ticket = controller.begin()
        assertTrue(controller.change("genre", "comic_series"))
        assertEquals(mapOf("genre" to "comic_series"), controller.selection.values)
        assertTrue("换类型后旧结果必须清空", controller.items.isEmpty())
        assertNull(controller.next)
        assertFalse(controller.loaded)
        assertFalse("清空同时作废在途请求", controller.succeed(ticket, page(listOf("9")), null))
        assertTrue(controller.items.isEmpty())
    }

    @Test
    fun choosingTheSameChipKeepsTheCurrentRequestAlive() {
        val controller = controller()
        assertTrue(controller.change("category_dim_theme", "theme_a"))
        val ticket = controller.begin()
        assertFalse(controller.change("category_dim_theme", "theme_a"))
        assertTrue(controller.succeed(ticket, page(listOf("1")), null))
        assertEquals(listOf("1"), controller.items.map { it.id })
    }

    @Test
    fun resetClearsEverythingAndDropsTheInFlightRequest() {
        val controller = controller()
        controller.state.selection = DiscoverySelection(mapOf("category_dim_theme" to "theme_a"))
        controller.state.items = listOf(item("1"))
        controller.state.loaded = true
        val ticket = controller.begin()
        assertTrue(controller.reset())
        assertTrue(controller.selection.values.isEmpty())
        assertTrue(controller.items.isEmpty())
        assertFalse(controller.loaded)
        assertFalse(controller.succeed(ticket, page(listOf("2")), null))
        assertFalse("已无条件下无需重置", controller.reset())
    }

    @Test
    fun cancelDropsTheInFlightResponseAndStopsLoading() {
        val controller = controller()
        val ticket = controller.begin()
        controller.cancel()
        assertFalse(controller.loading)
        assertFalse(controller.succeed(ticket, page(listOf("1")), null))
        assertFalse(controller.loaded)
    }

    @Test
    fun nextPageAppendsWithoutDuplicates() {
        val controller = controller()
        assertTrue(controller.succeed(controller.begin(), page(listOf("1", "2"), cursor()), null))
        assertTrue(controller.succeed(controller.begin(), page(listOf("2", "3")), cursor()))
        assertEquals(listOf("1", "2", "3"), controller.items.map { it.id })
        assertTrue(controller.loaded)
        assertNull(controller.next)
    }

    @Test
    fun failureKeepsAlreadyLoadedItems() {
        val controller = controller()
        assertTrue(controller.succeed(controller.begin(), page(listOf("1")), null))
        assertTrue(controller.fail(controller.begin(), "网络异常"))
        assertEquals(listOf("1"), controller.items.map { it.id })
        assertEquals("网络异常", controller.problem)
        controller.begin()
        assertEquals("重新请求时清掉旧提示", "", controller.problem)
    }

    @Test
    fun rememberKeepsTheVisiblePosterAcrossRebuilds() {
        val controller = controller()
        controller.remember("42", 3)
        assertEquals("42".to(3), controller.state.position)
        assertTrue(controller.succeed(controller.begin(), page(listOf("1")), null))
        assertEquals("结果更新不应丢掉返回焦点", "42".to(3), controller.state.position)
        controller.change("category_dim_theme", "theme_a")
        assertNull(controller.state.position)
    }
}
