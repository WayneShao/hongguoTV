// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.*
import java.util.concurrent.Executors
import java.util.concurrent.Future

/** App-owned content view: hosted under the existing stereo root, no external login window. */
internal class AccountScreen(
    private val activity: Activity,
    private val repository: AccountRepository,
    private val button: (String, () -> Unit) -> TextView,
    private val back: () -> Unit,
    private val changed: () -> Unit,
    private val open: (Series, AccountHistoryItem?, String) -> Unit,
) {
    private val main=Handler(Looper.getMainLooper())
    private val worker=Executors.newSingleThreadExecutor()
    private var revision=0
    private var scope: RequestScope?=null
    private var future: Future<*>?=null
    private var parent: LinearLayout?=null
    private var status: TextView?=null
    private var poll: Runnable?=null
    private var page="home"
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=16f)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(6),dp(5),dp(6),dp(5))
    }
    private fun action(target: LinearLayout,title: String,click: ()->Unit): View = button(title,click).also {
        it.id=View.generateViewId(); target.addView(it,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(5) })
        val count=target.childCount
        if(count>1) { val previous=target.getChildAt(count-2); if(previous.isFocusable) { previous.nextFocusDownId=it.id; it.nextFocusUpId=previous.id } }
    }
    fun show(host: LinearLayout,section: String?=null,focusSeries: String?=null) { close(); parent=host; if(section==null) render() else listing(section,focusSeries) }
    private fun reset(): LinearLayout {
        cancel(); val host=parent ?: error("Account screen detached")
        host.removeAllViews()
        val scroll=ScrollView(activity)
        val content=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),0,dp(12),dp(12)) }
        scroll.addView(content); host.addView(scroll,LinearLayout.LayoutParams(-1,-1))
        return content
    }
    private fun render() {
        page="home"
        val body=reset()
        action(body,"返回设置",back)
        val session=repository.currentSession()
        body.addView(label("红果账号",22f))
        status=label(if(session==null) "使用手机红果扫码登录" else session.user.name.ifBlank { "已登录红果账号" })
        body.addView(status)
        if(session==null) {
            body.addView(label("登录后可读取云端收藏、历史和预约，并同步登录期间的观看进度。",13f))
            action(body,"显示登录二维码") { qr() }.requestFocus()
        } else {
            action(body,"云端观看历史") { listing("history") }.requestFocus()
            action(body,"云端收藏") { listing("favorites") }
            action(body,"云端预约") { listing("reservations") }
            action(body,"刷新账号资料") { task({ repository.refreshUser() }) {
                AuthStore.save(activity,it); changed(); render()
            } }
            action(body,"退出红果账号") {
                repository.logout(); AuthStore.clear(activity); changed(); render()
            }
            body.addView(label("本机收藏和历史保留。登录前的访客记录不会批量上传。",13f))
        }
    }
    private fun qr() {
        val body=reset(); page="qr"
        action(body,"返回账号") { render() }.requestFocus()
        status=label("正在获取二维码…"); body.addView(status)
        val image=ImageView(activity).apply { scaleType=ImageView.ScaleType.FIT_CENTER; setBackgroundColor(Color.WHITE) }
        body.addView(image,LinearLayout.LayoutParams(dp(220),dp(220)))
        action(body,"刷新二维码") { qr() }
        task({ repository.createLoginQr() }) { qr ->
            val bytes=runCatching { Base64.decode(qr.image.substringAfter(','),Base64.DEFAULT) }.getOrNull()
            val bitmap=bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) }
            if(bitmap==null) { status?.text="二维码图片无效，请刷新"; return@task }
            image.setImageBitmap(bitmap); status?.text="请用手机红果扫码，并在手机上确认"
            schedulePoll(qr.expiresAt)
        }
    }
    private fun schedulePoll(expiresAt: Long) {
        val ticket=revision
        val next=Runnable {
            if(parent==null || revision!=ticket) return@Runnable
            if(System.currentTimeMillis()>=expiresAt) { status?.text="二维码已过期，请刷新"; repository.cancelLoginQr(); return@Runnable }
            task({ repository.pollLoginQr() }) { result ->
                when(result.status) {
                    LoginQrStatus.CONFIRMED -> {
                        val session=result.session ?: return@task
                        AuthStore.save(activity,session); changed(); render()
                    }
                    LoginQrStatus.EXPIRED -> status?.text="二维码已过期，请刷新"
                    else -> { status?.text=if(result.status==LoginQrStatus.SCANNED) "已扫码，请在手机上确认" else "等待手机扫码"; schedulePoll(expiresAt) }
                }
            }
        }
        poll=next; main.postDelayed(next,2000)
    }
    private fun listing(kind: String,focusSeries: String?=null) {
        val body=reset(); page="list"
        action(body,"返回账号") { render() }.requestFocus()
        action(body,"刷新列表") { listing(kind) }
        status=label("正在读取云端列表…"); body.addView(status)
        task({ when(kind) {
            "history" -> repository.accountResumeHistory()
            "favorites" -> repository.accountFavorites()
            else -> repository.accountReservations()
        } }) { page ->
            status?.text=if(page.items.isEmpty()) "云端暂无记录" else "已读取 ${page.items.size} 部剧集"
            page.items.forEach { series ->
                val progress=page.history.firstOrNull { it.series.id==series.id }
                val reservation=page.reservations.firstOrNull { it.series.id==series.id }
                val suffix=progress?.let { " · 第 ${it.episodeIndex+1} 集" } ?: if(series.preview) " · 待上线" else ""
                action(body,series.title+suffix) {
                    if(series.preview || reservation?.online==false) reservationActions(series,reservation)
                    else open(series,progress,kind)
                }.also { if(series.id==focusSeries) it.requestFocus() }
            }
        }
    }
    private fun reservationActions(series: Series,reservation: AccountReservation?) {
        val body=reset(); page="reservation"
        action(body,"返回预约列表") { listing("reservations") }.requestFocus()
        body.addView(label(series.title,20f)); status=label(reservation?.expectedText.orEmpty().ifBlank { "剧集尚未上线" }); body.addView(status)
        if(reservation!=null) action(body,if(reservation.subscribed) "取消预约" else "预约") {
            task({ repository.setReservation(series.id,!reservation.subscribed,reservation.itemType) }) { listing("reservations") }
        }
    }
    private fun <T> task(work: ()->T,done: (T)->Unit) {
        val ticket=revision; val request=RequestScope(); scope?.cancel(); future?.cancel(true); scope=request
        future=worker.submit {
            val result=runCatching { request.run(work) }
            main.post {
                if(ticket!=revision || parent==null || activity.isDestroyed) return@post
                result.onSuccess(done).onFailure {
                    val message=it.message.orEmpty()
                    val expired=message.contains("未登录") || message.contains("登录已过期") || message.contains("请先登录")
                    if(expired) { repository.logout(); AuthStore.clear(activity); changed(); render(); status?.text="登录已过期，请重新扫码" }
                    else status?.text="账号请求失败，请重试（${it.javaClass.simpleName}）"
                }
            }
        }
    }
    private fun cancel() { revision++; poll?.let(main::removeCallbacks); poll=null; scope?.cancel(); future?.cancel(true); scope=null; future=null; repository.cancelLoginQr() }
    fun goBack() { when(page) { "home" -> back(); "reservation" -> listing("reservations"); else -> render() } }
    fun close() { cancel(); AuthStore.restore(activity,repository); parent=null; status=null }
    fun destroy() { close(); worker.shutdownNow() }
}
