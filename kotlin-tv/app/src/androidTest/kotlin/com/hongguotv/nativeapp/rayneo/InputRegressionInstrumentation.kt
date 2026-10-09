package com.hongguotv.nativeapp.rayneo

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.Window
import android.view.ViewGroup
import android.widget.TextView
import android.app.Dialog
import com.hongguotv.nativeapp.TvTools
import com.hongguotv.nativeapp.EpisodePanel

/** Exercises the production window callback on the device without changing Library preferences. */
class InputRegressionInstrumentation : Instrumentation() {
    private lateinit var activity: Activity
    private lateinit var dialog: AlertDialog
    private val report = StringBuilder()
    private var chosen = -1
    private var menuDowns = 0
    private var menuUps = 0
    private var centers = 0
    private var device = 0
    private var now = 0L
    private var inputWindow: Window? = null
    private var leaveOpen = false
    private var temporaryPanel: EpisodePanel? = null

    override fun onCreate(arguments: Bundle?) { super.onCreate(arguments); leaveOpen=arguments?.getString("leaveOpen")=="true"; start() }
    private fun onMain(block: () -> Unit) = runOnMainSync(block)
    private fun settle() { waitForIdleSync(); SystemClock.sleep(100); waitForIdleSync() }
    private fun state(): String {
        var value = ""
        onMain {
            val root=dialog.window!!.decorView
            val all=ArrayList<View>(); root.addFocusables(all,View.FOCUS_FORWARD,View.FOCUSABLES_ALL)
            value = "windowFocus=${root.hasWindowFocus()} focus=${root.findFocus()?.javaClass?.simpleName} selected=${dialog.listView.selectedItemPosition} checked=${dialog.listView.checkedItemPosition} touch=${dialog.listView.isInTouchMode} layout=${dialog.listView.isLayoutRequested} normalCandidates=${root.getFocusables(View.FOCUS_FORWARD).size} allCandidates=${all.size} systemSlop=${android.view.ViewConfiguration.get(root.context).scaledTouchSlop}"
        }
        return value
    }
    private fun record(label: String) { report.append(label).append(": ").append(state()).append('\n') }
    private fun selected(): Int { var value = -1; onMain { value = dialog.listView.selectedItemPosition }; return value }

    override fun onStart() {
        val result = Bundle()
        java.io.File(targetContext.filesDir,"input-regression.txt").writeText("RUNNING")
        try {
            device = InputDevice.getDeviceIds().first { InputDevice.getDevice(it)?.name == "cyttsp5_mt" }
            activity = startActivitySync(Intent().setClassName(targetContext.packageName,"com.hongguotv.nativeapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            settle()
            onMain {
                dialog = RayNeoDialogs.builder(activity).setTitle("清晰度输入回归")
                    .setSingleChoiceItems(arrayOf("480P","720P","1080P"),1) { _, index -> chosen = index }
                    .setNegativeButton("取消",null).create()
                dialog.show()
            }
            settle(); record("opened")
            onMain { RayNeoInput.traceForTest(true) }
            swipe(0f,55f); settle(); record("swipe-down")
            check(selected() == 2) { "Down must move from checked 720P to 1080P" }
            setInTouchMode(true); settle(); record("touch-mode-selection-loss")
            check(selected() == 2) { "Touch mode must retain browsed 1080P, not reset to checked 720P" }
            onMain { dialog.listView.setSelection(-1) }; settle(); record("browsed-row-cleared")
            check(selected() == 2) { "Explicit selection loss must restore browsed 1080P, not checked 720P" }
            swipe(0f,-55f); settle(); record("swipe-up")
            check(selected() == 1) { "Up must move back to 720P" }
            onMain { dialog.listView.setSelection(-1) }; settle()
            record("selection-cleared")
            swipe(0f,55f); settle(); record("recovered-down")
            check(selected() == 2) { "Lost list selection must recover the current row then move once" }
            tap(); SystemClock.sleep(450); settle()
            check(chosen == 2) { "Single tap must activate current row after recovery, got $chosen" }
            onMain {
                val window = dialog.window!!
                val original = window.callback
                window.callback = object : Window.Callback by original {
                    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
                        if(event.keyCode == KeyEvent.KEYCODE_MENU) {
                            if(event.action == KeyEvent.ACTION_DOWN) menuDowns++ else menuUps++
                            return true
                        }
                        if(event.keyCode == KeyEvent.KEYCODE_DPAD_CENTER) centers++
                        return original.dispatchKeyEvent(event)
                    }
                }
                RayNeoInput.install(window)
            }
            pair(); SystemClock.sleep(450); settle()
            check(menuDowns == 1 && menuUps == 1 && centers == 0) { "Two fingers: down=$menuDowns up=$menuUps center=$centers" }
            record("two-finger-menu-once")
            onMain {
                val time=SystemClock.uptimeMillis()
                dialog.window!!.callback.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_UP,0))
                dialog.window!!.callback.dispatchKeyEvent(KeyEvent(time,time,KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_UP,0))
            }
            settle(); check(selected()==1) { "Real DPAD up" }
            setInTouchMode(true); settle(); swipe(0f,-55f); settle()
            check(selected()==0) { "DPAD and temple cursor memory diverged" }
            report.append("real DPAD -> touch mode -> temple continued from same row: PASS\n")
            onMain { dialog.dismiss() }; settle()
            modalCoverage()
            pageCoverage()
            result.putString("stream", "PASS\n$report")
            complete(Activity.RESULT_OK,result)
        } catch (error: Throwable) {
            var trace=""; onMain { trace=RayNeoInput.traceForTest(false) }
            result.putString("stream", "FAIL ${error.message}\n$report\n$trace\n${error.stackTraceToString()}")
            if(::dialog.isInitialized) onMain { dialog.dismiss() }
            complete(Activity.RESULT_CANCELED,result)
        }
    }

    private fun complete(code: Int,result: Bundle) {
        onMain {
            RayNeoInput.traceForTest(false)
            temporaryPanel?.dismiss(); temporaryPanel=null
            if(::dialog.isInitialized && dialog.isShowing) dialog.dismiss()
            if(::activity.isInitialized) button(activity.window.decorView,"设置")?.performClick()
        }
        settle()
        // Leave the real settings page open; fixture choices never write Library preferences.
        if(leaveOpen) {
            java.io.File(targetContext.filesDir,"input-regression.txt").writeText(result.getString("stream").orEmpty())
            sendStatus(code,result)
        } else finish(code,result)
    }

    private fun views(root: View): List<View> = buildList {
        fun visit(v: View) { add(v); if(v is ViewGroup) for(i in 0 until v.childCount) visit(v.getChildAt(i)) }
        visit(root)
    }
    private fun button(root: View,label: String) = views(root).filterIsInstance<TextView>().firstOrNull { it.isFocusable && it.text.toString().startsWith(label) }
    private fun focusLabel(window: Window): String {
        var value=""; onMain { value=(window.decorView.findFocus() as? TextView)?.text?.toString().orEmpty() }; return value
    }
    private fun toolDialog(tools: TvTools): AlertDialog = TvTools::class.java.getDeclaredField("dialog").apply { isAccessible=true }.get(tools) as AlertDialog
    private fun modalCoverage() {
        // Continuous navigation must retain a browsed row different from the checked setting.
        onMain {
            dialog=RayNeoDialogs.builder(activity).setTitle("连续光标回归").setSingleChoiceItems(arrayOf("480P","720P","1080P"),0,null).setNegativeButton("取消",null).create()
            dialog.show()
        }
        settle()
        repeat(3) {
            swipe(0f,55f); settle(); check(selected()==1) { "continuous first down" }
            setInTouchMode(true); settle(); hoverSwipe(0f,55f); settle(); check(selected()==2) { "continuous second down reset to first row" }
            onMain { dialog.listView.setSelection(-1) }; settle()
            hoverSwipe(0f,-55f); settle(); check(selected()==1) { "hover selection recovery" }
            swipe(0f,-55f); settle(); check(selected()==0) { "continuous up" }
        }
        report.append("quality repeated touch/hover 480 -> 720 -> 1080 -> 720 -> 480: PASS\n")
        onMain { dialog.dismiss() }
        val tools=TvTools(activity)
        onMain { tools.confirm("确认框回归","保持取消为默认",null) { error("Unexpected confirmation") }; dialog=toolDialog(tools) }
        settle(); check(focusLabel(dialog.window!!)=="取消") { "Wrapper lost default cancel focus" }
        onMain { dialog.dismiss(); tools.episodePicker(100,7,null) {}; dialog=toolDialog(tools) }
        settle(); check(focusLabel(dialog.window!!)=="1") { "Wrapper lost numeric keypad initial focus" }
        swipe(55f,0f); settle(); check(focusLabel(dialog.window!!)=="2") { "Keypad right" }
        setInTouchMode(true); swipe(0f,55f); settle(); check(focusLabel(dialog.window!!)=="5") { "Keypad down after touch mode" }
        report.append("confirm cancel; numeric keypad 1 -> 2 -> 5: PASS\n")
        onMain { dialog.dismiss(); tools.choose("长列表回归",(0..39).map { "选项 $it" },null,{ chosen=it }); dialog=toolDialog(tools) }
        settle()
        repeat(22) { swipe(0f,55f); settle() }
        check(selected()==22) { "Long list failed to navigate beyond visible rows: ${selected()}" }
        setInTouchMode(true); onMain { dialog.listView.setSelection(-1) }; settle()
        swipe(0f,55f); settle(); check(selected()==23) { "Long list lost item identity after relayout: ${selected()}" }
        chosen=-1; tap(); SystemClock.sleep(450); settle(); check(chosen==23) { "Long list confirmation changed item" }
        report.append("plain list 0 -> 22 -> selection loss -> 23 -> confirm: PASS\n")
        onMain { tools.destroy() }
        lateinit var panel: EpisodePanel
        lateinit var panelDialog: Dialog
        onMain {
            panel=EpisodePanel(activity,60,7,{ _,_,_-> },{},{}); panel.show()
            temporaryPanel=panel
            panelDialog=EpisodePanel::class.java.getDeclaredField("dialog").apply { isAccessible=true }.get(panel) as Dialog
            inputWindow=panelDialog.window
        }
        settle(); check(focusLabel(inputWindow!!)=="8") { "Episode initial focus" }
        swipe(55f,0f); settle(); check(focusLabel(inputWindow!!)=="9") { "Episode right" }
        setInTouchMode(true); swipe(0f,55f); settle(); check(focusLabel(inputWindow!!)=="14") { "Episode down after touch" }
        onMain { panel.dismiss(); temporaryPanel=null; inputWindow=null }
        report.append("episode panel 8 -> 9 -> 14: PASS\n")
    }
    private fun pageCoverage() {
        inputWindow=activity.window
        for(label in listOf("首页","搜索","排行榜","收藏","最近观看","设置")) {
            onMain { checkNotNull(button(activity.window.decorView,label)).performClick() }; settle()
            swipe(0f,55f); settle(); setInTouchMode(true); settle()
            var focus: View?=null; onMain { focus=activity.window.decorView.findFocus() }
            check(focus?.isAttachedToWindow==true) { "$label lost focus" }
            report.append("page ").append(label).append(" valid focus after temple/touch: PASS\n")
        }
        onMain { checkNotNull(button(activity.window.decorView,"默认清晰度")).requestFocusFromTouch() }
        swipe(0f,55f); settle(); check(focusLabel(activity.window).startsWith("默认倍速")) { "settings next row" }
        setInTouchMode(true); swipe(0f,55f); settle(); check(focusLabel(activity.window).startsWith("画面模式")) { "settings repeated down" }
        swipe(55f,0f); settle(); check(focusLabel(activity.window).startsWith("版本与更新")) { "settings cross column" }
        report.append("settings quality -> speed -> frame -> version: PASS\n")
        inputWindow=null
    }

    private fun motion(action: Int, ids: IntArray, xs: FloatArray, ys: FloatArray) {
        now += 20
        onMain {
            val props = Array(ids.size) { i -> MotionEvent.PointerProperties().apply { id=ids[i]; toolType=MotionEvent.TOOL_TYPE_FINGER } }
            val coords = Array(ids.size) { i -> MotionEvent.PointerCoords().apply { x=xs[i]; y=ys[i]; pressure=1f; size=1f } }
            val e = MotionEvent.obtain(now-20,now,action,ids.size,props,coords,0,0,1f,1f,device,0,InputDevice.SOURCE_TOUCHSCREEN,0)
            try { (inputWindow ?: dialog.window!!).callback.dispatchTouchEvent(e) } finally { e.recycle() }
        }
    }
    private fun swipe(dx: Float,dy: Float) {
        now=SystemClock.uptimeMillis()-100
        motion(MotionEvent.ACTION_DOWN,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
        motion(MotionEvent.ACTION_MOVE,intArrayOf(0),floatArrayOf(100f+dx),floatArrayOf(100f+dy))
        motion(MotionEvent.ACTION_UP,intArrayOf(0),floatArrayOf(100f+dx),floatArrayOf(100f+dy))
    }
    private fun tap() {
        now=SystemClock.uptimeMillis()-100
        motion(MotionEvent.ACTION_DOWN,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
        motion(MotionEvent.ACTION_UP,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
    }
    private fun hoverSwipe(dx: Float,dy: Float) {
        now=SystemClock.uptimeMillis()-100
        motion(MotionEvent.ACTION_HOVER_ENTER,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
        motion(MotionEvent.ACTION_HOVER_MOVE,intArrayOf(0),floatArrayOf(100f+dx),floatArrayOf(100f+dy))
        motion(MotionEvent.ACTION_HOVER_EXIT,intArrayOf(0),floatArrayOf(100f+dx),floatArrayOf(100f+dy))
    }
    private fun pair() {
        now=SystemClock.uptimeMillis()-100
        motion(MotionEvent.ACTION_DOWN,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
        motion(MotionEvent.ACTION_POINTER_DOWN or (1 shl 8),intArrayOf(0,1),floatArrayOf(100f,160f),floatArrayOf(100f,100f))
        motion(MotionEvent.ACTION_POINTER_UP or (1 shl 8),intArrayOf(0,1),floatArrayOf(100f,160f),floatArrayOf(100f,100f))
        motion(MotionEvent.ACTION_UP,intArrayOf(0),floatArrayOf(100f),floatArrayOf(100f))
    }
}
