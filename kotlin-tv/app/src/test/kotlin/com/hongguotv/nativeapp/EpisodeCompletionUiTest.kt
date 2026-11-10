package com.hongguotv.nativeapp

import android.app.Activity
import android.view.ViewGroup
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class EpisodeCompletionUiTest {
    @Test fun `detail and panel labels expose current and completed state together`() {
        assertEquals("3", EpisodeLabels.label(3, false))
        assertEquals("3✓", EpisodeLabels.label(3, true))
        assertEquals("第 3 集", EpisodeLabels.description(3, current = false, completed = false))
        assertEquals("第 3 集，当前播放", EpisodeLabels.description(3, current = true, completed = false))
        assertEquals("第 3 集，已看完", EpisodeLabels.description(3, current = false, completed = true))
        assertEquals("第 3 集，当前播放，已看完", EpisodeLabels.description(3, current = true, completed = true))
        assertEquals("已看完 2 / 5 集", EpisodeLabels.summary(2, 5))
    }

    @Test fun `completion counts only ids present in the displayed episode list`() {
        val episodes = listOf("1001", "1002", "1003", "1004")
        val completed = setOf("1001", "1003", "9999")
        assertEquals(setOf(0, 2), EpisodeLabels.indexes(episodes, completed))
        assertEquals(2, EpisodeLabels.count(episodes, completed))
        assertEquals("已看完 2 / 4 集", EpisodeLabels.summary(EpisodeLabels.count(episodes, completed), episodes.size))
    }

    @Test fun `panel view marks completions and focuses the current episode across a page boundary`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Episode 21 starts the second page, episode 26 ends it; both are the current and completed cell.
        listOf(20 to "21✓", 25 to "26✓").forEach { (current, label) ->
            val panel = EpisodePanel(activity, 26, current, { _, _, _ -> }, {}, {}, setOf(0, 2, 20, 25))
            panel.show()
            ShadowLooper.idleMainLooper()
            val labels = (ShadowDialog.getLatestDialog().window!!.decorView as ViewGroup).textViews()
            val cell = labels.first { it.text == label }
            assertTrue("current episode keeps focus", cell.hasFocus())
            assertEquals("第 ${current + 1} 集，当前播放，已看完", cell.contentDescription)
            assertTrue(labels.any { it.text == "已看完 4 / 26 集" })
            assertFalse(labels.any { it.text == "23✓" })
            panel.dismiss()
        }
    }

    private fun ViewGroup.textViews(): List<TextView> = (0 until childCount).flatMap { index ->
        when (val child = getChildAt(index)) {
            is TextView -> listOf(child)
            is ViewGroup -> child.textViews()
            else -> emptyList()
        }
    }
}
