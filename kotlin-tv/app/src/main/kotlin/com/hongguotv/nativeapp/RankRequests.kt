// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import com.hongguotv.core.RankBoard
import com.hongguotv.core.RankBrowserState
import com.hongguotv.core.RankSnapshot

/**
 * 榜单请求的归属。四个榜各自独立排队：同一榜只允许一个在途请求，跨榜互不影响。
 *
 * 离开界面时用 [cancelAll] 收掉在途请求，这样回来后会重新发起，
 * 不会因为「在途标记没清」而一直停在加载态。被取消的请求晚到时
 * [finish] 返回 false，既不写缓存，也不会清掉新请求的在途标记。
 */
class RankRequests(private val state: RankBrowserState) {
    private val tickets = mutableMapOf<RankBoard, Int>()

    /** 认领一次请求；该榜已有在途请求时返回 null，调用方不要再发一次。 */
    fun begin(board: RankBoard): Int? {
        if (!state.beginRefresh(board)) return null
        val ticket = (tickets[board] ?: 0) + 1
        tickets[board] = ticket
        return ticket
    }

    /** 作废全部在途请求，只清在途标记，已有的榜单一律保留。 */
    fun cancelAll() {
        RankBoard.entries.forEach { board ->
            if (!state.isRefreshing(board)) return@forEach
            tickets[board] = (tickets[board] ?: 0) + 1
            state.endRefresh(board, null)
        }
    }

    /** 请求结束时调用；返回 false 表示结果已过期，调用方不要碰界面也不要写缓存。 */
    fun finish(board: RankBoard, ticket: Int, snapshot: RankSnapshot?): Boolean {
        if (tickets[board] != ticket) return false
        state.endRefresh(board, snapshot)
        return true
    }
}
