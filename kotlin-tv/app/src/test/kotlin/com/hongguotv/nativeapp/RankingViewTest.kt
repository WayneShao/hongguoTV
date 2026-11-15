// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.hongguotv.core.ComicRanking
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankBrowserState
import com.hongguotv.core.RankPosition
import com.hongguotv.core.RankSnapshot
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w960dp-h540dp-xhdpi")
class RankingViewTest {
    private fun snapshot(board: RankBoard, rows: List<Pair<Series, Int>>): RankSnapshot {
        val positions = rows.associate { (series, rank) -> series.id to RankPosition(rank, "热度 $rank 万") }
        return RankSnapshot(board, rows.map { it.first }, ComicRanking(positions, 1, "今天 10:00 更新"))
    }

    private fun descendants(view: View): List<View> =
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } + view
        else listOf(view)

    private fun texts(view: View) = descendants(view).filterIsInstance<TextView>()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun text(view: View, label: String) = texts(view).first { it.text.toString() == label }

    private fun cards(view: View) =
        descendants(view).filter { it.tag?.toString()?.startsWith("rank:card:") == true }

    @Test
    fun cachedBoardStaysVisibleWhileRefreshing() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "旧榜剧") to 1)))
        val fetches = mutableListOf<Pair<RankBoard, Boolean>>()
        lateinit var view: RankingView
        view = RankingView(
            activity, state, { _, _ -> }, { _, _ -> },
            { board, immediate ->
                fetches += board to immediate
                if (state.beginRefresh(board)) view.render()
            },
        )
        activity.setContentView(view)
        view.start(); idle()
        assertTrue("十分钟内的缓存不发请求", fetches.isEmpty())
        assertTrue(texts(view).any { it.text.toString() == "旧榜剧" })

        text(view, "刷新榜单").performClick(); idle()
        assertEquals(listOf(RankBoard.ALL to true), fetches)
        assertTrue(state.isRefreshing(RankBoard.ALL))
        assertTrue("刷新时先显示上次结果", texts(view).any { it.text.toString() == "旧榜剧" })
        assertTrue(texts(view).any { it.text.toString().contains("正在刷新") })

        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("2", "新榜剧") to 1)))
        view.render(); idle()
        assertFalse(texts(view).any { it.text.toString() == "旧榜剧" })
        assertTrue(texts(view).any { it.text.toString() == "新榜剧" })
    }

    @Test
    fun failureKeepsThePreviousBoardAndSaysSo() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "旧榜剧") to 1)))
        val view = RankingView(activity, state, { _, _ -> }, { _, _ -> }, { _, _ -> })
        activity.setContentView(view)
        view.start(); idle()
        view.renderFailure("无法连接内容网站"); idle()
        assertTrue("失败不能清空旧榜单", texts(view).any { it.text.toString() == "旧榜剧" })
        val status = texts(view).first { it.text.toString().contains("无法连接内容网站") }
        assertTrue(status.text.toString().contains("上次结果"))
    }

    @Test
    fun switchingBoardClearsTopicAndCardsKeepOriginalRanks() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        val a = Series("1", "A剧", tags = "都市 · 甜宠")
        val b = Series("2", "B剧", tags = "都市")
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(a to 7, b to 2)))
        val fetches = mutableListOf<Pair<RankBoard, Boolean>>()
        val view = RankingView(
            activity, state, { _, _ -> }, { _, _ -> },
            { board, immediate -> fetches += board to immediate },
        )
        activity.setContentView(view)
        view.start(); idle()
        assertEquals(2, cards(view).size)
        assertTrue(cards(view).first { it.tag == "rank:card:1" }.contentDescription.toString().startsWith("第 7 名"))

        descendants(view).first { it.tag == "rank:topic:甜宠" }.performClick(); idle()
        assertEquals("甜宠", state.topic)
        assertEquals(1, cards(view).size)
        assertTrue(
            "题材筛选后仍沿用榜单原始名次",
            cards(view).single().contentDescription.toString().startsWith("第 7 名"),
        )

        descendants(view).first { it.tag == "rank:board:REAL" }.performClick(); idle()
        assertEquals(RankBoard.REAL, state.board)
        assertEquals("换榜清掉题材", "", state.topic)
        assertTrue(fetches.contains(RankBoard.REAL to false))
    }

    @Test
    fun returningFromDetailRestoresBoardFocus() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "旧榜剧") to 1)))
        val view = RankingView(activity, state, { _, _ -> }, { _, _ -> }, { _, _ -> })
        activity.setContentView(view)
        view.start(); idle()
        val card = cards(view).single()
        card.requestFocus(); idle()
        assertTrue("海报可获得焦点", card.hasFocus())
        assertEquals("1", state.selectedId)
        val saved = view.capture()
        assertEquals("rank:card:1", saved.second)

        val restored = RankingView(activity, state, { _, _ -> }, { _, _ -> }, { _, _ -> }, saved.first, saved.second)
        activity.setContentView(restored)
        restored.start(); idle()
        assertTrue(cards(restored).single().hasFocus())
    }

    @Test
    fun enteringTheTabKeepsFocusOnTheNavBarAndLinksBackUp() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "旧榜剧") to 1)))
        val tab = TextView(activity).apply {
            id = View.generateViewId(); text = "排行榜"; isFocusable = true; isFocusableInTouchMode = true
        }
        val host = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        host.addView(tab)
        val view = RankingView(activity, state, { _, _ -> }, { _, _ -> }, { _, _ -> }, 0, "", tab.id)
        host.addView(view, android.widget.LinearLayout.LayoutParams(-1, 0).apply { weight = 1f })
        activity.setContentView(host)
        tab.requestFocus(); idle()
        assertTrue("页签可获得焦点", tab.hasFocus())

        view.start(); idle()
        assertTrue("进入榜单不能把焦点从导航栏抢走", tab.hasFocus())
        val refresh = descendants(view).single { it.tag == "rank:refresh" }
        assertEquals("向上回到导航栏，避免焦点被困在榜单里", tab.id, refresh.nextFocusUpId)
    }

    @Test
    fun entryFocusIdStillPointsAtABoardAfterARebuild() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "旧榜剧") to 1)))
        val tab = TextView(activity).apply {
            id = View.generateViewId(); text = "排行榜"; isFocusable = true; isFocusableInTouchMode = true
        }
        val host = android.widget.LinearLayout(activity).apply { orientation = android.widget.LinearLayout.VERTICAL }
        host.addView(tab)
        val view = RankingView(activity, state, { _, _ -> }, { _, _ -> }, { _, _ -> }, 0, "", tab.id)
        host.addView(view, android.widget.LinearLayout.LayoutParams(-1, 0).apply { weight = 1f })
        activity.setContentView(host)
        view.start(); idle()

        tab.nextFocusDownId = view.entryId
        val entry = tab.nextFocusDownId
        val first = view.findViewById<View>(entry)
        assertEquals("导航栏按「下」要落到第一个榜签", "rank:board:ALL", first?.tag)

        // 网络回来后整页重建，导航栏的方向键不能指向已销毁的视图。
        view.render(); idle()
        assertEquals("重建后入口 id 不能变", entry, view.entryId)
        val target: View? = view.findViewById<View>(tab.nextFocusDownId)
        assertNotNull("重建后落点仍然存在", target)
        assertEquals("重建后落点仍是第一个榜签", "rank:board:ALL", target?.tag)
        assertTrue("落点仍挂在界面上", target?.isAttachedToWindow == true)
        assertTrue("落点仍可获得焦点", target?.isFocusable == true)
    }

    @Test
    fun switchingToAnUncachedBoardShowsLoadingInsteadOfAnEmptyList() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val state = RankBrowserState()
        state.endRefresh(RankBoard.ALL, snapshot(RankBoard.ALL, listOf(Series("1", "综合剧") to 1)))
        val fetches = mutableListOf<RankBoard>()
        val view = RankingView(
            activity, state, { _, _ -> }, { _, _ -> },
            { board, _ ->
                fetches += board
                // 真实入口在发起请求时同步标记在途，这里照同样顺序模拟。
                state.beginRefresh(board)
            },
        )
        activity.setContentView(view)
        view.start(); idle()

        descendants(view).first { it.tag == "rank:board:REAL" }.performClick(); idle()
        assertEquals(listOf(RankBoard.REAL), fetches)
        assertTrue(
            "换榜后焦点留在新选中的榜签上",
            descendants(view).first { it.tag == "rank:board:REAL" }.hasFocus(),
        )
        assertTrue(
            "换到没有缓存的榜要显示正在读取",
            texts(view).any { it.text.toString().contains("正在读取最新榜单") },
        )
        assertFalse(
            "不能先闪一帧「暂无内容」",
            texts(view).any { it.text.toString().contains("还没有榜单内容") },
        )
    }
}
