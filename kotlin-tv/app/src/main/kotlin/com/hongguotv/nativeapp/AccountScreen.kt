// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
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
    private val intents: AccountIntents? = null,
    private val openCaptured: ((Series, AccountHistoryItem?, String, AccountContext) -> Unit)? = null,
    private val mergeFavorites: ((List<Series>, AccountContext, Long) -> Unit)? = null,
    private val mergeProgress: ((AccountContext, (String) -> Unit) -> Boolean)? = null,
    private val loadCover: (ImageView, String) -> Unit = { _, _ -> },
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
    private var expiry: Runnable?=null
    private var qrFrame: FrameLayout?=null
    private var qrImage: ImageView?=null
    private var zoomImage: ImageView?=null
    private var zoomStatus: TextView?=null
    private var zoomSurface: View?=null
    private var zoomAnchor: View?=null
    private var acceptedQr: Bitmap?=null
    private var intentListener: (() -> Unit)?=null
    private fun dp(n: Int)=(n*activity.resources.displayMetrics.density).toInt()
    private fun label(value: String,size: Float=16f)=TextView(activity).apply {
        text=value; textSize=size; setTextColor(Color.WHITE); setPadding(dp(6),dp(5),dp(6),dp(5))
    }
    private fun action(target: LinearLayout,title: String,click: ()->Unit): View = button(title,click).also {
        it.id=View.generateViewId(); target.addView(it,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(5) })
        val count=target.childCount
        if(count>1) { val previous=target.getChildAt(count-2); if(previous.isFocusable) { previous.nextFocusDownId=it.id; it.nextFocusUpId=previous.id } }
    }
    /** Cloud list rows carry a poster; the button itself stays the focus and click target. */
    private fun posterRow(target: LinearLayout,cover: String,title: String,click: ()->Unit): View {
        val line=LinearLayout(activity).apply { orientation=LinearLayout.HORIZONTAL; gravity=android.view.Gravity.CENTER_VERTICAL }
        val poster=ImageView(activity).apply { scaleType=ImageView.ScaleType.CENTER_CROP }
        line.addView(poster,LinearLayout.LayoutParams(dp(54),dp(72)).apply { rightMargin=dp(10) })
        loadCover(poster,cover)
        val entry=button(title,click)
        entry.id=View.generateViewId()
        line.addView(entry,LinearLayout.LayoutParams(0,-2).apply { weight=1f })
        target.addView(line,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(5) })
        val count=target.childCount
        if(count>1) {
            val previous=target.getChildAt(count-2)
            val previousButton=if(previous is LinearLayout) previous.getChildAt(minOf(1,previous.childCount-1)) else previous
            if(previousButton.isFocusable) { previousButton.nextFocusDownId=entry.id; entry.nextFocusUpId=previousButton.id }
        }
        return entry
    }
    fun show(host: LinearLayout,section: String?=null,focusSeries: String?=null) { close(); parent=host; if(section==null) render() else listing(section,focusSeries) }
    private fun reset(): LinearLayout {
        detachIntents()
        cancel(); dismissZoom(); qrFrame=null; qrImage=null; zoomAnchor=null
        val host=parent ?: error("Account screen detached")
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
            val context=repository.captureAccount() ?: return
            action(body,"云端观看历史") { listing("history") }.requestFocus()
            action(body,"云端收藏") { listing("favorites") }
            action(body,"云端预约") { listing("reservations") }
            action(body,"刷新账号资料") { task({ repository.refreshUser() },context) {
                AuthStore.save(activity,it); changed(); render()
            } }
            action(body,"退出红果账号") {
                if(!repository.isCurrent(context)) return@action
                repository.logout(); AuthStore.clear(activity); changed(); render()
            }
            body.addView(label("本机收藏和历史保留。登录不会自动上传访客记录；手动合并观看进度时可纳入本机访客记录。",13f))
            if(mergeProgress!=null) mergeAction(body,context)
            if(intents!=null) {
                action(body,"刷新收藏同步状态") {
                    if(repository.isCurrent(context)) intents.resume(context)
                }
                val statuses=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }
                body.addView(statuses)
                observeIntents(context) { favoriteStatuses(statuses,context) }
            }
        }
    }
    private fun generation(context: AccountContext)=intents?.revision(context) ?: 0L
    private fun mergeAction(body: LinearLayout,context: AccountContext) {
        body.addView(label("按观看进度合并，本地领先时上报云端",13f))
        val result=label("",13f)
        val ticket=revision
        var busy=false
        lateinit var control: View
        control=action(body,"合并观看进度") {
            if(busy || parent==null || page!="home" || ticket!=revision || !repository.isCurrent(context)) return@action
            val capturedGeneration=generation(context)
            if(!current(context,capturedGeneration)) return@action
            busy=true; control.isEnabled=false; result.text="正在合并观看进度…"
            val started=mergeProgress?.invoke(context) { message ->
                main.post {
                    if(parent!=null && !activity.isDestroyed && ticket==revision && page=="home" && repository.isCurrent(context)) {
                        busy=false; control.isEnabled=true
                        result.text=if(current(context,capturedGeneration)) message else "同步状态已变化，请重新合并。"
                    }
                }
            }==true
            if(!started) { busy=false; control.isEnabled=true; result.text="暂时无法合并，请等待账号和本机记录就绪后重试。" }
        }
        body.addView(result)
    }
    private fun current(context: AccountContext,expectedRevision: Long)=repository.isCurrent(context) &&
        expectedRevision>=0 && generation(context)==expectedRevision
    private fun detachIntents() {
        intentListener?.let { intents?.removeListener(it) }; intentListener=null
    }
    /** Coordinator events only update this page's content, never reset a live QR presentation. */
    private fun observeIntents(context: AccountContext,update: ()->Unit) {
        detachIntents()
        val coordinator=intents ?: return
        val ticket=revision; val targetPage=page
        val listener: ()->Unit = {
            val capturedGeneration=generation(context)
            main.post {
                if(parent!=null && !activity.isDestroyed && ticket==revision && page==targetPage &&
                    current(context,capturedGeneration)) update()
            }
        }
        intentListener=listener; coordinator.addListener(listener)
        if(repository.isCurrent(context)) update()
    }
    private fun retryable(status: AccountIntents.FavoriteStatus)=status in setOf(
        AccountIntents.FavoriteStatus.OUTCOME_UNKNOWN,AccountIntents.FavoriteStatus.FAILED,
        AccountIntents.FavoriteStatus.SUBMITTED_UNCONFIRMED)
    private fun favoriteStatuses(target: LinearLayout,context: AccountContext) {
        val coordinator=intents ?: return
        if(!repository.isCurrent(context)) return
        target.removeAllViews()
        target.addView(label("收藏同步状态",18f))
        if(!coordinator.isReady()) { target.addView(label("正在读取本机同步状态…",13f)); return }
        val entries=coordinator.favoriteEntries(context)
        if(entries.isEmpty()) target.addView(label("暂无收藏同步操作",13f))
        val ticket=revision; val capturedGeneration=generation(context)
        entries.forEach { entry ->
            val verb=if(entry.added) "收藏" else "取消收藏"
            val message=when(entry.status) {
                AccountIntents.FavoriteStatus.PENDING -> "$verb：等待提交"
                AccountIntents.FavoriteStatus.SUBMITTING -> "$verb：正在提交，结果尚未确认"
                AccountIntents.FavoriteStatus.OUTCOME_UNKNOWN -> "$verb：结果未知，请刷新确认或明确重试"
                AccountIntents.FavoriteStatus.SUBMITTED_UNCONFIRMED -> "已提交$verb，云端尚未确认"
                AccountIntents.FavoriteStatus.CONFIRMED -> "$verb：云端已确认"
                AccountIntents.FavoriteStatus.FAILED -> "$verb：提交失败，尚未确认"
            }
            target.addView(label("${entry.id} · $message",13f))
            if(retryable(entry.status)) action(target,"重试$verb（${entry.id}）") {
                if(ticket!=revision || page!="home" || !current(context,capturedGeneration)) return@action
                val latest=coordinator.favoriteEntries(context).firstOrNull { it.id==entry.id }
                if(latest!=null && latest.revision==entry.revision && retryable(latest.status)) coordinator.retryFavorite(context,entry.id)
            }
        }
    }
    private fun qr(enlarged: Boolean=false) {
        val body=reset(); page="qr"
        val host=parent ?: return
        val scroll=host.getChildAt(0); host.removeView(scroll)
        val frame=FrameLayout(activity); frame.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        host.addView(frame,LinearLayout.LayoutParams(-1,-1)); qrFrame=frame
        action(body,"返回账号") { render() }.requestFocus()
        status=label("正在获取二维码…"); body.addView(status)
        val image=newQrImage().apply { contentDescription="登录二维码，点击放大"; setOnClickListener { enlarge(this) } }
        qrImage=image
        body.addView(image,LinearLayout.LayoutParams(dp(220),dp(220)))
        lateinit var enlargeButton: View
        enlargeButton=action(body,"放大二维码") { enlarge(enlargeButton) }; zoomAnchor=enlargeButton
        action(body,"刷新二维码") { qr(zoomSurface!=null) }
        if(enlarged) enlarge(zoomAnchor)
        task({ repository.createLoginQr() }) { qr ->
            val bytes=runCatching { Base64.decode(qr.image.substringAfter(','),Base64.DEFAULT) }.getOrNull()
            val bitmap=bytes?.let { BitmapFactory.decodeByteArray(it,0,it.size) }
            if(bitmap==null) { qrMessage("二维码图片无效，请刷新"); return@task }
            acceptedQr=bitmap
            displayQr(image,bitmap); zoomImage?.let { displayQr(it,bitmap) }
            qrMessage("请用手机红果扫码，并在手机上确认")
            val ticket=revision
            expiry=Runnable { if(ticket==revision && page=="qr") expireQr() }.also {
                main.postDelayed(it,(qr.expiresAt-System.currentTimeMillis()).coerceAtLeast(0))
            }
            schedulePoll(qr.expiresAt)
        }
    }
    private fun newQrImage()=ImageView(activity).apply {
        id=View.generateViewId(); scaleType=ImageView.ScaleType.FIT_CENTER
        setBackgroundColor(Color.WHITE); isFocusable=true; isFocusableInTouchMode=true; isEnabled=false
    }
    private fun displayQr(image: ImageView,bitmap: Bitmap?) {
        image.setImageDrawable(bitmap?.let { BitmapDrawable(activity.resources,it).apply { setFilterBitmap(false) } })
        image.isEnabled=bitmap!=null
    }
    private fun qrMessage(message: String) { status?.text=message; zoomStatus?.text=message }
    private fun enlarge(anchor: View?) {
        if(zoomSurface!=null || page!="qr") return
        zoomAnchor=anchor
        val image=newQrImage(); zoomImage=image; displayQr(image,acceptedQr)
        val body=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL; setPadding(dp(12),dp(8),dp(12),dp(8)); setBackgroundColor(Color.rgb(20,20,20)) }
        zoomStatus=label(status?.text.toString()).also(body::addView)
        body.addView(image,LinearLayout.LayoutParams(dp(64),dp(64)))
        action(body,"刷新二维码") { qr(true) }
        val close=action(body,"关闭放大") { dismissZoom() }
        val scroll=object: ScrollView(activity) {
            override fun onMeasure(widthMeasureSpec: Int,heightMeasureSpec: Int) {
                val width=View.MeasureSpec.getSize(widthMeasureSpec).coerceAtLeast(1)
                val available=(width-body.paddingLeft-body.paddingRight).coerceAtLeast(1)
                val childWidth=View.MeasureSpec.makeMeasureSpec(available,View.MeasureSpec.EXACTLY)
                var controls=body.paddingTop+body.paddingBottom
                for(index in 0 until body.childCount) {
                    val child=body.getChildAt(index)
                    if(child===image) continue
                    child.measure(childWidth,View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
                    val lp=child.layoutParams as LinearLayout.LayoutParams
                    controls+=child.measuredHeight+lp.topMargin+lp.bottomMargin
                }
                val remaining=(View.MeasureSpec.getSize(heightMeasureSpec)-controls).coerceAtLeast(dp(64))
                val side=minOf(available,remaining)
                val params=image.layoutParams as LinearLayout.LayoutParams
                if(params.width!=side || params.height!=side) image.layoutParams=params.apply {
                    this.width=side; height=side; gravity=android.view.Gravity.CENTER_HORIZONTAL
                }
                super.onMeasure(widthMeasureSpec,heightMeasureSpec)
            }
        }
        scroll.addView(body); zoomSurface=scroll
        qrFrame?.getChildAt(0)?.visibility=View.GONE
        qrFrame?.addView(scroll,FrameLayout.LayoutParams(-1,-1)); close.requestFocus()
    }
    private fun dismissZoom() {
        zoomSurface?.let { qrFrame?.removeView(it) }; zoomSurface=null; zoomImage=null; zoomStatus=null
        qrFrame?.getChildAt(0)?.visibility=View.VISIBLE
        zoomAnchor?.takeIf { it.parent!=null }?.requestFocus()
    }
    private fun expireQr() {
        cancel()
        qrMessage("二维码已过期，请刷新")
    }
    private fun schedulePoll(expiresAt: Long) {
        val ticket=revision
        val next=Runnable {
            if(parent==null || revision!=ticket) return@Runnable
            if(System.currentTimeMillis()>=expiresAt) { expireQr(); return@Runnable }
            task({ repository.pollLoginQr() }) { result ->
                when(result.status) {
                    LoginQrStatus.CONFIRMED -> {
                        val session=result.session ?: return@task
                        AuthStore.save(activity,session); changed(); render()
                    }
                    LoginQrStatus.EXPIRED -> expireQr()
                    else -> { qrMessage(if(result.status==LoginQrStatus.SCANNED) "已扫码，请在手机上确认" else "等待手机扫码"); schedulePoll(expiresAt) }
                }
            }
        }
        poll=next; main.postDelayed(next,2000)
    }
    private fun listing(kind: String,focusSeries: String?=null) {
        val body=reset(); page="list"
        action(body,"返回账号") { render() }.requestFocus()
        val context=repository.captureAccount()
        action(body,"刷新列表") {
            if(context!=null && repository.isCurrent(context)) { intents?.resume(context); listing(kind) }
        }
        status=label("正在读取云端列表…"); body.addView(status)
        if(context==null) { status?.text="请先登录"; return }
        val pullRevision=generation(context)
        val rows=LinearLayout(activity).apply { orientation=LinearLayout.VERTICAL }; body.addView(rows)
        task({
            repository.requireCurrent(context)
            val result=when(kind) {
                "history" -> repository.accountResumeHistory(context.session)
                "favorites" -> repository.accountFavorites(context)
                else -> repository.accountReservations()
            }
            repository.requireCurrent(context)
            result
        },context) { result ->
            if(kind=="favorites" && current(context,pullRevision)) {
                val allowed=result.items.filter { intents?.suppressFavorite(context,it.id)!=true }
                if(current(context,pullRevision)) mergeFavorites?.invoke(allowed,context,pullRevision)
            }
            // A pull payload is only ever authorized for the intent generation it was requested under.
            // A later delete/rewatch must not re-authorize it: invalidate the list instead of refetching.
            var payloadValid=true
            val update: ()->Unit = update@ {
                if(!payloadValid) return@update
                if(!current(context,pullRevision)) {
                    payloadValid=false
                    rows.removeAllViews()
                    if(repository.isCurrent(context)) status?.text="账号同步状态已变化，请刷新列表"
                    return@update
                }
                val ticket=revision
                val items=result.items.filter { series -> when(kind) {
                    "history" -> intents?.suppressHistory(context,series.id)!=true
                    "favorites" -> intents?.suppressFavorite(context,series.id)!=true
                    else -> true
                } }
                rows.removeAllViews()
                status?.text=if(items.isEmpty()) "云端暂无记录" else "已读取 ${items.size} 部剧集"
                items.forEach { series ->
                    val progress=result.history.firstOrNull { it.series.id==series.id }
                    val reservation=result.reservations.firstOrNull { it.series.id==series.id }
                    val suffix=progress?.let { " · 第 ${it.episodeIndex+1} 集" } ?: if(series.preview) " · 待上线" else ""
                    posterRow(rows,series.cover,series.title+suffix) {
                        if(ticket!=revision || page!="list" || !current(context,pullRevision)) return@posterRow
                        if(kind=="history" && intents?.suppressHistory(context,series.id)==true) return@posterRow
                        if(kind=="favorites" && intents?.suppressFavorite(context,series.id)==true) return@posterRow
                        if(series.preview || reservation?.online==false) reservationActions(series,reservation,context,pullRevision)
                        else if(openCaptured!=null) openCaptured.invoke(series,progress,kind,context)
                        else open(series,progress,kind)
                    }.also { if(series.id==focusSeries) it.requestFocus() }
                }
            }
            if(intents==null) update() else observeIntents(context,update)
        }
    }
    private fun reservationActions(series: Series,reservation: AccountReservation?,context: AccountContext,expectedRevision: Long) {
        if(!current(context,expectedRevision)) return
        val body=reset(); page="reservation"
        action(body,"返回预约列表") { listing("reservations") }.requestFocus()
        body.addView(label(series.title,20f)); status=label(reservation?.expectedText.orEmpty().ifBlank { "剧集尚未上线" }); body.addView(status)
        if(reservation!=null) action(body,if(reservation.subscribed) "取消预约" else "预约") {
            if(!current(context,expectedRevision)) return@action
            task({
                repository.requireCurrent(context)
                val result=repository.setReservation(series.id,!reservation.subscribed,reservation.itemType)
                repository.requireCurrent(context)
                result
            },context) { listing("reservations") }
        }
    }
    private fun <T> task(work: ()->T,context: AccountContext?=null,done: (T)->Unit) {
        val ticket=revision; val request=RequestScope(); scope?.cancel(); future?.cancel(true); scope=request
        future=worker.submit {
            val result=runCatching { request.run { context?.let(repository::requireCurrent); work() } }
            main.post {
                if(ticket!=revision || parent==null || activity.isDestroyed) return@post
                // Guard failures too: an old expired request must never log out a replacement session.
                // QR work deliberately has no context: its own successful login changes null -> auth.
                if(context!=null && !repository.isCurrent(context)) return@post
                result.onSuccess(done).onFailure {
                    val message=it.message.orEmpty()
                    val expired=message.contains("未登录") || message.contains("登录已过期") || message.contains("请先登录")
                    if(expired) {
                        if(context!=null && !repository.isCurrent(context)) return@onFailure
                        repository.logout(); AuthStore.clear(activity); changed(); render(); status?.text="登录已过期，请重新扫码"
                    }
                    else if(it is AccountProfileUnavailableException) qrMessage(it.message.orEmpty())
                    else qrMessage("账号请求失败，请重试（${it.javaClass.simpleName}）")
                }
            }
        }
    }
    private fun cancel() {
        revision++; poll?.let(main::removeCallbacks); poll=null; expiry?.let(main::removeCallbacks); expiry=null
        scope?.cancel(); future?.cancel(true); scope=null; future=null; repository.cancelLoginQr()
        if(qrFrame!=null) AuthStore.restore(activity,repository)
        acceptedQr=null
        qrImage?.let { displayQr(it,null) }; zoomImage?.let { displayQr(it,null) }
    }
    fun goBack() { if(zoomSurface!=null) dismissZoom() else when(page) { "home" -> back(); "reservation" -> listing("reservations"); else -> render() } }
    fun close() { detachIntents(); cancel(); dismissZoom(); qrFrame=null; qrImage=null; zoomAnchor=null; AuthStore.restore(activity,repository); parent=null; status=null }
    fun destroy() { close(); worker.shutdownNow() }
}
