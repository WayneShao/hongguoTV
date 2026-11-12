package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.RelatedWork
import com.hongguotv.core.Series
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [21, 28])
class RelatedWorksViewTest {
    private fun descendants(view: View): List<View> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } + view
        else listOf(view)

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()

    @Test @Config(qualifiers = "w960dp-h540dp-xhdpi")
    fun allSeasonsCellsStayInsideTheMeasuredViewportAfterResize() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            val strip = RelatedWorksView(activity, emptyList(), { _, _ -> }, { "预约" }, { _, _ -> }, {})
            activity.setContentView(strip)
            strip.show((1..44).map { RelatedWork(Series("$it", "长剧第${it}季"), "第${it}季", false, it) })
            texts(strip).single { it.text.toString().startsWith("全部季 /") }.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val grid = descendants(dialog.window!!.decorView).filterIsInstance<GridLayout>().single()
            val viewport = grid.parent as ViewGroup
            val panel = viewport.parent as ViewGroup
            // The dialog/eye viewport, not the 1920px display, constrains the content.
            for (width in listOf(960, 640, 1100)) {
                panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
                    View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.AT_MOST))
                panel.layout(0, 0, panel.measuredWidth, panel.measuredHeight)
                assertTrue(viewport.width <= width - panel.paddingLeft - panel.paddingRight)
                for (index in 0 until grid.childCount) {
                    val cell = grid.getChildAt(index)
                    val left = grid.left + cell.left
                    val right = grid.left + cell.right
                    assertTrue("cell $index has positive width", cell.width > 0)
                    assertTrue("cell $index left=$left", left >= viewport.paddingLeft)
                    assertTrue("cell $index right=$right viewport=${viewport.width}",
                        right <= viewport.width - viewport.paddingRight)
                    if (index % 4 != 0) {
                        val previous = grid.getChildAt(index - 1)
                        assertEquals(previous.top, cell.top)
                        assertTrue(previous.right <= cell.left)
                    }
                }
                assertTrue("44 items remain vertically scrollable", grid.height > viewport.height)
            }
            dialog.dismiss()
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun relatedStripOnlyLinksTheActualLastEpisodeRow() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            for (columns in listOf(5, 10)) {
                val page = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
                activity.setContentView(page)
                val upper = LinearLayout(activity)
                val lower = LinearLayout(activity)
                page.addView(upper); page.addView(lower)
                val episodes = (0 until columns + 2).map { index ->
                    TextView(activity).apply {
                        id = View.generateViewId()
                        nextFocusDownId = id
                        (if (index < columns) upper else lower).addView(this)
                    }
                }
                val strip = RelatedWorksView(activity, episodes, { _, _ -> }, { "预约" }, { _, _ -> }, {})
                page.addView(strip)
                assertEquals(episodes[columns - 1].id, episodes[columns - 1].nextFocusDownId)
                assertEquals(texts(strip).single { it.text == "刷新" }.id, episodes.last().nextFocusDownId)
                strip.show(listOf(RelatedWork(Series("99", "长剧第三季"), "第3季", false, 3)))
                assertEquals(episodes[columns - 1].id, episodes[columns - 1].nextFocusDownId)
                assertNotEquals(episodes.last().id, episodes.last().nextFocusDownId)
            }
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun allSeasonsGridCanOpenTheFortiethSeasonAndClosesOnDetach() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        var selected: RelatedWork? = null
        val strip = RelatedWorksView(activity, emptyList(), { _, _ -> }, { "预约" }, { work, _ -> selected = work }, {})
        try {
            activity.setContentView(strip)
            val rows = (1..40).map { RelatedWork(Series("$it", "长剧第${it}季"), "第${it}季", false, it) }
            strip.show(rows)
            texts(strip).single { it.text.toString().startsWith("全部季 /") }.performClick()
            val dialog = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            val grid = descendants(dialog.window!!.decorView).filterIsInstance<GridLayout>().single()
            assertEquals(40, grid.childCount)
            assertEquals(40, descendants(dialog.window!!.decorView).filter { it.tag?.toString()?.startsWith("all-") == true }
                .sumOf { descendants(it).filterIsInstance<android.widget.ImageView>().size })
            descendants(dialog.window!!.decorView).single { it.tag == "all-40" }.performClick()
            assertEquals("40", selected?.series?.id)
            assertFalse(dialog.isShowing)

            texts(strip).single { it.text.toString().startsWith("全部季 /") }.performClick()
            val second = requireNotNull(ShadowAlertDialog.getLatestAlertDialog())
            assertTrue(second.isShowing)
            activity.setContentView(TextView(activity))
            assertFalse(second.isShowing)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test fun stripLinksEpisodeFocusAndShowsReservationState() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val episodes = (1..2).map {
            TextView(activity).apply { id = View.generateViewId(); text = "第${it}集" }
        }
        var reserved = true
        var opened: RelatedWork? = null
        val strip = RelatedWorksView(
            activity,
            episodes,
            { _, _ -> },
            { if (reserved) "已预约 · 取消预约" else "待上线 · 预约" },
            { work, _ -> opened = work },
            {},
        )
        try {
            activity.setContentView(strip)
            val playable = RelatedWork(Series("1", "长剧第一季"), "第1季", false, 1)
            val upcoming = RelatedWork(Series("2", "长剧第二季"), "第2季", true, 2)
            strip.show(listOf(playable, upcoming))
            val refresh = texts(strip).single { it.text.toString() == "刷新" }
            val all = texts(strip).single { it.text.toString().startsWith("全部季 /") }
            assertEquals(episodes.last().id, refresh.nextFocusUpId)
            assertEquals(refresh.id, all.nextFocusUpId)
            assertEquals(all.id, refresh.nextFocusDownId)
            val firstCard = descendants(strip).first {
                it.contentDescription?.toString()?.startsWith("长剧第一季") == true
            }
            assertEquals(firstCard.id, episodes.first().nextFocusDownId)
            assertEquals("已预约 · 取消预约", texts(strip).single { it.text.toString() == "已预约 · 取消预约" }.text.toString())
            val card = descendants(strip).single {
                it.contentDescription?.toString()?.startsWith("长剧第二季") == true
            }
            card.performClick()
            assertEquals(upcoming, opened)
            reserved = false
            strip.refreshLabels()
            assertTrue(texts(strip).any { it.text.toString() == "待上线 · 预约" })
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
