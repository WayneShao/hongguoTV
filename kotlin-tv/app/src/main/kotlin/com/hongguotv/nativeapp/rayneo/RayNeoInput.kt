package com.hongguotv.nativeapp.rayneo

import android.database.DataSetObserver
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.*
import android.widget.ListView
import android.widget.ListAdapter
import android.widget.TextView
import java.lang.ref.WeakReference

/** Each activity/dialog retains its own callback, gesture lifetime and focus. */
object RayNeoInput {
    /** Only the embedded keyboard supplies this owner; ordinary windows keep MENU. */
    fun installKeyboard(dialog: android.app.Dialog) {
        val window = dialog.window ?: return
        val original = window.callback ?: return
        if (original !is InputCallback) window.callback = InputCallback(window, original, dialog)
    }
    private var tracing = false
    private val traces = java.util.ArrayDeque<String>()
    @androidx.annotation.VisibleForTesting
    fun traceForTest(enabled: Boolean): String {
        val result = traces.joinToString("\n")
        tracing = enabled && com.hongguotv.nativeapp.BuildConfig.DEBUG
        if (enabled) traces.clear()
        return result
    }
    private fun trace(message: String) {
        if (!tracing) return
        if (traces.size == 60) traces.removeFirst()
        traces.addLast(message)
    }
    fun install(window: Window) {
        val original = window.callback ?: return
        if (original !is InputCallback) window.callback = InputCallback(window, original)
    }

    private class InputCallback(val window: Window, val original: Window.Callback, val keyboard: android.app.Dialog? = null) : Window.Callback by original {
        private val root = window.decorView
        private val handler = Handler(Looper.getMainLooper())
        // The glasses' framework touch slop is 65 px, unsuitable for short temple swipes.
        private val slop = (12f * root.resources.displayMetrics.density).coerceIn(12f,24f)
        private val gestures = GestureCore(slop,
            ViewConfiguration.getDoubleTapTimeout().toLong(), ViewConfiguration.getLongPressTimeout().toLong())
        private val twoFinger = TwoFingerTap(slop, ViewConfiguration.getLongPressTimeout().toLong())
        private var pairTarget: Target? = null
        private var delivery: Runnable? = null
        private var deliveryTarget: Target? = null
        private var target: Target? = null
        private var previous = WeakReference<View>(null)
        private val listRows = java.util.WeakHashMap<ListView,RowMemory>()
        private var regions = emptyList<WeakReference<View>>()
        private var observing: ViewTreeObserver? = null
        private var recoveryQueued = false
        private var recoveryDeadline = 0L
        private val focusListener = ViewTreeObserver.OnGlobalFocusChangeListener { old, next ->
            if (target != null && target?.focus !== next) cancel()
            remember(old); remember(next)
            requestRecovery()
        }
        private val layoutListener = ViewTreeObserver.OnGlobalLayoutListener {
            remember(root.findFocus()); requestRecovery()
        }
        private val recovery = object : Runnable {
            override fun run() {
                if (!root.isAttachedToWindow || !root.hasWindowFocus()) { recoveryQueued = false; return }
                if (root.isLayoutRequested) {
                    if (SystemClock.uptimeMillis() < recoveryDeadline) root.postOnAnimation(this)
                    else recoveryQueued = false
                    return
                }
                // Keep the queue flag set during requestFocus; callbacks merge into this run.
                if (!validFocus(root.findFocus())) prepare()
                recoveryQueued = false
            }
        }
        private var lastTime = -1L
        private var lastAction = -1
        private var lastDevice = -1
        private var hover = false
        private var touching = false
        private var hoverX = 0f
        private var hoverY = 0f
        private var hoverLastX = 0f
        private var hoverLastY = 0f
        private val confirm = Runnable {
            val owner = target
            val action = if (owner?.valid() == true) gestures.confirm(SystemClock.uptimeMillis()) else GestureCore.Action.NONE
            cancel()
            send(action)
        }

        init {
            root.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(v: View) { observe(); requestRecovery() }
                override fun onViewDetachedFromWindow(v: View) { cancel(); stopRecovery(); unobserve() }
            })
            observe(); requestRecovery()
        }

        private fun observe() {
            val observer = root.viewTreeObserver
            if (observing === observer) return
            unobserve(); observing = observer
            observer.addOnGlobalFocusChangeListener(focusListener)
            observer.addOnGlobalLayoutListener(layoutListener)
        }
        private fun unobserve() {
            observing?.takeIf { it.isAlive }?.let {
                it.removeOnGlobalFocusChangeListener(focusListener)
                it.removeOnGlobalLayoutListener(layoutListener)
            }
            observing = null
        }
        private fun requestRecovery() {
            if (recoveryQueued || !root.isAttachedToWindow || !root.hasWindowFocus() || validFocus(root.findFocus())) return
            recoveryQueued = true; recoveryDeadline = SystemClock.uptimeMillis() + 400
            root.postOnAnimation(recovery)
        }
        private fun stopRecovery() { root.removeCallbacks(recovery); recoveryQueued = false }
        override fun onContentChanged() {
            cancel(); previous.clear(); regions = emptyList(); original.onContentChanged(); requestRecovery()
        }
        override fun onWindowFocusChanged(hasFocus: Boolean) {
            if (!hasFocus) { cancel(); stopRecovery() }
            original.onWindowFocusChanged(hasFocus)
            if (hasFocus) requestRecovery()
        }
        override fun dispatchKeyEvent(event: KeyEvent): Boolean {
            if (keyboard != null && io.github.wayneshao.rayneo.ime.EmbeddedInput.key(keyboard, event)) {
                cancel(); return true
            }
            if (GestureCore.source(event.device?.name) < 0) return true
            // Native DPAD/mouse behavior remains authoritative and invalidates a delayed tap.
            cancel()
            val handled = original.dispatchKeyEvent(event)
            remember(root.findFocus())
            requestRecovery()
            return handled
        }
        override fun dispatchTouchEvent(event: MotionEvent) = motion(event) || original.dispatchTouchEvent(event)
        override fun dispatchGenericMotionEvent(event: MotionEvent) = motion(event) || original.dispatchGenericMotionEvent(event)

        private fun motion(e: MotionEvent): Boolean {
            if (keyboard != null && io.github.wayneshao.rayneo.ime.EmbeddedInput.motion(keyboard, e)) {
                cancel(); return true
            }
            val source = GestureCore.source(e.device?.name)
            trace("motion action=${e.actionMasked} source=$source device=${e.deviceId} xy=${e.x},${e.y} time=${e.eventTime} count=${e.pointerCount} touching=$touching focus=${root.findFocus()?.javaClass?.simpleName} windowFocus=${root.hasWindowFocus()} attached=${root.isAttachedToWindow}")
            if (source == 0) { cancel(); return false }
            if (source < 0) return true
            if (!root.hasWindowFocus() || !root.isAttachedToWindow) { cancel(); return true }
            if (lastDevice != e.deviceId) { cancel(); lastDevice = e.deviceId }
            if (lastTime == e.eventTime && lastAction == e.actionMasked) return true
            lastTime = e.eventTime; lastAction = e.actionMasked
            val action = e.actionMasked
            if (action == MotionEvent.ACTION_DOWN) cancelDelivery()
            if (action in listOf(MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_CANCEL)) {
                val ids = IntArray(e.pointerCount) { e.getPointerId(it) }
                for (h in 0 until e.historySize) twoFinger.handle(MotionEvent.ACTION_MOVE,0,ids,
                    FloatArray(e.pointerCount) { e.getHistoricalX(it,h) },FloatArray(e.pointerCount) { e.getHistoricalY(it,h) },e.getHistoricalEventTime(h))
                val multi = twoFinger.handle(action,e.actionIndex,ids,
                    FloatArray(e.pointerCount) { e.getX(it) },FloatArray(e.pointerCount) { e.getY(it) },e.eventTime)
                if (multi != TwoFingerTap.Result.PASS) {
                    if (pairTarget == null && action == MotionEvent.ACTION_POINTER_DOWN && e.pointerCount == 2) pairTarget = Target()
                    clearSingle()
                    if (multi == TwoFingerTap.Result.MENU) {
                        val valid = pairTarget?.valid() == true
                        cancel()
                        if (valid) send(GestureCore.Action.MENU)
                    } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) cancel()
                    return true
                }
            }
            if (e.pointerCount != 1 || action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_UP) {
                cancel(); return true
            }
            when (action) {
                MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> {
                    if (touching) return true
                    hover = false
                    // An expired single tap is delivered before capturing a new target.
                    if (gestures.pending()) {
                        val expired = gestures.confirm(e.eventTime)
                        if (expired != GestureCore.Action.NONE && target?.valid() == true) send(expired)
                    }
                    handler.removeCallbacks(confirm)
                    if (!gestures.pending()) { target?.close(); target = null; prepare(); target = Target() }
                    touching = true
                    gestures.down(e.x, e.y, e.eventTime)
                }
                MotionEvent.ACTION_MOVE -> {
                    for (h in 0 until e.historySize) gestures.move(e.getHistoricalX(h),e.getHistoricalY(h))
                    gestures.move(e.x, e.y)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> {
                    if (!touching) return true
                    touching = false
                    val result = gestures.up(e.x, e.y, e.eventTime)
                    trace("gesture=$result")
                    if (result != GestureCore.Action.NONE) { cancel(); send(result) }
                    else if (gestures.pending()) {
                        handler.removeCallbacks(confirm)
                        handler.postDelayed(confirm, ViewConfiguration.getDoubleTapTimeout().toLong())
                    }
                }
                MotionEvent.ACTION_HOVER_ENTER, MotionEvent.ACTION_HOVER_MOVE -> {
                    if (touching) { gestures.move(e.x, e.y); return true }
                    if (!hover) { hover = true; hoverX = e.x; hoverY = e.y }
                    hoverLastX = e.x; hoverLastY = e.y
                }
                MotionEvent.ACTION_HOVER_EXIT -> if (hover && !touching) {
                    cancel(); gestures.down(hoverX, hoverY, e.eventTime)
                    gestures.move(hoverLastX, hoverLastY)
                    val result = gestures.up(hoverLastX, hoverLastY, e.eventTime)
                    cancel()
                    if (result in listOf(GestureCore.Action.LEFT, GestureCore.Action.RIGHT, GestureCore.Action.UP, GestureCore.Action.DOWN)) send(result)
                }
            }
            return true
        }

        private fun clearSingle() {
            trace("cancel touching=$touching pending=${gestures.pending()}")
            handler.removeCallbacks(confirm); gestures.cancel(); hover = false; touching = false
            target?.close(); target = null
        }
        private fun cancelDelivery() {
            delivery?.let(root::removeCallbacks); delivery=null
            deliveryTarget?.close(); deliveryTarget=null
        }
        private fun cancel() {
            clearSingle(); twoFinger.cancel(); pairTarget?.close(); pairTarget=null; cancelDelivery()
        }

        private fun inside(v: View?): Boolean {
            var node = v
            while (node != null) {
                if (!node.isEnabled || !node.isShown) return false
                if (node === root) return v !== root && v?.isAttachedToWindow == true
                node = node.parent as? View
            }
            return false
        }
        private fun usable(v: View?) = inside(v) && v?.isFocusable == true
        private fun validFocus(v: View?): Boolean {
            if (!usable(v)) return false
            if (v is ListView) {
                val adapter = v.adapter ?: return false
                return !v.isInTouchMode && v.selectedItemPosition in 0 until adapter.count && adapter.isEnabled(v.selectedItemPosition)
            }
            return true
        }
        private fun remember(v: View?) {
            if (!usable(v)) return
            if (v is ListView && !v.isInTouchMode) {
                v.adapter?.let { adapter ->
                    listRows.getOrPut(v) { RowMemory() }.remember(adapter,rows(adapter),v.selectedItemPosition)
                }
            }
            previous = WeakReference(v)
            regions = generateSequence(v?.parent as? View) { it.parent as? View }
                .takeWhile { it !== root }.map { WeakReference(it) }.toList()
        }

        private fun prepare(): View? {
            var focus = root.findFocus()
            val lostList = (focus as? ListView) ?: (previous.get() as? ListView)?.takeIf(::inside)
            val needsListRestore = lostList != null && (focus == null || lostList.isInTouchMode || !validFocus(lostList))
            val rememberedRow = lostList?.adapter?.let { listRows[lostList]?.restore(it,rows(it)) }?.takeIf { it>=0 }
            if (usable(focus) && focus?.isInTouchMode == true) focus.requestFocusFromTouch()
            if (!usable(focus)) {
                val scope = regions.firstNotNullOfOrNull { it.get()?.takeIf { view -> inside(view) } } ?: root
                fun candidates(view: View) = ArrayList<View>().also { view.addFocusables(it,View.FOCUS_FORWARD,View.FOCUSABLES_ALL) }
                val candidates = listOfNotNull(previous.get()) + candidates(scope) + candidates(root)
                for (candidate in candidates) {
                    if (!usable(candidate)) continue
                    candidate.requestFocusFromTouch()
                    focus = root.findFocus()
                    if (usable(focus)) break
                }
            }
            if (focus is ListView) {
                val list = focus
                val adapter = list.adapter
                if (adapter != null && ((list === lostList && needsListRestore) || list.selectedItemPosition !in 0 until adapter.count || !adapter.isEnabled(list.selectedItemPosition))) {
                    val position = rememberedRow?.takeIf { list === lostList && it in 0 until adapter.count && adapter.isEnabled(it) }
                        ?: listRows[list]?.restore(adapter,rows(adapter))?.takeIf { it>=0 }
                        ?: list.checkedItemPosition.takeIf { it in 0 until adapter.count && adapter.isEnabled(it) }
                        ?: (list.firstVisiblePosition.coerceAtLeast(0) until adapter.count).firstOrNull { adapter.isEnabled(it) }
                        ?: (0 until adapter.count).firstOrNull { adapter.isEnabled(it) }
                    if (position != null) list.setSelection(position)
                }
            }
            if (usable(focus)) { remember(focus); return focus }
            return null
        }

        private fun rows(adapter: ListAdapter) = object : RowMemory.Rows {
            override fun count() = adapter.count
            override fun enabled(index: Int) = adapter.isEnabled(index)
            override fun item(index: Int): Any? = adapter.getItem(index)
            override fun hasStableIds() = adapter.hasStableIds()
            override fun id(index: Int) = adapter.getItemId(index)
        }

        private fun send(action: GestureCore.Action) {
            if (action == GestureCore.Action.NONE || !root.hasWindowFocus() || !root.isAttachedToWindow) return
            if (action != GestureCore.Action.BACK) {
                val focus = prepare()
                if (focus is ListView && focus.isLayoutRequested) {
                    cancelDelivery()
                    val scope = Target(); deliveryTarget=scope
                    val deadline = SystemClock.uptimeMillis()+400
                    val next = object : Runnable {
                        override fun run() {
                            if (!scope.valid(allowLayout=true) || SystemClock.uptimeMillis()>deadline) { cancelDelivery(); return }
                            if (focus.isLayoutRequested) { root.postOnAnimation(this); return }
                            cancelDelivery(); deliver(action)
                        }
                    }
                    delivery=next; root.postOnAnimation(next); return
                }
            }
            deliver(action)
        }

        private fun deliver(action: GestureCore.Action) {
            if (!root.hasWindowFocus() || !root.isAttachedToWindow) return
            val code = when (action) {
                GestureCore.Action.CENTER -> KeyEvent.KEYCODE_DPAD_CENTER
                GestureCore.Action.BACK -> KeyEvent.KEYCODE_BACK
                GestureCore.Action.MENU -> KeyEvent.KEYCODE_MENU
                GestureCore.Action.LEFT -> KeyEvent.KEYCODE_DPAD_LEFT
                GestureCore.Action.RIGHT -> KeyEvent.KEYCODE_DPAD_RIGHT
                GestureCore.Action.UP -> KeyEvent.KEYCODE_DPAD_UP
                GestureCore.Action.DOWN -> KeyEvent.KEYCODE_DPAD_DOWN
                else -> return
            }
            val direction = when (action) {
                GestureCore.Action.LEFT -> View.FOCUS_LEFT
                GestureCore.Action.RIGHT -> View.FOCUS_RIGHT
                GestureCore.Action.UP -> View.FOCUS_UP
                GestureCore.Action.DOWN -> View.FOCUS_DOWN
                else -> 0
            }
            if (direction != 0) prepare()
            val before = root.findFocus()
            val ownership = Target()
            val scroll = scrollState(before)
            val time = SystemClock.uptimeMillis()
            // Always reach the original callback, including a player with no focusable controls.
            try {
                val handled = original.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_DOWN, code, 0))
                trace("key=$code handled=$handled before=${before?.javaClass?.simpleName} selected=${(before as? ListView)?.selectedItemPosition}")
                if (!handled && direction != 0 && ownership.valid() && before != null && scroll == scrollState(before)) {
                    val next = before.focusSearch(direction)
                    if (next !== before && inside(next) && next?.hasFocusable() == true) next.requestFocus(direction)
                }
                original.dispatchKeyEvent(KeyEvent(time, time, KeyEvent.ACTION_UP, code, 0))
                remember(root.findFocus())
            } finally {
                ownership.close()
            }
            if (direction != 0) requestRecovery()
            root.postInvalidateOnAnimation()
        }

        private fun scrollState(view: View?): String {
            var node = view
            val state = StringBuilder()
            while (node != null) {
                state.append(node.scrollX).append(':').append(node.scrollY).append(';')
                if (node is ListView) state.append(node.selectedItemPosition).append(':').append(node.firstVisiblePosition).append(':').append(node.getChildAt(0)?.top)
                node = node.parent as? View
            }
            return state.toString()
        }

        /** Observe list rebinding as well as hierarchy and focus identity before delayed activation. */
        private inner class Target {
            val focus = root.findFocus()
            private val tag = focus?.tag
            private val text = (focus as? TextView)?.text?.toString()
            private val description = focus?.contentDescription?.toString()
            private val list = generateSequence(focus) { it.parent as? View }.filterIsInstance<ListView>().firstOrNull()
            private val nodes = hierarchy(root,list)
            private val adapter = list?.adapter
            private val row = list?.selectedItemPosition
            private val item = list?.selectedItemId
            private var changed = false
            private var closed = false
            private val observer = object : DataSetObserver() {
                override fun onChanged() { changed = true }
                override fun onInvalidated() { changed = true }
            }
            init { adapter?.registerDataSetObserver(observer) }
            fun valid(allowLayout: Boolean=false) = !changed && root.isAttachedToWindow && root.hasWindowFocus() && root.findFocus() === focus &&
                (focus == null || usable(focus)) && focus?.tag == tag && hierarchy(root,list) == nodes &&
                (focus as? TextView)?.text?.toString() == text && focus?.contentDescription?.toString() == description &&
                (list == null || (list.adapter === adapter && list.selectedItemPosition == row && list.selectedItemId == item && (allowLayout || !list.isLayoutRequested)))
            fun close() { if (!closed) { closed = true; adapter?.unregisterDataSetObserver(observer) } }
        }
        private fun hierarchy(node: View, skipRows: View? = null): List<View> {
            val result = ArrayList<View>()
            fun visit(v: View) { result.add(v); if (v is ViewGroup && v !== skipRows) for (i in 0 until v.childCount) visit(v.getChildAt(i)) }
            visit(node); return result
        }
    }
}
