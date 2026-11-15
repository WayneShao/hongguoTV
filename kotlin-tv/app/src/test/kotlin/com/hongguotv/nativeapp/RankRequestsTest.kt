// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.ComicRanking
import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankBrowserState
import com.hongguotv.core.RankPosition
import com.hongguotv.core.RankSnapshot
import com.hongguotv.core.Series
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 榜单请求的归属：离开界面作废在途请求，晚到的旧结果不能清掉新请求的状态。 */
class RankRequestsTest {
    private fun snapshot(board: RankBoard, title: String): RankSnapshot =
        RankSnapshot(
            board,
            listOf(Series("1", title)),
            ComicRanking(mapOf("1" to RankPosition(1, "热度 1 万")), 1, ""),
        )

    @Test
    fun cancelledRequestCannotClearANewerRefresh() {
        val state = RankBrowserState()
        val requests = RankRequests(state)
        val stale = requests.begin(RankBoard.ALL)!!
        requests.cancelAll()
        assertFalse("停掉后不能还停在加载态", state.isRefreshing(RankBoard.ALL))
        val fresh = requests.begin(RankBoard.ALL)!!
        assertTrue("重新进入要能发起新请求", fresh > stale)

        assertFalse("旧结果不属于这次请求", requests.finish(RankBoard.ALL, stale, snapshot(RankBoard.ALL, "旧榜剧")))
        assertTrue("新请求仍在途标记不能被旧结果清掉", state.isRefreshing(RankBoard.ALL))
        assertNull("被取消的旧结果不能写缓存", state.snapshots[RankBoard.ALL])
    }

    @Test
    fun cancelledFailureKeepsThePreviousBoard() {
        val state = RankBrowserState()
        val requests = RankRequests(state)
        state.endRefresh(RankBoard.REAL, snapshot(RankBoard.REAL, "旧榜剧"))
        val stale = requests.begin(RankBoard.REAL)!!
        requests.cancelAll()
        val fresh = requests.begin(RankBoard.REAL)!!

        assertFalse(requests.finish(RankBoard.REAL, stale, null))
        assertEquals("失败或被取消都要留着旧榜单", "旧榜剧", state.snapshots.getValue(RankBoard.REAL).items.single().title)
        assertTrue(state.isRefreshing(RankBoard.REAL))

        assertTrue(requests.finish(RankBoard.REAL, fresh, snapshot(RankBoard.REAL, "新榜剧")))
        assertEquals("新榜剧", state.snapshots.getValue(RankBoard.REAL).items.single().title)
        assertFalse(state.isRefreshing(RankBoard.REAL))
    }

    @Test
    fun boardsStayIndependent() {
        val state = RankBrowserState()
        val requests = RankRequests(state)
        val all = requests.begin(RankBoard.ALL)!!
        val real = requests.begin(RankBoard.REAL)!!
        assertNull("同一榜不重复发起", requests.begin(RankBoard.ALL))
        assertTrue(state.isRefreshing(RankBoard.ALL) && state.isRefreshing(RankBoard.REAL))

        assertTrue(requests.finish(RankBoard.ALL, all, snapshot(RankBoard.ALL, "综合榜")))
        assertTrue("一个榜结束不影响另一个榜", state.isRefreshing(RankBoard.REAL))
        assertFalse(state.isRefreshing(RankBoard.ALL))
        assertTrue(requests.finish(RankBoard.REAL, real, null))
    }

    @Test
    fun cancelAllOnlyDropsInFlightRequests() {
        val state = RankBrowserState()
        val requests = RankRequests(state)
        state.endRefresh(RankBoard.COMIC, snapshot(RankBoard.COMIC, "漫画剧"))
        val ticket = requests.begin(RankBoard.COMIC)!!
        requests.cancelAll()

        assertFalse(state.isRefreshing(RankBoard.COMIC))
        assertEquals("缓存不能被取消清掉", "漫画剧", state.snapshots.getValue(RankBoard.COMIC).items.single().title)
        assertFalse("作废后回来的结果不再认领", requests.finish(RankBoard.COMIC, ticket, snapshot(RankBoard.COMIC, "迟到的剧")))
        assertEquals("漫画剧", state.snapshots.getValue(RankBoard.COMIC).items.single().title)
    }
}
