// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** 首页「多条件找剧」入口：可点、可达、返回后仍落回入口。 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w960dp-h540dp-xhdpi")
class HomeDiscoverEntryTest {
    private fun descendants(view: View): List<View> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } + view
        else listOf(view)

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    @Test
    fun discoverBannerIsTheFirstStopAndOpensDiscovery() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val top = TextView(activity).apply { id = View.generateViewId(); isFocusable = true }
        var opened = 0
        val home = HomeScreen(
            activity,
            top,
            { _, _ -> },
            { _, _ -> },
            { _, _ -> },
            { _, _ -> },
            {},
            { opened++ },
        )
        activity.setContentView(home)
        home.render(emptyList(), "", requestFocus = false, more = null); idle()

        val banner = descendants(home).single { it.tag == "home:discover" }
        assertEquals("首页向下首先到入口", banner.id, home.firstId())
        assertEquals(top.id, banner.nextFocusUpId)
        assertEquals(home.refresh.id, banner.nextFocusDownId)

        banner.requestFocus(); idle()
        assertTrue("入口可用遥控选中", banner.hasFocus())
        assertEquals("home:discover", home.capturePosition().focus)

        banner.performClick()
        assertEquals(1, opened)
    }

    @Test
    fun discoverEntryKeepsShelfNavigationIntact() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val top = TextView(activity).apply { id = View.generateViewId() }
        var opened = 0
        val home = HomeScreen(
            activity, top, { _, _ -> }, { _, _ -> }, { _, _ -> }, { _, _ -> }, {}, { opened++ },
        )
        activity.setContentView(home)
        val shelf = HomeScreen.Shelf(
            "hot",
            "热门推荐",
            listOf(HomeScreen.Entry(com.hongguotv.core.Series("9", "剧9"), "热播")),
            null,
        )
        home.render(listOf(shelf), "", requestFocus = false, more = null); idle()

        val banner = descendants(home).single { it.tag == "home:discover" }
        val card = descendants(home).single { it.tag == "hot:9" }
        assertEquals("下移到第一个分区", card.id, banner.nextFocusDownId)
        assertEquals("分区返回入口", banner.id, card.nextFocusUpId)
        assertEquals("焦点仍从入口开始", banner.id, home.firstId())
        assertEquals(0, opened)
    }
}
