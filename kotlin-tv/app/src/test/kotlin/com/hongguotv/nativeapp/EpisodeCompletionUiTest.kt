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
        assertEquals("3", EpisodeLabels.label(3, true))
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

    @Test fun `completion mark is drawn after the number and follows the label colour`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val cell = TextView(activity).apply {
            text = EpisodeLabels.label(3, true)
            textSize = 15f
            setTextColor(TvStyle.text)
        }
        assertFalse(cell.text.contains('✓'))
        EpisodeLabels.completion(cell, true)
        val mark = cell.compoundDrawables[2] as TvTextGlyph
        assertEquals(TvTextGlyph.Kind.CHECK, mark.kind)
        assertEquals(mark.getIntrinsicWidth(), mark.getIntrinsicHeight())
        assertTrue(mark.getIntrinsicWidth() > 0)
        assertTrue(mark.isStateful())
        assertEquals(TvStyle.text, mark.color)
        // A focus treatment that tints the label text must tint the drawn mark as well.
        cell.setTextColor(TvStyle.accent)
        assertEquals(TvStyle.accent, mark.color)
        EpisodeLabels.completion(cell, false)
        assertNull(cell.compoundDrawables[2])
        assertEquals(0, cell.compoundDrawablePadding)
        assertEquals("3", cell.text.toString())
    }

    @Test fun `panel view marks completions and focuses the current episode across a page boundary`() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        // Episode 21 starts the second page, episode 26 ends it; both are the current and completed cell.
        listOf(20, 25).forEach { current ->
            val panel = EpisodePanel(activity, 26, current, { _, _, _ -> }, {}, {}, setOf(0, 2, 20, 25))
            panel.show()
            ShadowLooper.idleMainLooper()
            val labels = (ShadowDialog.getLatestDialog().window!!.decorView as ViewGroup).textViews()
            val cell = labels.first { it.text.toString() == "${current + 1}" }
            assertTrue("current episode keeps focus", cell.hasFocus())
            assertEquals("第 ${current + 1} 集，当前播放，已看完", cell.contentDescription)
            assertEquals(TvTextGlyph.Kind.CHECK, (cell.compoundDrawables[2] as TvTextGlyph).kind)
            assertNull("the check is not prefixed to the number", cell.compoundDrawables[0])
            assertTrue(labels.any { it.text == "已看完 4 / 26 集" })
            assertNull("uncompleted episodes stay clear", labels.first { it.text.toString() == "23" }.compoundDrawables[2])
            assertFalse(labels.any { it.text.contains('✓') })
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
