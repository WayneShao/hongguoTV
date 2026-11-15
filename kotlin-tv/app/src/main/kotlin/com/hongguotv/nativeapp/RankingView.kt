// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.graphics.Color
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.hongguotv.core.*
import com.hongguotv.nativeapp.rayneo.RayNeoDisplay

/**
 * 四类排行榜：综合、真人剧、漫剧、AI 剧。
 * 每榜独立缓存由 [RankBrowserState] 持有，这里只负责显示缓存、发起请求与还原焦点；
 * 刷新时先显示上次结果，失败则保留旧榜单并给出重试提示。
 */
class RankingView(
    context: Context,
    private val state: RankBrowserState,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, View) -> Unit,
    private val fetch: (RankBoard, Boolean) -> Unit,
    private val startScroll: Int = 0,
    private val startFocus: String = "",
    private val upId: Int = View.NO_ID,
) : LinearLayout(context) {
    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
    private val contents = LinearLayout(context).apply { orientation = VERTICAL }
    private val refreshButton = label("刷新榜单", 13f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(36)
        id = View.generateViewId(); tag = "rank:refresh"
        isFocusable = true; isFocusableInTouchMode = true
        background = TvStyle.card(context, false)
        setOnFocusChangeListener { view, focused -> view.background = TvStyle.card(context, focused) }
    }
    private var boardButtons = emptyList<TextView>()
    private var topicButtons = emptyList<TextView>()
    private var cards = emptyList<LinearLayout>()
    // 榜签的 id 只生成一次：整页重建后导航栏按「下」仍能落到同一个控件上。
    private val boardIds = RankBoard.entries.associateWith { View.generateViewId() }
    private var problem = ""
    private var cardWidth = 0
    private var resetScroll = false
    private var pendingScroll: Int? = startScroll
    private var pendingFocus: String? = startFocus

    init {
        orientation = VERTICAL
        refreshButton.setOnClickListener { if (!state.isRefreshing(state.board)) fetch(state.board, true) }
        val heading = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(8), 0, dp(6))
        }
        heading.addView(label("排行榜", 25f).apply { typeface = android.graphics.Typeface.DEFAULT_BOLD }, lp(0, -2).apply { weight = 1f })
        heading.addView(refreshButton, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT })
        addView(heading, lp(-1, -2))
        scroll.addView(contents)
        addView(scroll, lp(-1, 0).apply { weight = 1f })
    }

    /** 成功返回后由入口调用；清掉上一次的失败提示。 */
    fun render() {
        problem = ""
        rebuild()
    }

    /** 失败时保留已有榜单，只把失败写在状态行。 */
    fun renderFailure(message: String) {
        problem = message
        rebuild()
    }

    private fun rebuild() {
        val focused = pendingFocus ?: findFocus()?.tag?.toString()
        val vertical = when {
            resetScroll -> 0
            pendingScroll != null -> pendingScroll!!
            else -> scroll.scrollY
        }
        resetScroll = false
        pendingScroll = null
        pendingFocus = null
        contents.removeAllViews()
        boardButtons = emptyList()
        topicButtons = emptyList()
        cards = emptyList()
        val snapshot = state.snapshots[state.board]
        buildBoards()
        buildTopics(snapshot)
        contents.addView(label(statusText(snapshot), 12f, if (problem.isNotBlank()) TvStyle.accent else TvStyle.muted).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(6))
        }, lp(-1, -2))
        if (snapshot != null) buildGrid(snapshot)
        refreshButton.text = if (state.isRefreshing(state.board)) "正在刷新…" else "刷新榜单"
        refreshButton.alpha = if (state.isRefreshing(state.board)) .5f else 1f
        linkFocus()
        post {
            scroll.scrollTo(0, vertical)
            val target = focused?.let(::viewByTag)
            if (target != null && target.isAttachedToWindow) target.requestFocus()
            // 只有整页都没有焦点时才落进来，切页签时不会把焦点从导航栏抢走。
            else if (rootView?.findFocus() == null) {
                val remembered = cards.firstOrNull { it.tag == "rank:card:" + state.selectedId }
                (remembered ?: boardButtons.firstOrNull() ?: refreshButton).requestFocus()
            }
        }
    }

    private fun buildBoards() {
        val line = LinearLayout(context).apply {
            orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setPadding(0, dp(2), 0, dp(2))
        }
        val built = mutableListOf<TextView>()
        RankBoard.entries.forEach { board ->
            val view = chip(board.label, state.board == board) { selectBoard(board) }
            // 复用固定的 id，重建不会让导航栏的方向键指向已销毁的视图。
            view.id = boardIds.getValue(board)
            view.tag = "rank:board:" + board.name
            view.contentDescription = "榜单 " + board.label
            line.addView(view, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin = dp(8) })
            built += view
        }
        contents.addView(line, lp(-1, -2))
        boardButtons = built
    }

    private fun buildTopics(snapshot: RankSnapshot?) {
        val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        line.addView(label("题材", 12f, TvStyle.muted), lp(dp(44), -2))
        val strip = HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            clipToPadding = false
            isFocusable = false
        }
        val stripRow = LinearLayout(context).apply { orientation = HORIZONTAL }
        strip.addView(stripRow)
        line.addView(strip, lp(0, -2).apply { weight = 1f })
        val built = mutableListOf<TextView>()
        fun option(name: String) {
            val view = chip(if (name.isBlank()) "全部" else name, state.topic == name) { selectTopic(name) }
            view.tag = "rank:topic:" + name
            stripRow.addView(view, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin = dp(6) })
            built += view
        }
        option("")
        snapshot?.topics.orEmpty().take(24).forEach { option(it) }
        contents.addView(line, lp(-1, -2).apply { topMargin = dp(2) })
        topicButtons = built
    }

    private fun buildGrid(snapshot: RankSnapshot) {
        val rows = snapshot.filtered(state.topic)
        if (rows.isEmpty()) {
            contents.addView(label(
                if (state.topic.isBlank()) "榜单暂时没有内容。" else "该题材下暂无剧集，可切换题材。",
                14f, TvStyle.muted,
            ).apply { setPadding(0, dp(10), 0, dp(10)) }, lp(-1, -2))
            return
        }
        val columns = RayNeoDisplay.columns(context)
        val gap = dp(14)
        cardWidth = ((RayNeoDisplay.eyeWidth(context) * .9f - gap * (columns - 1)) / columns).toInt().coerceAtLeast(dp(92))
        val built = mutableListOf<LinearLayout>()
        rows.chunked(columns).forEach { lineItems ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
            lineItems.forEachIndexed { index, item ->
                val view = card(item, snapshot, built.size)
                line.addView(view, lp(cardWidth, -1).apply { if (index < columns - 1) rightMargin = gap })
                built += view
            }
            contents.addView(line, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        cards = built
    }

    /** 名次沿用榜单原始名次，题材筛选只收起卡片，不重新编号。 */
    private fun card(item: Series, snapshot: RankSnapshot, index: Int): LinearLayout {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val position = snapshot.ranking.positions[item.id]
        val rank = position?.rank ?: (snapshot.items.indexOfFirst { it.id == item.id } + 1).takeIf { it > 0 } ?: (index + 1)
        val heat = position?.heat.orEmpty()
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(9))
            id = View.generateViewId()
            tag = "rank:card:" + item.id
            isFocusable = true; isFocusableInTouchMode = true
            contentDescription = "第 " + rank + " 名，" + item.title + (if (heat.isNotBlank()) "，热度 " + heat else "")
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { view, focused ->
                view.background = TvStyle.card(context, focused)
                if (focused) {
                    state.selectedId = item.id
                    loadCover(image, item.cover)
                }
            }
            setOnClickListener {
                state.selectedId = item.id
                open(item, this)
            }
        }
        val artwork = FrameLayout(context)
        card.addView(artwork, lp(-1, ((cardWidth - dp(8)) / TvStyle.POSTER_ASPECT).toInt()))
        artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
        artwork.addView(
            TextView(context).apply {
                text = rank.toString()
                textSize = 13f
                gravity = Gravity.CENTER
                setTextColor(if (rank <= 3) Color.rgb(38, 24, 20) else TvStyle.text)
                setPadding(dp(8), dp(3), dp(8), dp(3))
                background = TvStyle.shape(
                    context,
                    if (rank <= 3) TvStyle.accent else Color.rgb(20, 26, 34),
                    Color.TRANSPARENT,
                    8,
                )
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            },
            FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START).apply {
                topMargin = dp(6); leftMargin = dp(6)
            },
        )
        // 首屏卡片立即取图，其余等获得焦点再取，避免一次铺开太多请求。
        if (index < RayNeoDisplay.columns(context) * 3) loadCover(image, item.cover)
        card.addView(
            label(item.title, 13f).apply {
                maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(3), dp(6), dp(3), 0)
            },
            lp(-1, -2),
        )
        card.addView(
            label(heat.ifBlank { item.badge.ifBlank { item.tags } }, 11f, TvStyle.muted).apply {
                maxLines = 1; minLines = 1; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(3), 0, 0, 0)
            },
            lp(-1, -2),
        )
        return card
    }

    private fun statusText(snapshot: RankSnapshot?): String {
        val refreshing = state.isRefreshing(state.board)
        return when {
            problem.isNotBlank() -> problem + if (snapshot != null) "（当前仍显示上次结果）" else ""
            snapshot == null -> if (refreshing) "正在读取最新榜单…" else "还没有榜单内容，可按刷新重试。"
            refreshing -> "正在刷新… 当前显示上次缓存。"
            else -> buildString {
                append("共 ").append(snapshot.items.size).append(" 部")
                snapshot.ranking.updatedText.takeIf { it.isNotBlank() }?.let { append(" · ").append(it) }
                if (!state.isFresh(state.board)) append(" · 内容可能已过期，可刷新")
            }
        }
    }

    private fun selectBoard(board: RankBoard) {
        if (state.board == board) return
        state.select(board)
        problem = ""
        resetScroll = true
        // 换榜后焦点留在刚选中的榜签上，不会跳回第一个。
        pendingFocus = "rank:board:" + board.name
        // 先让请求进入在途状态再重建：换成没缓存过的榜时显示「正在读取」而不是「暂无内容」。
        if (!state.isFresh(board) && !state.isRefreshing(board)) fetch(board, false)
        rebuild()
    }

    private fun selectTopic(name: String) {
        if (state.topic == name) return
        state.filter(name)
        rebuild()
    }

    /** 离开榜单或进入详情前记下滚动与焦点，返回时由入口传入还原。 */
    fun capture(): Pair<Int, String> {
        val focused = findFocus()?.tag?.toString().orEmpty()
        if (focused.startsWith("rank:card:")) state.selectedId = focused.removePrefix("rank:card:")
        return scroll.scrollY to focused
    }

    /** 进入榜单：先用缓存铺一层，过期或无缓存时再拉取。 */
    fun start() {
        if (!state.isFresh(state.board) && !state.isRefreshing(state.board)) fetch(state.board, false)
        rebuild()
    }

    /** 导航栏按「下」进入的第一个控件；榜签 id 固定，整页重建后依然有效。 */
    val entryId: Int get() = boardButtons.firstOrNull()?.id ?: refreshButton.id

    private fun linkFocus() {
        val boards = boardButtons
        val topics = topicButtons
        refreshButton.nextFocusLeftId = refreshButton.id
        refreshButton.nextFocusRightId = refreshButton.id
        refreshButton.nextFocusUpId = upId
        refreshButton.nextFocusDownId = boards.firstOrNull()?.id ?: refreshButton.id
        boards.forEachIndexed { index, view ->
            view.nextFocusLeftId = boards[(index - 1).coerceAtLeast(0)].id
            view.nextFocusRightId = boards[(index + 1).coerceAtMost(boards.lastIndex)].id
            view.nextFocusUpId = refreshButton.id
            view.nextFocusDownId = topics.firstOrNull()?.id ?: cards.firstOrNull()?.id ?: refreshButton.id
        }
        topics.forEachIndexed { index, view ->
            view.nextFocusLeftId = topics[(index - 1).coerceAtLeast(0)].id
            view.nextFocusRightId = topics[(index + 1).coerceAtMost(topics.lastIndex)].id
            view.nextFocusUpId = boards.firstOrNull()?.id ?: refreshButton.id
            view.nextFocusDownId = cards.firstOrNull()?.id ?: refreshButton.id
        }
        val columns = RayNeoDisplay.columns(context)
        cards.forEachIndexed { index, view ->
            view.nextFocusLeftId = if (index % columns == 0) view.id else cards[index - 1].id
            view.nextFocusRightId = if (index % columns == columns - 1 || index == cards.lastIndex) view.id else cards[index + 1].id
            view.nextFocusUpId = if (index < columns) (topics.firstOrNull()?.id ?: refreshButton.id) else cards[index - columns].id
            view.nextFocusDownId = if (index + columns < cards.size) cards[index + columns].id else refreshButton.id
        }
    }

    private fun viewByTag(tag: String): View? {
        val all = mutableListOf<View>()
        all += boardButtons; all += topicButtons; all += cards
        return all.firstOrNull { it.tag == tag }
    }

    private fun chip(value: String, selected: Boolean, onClick: () -> Unit) = label(value, 13f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(11), dp(6), dp(11), dp(6)); minHeight = dp(34)
        id = View.generateViewId()
        isFocusable = true; isFocusableInTouchMode = true
        setTextColor(if (selected) TvStyle.accent else TvStyle.text)
        background = chipShape(selected, false)
        setOnClickListener { onClick() }
        setOnFocusChangeListener { view, focused ->
            setTextColor(if (selected || focused) TvStyle.accent else TvStyle.text)
            view.background = chipShape(selected, focused)
        }
    }

    private fun chipShape(selected: Boolean, focused: Boolean) =
        TvStyle.shape(
            context,
            if (focused) TvStyle.raised else if (selected) Color.rgb(61, 39, 34) else TvStyle.surface,
            if (focused) TvStyle.accent else if (selected) Color.rgb(122, 62, 47) else Color.TRANSPARENT,
        )

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) =
        LinearLayout.LayoutParams(w, h)

    private fun label(value: String, size: Float, color: Int = TvStyle.text) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }
}
