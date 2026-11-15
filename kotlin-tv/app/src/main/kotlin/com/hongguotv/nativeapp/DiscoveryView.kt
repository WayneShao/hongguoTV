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
 * 多条件找剧的请求序号。每次接受新条件、重试或取消都会推进 [ticket]，
 * 因此晚到的旧响应只会被丢弃，不会覆盖用户刚选的结果。
 */
class DiscoveryController(val state: DiscoveryBrowserState = DiscoveryBrowserState()) {
    var loading = false
        private set
    var problem = ""
        private set
    private var ticket = 0

    val selection: DiscoverySelection get() = state.selection
    val groups: List<DiscoveryGroup> get() = state.groups
    val items: List<Series> get() = state.items
    val next: DiscoveryCursor? get() = state.next
    val loaded: Boolean get() = state.loaded

    /** 类型之间分类体系不同，换类型时由核心清掉不适用条件。 */
    fun change(type: String, id: String): Boolean {
        val next = try {
            state.selection.choose(type, id)
        } catch (_: IllegalArgumentException) {
            return false
        }
        if (next == state.selection) return false
        state.selection = next
        discard()
        return true
    }

    fun reset(): Boolean {
        if (state.selection.values.isEmpty()) return false
        state.selection = DiscoverySelection()
        discard()
        return true
    }

    /** 开始一次请求；返回的序号只允许一个响应落地。 */
    fun begin(): Int {
        ticket++
        loading = true
        problem = ""
        return ticket
    }

    /** 离开页面或暂停时丢弃在途响应。 */
    fun cancel() {
        ticket++
        loading = false
    }

    fun succeed(ticket: Int, page: FilteredDiscoveryPage, cursor: DiscoveryCursor?): Boolean {
        if (ticket != this.ticket) return false
        if (cursor == null) {
            state.items = page.items
        } else {
            val known = state.items.map { it.id }.toSet()
            state.items = state.items + page.items.filterNot { it.id in known }
        }
        if (page.groups.isNotEmpty()) state.groups = page.groups
        state.next = page.next
        state.loaded = true
        loading = false
        problem = ""
        return true
    }

    fun fail(ticket: Int, message: String): Boolean {
        if (ticket != this.ticket) return false
        loading = false
        problem = message.ifBlank { "暂时无法加载，请重试。" }
        return true
    }

    fun remember(seriesId: String, index: Int) {
        state.position = seriesId to index
    }

    /** 条件变化后旧结果不再对应当前选择，先清空再等待新结果。 */
    private fun discard() {
        ticket++
        state.items = emptyList()
        state.next = null
        state.loaded = false
        state.position = null
        loading = false
        problem = ""
    }
}

/**
 * 多条件找剧页：条件由接口下发，结果沿用现有海报与详情导航。
 * 类型行常显，其余条件可收起；条件与结果共用一个竖向滚动，
 * 从详情返回时能同时还原条件位置和海报焦点。
 */
class DiscoveryView(
    context: Context,
    private val controller: DiscoveryController,
    private val loadCover: (ImageView, String) -> Unit,
    private val open: (Series, View) -> Unit,
    private val fetch: (DiscoverySelection, DiscoveryCursor?, (Result<FilteredDiscoveryPage>) -> Unit) -> Unit,
    private val upId: Int = View.NO_ID,
) : LinearLayout(context) {
    private data class GroupRow(val type: String, val scroll: HorizontalScrollView, val chips: List<View>)

    private val scroll = ScrollView(context).apply { isVerticalScrollBarEnabled = false; clipToPadding = false }
    private val contents = LinearLayout(context).apply { orientation = VERTICAL }
    private val genreHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val extraHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val resultHost = LinearLayout(context).apply { orientation = VERTICAL }
    private val toggle = plainButton("更多条件")
    private val reset = plainButton("重置条件")
    private val refresh = plainButton("刷新")
    private val retry = plainButton("重试")
    private val summary = label("", 12f, TvStyle.muted).apply { maxLines = 1; ellipsize = TextUtils.TruncateAt.END }
    private val status = label("", 12f, TvStyle.muted).apply {
        maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(0, dp(2), 0, dp(4))
    }
    private val chipRows = linkedMapOf<String, MutableMap<String, TextView>>()
    private var rows = emptyList<GroupRow>()
    private var cards = emptyList<LinearLayout>()
    private var more: View? = null
    private var cardWidth = 0
    private var signature = ""
    private var lastCursor: DiscoveryCursor? = null
    private var resumeTag: String? = null

    init {
        if (controller.groups.isEmpty()) controller.state.expanded = false
        orientation = VERTICAL
        toggle.setOnClickListener { controller.state.expanded = !controller.state.expanded; applyExpanded(); linkFocus() }
        reset.setOnClickListener { if (controller.reset()) { paint(); load(null) } }
        refresh.setOnClickListener { if (!controller.loading) load(null) }
        // 续页失败后重试只补那一页，不把已经显示的结果整页换掉。
        retry.setOnClickListener { if (!controller.loading && controller.problem.isNotBlank()) load(lastCursor) }
        val actions = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        listOf(toggle, reset, refresh, retry).forEach { view ->
            actions.addView(view, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin = dp(8) })
        }
        actions.addView(summary, lp(0, -2).apply { weight = 1f })
        addView(actions, lp(-1, -2))
        addView(status, lp(-1, -2))
        scroll.addView(contents)
        addView(scroll, lp(-1, 0).apply { weight = 1f })
        contents.addView(genreHost)
        contents.addView(extraHost)
        contents.addView(View(context).apply { setBackgroundColor(TvStyle.outline) }, lp(-1, dp(1)).apply { topMargin = dp(4); bottomMargin = dp(8) })
        contents.addView(resultHost)
        applyExpanded()
    }

    /** 从首页进入：已有结果直接还原，否则先画条件再按默认条件拉取一次。 */
    fun start() {
        if (controller.loaded) render(true) else {
            buildFilters()
            paint()
            applyExpanded()
            linkFocus()
            load(null)
        }
    }

    /** 条件收起时只有类型行可见，入口焦点不能落到被隐藏的行上。 */
    private fun firstChip(): View? =
        (rows.firstOrNull { controller.state.expanded || it.type == "genre" } ?: rows.firstOrNull())
            ?.chips?.firstOrNull()

    /** 导航栏按「下」或进入页面时落到的第一个控件。 */
    val entryId: Int get() = firstChip()?.id ?: toggle.id

    fun requestEntry() {
        if (findFocus() != null) return
        val target = firstChip() ?: toggle
        if (target.isAttachedToWindow) target.requestFocus()
    }

    /** 离开页面前记录滚动、条件行与海报焦点。 */
    fun capture() {
        controller.state.expanded = extraHost.visibility == View.VISIBLE
        controller.state.headerOffset = scroll.scrollY
        rows.forEach { row -> controller.state.filterOffsets[row.type] = row.scroll.scrollX }
        cards.firstOrNull { it.hasFocus() }?.let { card -> controller.remember(card.tag.toString(), cards.indexOf(card)) }
    }

    /** 详情返回后由入口调用，重建视图并把焦点放回原海报。 */
    fun render(restore: Boolean) {
        // 重建前记下当前位置：非返回场景沿用用户此刻的滚动，返回场景用离开时的记录。
        val vertical = if (restore) controller.state.headerOffset else scroll.scrollY
        val keepTag = resumeTag
        resumeTag = null
        buildFilters()
        paint()
        renderResults()
        linkFocus()
        applyExpanded()
        post {
            scroll.scrollTo(0, vertical)
            if (restore) rows.forEach { row -> row.scroll.scrollTo(controller.state.filterOffsets[row.type] ?: 0, 0) }
            // 续页成功后仍停在原来的海报或「加载更多」上，不把用户甩回列表顶部。
            val kept = keepTag?.let(::viewByTag)
            if (kept != null && kept.isAttachedToWindow) kept.requestFocus()
            else if (restore || findFocus() == null) focusRemembered()
        }
    }

    private fun viewByTag(tag: String): View? = when {
        tag == "discovery:more" -> more
        else -> cards.firstOrNull { it.tag == tag }
    }

    fun cancel() {
        controller.cancel()
        paintStatus()
        // 重试键的状态可能刚变，重新接一遍焦点链，避免留下指向隐藏按钮的方向键。
        linkFocus()
    }

    private fun load(cursor: DiscoveryCursor?) {
        val selection = controller.selection
        val ticket = controller.begin()
        lastCursor = cursor
        if (cursor == null) {
            // 换条件或刷新：旧结果已不对应当前选择，直接重画提示区。
            renderResults()
            linkFocus()
        } else {
            // 续页只把按钮改成进行中，已显示的海报保持不动。
            resumeTag = findFocus()?.tag?.toString()
            (more as? TextView)?.text = "正在加载…"
        }
        paintStatus()
        fetch(selection, cursor) { result ->
            result.onSuccess { page ->
                if (controller.succeed(ticket, page, cursor)) render(false)
            }.onFailure { problem ->
                if (controller.fail(ticket, describe(problem))) render(false)
            }
        }
    }

    /** 只在条件结构变化时重建按钮，选中态变化只重绘，避免打断遥控焦点。 */
    private fun buildFilters() {
        val next = controller.groups.joinToString("|") { group ->
            group.type + ":" + group.options.joinToString(",") { it.id + "=" + it.label }
        }
        if (next == signature) return
        signature = next
        genreHost.removeAllViews(); extraHost.removeAllViews()
        chipRows.clear()
        val built = mutableListOf<GroupRow>()
        if (controller.groups.isEmpty()) {
            genreHost.addView(label("正在读取筛选条件…", 13f, TvStyle.muted).apply { setPadding(0, dp(6), 0, dp(6)) })
        }
        controller.groups.forEach { group ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
            line.addView(label(group.label, 12f, TvStyle.muted), lp(dp(44), -2))
            val strip = HorizontalScrollView(context).apply {
                isHorizontalScrollBarEnabled = false
                clipToPadding = false
                isFocusable = false
            }
            val stripRow = LinearLayout(context).apply { orientation = HORIZONTAL }
            strip.addView(stripRow)
            line.addView(strip, lp(0, -2).apply { weight = 1f })
            val chips = mutableListOf<View>()
            val selected = mutableMapOf<String, TextView>()
            fun option(id: String, text: String) {
                val view = chip(text, group.type, id) { choose(group.type, id) }
                stripRow.addView(view, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin = dp(6) })
                selected[id] = view
                chips += view
            }
            option("", "全部")
            group.options.forEach { option(it.id, it.label) }
            chipRows[group.type] = selected
            built += GroupRow(group.type, strip, chips)
            (if (group.type == "genre") genreHost else extraHost).addView(line, lp(-1, -2).apply { topMargin = dp(2) })
        }
        rows = built
    }

    private fun choose(type: String, id: String) {
        if (!controller.change(type, id)) return
        paint()
        load(null)
    }

    private fun paint() {
        chipRows.forEach { (type, chips) ->
            val selected = controller.selection.values[type].orEmpty()
            chips.forEach { (id, view) ->
                val chosen = id == selected
                view.setTextColor(if (chosen) TvStyle.accent else TvStyle.text)
                view.background = chipShape(chosen, view.isFocused)
            }
        }
        val labels = DiscoverySelection.labels
        summary.text = controller.selection.values.entries.joinToString(" · ") { (type, id) ->
            val name = controller.groups.firstOrNull { it.type == type }?.options?.firstOrNull { it.id == id }?.label ?: "已选"
            (labels[type] ?: type) + " " + name
        }.ifBlank { "未选择条件" }
        reset.setTextColor(if (controller.selection.values.isEmpty()) TvStyle.muted else TvStyle.text)
        toggle.text = if (controller.state.expanded) "收起条件" else "更多条件"
        applyExpanded()
        paintStatus()
    }

    private fun paintStatus() {
        status.text = when {
            controller.loading -> "正在按条件查找…"
            controller.problem.isNotBlank() -> controller.problem
            controller.loaded -> "共 " + controller.items.size + " 部" + when {
                controller.next != null -> " · 还有更多"
                else -> " · 已全部显示"
            }
            else -> "选择条件，结果会即时更新。"
        }
        retry.alpha = if (controller.problem.isNotBlank() && !controller.loading) 1f else .45f
        // 没有失败时不放一个按了没反应的按钮，避免遥控焦点停在死键上。
        retry.visibility = if (controller.problem.isNotBlank() && !controller.loading) View.VISIBLE else View.GONE
        status.setTextColor(if (controller.problem.isNotBlank() && !controller.loading) TvStyle.accent else TvStyle.muted)
    }

    private fun renderResults() {
        resultHost.removeAllViews()
        cards = emptyList()
        more = null
        val items = controller.items
        if (items.isEmpty()) {
            val hint = when {
                controller.loading -> "正在按条件查找…"
                controller.problem.isNotBlank() -> "暂时无法加载，请按重试。"
                controller.loaded -> "没有符合条件的剧集，换个条件试试。"
                else -> "选择条件后，结果会显示在这里。"
            }
            resultHost.addView(label(hint, 15f, TvStyle.muted).apply { setPadding(0, dp(12), 0, dp(12)) })
            return
        }
        val columns = RayNeoDisplay.columns(context)
        val gap = dp(14)
        cardWidth = ((RayNeoDisplay.eyeWidth(context) * .9f - gap * (columns - 1)) / columns).toInt().coerceAtLeast(dp(92))
        val built = mutableListOf<LinearLayout>()
        items.chunked(columns).forEach { lineItems ->
            val line = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.TOP }
            lineItems.forEachIndexed { index, item ->
                val card = card(item, built.size)
                line.addView(card, lp(cardWidth, -1).apply { if (index < columns - 1) rightMargin = gap })
                built += card
            }
            resultHost.addView(line, lp(-1, -2).apply { bottomMargin = dp(10) })
        }
        cards = built
        val footer = LinearLayout(context).apply { orientation = HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        footer.addView(label(
            if (controller.next != null) "已显示 " + items.size + " 部 · 还有更多" else "已显示全部 " + items.size + " 部",
            12f, TvStyle.muted,
        ), lp(0, dp(40)).apply { weight = 1f })
        if (controller.next != null) {
            val next = controller.next
            val button = plainButton(if (controller.loading) "正在加载…" else "加载更多")
            button.id = View.generateViewId()
            button.tag = "discovery:more"
            button.setOnClickListener { if (!controller.loading) load(next) }
            footer.addView(button, lp().apply { width = LinearLayout.LayoutParams.WRAP_CONTENT })
            more = button
        }
        resultHost.addView(footer, lp(-1, -2))
    }

    private fun card(item: Series, index: Int): LinearLayout {
        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.FIT_CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
        val card = LinearLayout(context).apply {
            orientation = VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(9))
            id = View.generateViewId(); tag = item.id
            isFocusable = true; isFocusableInTouchMode = true
            contentDescription = item.title
            background = TvStyle.card(context, false)
            setOnFocusChangeListener { view, focused ->
                view.background = TvStyle.card(context, focused)
                if (focused) { controller.remember(item.id, index); loadCover(image, item.cover) }
            }
            setOnClickListener { controller.remember(item.id, index); open(item, this) }
        }
        val artwork = FrameLayout(context)
        card.addView(artwork, lp(-1, ((cardWidth - dp(8)) / TvStyle.POSTER_ASPECT).toInt()))
        artwork.addView(image, FrameLayout.LayoutParams(-1, -1))
        // 首屏先取图，后面的卡片等焦点到达再取，避免一次铺开太多请求。
        if (index < RayNeoDisplay.columns(context) * 3) loadCover(image, item.cover)
        card.addView(label(item.title, 13f).apply {
            maxLines = 2; minLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(3), dp(6), dp(3), 0)
        }, lp(-1, -2))
        card.addView(label(item.badge.ifBlank { item.tags }, 11f, TvStyle.muted).apply {
            maxLines = 1; minLines = 1; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(3), 0, 0, 0)
        }, lp(-1, -2))
        return card
    }

    private fun linkFocus() {
        val visible = rows.filter { controller.state.expanded || it.type == "genre" }
        val fallback = firstChip()?.id ?: reset.id
        // 重试只在失败后出现，左右链按当前可见的按钮接起来。
        val header = listOf(toggle, reset, refresh) + if (retry.visibility == View.VISIBLE) listOf(retry) else emptyList()
        header.forEachIndexed { index, view ->
            view.nextFocusLeftId = header[(index - 1 + header.size) % header.size].id
            view.nextFocusRightId = header[(index + 1) % header.size].id
            view.nextFocusDownId = fallback
            view.nextFocusUpId = upId
        }
        visible.forEachIndexed { index, row ->
            val up = if (index == 0) toggle.id else visible[index - 1].chips.first().id
            val down = if (index < visible.lastIndex) visible[index + 1].chips.first().id
                else (cards.firstOrNull()?.id ?: fallback)
            row.chips.forEachIndexed { column, chip ->
                chip.nextFocusLeftId = row.chips[(column - 1).coerceAtLeast(0)].id
                chip.nextFocusRightId = row.chips[(column + 1).coerceAtMost(row.chips.lastIndex)].id
                chip.nextFocusUpId = up
                chip.nextFocusDownId = down
            }
        }
        val columns = RayNeoDisplay.columns(context)
        cards.forEachIndexed { index, card ->
            card.nextFocusLeftId = if (index % columns == 0) card.id else cards[index - 1].id
            card.nextFocusRightId = if (index % columns == columns - 1 || index == cards.lastIndex) card.id else cards[index + 1].id
            card.nextFocusUpId = if (index < columns) fallback else cards[index - columns].id
            card.nextFocusDownId = if (index + columns < cards.size) cards[index + columns].id else (more ?: refresh).id
        }
        more?.let { button ->
            button.nextFocusUpId = cards.lastOrNull()?.id ?: fallback
            button.nextFocusDownId = toggle.id
            button.nextFocusLeftId = button.id
            button.nextFocusRightId = button.id
        }
    }

    private fun applyExpanded() {
        val expanded = controller.state.expanded
        extraHost.visibility = if (expanded) View.VISIBLE else View.GONE
        toggle.text = if (expanded) "收起条件" else "更多条件"
    }

    private fun focusRemembered() {
        if (!isAttachedToWindow) return
        val saved = controller.state.position
        val target = saved?.let { position ->
            cards.firstOrNull { it.tag == position.first } ?: cards.getOrNull(position.second)
        } ?: cards.firstOrNull() ?: firstChip() ?: toggle
        if (target.isAttachedToWindow && !target.hasFocus()) target.requestFocus()
    }

    private fun dp(n: Int) = (n * resources.displayMetrics.density).toInt()
    private fun lp(w: Int = LinearLayout.LayoutParams.MATCH_PARENT, h: Int = LinearLayout.LayoutParams.WRAP_CONTENT) = LinearLayout.LayoutParams(w, h)
    private fun label(value: String, size: Float, color: Int = TvStyle.text) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color); includeFontPadding = false
    }

    private fun plainButton(value: String) = label(value, 13f).apply {
        gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8)); minHeight = dp(36)
        id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true
        background = TvStyle.card(context, false)
        setOnFocusChangeListener { view, focused -> view.background = TvStyle.card(context, focused) }
    }

    private fun isChosen(type: String, id: String) = controller.selection.values[type].orEmpty() == id

    /** 条件按钮。选项值刻意不叫 id，避免和 View.id 在 apply 作用域里互相遮蔽。 */
    private fun chip(value: String, type: String, optionId: String, onClick: () -> Unit) =
        label(value, 12f).apply {
            gravity = Gravity.CENTER
            setPadding(dp(11), dp(6), dp(11), dp(6)); minHeight = dp(32)
            this.id = View.generateViewId(); isFocusable = true; isFocusableInTouchMode = true
            setTextColor(if (isChosen(type, optionId)) TvStyle.accent else TvStyle.text)
            background = chipShape(isChosen(type, optionId), false)
            setOnClickListener { onClick() }
            setOnFocusChangeListener { view, focused ->
                view.background = chipShape(isChosen(type, optionId), focused)
            }
        }

    private fun chipShape(selected: Boolean, focused: Boolean) =
        TvStyle.shape(
            context,
            if (focused) TvStyle.raised else if (selected) Color.rgb(61, 39, 34) else TvStyle.surface,
            if (focused) TvStyle.accent else if (selected) Color.rgb(122, 62, 47) else Color.TRANSPARENT,
        )

    private fun describe(problem: Throwable) = when (problem) {
        is java.net.UnknownHostException -> "无法连接内容网站，请检查网络后重试。"
        is java.net.SocketTimeoutException -> "连接超时，请重试。"
        else -> problem.message.orEmpty().replace(Regex("https?://\\S+"), "[地址]").take(80)
            .ifBlank { "暂时无法加载，请重试。" }
    }
}
