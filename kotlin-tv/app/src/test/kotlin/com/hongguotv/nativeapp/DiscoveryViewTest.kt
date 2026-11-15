// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.DiscoveryCursor
import com.hongguotv.core.DiscoveryFilter
import com.hongguotv.core.DiscoveryGroup
import com.hongguotv.core.DiscoverySelection
import com.hongguotv.core.FilteredDiscoveryPage
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w960dp-h540dp-xhdpi")
class DiscoveryViewTest {
    private fun item(id: String) = Series(id, "剧$id", cover = "https://cdn/$id.jpg", tags = "都市")

    private fun groups() =
        listOf(
            DiscoveryGroup(
                "genre",
                "类型",
                listOf(DiscoveryFilter("comic_series", "漫剧"), DiscoveryFilter("ai_series", "AI剧")),
            ),
            DiscoveryGroup(
                "category_dim_theme",
                "主题",
                listOf(DiscoveryFilter("theme_a", "甜宠"), DiscoveryFilter("theme_b", "奇幻")),
            ),
        )

    private fun page(ids: List<String>, next: DiscoveryCursor? = null) =
        FilteredDiscoveryPage(ids.map(::item), groups(), next)

    private fun cursor() = DiscoveryCursor(24, "session-1", 2)

    private fun descendants(view: View): List<View> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } + view
        else listOf(view)

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun text(view: View, label: String) = texts(view).first { it.text.toString() == label }

    @Test
    fun stalePageCannotReplaceANewerChipSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<(Result<FilteredDiscoveryPage>) -> Unit>()
        val view = DiscoveryView(
            activity,
            DiscoveryController(),
            { _, _ -> },
            { _, _ -> },
            { _, _, done -> calls += done },
        )
        activity.setContentView(view)
        view.start(); idle()
        assertEquals(1, calls.size)
        calls[0](Result.success(page(listOf("1", "2")))); idle()
        assertTrue(texts(view).any { it.text.toString() == "剧1" })
        assertFalse("一切正常时不显示重试", text(view, "重试").isShown)

        text(view, "更多条件").performClick(); idle()
        text(view, "甜宠").performClick()
        val stale = calls.last()
        text(view, "奇幻").performClick()
        val latest = calls.last()
        assertEquals(3, calls.size)

        stale(Result.success(page(listOf("9")))); idle()
        assertFalse("晚到的旧条件结果必须被丢弃", texts(view).any { it.text.toString() == "剧9" })
        latest(Result.success(page(listOf("8")))); idle()
        assertTrue(texts(view).any { it.text.toString() == "剧8" })
    }

    @Test
    fun failedLoadMoreKeepsResultsAndRetryRepeatsTheSamePage() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<(Result<FilteredDiscoveryPage>) -> Unit>()
        val cursors = mutableListOf<DiscoveryCursor?>()
        val view = DiscoveryView(
            activity,
            DiscoveryController(),
            { _, _ -> },
            { _, _ -> },
            { _, cursor, done -> cursors += cursor; calls += done },
        )
        activity.setContentView(view)
        view.start(); idle()
        calls[0](Result.success(page(listOf("1"), cursor()))); idle()

        text(view, "加载更多").performClick()
        assertEquals(24, cursors.last()?.offset)
        calls.last()(Result.failure(java.net.UnknownHostException("offline"))); idle()
        assertTrue("失败后已显示的结果要留下", texts(view).any { it.text.toString() == "剧1" })
        assertTrue(texts(view).any { it.text.toString().contains("无法连接内容网站") })
        assertTrue("失败后重试键才出现", text(view, "重试").isShown)

        text(view, "重试").performClick()
        assertEquals("重试应续页而不是整页重来", 24, cursors.last()?.offset)
        calls.last()(Result.success(page(listOf("2")))); idle()
        assertTrue(texts(view).any { it.text.toString() == "剧2" })
        assertTrue("续页不重复首屏", texts(view).any { it.text.toString() == "剧1" })
    }

    @Test
    fun returningFromDetailRestoresTheRememberedPoster() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val controller = DiscoveryController()
        val calls = mutableListOf<(Result<FilteredDiscoveryPage>) -> Unit>()
        val fetch: (DiscoverySelection, DiscoveryCursor?, (Result<FilteredDiscoveryPage>) -> Unit) -> Unit =
            { _, _, done -> calls += done }
        val view = DiscoveryView(activity, controller, { _, _ -> }, { _, _ -> }, fetch)
        activity.setContentView(view)
        view.start(); idle()
        calls[0](Result.success(page(listOf("1", "2")))); idle()

        val first = descendants(view).first { it.tag == "1" }
        first.requestFocus(); idle()
        assertTrue("海报可获得焦点", first.hasFocus())
        view.capture()
        assertEquals("1", controller.state.position?.first)

        val restored = DiscoveryView(activity, controller, { _, _ -> }, { _, _ -> }, fetch)
        activity.setContentView(restored)
        restored.start(); idle()
        assertTrue("返回后焦点回到原海报", descendants(restored).first { it.tag == "1" }.hasFocus())
        assertEquals("已有结果时不再重复请求", 1, calls.size)
    }

    @Test
    fun collapsingHidesExtraConditionsButKeepsTheGenreRow() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val calls = mutableListOf<(Result<FilteredDiscoveryPage>) -> Unit>()
        val controller = DiscoveryController()
        val view = DiscoveryView(activity, controller, { _, _ -> }, { _, _ -> }, { _, _, done -> calls += done })
        activity.setContentView(view)
        view.start(); idle()
        calls[0](Result.success(page(listOf("1")))); idle()

        val theme = text(view, "甜宠")
        assertFalse(controller.state.expanded)
        assertTrue("未展开时类型行仍可见", text(view, "漫剧").isShown)
        assertFalse("未展开时其它条件收起", theme.isShown)
        text(view, "更多条件").performClick(); idle()
        assertTrue(controller.state.expanded)
        assertTrue(theme.isShown)
    }
}
