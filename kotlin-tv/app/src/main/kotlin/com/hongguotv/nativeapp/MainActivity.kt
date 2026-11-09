// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import com.hongguotv.nativeapp.rayneo.*
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.*
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.widget.*
import android.text.TextUtils
import androidx.media3.common.*
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.PlayerView
import com.hongguotv.core.*
import java.util.concurrent.Executors

@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class MainActivity: Activity() {
    private val bg=TvStyle.background; private val surface=TvStyle.surface
    private val accent=TvStyle.accent; private val white=TvStyle.text; private val muted=TvStyle.muted
    private val main=Handler(Looper.getMainLooper())
    private val io=Executors.newFixedThreadPool(3)
    private val repository=ContentRepository(prepareIdentityRetry = DeviceIdentity::prepareRotation, commitIdentityRetry = DeviceIdentity::commitRotation, rollbackIdentityRetry = DeviceIdentity::rollbackRotation)
    private val accountRepository by lazy { AccountRepository(repository) }
    private var accountScreen: AccountScreen?=null
    private val cloudHistory=mutableMapOf<String,AccountHistoryItem>()
    private var cloudOwner=""
    private val accountOwners by lazy { getSharedPreferences("account-local-progress-owners",Context.MODE_PRIVATE) }
    private var playbackAccount: AuthSession?=null
    private var accountReturn=false
    private var detailLocalSelection=true
    private fun detailProgress(id: String)=ResumePlayback.progress(detailLocalSelection,library.progress(id),if(detailLocalSelection) null else accountProgress(id))
    private var accountReturnSection: String?=null
    private val accountHistorySync by lazy { AccountHistorySync(this,accountRepository) { session,rows ->
        if(!isDestroyed && accountRepository.currentSession()?.user?.id==session.user.id) {
            if(cloudOwner!=session.user.id) cloudHistory.clear()
            cloudOwner=session.user.id
            rows.forEach { row -> if((cloudHistory[row.series.id]?.readAt ?: 0L)<row.readAt) cloudHistory[row.series.id]=row }
        }
    } }
    private fun accountProgress(id: String): WatchProgress? {
        val session=accountRepository.currentSession() ?: return library.progress(id)
        val local=library.progress(id).takeIf { accountOwners.getString(id,null)==session.user.id }
        val row=cloudHistory[id].takeIf { cloudOwner==session.user.id }
        return if(row!=null && row.readAt>(local?.updatedAt ?: 0L))
            WatchProgress(row.series,row.episodeId,row.episodeIndex,row.position,row.duration,false,row.readAt) else local
    }
    private fun accountChanged() {
        cloudHistory.clear(); cloudOwner=accountRepository.currentSession()?.user?.id.orEmpty()
        relatedReserveTicket++; relatedReservations.clear(); relatedSection?.refreshLabels()
        if(library.isLoaded) accountHistorySync.resume(library.history())
    }
    private fun showAccount(section: String?=null,focusSeries: String?=null) {
        invalidatePage(); releaseCatalogViews(); screen="account"; accountReturn=false
        val body=base("红果账号")
        val host=column(); body.addView(host,lp(-1,0).apply { weight=1f })
        val controller=AccountScreen(this,accountRepository,{ title,action -> button(title,onClick=action) },
            { accountReturn=false; tab=5; showCatalog() },::accountChanged) { series,progress,kind ->
                accountReturn=true; accountReturnSection=kind
                if(progress!=null) { cloudOwner=accountRepository.currentSession()?.user?.id.orEmpty(); cloudHistory[series.id]=progress }
                openDetail(series,resume=progress!=null)
            }
        accountScreen=controller; controller.show(host,section,focusSeries)
    }

    private val networkCleanup=Executors.newSingleThreadExecutor()
    private val preloadStatistics=PreloadStatistics.session
    private var playbackMeasurement: com.hongguotv.core.PlaybackSessionMeasurement?=null
    private val preloadPreferences by lazy { PreloadPreferences(this) }
    private var preloadConfig=PreloadSettings()
    private val preloadClock=PreloadCheckClock()
    private var preloadCycle=false
    private val preloadGate=PreloadGate { android.os.SystemClock.elapsedRealtime() }
    private var episodeRequestBudget=PlaybackRequestBudget()
    private val prefetchWorker=Executors.newSingleThreadExecutor()
    private val prefetchHttp by lazy { repository.http.newBuilder()
        .connectionPool(okhttp3.ConnectionPool()).dispatcher(okhttp3.Dispatcher()).build() }
    private val prefetchDirectory by lazy { java.io.File(cacheDir,"episode-prefetch").also { dir ->
        dir.mkdirs(); dir.listFiles()?.filter { it.name.startsWith("hongguo-prefetch-") && it.extension=="part" }?.forEach { it.delete() }
    } }
    private val batchTransport by lazy { BatchModelTransport(prefetchHttp.newBuilder().callTimeout(8,java.util.concurrent.TimeUnit.SECONDS).build()) }
    private val batchModels by lazy { BatchModelPrefetch(prefetchWorker,preloadGate,batchTransport::identity,{library.maxQuality},batchTransport::fetch) }
    private fun takeBatchModel(id: String,quality: Int): StreamInfo? = if(preloadConfig.enabled && preloadConfig.batchModels)
        batchTransport.identity()?.let {batchModels.cache.take(it,id,quality)} else null
    private val upcoming by lazy { EpisodePreloader(prefetchWorker,prefetchHttp,{prefetchDirectory},
        resolve={key -> takeBatchModel(key.substringBefore(':'),key.substringAfter(':').toInt()) ?: repository.stream(key.substringBefore(':'),key.substringAfter(':').toInt(),PlaybackRequestBudget(8,2))},
        gate=preloadGate,clock={android.os.SystemClock.elapsedRealtime()},batchModels=batchModels) }
    private var playbackPreparation: PendingPreparation<RemoteVideo>?=null
    private var playbackHttp: okhttp3.OkHttpClient?=null
    private var readySince=0L
    private lateinit var artwork: ArtworkLoader
    private data class DeferredUi(val apply: ()->Unit,val discard: ()->Unit={})
    private var deferredWork: DeferredUi?=null
    private var initialCatalogPending=true
    private var restoreStoppedPage=false
    private var pendingDetail: Series?=null
    private var detailAutoplay=true
    private var seasonGeneration=0
    private var seasonBanner: TextView?=null
    private var nextSeasonDetail: Detail?=null
    private var seasonSuppressed=false
    private var seasonPending=false
    private var seasonPreparationAttempted=false
    private var seasonLookup: RequestScope?=null
    private var relatedSection: RelatedWorksView?=null
    private var relatedLoadTicket=0
    private val relatedReservations=mutableMapOf<String,AccountReservation>()
    private var relatedReserveTicket=0
    private var libraryLoadError: Throwable?=null
    private var workScope: RequestScope?=null
    private var workJob: java.util.concurrent.Future<*>?=null
    private var playerListener: Player.Listener?=null
    private var playbackQueue: SeamlessPlaybackQueue?=null
    private var playbackAnalytics: androidx.media3.exoplayer.analytics.AnalyticsListener?=null
    private val completedEpisodes=mutableSetOf<String>()
    private val rejectedQueueItems=mutableSetOf<String>()
    private var currentEpisodeDuration=0L
    private lateinit var playbackTitle: TextView
    private var directPlayback=false
    private var collection: String?=null
    private var collectionReturn: CatalogState?=null
    private var collectionFocus=""
    private var collectionBack: View?=null
    private val pageSize=20
    private var catalogScroll: ScrollView?=null
    private var catalogBody: LinearLayout?=null
    private var homePosition: HomeScreen.Position?=null
    private var catalogPageKey=""
    private val scrollPositions=mutableMapOf<String,Int>()
    private val catalogCards=linkedMapOf<String,View>()
    private val cardImages=linkedMapOf<String,Pair<ImageView,String>>()
    private var homeScreen: HomeScreen?=null
    private var selectionPreview: SeriesPreview?=null
    private var homeFromCache=false
    private var homeUpdateCount=0
    private var launchStarted=android.os.SystemClock.elapsedRealtime()
    private val activityStarted=launchStarted
    private var homeContentLogged=false
    private lateinit var library: Library
    private lateinit var tvTools: TvTools
    private lateinit var updater: AppUpdater
    private lateinit var mediaSession: TvMediaSession
    private val sleepTimer=SleepTimer { android.os.SystemClock.elapsedRealtime() }
    private var sleepStopped=false
    private var playerView: PlayerView?=null
    private var stereoVideo: StereoVideoView?=null
    private lateinit var scene: FrameLayout
    private lateinit var notices: RayNeoNotice
    private lateinit var favoriteMonitor: FavoriteMonitor
    private val favoriteBadges=mutableMapOf<String,TextView>()
    private var favoriteStatus: TextView?=null
    private var favoriteCheck: TextView?=null
    private var homeUpdates: TextView?=null
    private val resumeCards=mutableListOf<View>()
    private var episodePanel: EpisodePanel?=null
    private val recovery=RecoveryBudget()
    private var recoveryQuality: Pair<String,Int>?=null
    private var retryPending=false
    private var autoRecovery=false
    private var startPosition=0L
    private var retryPosition=0L
    private var requestedAutoplay=true
    private var retryAutoplay=true
    private var foreground=false
    private lateinit var connectivity: ConnectivityManager
    private var networkRegistered=false
    private val networkCallback=object: ConnectivityManager.NetworkCallback() {
        private fun changed() { main.post { if(connectivity.isActiveNetworkMetered || !networkAvailable()) cancelPrefetch(); if(foreground && screen=="player" && playError && autoRecovery) scheduleRecovery() } }
        override fun onAvailable(network: Network) { changed() }
        override fun onLost(network: Network) { changed() }
        override fun onCapabilitiesChanged(network: Network,capabilities: NetworkCapabilities) { changed() }
        override fun onBlockedStatusChanged(network: Network,blocked: Boolean) { changed() }
    }
    private val retryRunnable=Runnable {
        retryPending=false
        if(foreground && screen=="player" && playError && autoRecovery) {
            if(!networkAvailable()) scheduleRecovery()
            else if(recovery.consume()) {
                android.util.Log.i("HongguoTV","Automatic playback recovery ${recovery.attempts}/3 at ${retryPosition}ms")
                playEpisode(episodeIndex,retryPosition,retryAutoplay,recovering=true)
            }
        }
    }
    private lateinit var root: FrameLayout
    private var generation=0
    private val seasonGate=com.hongguotv.core.SeasonContinuationGate()
    private var seasonSeekToEnd=false
    private var screen="catalog"
    private var tab=0
    private var page=1
    private var query=""
    private var catalog=emptyList<Series>()
    private var hasMore=false
    private var catalogFocus=""
    private data class CatalogState(val page: Int,val items: List<Series>,val hasMore: Boolean,val ranking: ComicRanking?)
    private var ranking: ComicRanking?=null
    private var rankRefresh: View?=null
    private var rankSubtitle: TextView?=null
    private val tabState=mutableMapOf<Int,CatalogState>()
    private val tabFocus=mutableMapOf<Int,String>()
    private val nav=mutableListOf<View>()
    private val typeButtons=mutableMapOf<ContentType,View>()
    private var searchInput: View?=null
    private var searchButton: View?=null
    private val searchActions=mutableListOf<View>()
    private var historyManage: View?=null
    private var detail: Detail?=null
    private var episodeIndex=0
    private var group=0
    private var synopsisExpanded=false
    private var player: ExoPlayer?=null
    private var video: RemoteVideo?=null
    private var playbackReady=false
    private var pausedForLifecycle=false
    private lateinit var hud: LinearLayout
    private lateinit var playbackText: TextView
    private lateinit var progressBar: ProgressBar
    private lateinit var controls: LinearLayout
    private var panel=false
    private var transportPlay: TextView?=null
    private var sleepButton: TextView?=null
    private var speedDialog: AlertDialog?=null
    private var pendingSeek: Long?=null
    private var playError=false
    private var lastSaved=0L
    private var recoveryReason=""
    private var quality=""
    private val bufferingWatchdog=BufferingWatchdog()
    private val tick=object: Runnable {
        override fun run() {
            if(!foreground || isDestroyed) return
            if(screen=="player") {
                syncBoundaryPause()
                if(sleepTimer.poll()) stopForSleep()
                player?.duration?.takeIf { it>0 }?.let { currentEpisodeDuration=it }
                val buffering=player?.playbackState==Player.STATE_BUFFERING && requestedAutoplay && !pausedForLifecycle && episodePanel==null && !playError && !sleepStopped
                if(bufferingWatchdog.poll(android.os.SystemClock.elapsedRealtime(),buffering,video?.deliveredBytes ?: 0L,player?.bufferedPosition ?: 0L)) {
                    video?.cancelReads(); playerFailure(java.net.SocketTimeoutException("视频读取停滞超过 30 秒"))
                }
                if(hud.visibility==View.VISIBLE) updatePlaybackText()
                maybePrefetch()
                maybeQueueNext()
                maybePrepareSeason()
                updateSeasonBanner()
                val now=android.os.SystemClock.elapsedRealtime()
                if(player?.isPlaying==true && now-lastSaved>5000) { saveProgress(); lastSaved=now }
            }
            main.postDelayed(this,1000)
        }
    }
    private val hideHud=Runnable { if(screen=="player" && !panel && player?.isPlaying==true && !playError) hidePlaybackOverlay() }
    private val seekRunnable=Runnable { pendingSeek?.let { detail?.episodes?.getOrNull(episodeIndex)?.let(completedEpisodes::remove); player?.seekTo(it) }; pendingSeek=null }
    private fun dp(value: Number)=(value.toFloat()*resources.displayMetrics.density).toInt()
    private fun widthDp()=RayNeoDisplay.eyeWidth(this)/resources.displayMetrics.density
    private fun lp(w: Int=LinearLayout.LayoutParams.MATCH_PARENT,h: Int=LinearLayout.LayoutParams.WRAP_CONTENT)=LinearLayout.LayoutParams(w,h)
    private fun rounded(color: Int,border: Int=Color.TRANSPARENT)=TvStyle.shape(this,color,border)
    private fun text(value: String,size: Float=16f,color: Int=white)=TextView(this).apply { text=value; textSize=size; setTextColor(color); includeFontPadding=false }
    private fun column()=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun row()=LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL }
    private fun focusStyle(view: View,selected: Boolean=false) {
        view.id=View.generateViewId(); view.isFocusable=true; view.isFocusableInTouchMode=true
        fun paint(focused: Boolean) { view.background=rounded(if(focused) TvStyle.raised else if(selected) Color.rgb(61,39,34) else surface,if(focused) accent else Color.TRANSPARENT) }
        paint(false); view.setOnFocusChangeListener { _,focused -> paint(focused) }
    }
    private fun button(label: String,selected: Boolean=false,onClick: ()->Unit): TextView = text(label,15f).apply {
        gravity=Gravity.CENTER; setPadding(dp(14),dp(10),dp(14),dp(10)); minHeight=dp(42); focusStyle(this,selected); setOnClickListener { onClick() }
    }
    private fun addButton(parent: LinearLayout,label: String,selected: Boolean=false,onClick: ()->Unit): TextView {
        val view=button(label,selected,onClick); parent.addView(view,lp().apply { width=LinearLayout.LayoutParams.WRAP_CONTENT; rightMargin=dp(8) }); return view
    }
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        ContentRepository.deviceStore = PreferencesPlayerDeviceStore(getSharedPreferences("registered-player", Context.MODE_PRIVATE))
        ContentRepository.searchDeviceStore = PreferencesPlayerDeviceStore(getSharedPreferences("registered-search", Context.MODE_PRIVATE))
        DeviceIdentity.configure(this)
        preloadConfig=preloadPreferences.load()
        AuthStore.restore(this,accountRepository)
        window.decorView.systemUiVisibility=View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
        library=Library(this); artwork=ArtworkLoader(this); tvTools=TvTools(this); root=FrameLayout(this).apply { setBackgroundColor(bg) }
        val logical=FrameLayout(this).apply { addView(root,FrameLayout.LayoutParams(-1,-1)) }
        notices=RayNeoNotice(logical)
        scene=FrameLayout(this).apply { setBackgroundColor(Color.BLACK); addView(RayNeoLayout(this@MainActivity,logical),FrameLayout.LayoutParams(-1,-1)) }
        setContentView(scene)
        RayNeoInput.install(window)
        updater=AppUpdater(this) { foreground && library.isLoaded && screen=="catalog" && !tvTools.showing }
        connectivity=getSystemService(ConnectivityManager::class.java)
        runCatching { connectivity.registerDefaultNetworkCallback(networkCallback); networkRegistered=true }
        favoriteMonitor=FavoriteMonitor(library,repository) { refreshFavoriteLabels() }
        mediaSession=TvMediaSession(this,{ if(foreground && screen=="player") requestPlayback(true) },{ if(foreground && screen=="player") requestPlayback(false) },{ position -> if(foreground && screen=="player") seekTo(position) },{ direction -> skipEpisode(direction) },{ if(foreground && screen=="player") returnToDetail() })
        message(base(),"正在读取本机记录…")
        loadLibrary()
    }
    private fun showLibraryError(problem: Throwable) {
        val body=base(); error(body,problem) { message(base(),"正在读取本机记录…"); loadLibrary() }
    }
    private fun loadLibrary() {
        libraryLoadError=null
        library.loadAsync(onFailure={ problem ->
            if(!isDestroyed) { libraryLoadError=problem; if(foreground) showLibraryError(problem) }
        }) {
            if(!isDestroyed && foreground) { showCatalog(load=true); favoriteMonitor.check(); accountHistorySync.resume(library.history()) }
        }
    }
    private fun base(title: String?=null): LinearLayout {
        root.removeAllViews(); root.setBackgroundColor(bg)
        val container=column(); val horizontal=dp(widthDp()*.05f); val vertical=(resources.displayMetrics.heightPixels*.05f).toInt()
        container.setPadding(horizontal,vertical,horizontal,vertical); root.addView(container,FrameLayout.LayoutParams(-1,-1))
        if(title!=null) { val heading=row(); addButton(heading,"‹ 返回") { goBack() }; heading.addView(text(title,22f).apply { setTypeface(null,Typeface.BOLD) },lp(0,dp(44)).apply { weight=1f }); container.addView(heading) }
        return container
    }
    private fun loadCover(view: ImageView,url: String) {
        var ancestor: View?=view
        var focused=false
        while(ancestor!=null && ancestor!==root) {
            if(ancestor.isFocused) { focused=true; break }
            ancestor=ancestor.parent as? View
        }
        artwork.load(view,url,if(focused) ArtworkLoader.PRIORITY_FOCUSED else ArtworkLoader.PRIORITY_VISIBLE)
    }
    private fun cancelWork() {
        playbackPreparation?.close(); playbackPreparation=null
        deferredWork?.discard?.invoke(); deferredWork=null
        workScope?.cancel(); workScope=null
        workJob?.cancel(true); workJob=null
        (io as? java.util.concurrent.ThreadPoolExecutor)?.purge()
    }
    private fun invalidatePage() {
        cancelNextSeason()
        seasonGate.invalidate()
        relatedSection=null
        accountScreen?.destroy(); accountScreen=null
        generation++; cancelWork(); artwork.cancelPage()
    }
    private fun releaseCatalogViews() {
        homeScreen?.let { homePosition=it.capturePosition() }
        catalogScroll?.let { scrollPositions[catalogPageKey]=it.scrollY }
        catalogBody=null
        homeScreen=null; selectionPreview=null; catalogScroll=null
        nav.clear(); typeButtons.clear(); searchInput=null; searchButton=null; searchActions.clear()
        historyManage=null; rankRefresh=null; rankSubtitle=null; collectionBack=null; favoriteBadges.clear()
        favoriteStatus=null; favoriteCheck=null; homeUpdates=null; resumeCards.clear()
        catalogCards.clear(); cardImages.clear()
    }
    private fun <T> work(action: ()->T,done: (T)->Unit,failed: (Throwable)->Unit) {
        cancelWork()
        val ticket=generation; val scope=RequestScope(); workScope=scope
        workJob=io.submit {
            try {
                val result=scope.run(action)
                main.post {
                    val discard={ if(result is RemoteVideo) result.close(); Unit }
                    if(isDestroyed || generation!=ticket) discard()
                    else if(foreground) done(result)
                    else { deferredWork?.discard?.invoke(); deferredWork=DeferredUi({ done(result) },discard) }
                }
            } catch(e: Exception) {
                main.post {
                    if(!isDestroyed && generation==ticket) {
                        if(foreground) failed(e) else deferredWork=DeferredUi({ failed(e) })
                    }
                }
            }
        }
    }
    private fun message(parent: LinearLayout,label: String) { parent.addView(text(label,17f,muted).apply { setPadding(0,dp(28),0,dp(18)) }) }
    private fun error(parent: LinearLayout,problem: Throwable,retry: ()->Unit) {
        message(parent,"暂时无法加载，请检查网络后重试。")
        val detail=if(problem is java.net.UnknownHostException) "无法连接内容网站" else if(problem is java.net.SocketTimeoutException) "连接超时" else problem.message.orEmpty().replace(Regex("https?://\\S+"),"[地址]").take(160)
        parent.addView(text(detail,13f,muted)); addButton(parent,"重试",onClick=retry).requestFocus()
    }
    private fun switchTab(next: Int) {
        leaveCollection()
        tabState[tab]=CatalogState(page,catalog,hasMore,ranking); tabFocus[tab]=catalogFocus
        tab=next; val saved=tabState[next]; page=saved?.page ?: 1; catalog=saved?.items ?: emptyList(); hasMore=saved?.hasMore ?: false; ranking=saved?.ranking; catalogFocus=tabFocus[next].orEmpty()
        showCatalog(load=(next<=2 && catalog.isEmpty() && (next!=1 || query.isNotBlank())))
    }
    private fun switchContentType(type: ContentType) {
        if(library.contentType==type) return
        leaveCollection()
        (searchInput as? EditText)?.let { query=it.text.toString().trim() }
        library.contentType=type
        for(index in 0..1) { tabState.remove(index); tabFocus.remove(index) }
        releaseCatalogViews()
        page=1; catalog=emptyList(); hasMore=false; ranking=null; catalogFocus=""; homePosition=null
        showCatalog(load=(tab==0 || query.isNotBlank()),focusType=true)
    }
    private fun leaveCollection() {
        if(collection==null) return
        collection=null
        collectionReturn?.let { page=it.page; catalog=it.items; hasMore=it.hasMore; ranking=it.ranking }
        catalogFocus=collectionFocus; collectionReturn=null
    }
    private fun showCollection(kind: String) {
        collectionReturn=CatalogState(page,catalog,hasMore,ranking); collectionFocus=catalogFocus
        collection=kind; page=1; catalogFocus=""; showCatalog()
    }
    private fun inlineHomeFilters()=tab==0 && resources.configuration.fontScale<=1.2f && widthDp()>=900
    private fun localCatalog()=tab==3 || tab==4 || collection!=null
    private fun localItems(): List<Series> = when(collection) {
        "hot" -> collectionReturn?.items.orEmpty().filterNot { it.id in library.hidden() }
        "later" -> library.later()
        "updates" -> library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        "resume" -> library.history().filterNot { library.watched(it.series.id) }.map { it.series }
        else -> if(tab==3) library.favorites() else library.history().map { it.series }
    }
    private fun showCatalog(load: Boolean=false,focusNav: Boolean=false,focusType: Boolean=false) {
        initialCatalogPending=false; restoreStoppedPage=false; pendingDetail=null
        // Never cache a new page number with the previous page's results while a request is pending.
        if(load) {
            val cached=if(tab==0 && page==1) library.cachedHome(library.contentType) else null
            catalog=cached?.items ?: emptyList(); hasMore=cached?.hasMore ?: false; ranking=null; homeFromCache=cached!=null
        }
        invalidatePage(); screen="catalog"; releaseCatalogViews(); val container=base()
        if(foreground && library.isLoaded && !favoriteMonitor.running) main.post { if(foreground && screen=="catalog") favoriteMonitor.check() }
        val top=row().apply { setPadding(0,0,0,dp(8)) }
        val brand=row()
        brand.addView(ImageView(this).apply { setImageResource(com.hongguotv.nativeapp.R.drawable.app_icon) },lp(dp(28),dp(28)).apply { rightMargin=dp(9) })
        brand.addView(text("红果TV",22f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL) })
        top.addView(brand,lp(dp(120),dp(44)))
        listOf("首页","搜索","排行榜","收藏","最近观看","设置").forEachIndexed { index,label ->
            val item=addButton(top,label,index==tab) { switchTab(index) }
            fun paint(focused: Boolean) {
                item.setTextColor(if(focused) bg else if(index==tab) accent else muted)
                item.background=rounded(if(focused) white else if(index==tab) Color.rgb(48,32,29) else Color.TRANSPARENT)
                item.setTypeface(null,if(focused || index==tab) Typeface.BOLD else Typeface.NORMAL)
            }
            paint(false); item.setOnFocusChangeListener { _,focused -> paint(focused) }; nav+=item
        }
        container.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(top) },lp(-1,-2))
        container.addView(View(this).apply { setBackgroundColor(TvStyle.outline) },lp(-1,dp(1)).apply { bottomMargin=dp(6) })
        if(tab==5) {
            val scroll=ScrollView(this); val body=column(); scroll.addView(body); container.addView(scroll,lp(-1,0).apply { weight=1f })
            settings(body); if(focusNav) nav[tab].requestFocus(); return
        }
        if(tab==0) {
            val types=row().apply { setPadding(0,dp(8),0,dp(6)) }
            if(!inlineHomeFilters()) types.addView(text("片库",12f,muted),lp(dp(42),-2))
            ContentType.entries.forEach { type ->
                typeButtons[type]=addButton(types,type.label,library.contentType==type) { switchContentType(type) }
                    .apply { isSelected=library.contentType==type; nextFocusUpId=nav[tab].id }
            }
            if(inlineHomeFilters()) {
                top.addView(Space(this),lp(0,1).apply { weight=1f })
                typeButtons.values.forEach { view -> (view as TextView).apply { textSize=12f; minHeight=dp(34); setPadding(dp(12),dp(7),dp(12),dp(7)) } }
                types.setPadding(0,0,0,0); top.addView(types)
            } else {
                if(tab==0) types.addView(text("长按确认 · 管理片单",12f,muted).apply { setPadding(dp(14),0,0,0) })
                container.addView(types)
            }
            nav.forEach { it.nextFocusDownId=typeButtons.getValue(library.contentType).id }
        }
        if(tab==1) {
            val searchRow=row(); val input=EditText(this).apply { id=View.generateViewId(); hint="输入剧名或关键词"; setText(query); textSize=16f; setTextColor(white); setHintTextColor(muted); isSingleLine=true; maxLines=1; filters=arrayOf(android.text.InputFilter.LengthFilter(80)); imeOptions=android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH }
            searchRow.addView(input,lp(0,dp(45)).apply { weight=1f; rightMargin=dp(10) })
            fun search() { val next=input.text.toString().trim(); if(next.isBlank()) { input.requestFocus(); return }; runSearch(next) }
            searchInput=input; searchButton=addButton(searchRow,"搜索") { search() }; input.nextFocusUpId=nav[tab].id; searchButton?.nextFocusUpId=input.nextFocusUpId
            lateinit var recent: TextView
            recent=addButton(searchRow,"历史") { showSearchHistory(recent) }; recent.nextFocusUpId=input.nextFocusUpId
            lateinit var phone: TextView
            phone=addButton(searchRow,"手机输入") { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken,0); tvTools.phoneInput(phone) { runSearch(it) } }; phone.nextFocusUpId=input.nextFocusUpId
            searchActions.addAll(listOf(recent,phone))
            nav.forEach { it.nextFocusDownId=input.id }
            input.setOnEditorActionListener { _,_,_-> search(); true }; container.addView(searchRow)
            com.hongguotv.nativeapp.rayneo.SearchKeyboard.bind(input)
        } else if(tab==2) {
            val heading=row().apply { setPadding(0,dp(8),0,dp(6)) }
            heading.addView(text("漫剧热播榜",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            rankRefresh=addButton(heading,"刷新榜单") { page=1; catalogFocus=""; showCatalog(true) }.apply { nextFocusUpId=nav[tab].id }
            container.addView(heading)
            rankSubtitle=text(if(load) "正在读取最新榜单…" else ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜",13f,muted)
                .apply { setPadding(0,0,0,dp(8)) }
            container.addView(rankSubtitle)
            nav.forEach { it.nextFocusDownId=rankRefresh!!.id }
        } else if(tab>2) {
            val heading=row().apply { setPadding(0,dp(14),0,dp(6)) }
            heading.addView(text(if(tab==3) "我的收藏" else "接着上次看",25f).apply { setTypeface(null,Typeface.BOLD) },lp(0,-2).apply { weight=1f })
            if(tab==3) {
                favoriteCheck=addButton(heading,"检查更新") { favoriteMonitor.check(force=true) }.apply { nextFocusUpId=nav[tab].id }
            }
            if(tab==4 && library.history().isNotEmpty()) {
                historyManage=addButton(heading,"管理记录") {
                    val rows=library.history()
                    tvTools.choose("选择要管理的观看记录",rows.map { it.series.title },historyManage,{ index -> manageHistory(rows[index].series,historyManage) },"清空记录") {
                        tvTools.confirm("清空观看记录","将删除本机所有观看进度，收藏会保留。",historyManage) { library.clearHistory(); catalogFocus=""; showCatalog() }
                    }
                }.apply { nextFocusUpId=nav[tab].id }
            }
            container.addView(heading)
            if(tab==3) {
                favoriteStatus=text(favoriteMonitor.status,13f,muted).apply { setPadding(0,0,0,dp(6)) }; container.addView(favoriteStatus)
            }
        }
        val body=column(); catalogBody=body; container.addView(body,lp(-1,0).apply { weight=1f })
        if(localCatalog()) {
            catalog=localItems(); ranking=null; page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
        }
        if(load) {
            if(tab==0) catalogGrid(body,focusNav,focusType,loading=true)
            else { message(body,"正在加载…"); if(focusType) typeButtons[library.contentType]?.requestFocus() else nav[tab].requestFocus() }
            val requestTab=tab; val requestPage=page; val requestQuery=query; val requestType=library.contentType
            work({
                val result=when(requestTab) { 0 -> repository.home(requestPage,requestType); 2 -> repository.comicRanking(requestPage); else -> repository.search(requestQuery,requestPage) }
                result
            }, { result ->
                if(requestTab==0 && requestPage==1) library.cacheHome(requestType,result.items,result.hasMore)
                if(requestTab==0 && requestPage==1 && (homeFromCache || tvTools.showing || updater.showing)) {
                    homeScreen?.refresh?.text="热门已更新 · 按确认查看"
                    homeScreen?.refresh?.setOnClickListener { catalog=result.items; hasMore=result.hasMore; ranking=result.ranking; showCatalog() }
                } else {
                    catalog=result.items; hasMore=result.hasMore; ranking=result.ranking
                    val keepNav=nav.any { it.hasFocus() }; val keepType=typeButtons.values.any { it.hasFocus() }
                    rankSubtitle?.text=ranking?.updatedText?.ifBlank { "来源：红果漫剧热播榜" } ?: "来源：红果漫剧热播榜"
                    body.removeAllViews(); catalogGrid(body,keepNav || focusNav,keepType || focusType)
                }
            }, { problem ->
                if(requestTab==0 && requestPage==1) {
                    homeScreen?.refresh?.text=if(catalog.isNotEmpty()) "当前显示上次内容 · 联网后按确认刷新" else "热门暂时无法加载 · 按确认重试"
                    homeScreen?.refresh?.setOnClickListener { showCatalog(true) }
                    return@work
                }
                catalog=emptyList(); hasMore=false; ranking=null; rankSubtitle?.text="榜单加载失败"
                if(problem is SearchSessionExpiredException) {
                    page=1; catalogFocus=""
                    notices.show("搜索结果已过期，已回到第 1 页刷新",1)
                    showCatalog(true)
                } else {
                    body.removeAllViews()
                    error(body,problem) { showCatalog(true) }
                    if(resumeCards.isNotEmpty()) resumeCards.first().requestFocus()
                }
            })
        } else catalogGrid(body,focusNav,focusType)
    }
    private fun catalogGrid(body: LinearLayout,focusNav: Boolean,focusType: Boolean=false,loading: Boolean=false) {
        if(tab==0 && page==1 && collection==null) { renderHome(body,focusNav,focusType,loading); return }
        resumeCards.clear(); catalogCards.clear(); cardImages.clear(); collectionBack=null
        if(collection!=null) {
            val heading=row()
            collectionBack=addButton(heading,"‹ 返回首页") { leaveCollection(); showCatalog() }.apply {
                nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[tab].id
                nextFocusDownId=id
            }
            heading.addView(text(when(collection) { "later" -> "稍后看"; "updates" -> "收藏有更新"; "hot" -> "热门发现"; else -> "接着看" },22f))
            body.addView(heading)
            typeButtons.values.forEach { it.nextFocusDownId=collectionBack!!.id }
        }
        catalogPageKey="$tab:${collection.orEmpty()}:$page:$query"
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val scroll=ScrollView(this).apply { isFillViewport=false; isVerticalScrollBarEnabled=false; clipToPadding=false }; catalogScroll=scroll
        val list=column(); scroll.addView(list); body.addView(scroll,lp(-1,0).apply { weight=1f })
        var restoringScroll=true
        val displayed=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else if(tab==0) catalog.filterNot { it.id in library.hidden() } else catalog.take(30)
        if(displayed.isEmpty()) {
            message(list,if(loading) "正在加载推荐…" else when(tab) { 1 -> if(query.isBlank()) "输入关键词，用遥控器确认搜索" else "没有找到相关剧集，换个关键词试试"; 3 -> "在剧集详情中选择收藏，喜欢的剧就会出现在这里"; 4 -> "播放过的剧集会自动保存在这里"; else -> "本页没有更多内容" })
            if((tab<=2 || localCatalog()) && page>1) addButton(list,"上一页") { page--; showCatalog(!localCatalog()) }
            if((tab<=2 || localCatalog()) && hasMore) addButton(list,"下一页") { page++; showCatalog(!localCatalog()) }
            if(resumeCards.isNotEmpty()) {
                typeButtons.values.forEach { it.nextFocusDownId=resumeCards.first().id }
                resumeCards.forEach { it.nextFocusDownId=it.id }
            }
            if(focusType) typeButtons[library.contentType]?.requestFocus() else if(collectionBack!=null && !focusNav) collectionBack?.requestFocus() else if(focusNav || resumeCards.isEmpty()) nav[tab].requestFocus() else resumeCards.first().requestFocus()
            return
        }
        val cards=mutableListOf<View>(); val count=RayNeoDisplay.columns(this); val gap=dp(14); val width=((RayNeoDisplay.eyeWidth(this)*.9f-gap*(count-1))/count).toInt()
        displayed.chunked(count).forEach { items ->
            val line=row(); line.gravity=Gravity.TOP
            items.forEachIndexed { columnIndex,item ->
                val card=column(); card.setPadding(dp(4),dp(4),dp(4),dp(9)); focusStyle(card); card.contentDescription=item.title; card.tag=item.id; catalogCards[item.id]=card
                val position=if(tab==2) ranking?.positions?.get(item.id) else null
                val artwork=FrameLayout(this); card.addView(artwork,lp(-1,((width-dp(8))/TvStyle.POSTER_ASPECT).toInt()))
                val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER; importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO }; artwork.addView(image,FrameLayout.LayoutParams(-1,-1)); loadCover(image,item.cover); cardImages[item.id]=image to item.cover
                if(tab==2) {
                    val rankLabel=position?.rank?.let { "第 $it 名" } ?: "名次暂无"
                    artwork.addView(text(rankLabel,15f).apply { setTypeface(null,Typeface.BOLD); setPadding(dp(8),dp(5),dp(8),dp(5)); background=rounded(if((position?.rank ?: Int.MAX_VALUE)<=3) accent else Color.rgb(28,31,39)) },FrameLayout.LayoutParams(-2,-2,Gravity.TOP or Gravity.START))
                    card.contentDescription="$rankLabel，${item.title}，${position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无"}"
                }
                card.addView(text(item.title,14f).apply { maxLines=2; minLines=2; ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),dp(7),dp(3),0) },lp(-1,-2).apply { weight=1f })
                val progress=if(tab==4 || collection=="resume") library.progress(item.id) else null
                val badge=text(if(tab==3) library.favoriteLabel(item.id) else if(tab==2) position?.heat?.ifBlank { "热度暂无" } ?: "热度暂无" else if(progress!=null) if(library.watched(item.id)) "整剧已看完" else "第 ${progress.episodeIndex+1} 集 · ${formatTime(progress.position)}" else item.badge,12f,if(tab==2 || tab==3) accent else muted).apply { maxLines=1; minLines=1; minHeight=dp(19); ellipsize=TextUtils.TruncateAt.END; setPadding(dp(3),0,0,0) }
                card.addView(badge,lp(-1,-2)); if(tab==3) favoriteBadges[item.id]=badge
                card.setOnClickListener { catalogFocus=item.id; openDetail(item,tab==4 || collection=="resume" || collection=="updates") }
                card.setOnLongClickListener { catalogFocus=item.id; quickActions(item,card); true }
                card.setOnKeyListener { _,key,event -> if(key==KeyEvent.KEYCODE_MENU) { if(event.action==KeyEvent.ACTION_UP) { catalogFocus=item.id; quickActions(item,card) }; true } else false }
                card.setOnFocusChangeListener { _,focused -> card.background=TvStyle.card(this,focused); if(focused) { catalogFocus=item.id; loadCover(image,item.cover); preview.show(item,selectionStatus(item)); if(!restoringScroll) scroll.post { scroll.smoothScrollTo(0,when { line.top<scroll.scrollY -> line.top; line.bottom>scroll.scrollY+scroll.height -> (line.bottom-scroll.height).coerceAtLeast(0); else -> scroll.scrollY }) } } }
                // The row measures its tallest card, then stretches siblings to keep badges aligned.
                line.addView(card,lp(width,-1).apply { if(columnIndex<count-1) rightMargin=gap }); cards+=card
            }
            list.addView(line,lp(-1,-2).apply { bottomMargin=dp(10) })
        }
        val paging=row(); paging.gravity=Gravity.CENTER
        var prev: View?=null; var next: View?=null
        if(tab<=2 || localCatalog()) {
            prev=addButton(paging,"上一页") { if(page>1) { page--; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=page>1; isFocusable=page>1; alpha=if(page>1) 1f else .4f }
            paging.addView(text(if(localCatalog()) "第 $page / ${maxOf(1,(catalog.size+pageSize-1)/pageSize)} 页" else if(tab==2 && ranking!=null) "第 $page / ${ranking!!.totalPages} 页" else "第 $page 页",14f,muted).apply { gravity=Gravity.CENTER },lp(dp(if(tab==2) 135 else 95),dp(44)))
            next=addButton(paging,"下一页") { if(hasMore) { page++; catalogFocus=""; showCatalog(!localCatalog()) } }.apply { isEnabled=hasMore; isFocusable=hasMore; alpha=if(hasMore) 1f else .4f }
            if(collection=="hot" && !hasMore && collectionReturn?.hasMore==true) {
                next=addButton(paging,"下一批热门") { collection=null; collectionReturn=null; page=2; catalogFocus=""; showCatalog(true) }
            }
            list.addView(paging,lp(-1,dp(50)))
        }
        cards.forEachIndexed { index,card ->
            card.nextFocusLeftId=if(index%count==0) card.id else cards[index-1].id
            card.nextFocusRightId=if(index%count==count-1 || index==cards.lastIndex) card.id else cards[index+1].id
            card.nextFocusUpId=if(index<count) (searchInput?.id ?: collectionBack?.id ?: typeButtons[library.contentType]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: resumeCards.getOrNull(minOf(index,2))?.id ?: nav[tab].id) else cards[index-count].id
            card.nextFocusDownId=if(index+count<cards.size) cards[index+count].id else if(index/count<cards.lastIndex/count) cards.last().id else next?.takeIf { it.isFocusable }?.id ?: prev?.takeIf { it.isFocusable }?.id ?: card.id
        }
        nav.forEach { it.nextFocusDownId=searchInput?.id ?: typeButtons[library.contentType]?.id ?: rankRefresh?.id ?: favoriteCheck?.id ?: historyManage?.id ?: cards.first().id }
        rankRefresh?.nextFocusDownId=cards.first().id
        historyManage?.nextFocusDownId=cards.first().id
        favoriteCheck?.nextFocusDownId=cards.first().id
        collectionBack?.nextFocusDownId=cards.first().id
        resumeCards.forEachIndexed { index,v -> v.nextFocusDownId=cards[minOf(index,cards.lastIndex)].id }
        if(resumeCards.isNotEmpty()) cards.take(count).forEachIndexed { index,v -> v.nextFocusUpId=resumeCards[minOf(index,resumeCards.lastIndex)].id }
        else homeUpdates?.let { updates -> updates.nextFocusUpId=typeButtons[library.contentType]?.id ?: nav[0].id; updates.nextFocusDownId=cards.first().id; cards.take(count).forEach { it.nextFocusUpId=updates.id } }
        typeButtons.values.forEach { it.nextFocusDownId=searchInput?.id ?: collectionBack?.id ?: resumeCards.firstOrNull()?.id ?: homeUpdates?.id ?: cards.first().id }
        searchInput?.nextFocusDownId=cards.first().id
        searchButton?.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id
        searchActions.forEach { it.nextFocusDownId=cards[minOf(4,cards.lastIndex)].id }
        if(focusType) typeButtons[library.contentType]?.requestFocus() else if(focusNav) nav[tab].requestFocus()
        else if(resumeCards.isNotEmpty() && (catalogFocus.isEmpty() || catalogFocus.startsWith("resume:"))) (resumeCards.firstOrNull { it.tag==catalogFocus } ?: resumeCards.first()).requestFocus()
        else cards[displayed.indexOfFirst { it.id==catalogFocus }.coerceAtLeast(0)].requestFocus()
        val savedScroll=scrollPositions[catalogPageKey]
        scroll.post {
            if(catalogScroll!==scroll) return@post
            cards.forEach { view ->
                val card=view as LinearLayout
                val image=card.getChildAt(0)
                val textHeight=card.paddingTop+card.paddingBottom+(1 until card.childCount).sumOf { card.getChildAt(it).measuredHeight }
                val target=minOf(((width-dp(8))/TvStyle.POSTER_ASPECT).toInt(),(scroll.height-textHeight-dp(4)).coerceAtLeast(dp(48)))
                if(image.layoutParams.height!=target) image.layoutParams=image.layoutParams.apply { height=target }
            }
            scroll.post settle@{
                if(catalogScroll!==scroll) return@settle
                savedScroll?.let { scroll.scrollTo(0,it) }
                (cards.firstOrNull { it.hasFocus() }?.parent as? View)?.let { line ->
                    val y=when {
                        line.top<scroll.scrollY -> line.top
                        line.bottom>scroll.scrollY+scroll.height -> if(line.height>=scroll.height) line.top else line.bottom-scroll.height
                        else -> scroll.scrollY
                    }
                    scroll.scrollTo(0,y.coerceAtLeast(0))
                }
                restoringScroll=false
            }
        }
    }
    private fun selectionStatus(series: Series): String {
        val progress=library.progress(series.id)
        return listOf(if(library.favorite(series.id)) "已收藏" else "",if(library.queued(series.id)) "稍后看" else "",
            if(library.watched(series.id)) "整剧已看完" else progress?.let { "上次第 ${it.episodeIndex+1} 集 · ${formatTime(it.position)}" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" · ")
    }
    private fun renderHome(body: LinearLayout,focusNav: Boolean,focusType: Boolean,loading: Boolean) {
        homeUpdateCount=library.updatedFavorites()
        val preview=SeriesPreview(this); selectionPreview=preview; body.addView(preview)
        val top=if(inlineHomeFilters()) nav[0] else typeButtons.getValue(library.contentType)
        val home=HomeScreen(this,top,::loadCover,{ item,label -> preview.show(item,if(label.startsWith("第 ")) selectionStatus(item) else listOf(label,selectionStatus(item)).filter { it.isNotBlank() }.joinToString(" · ")) },
            { item,resume -> openDetail(item,resume) },::quickActions,{ catalogFocus=it })
        homeScreen=home; body.addView(home,lp(-1,0).apply { weight=1f })
        val hot=catalog.filterNot { it.id in library.hidden() }
        home.render(homeShelves(),catalogFocus,!focusNav && !focusType,
            if(hasMore) { { page=2; catalogFocus=""; showCatalog(true) } } else null,homePosition)
        home.refresh.text=if(loading) if(homeFromCache) "已显示上次内容 · 正在更新热门…" else "正在更新热门…" else "刷新热门"
        home.refresh.setOnClickListener { showCatalog(true) }
        typeButtons.values.forEach { it.nextFocusDownId=home.firstId() }
        if(inlineHomeFilters()) nav.forEach { it.nextFocusDownId=home.firstId() }
        if(focusNav) nav[0].requestFocus() else if(focusType) typeButtons.getValue(library.contentType).requestFocus()
        if(hot.isNotEmpty() && !homeContentLogged) {
            homeContentLogged=true
            body.post { android.util.Log.i("HongguoTV","Home content ready cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-activityStarted}") }
        }
        if(launchStarted>0) {
            val started=launchStarted; launchStarted=0
            body.post { android.util.Log.i("HongguoTV","Home displayed cached=$homeFromCache elapsedMs=${android.os.SystemClock.elapsedRealtime()-started}") }
        }
    }
    private fun homeShelves(): List<HomeScreen.Shelf> {
        val recent=library.history().filterNot { library.watched(it.series.id) }
        val updates=library.favorites().filter { (library.favoriteUpdate(it.id)?.added ?: 0)>0 }
        val later=library.later(); val hidden=library.hidden(); val hot=catalog.filterNot { it.id in hidden }
        return listOf(
            HomeScreen.Shelf("resume","接着看",recent.take(8).map { p -> HomeScreen.Entry(p.series,
                "第 ${p.episodeIndex+1} 集 · ${if(p.completed) "接着看下一集" else formatTime(p.position)}",true,
                if(p.duration>0) (p.position*100/p.duration).toInt().coerceIn(0,100) else null) },
                if(recent.size>8) { { showCollection("resume") } } else null),
            HomeScreen.Shelf("updates","收藏有更新",updates.take(8).map { HomeScreen.Entry(it,library.favoriteLabel(it.id),true) },
                if(updates.size>8) { { showCollection("updates") } } else null),
            HomeScreen.Shelf("later","稍后看",later.take(10).map { HomeScreen.Entry(it,"稍后看 · 长按管理") },
                if(later.size>10) { { showCollection("later") } } else null),
            HomeScreen.Shelf("hot","热门发现 · ${library.contentType.label}",hot.take(10).map { HomeScreen.Entry(it,it.badge.ifBlank { "查看剧集" }) },
                if(hot.size>10) { { showCollection("hot") } } else null))
    }
    private fun refreshLibraryCards(changedId: String,removed: Boolean=false) {
        if(screen!="catalog") return
        val home=homeScreen
        if(home!=null) { home.updateShelves(homeShelves(),requestFocus=true); home.refreshSelection(); return }
        if(removed && (localCatalog() || tab==0)) {
            val oldIds=catalogCards.keys.toList(); val index=oldIds.indexOf(changedId).coerceAtLeast(0)
            if(localCatalog()) {
                catalog=localItems(); page=page.coerceIn(1,maxOf(1,(catalog.size+pageSize-1)/pageSize)); hasMore=page*pageSize<catalog.size
            }
            val remaining=if(localCatalog()) catalog.drop((page-1)*pageSize).take(pageSize) else catalog.filterNot { it.id in library.hidden() }
            catalogFocus=remaining.getOrNull(index.coerceAtMost((remaining.size-1).coerceAtLeast(0)))?.id.orEmpty()
            val y=catalogScroll?.scrollY ?: 0; scrollPositions[catalogPageKey]=y
            artwork.cancelPage(); favoriteBadges.clear()
            catalogBody?.let { body -> body.removeAllViews(); catalogGrid(body,false) }
        } else {
            catalog.firstOrNull { it.id==changedId }?.let { selectionPreview?.show(it,selectionStatus(it)) }
            favoriteBadges[changedId]?.text=library.favoriteLabel(changedId)
        }
    }
    private fun quickActions(series: Series,anchor: View) {
        val labels=mutableListOf(if(library.progress(series.id)!=null) "继续观看" else "直接播放",
            if(library.favorite(series.id)) "取消收藏" else "收藏这部剧",
            if(library.queued(series.id)) "移出稍后看" else "加入稍后看","查看剧集详情")
        if(tab==0) labels+="不在热门推荐中显示"
        if(tab==4) labels+="管理观看记录"
        tvTools.choose(series.title,labels,anchor,{ choice ->
            when(choice) {
                0 -> openDetail(series,true)
                1 -> { val added=library.toggle(series); notices.show(if(added) "已收藏" else "已取消收藏"); refreshLibraryCards(series.id,!added && (tab==3 || collection=="updates")); if(added) favoriteMonitor.check() }
                2 -> { val added=library.toggleLater(series); notices.show(if(added) "已加入稍后看" else "已移出稍后看"); refreshLibraryCards(series.id,!added && collection=="later") }
                3 -> openDetail(series)
                4 -> if(tab==4) manageHistory(series,anchor) else { library.hide(series.id); notices.show("已隐藏热门推荐，可在设置中恢复"); refreshLibraryCards(series.id,true) }
            }
        })
    }
    private fun refreshFavoriteLabels() {
        if(screen!="catalog" || !library.isLoaded) return
        favoriteBadges.forEach { (id,label) -> val value=library.favoriteLabel(id); if(label.text.toString()!=value) label.text=value }
        favoriteStatus?.text=favoriteMonitor.status
        favoriteCheck?.text=if(favoriteMonitor.running) "正在检查…" else "检查更新"
        homeUpdates?.text="收藏更新 ${library.updatedFavorites()} 部"
        val home=homeScreen
        if(screen=="catalog" && tab==0 && page==1 && collection==null && home!=null && library.updatedFavorites()!=homeUpdateCount) {
            // A stable resume key can be restored without moving the user's selection.
            if(!tvTools.showing && !updater.showing && currentFocus?.tag?.toString()?.startsWith("resume:")==true) {
                homeUpdateCount=library.updatedFavorites(); home.updateShelves(homeShelves())
            } else {
                home.refresh.text="收藏更新 ${library.updatedFavorites()} 部 · 按确认查看"
                home.refresh.setOnClickListener { showCatalog() }
            }
        }
    }
    private fun settings(parent: LinearLayout) {
        val accountEntry=addButton(parent,"红果账号 · 登录与云端片单") { showAccount() }
        parent.addView(text("按你的习惯播放",25f).apply { typeface=Typeface.create("sans-serif-medium",Typeface.NORMAL); setPadding(0,dp(15),0,dp(7)) })
        parent.addView(text("偏好自动保存，下一部剧继续使用。",13f,muted).apply { setPadding(0,0,0,dp(18)) })
        val groups=row().apply { gravity=Gravity.TOP }
        val playback=column(); val device=column()
        listOf(playback,device).forEachIndexed { i,group ->
            group.setPadding(dp(16),dp(16),dp(16),dp(16)); group.background=rounded(surface)
            groups.addView(group,lp(0,-2).apply { weight=1f; if(i==0) rightMargin=dp(16) })
        }
        parent.addView(groups)
        fun heading(group: LinearLayout,title: String,subtitle: String) {
            group.addView(text(title,18f).apply { setTypeface(null,Typeface.BOLD) })
            group.addView(text(subtitle,12f,muted).apply { setPadding(0,dp(5),0,dp(15)) })
        }
        fun setting(group: LinearLayout,label: String,action: ()->Unit): TextView {
            val view=button(label,onClick=action).apply {
                gravity=Gravity.CENTER_VERTICAL; setPadding(dp(13),dp(11),dp(13),dp(11))
                background=rounded(bg)
                setOnFocusChangeListener { _,focused -> background=rounded(if(focused) TvStyle.raised else bg,if(focused) accent else Color.TRANSPARENT) }
            }
            group.addView(view,lp(-1,-2).apply { bottomMargin=dp(8) })
            return view
        }
        heading(playback,"播放偏好","所有剧集共用，播放中也可调整")
        heading(device,"片单与设备","保管记录，保持应用更新")
        lateinit var qualityButton: TextView
        qualityButton=setting(playback,"默认清晰度  ·  ${library.maxQuality}P") { showQualityPicker(qualityButton,false) }
        lateinit var defaultSpeed: TextView
        defaultSpeed=setting(playback,"默认倍速  ·  ${PlaybackSpeed.label(library.playbackSpeed)}") { showSpeedPicker(defaultSpeed,false) }
        lateinit var frame: TextView
        frame=setting(playback,"画面模式  ·  ${library.frameMode.label}") { showFramePicker(frame,false) }
        lateinit var autoNextButton: TextView
        autoNextButton=setting(playback,"自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}") {
            library.autoNext=!library.autoNext
            autoNextButton.text="自动连播  ·  ${if(library.autoNext) "开启" else "关闭"}"
        }
        lateinit var seasonButton: TextView
        seasonButton=setting(playback,"跨季自动连播  ·  ${if(library.seasonAutoplay) "开启" else "关闭"}") {
            library.seasonAutoplay=!library.seasonAutoplay
            seasonButton.text="跨季自动连播  ·  ${if(library.seasonAutoplay) "开启" else "关闭"}"
            if(library.seasonAutoplay && !library.autoNext) notices.show("还需开启自动连播才会生效",1)
        }
        lateinit var backup: TextView
        backup=setting(device,"手机备份与恢复") {
            runCatching { LibraryBackup.encode(library.snapshot()) }.onSuccess { encoded ->
                tvTools.libraryTransfer(encoded,backup) { data,restoreSettings ->
                    favoriteMonitor.stop(); library.restore(data,restoreSettings)
                    tabState.clear(); tabFocus.clear(); catalogFocus=""; page=1
                    showCatalog(); favoriteMonitor.check()
                    notices.show("记录已合并恢复",1)
                }
            }.onFailure { tvTools.info("无法生成备份","本机记录暂时无法导出，请重试。",backup) }
        }
        lateinit var hidden: TextView
        hidden=setting(device,"恢复隐藏推荐  ·  ${library.hidden().size} 部") {
            library.unhideAll(); hidden.text="恢复隐藏推荐  ·  0 部"; notices.show("热门推荐已恢复")
        }
        lateinit var updates: TextView
        updates=setting(device,"版本与更新  ·  ${BuildConfig.VERSION_NAME}") { updater.show(updates) }
        lateinit var route: TextView
        route=setting(device,"GitHub 更新线路  ·  ${updater.routeLabel}") {
            tvTools.chooseSelected("GitHub 更新线路",updater.routeLabels,updater.routeIndex,route) { index ->
                updater.selectRoute(index)
                route.text="GitHub 更新线路  ·  ${updater.routeLabel}"
            }
        }
        val license=setting(device,"开源许可") {
            RayNeoDialogs.builder(this).setTitle("开源许可").setMessage("本原生版以 GPL-3.0 发布。\n内容协议与加密处理移植自 drpys（22261ad）。\nAndroidX Media3 / OkHttp：Apache-2.0\nKotlin：Apache-2.0\nBouncy Castle：MIT\n完整源码和许可证见 GitHub：N3urda/hongguoTV-updates。").setPositiveButton("关闭",null).show()
        }
        val left=listOf(qualityButton,defaultSpeed,frame,autoNextButton,seasonButton); val right=listOf(backup,hidden,updates,route,license)
        listOf(left,right).forEach { items -> items.forEachIndexed { index,view ->
            view.nextFocusUpId=items.getOrNull(index-1)?.id ?: accountEntry.id
            view.nextFocusDownId=items.getOrNull(index+1)?.id ?: view.id
        } }
        left.forEachIndexed { i,v -> v.nextFocusLeftId=v.id; v.nextFocusRightId=right.getOrNull(i)?.id ?: right.last().id }
        right.forEachIndexed { i,v -> v.nextFocusLeftId=left.getOrNull(i)?.id ?: left.last().id; v.nextFocusRightId=v.id }
        val preloadGroup=column().apply { setPadding(dp(12),dp(12),dp(12),dp(4)) }
        parent.addView(preloadGroup)
        heading(preloadGroup,"预加载","关闭仅停止后台预下载，当前播放与失败重试保留")
        lateinit var enabledButton: TextView
        lateinit var modeButton: TextView
        lateinit var thresholdButton: TextView
        lateinit var batchButton: TextView
        lateinit var intervalButton: TextView
        fun refreshPreloadLabels() {
            batchButton.text="批量预取播放信息（实验） · ${if(preloadConfig.batchModels) "开启" else "关闭"}"
            enabledButton.text="预加载开关  ·  ${if(preloadConfig.enabled) "开启" else "关闭"}"
            val timed=preloadConfig.mode==PreloadMode.TIME
            modeButton.text="预加载模式  ·  ${if(timed) "按时间" else "按集数"}"
            thresholdButton.text="预加载阈值  ·  ${if(timed) "${preloadConfig.minutes} 分钟" else "${preloadConfig.episodes} 集"}"
            intervalButton.text="检测频率  ·  ${if(timed) "每播放 ${preloadConfig.checkMinutes} 分钟" else "每播放 ${preloadConfig.checkEpisodes} 集"}"
        }
        fun savePreload(next: PreloadSettings) { preloadConfig=next.normalized(); preloadPreferences.save(preloadConfig); cancelPrefetch(); refreshPreloadLabels() }
        enabledButton=setting(preloadGroup,"") {
            savePreload(preloadConfig.copy(enabled=!preloadConfig.enabled))
        }
        modeButton=setting(preloadGroup,"") {
            tvTools.chooseSelected("预加载模式",listOf("按时间","按集数"),preloadConfig.mode.ordinal,modeButton) {
                savePreload(preloadConfig.copy(mode=PreloadMode.entries[it]))
            }
        }
        thresholdButton=setting(preloadGroup,"") {
            val timed=preloadConfig.mode==PreloadMode.TIME
            val values=if(timed) PreloadSettings.timeTargets else PreloadSettings.episodeTargets
            val selected=if(timed) preloadConfig.minutes else preloadConfig.episodes
            tvTools.chooseSelected("预加载阈值",values.map { "$it ${if(timed) "分钟" else "集"}" },values.indexOf(selected),thresholdButton) {
                savePreload(if(timed) preloadConfig.copy(minutes=values[it]) else preloadConfig.copy(episodes=values[it]))
            }
        }
        intervalButton=setting(preloadGroup,"") {
            val timed=preloadConfig.mode==PreloadMode.TIME
            val values=if(timed) PreloadSettings.timeIntervals else PreloadSettings.episodeIntervals
            val selected=if(timed) preloadConfig.checkMinutes else preloadConfig.checkEpisodes
            tvTools.chooseSelected("预加载检测频率",values.map { "每播放 $it ${if(timed) "分钟" else "集"}" },values.indexOf(selected),intervalButton) {
                savePreload(if(timed) preloadConfig.copy(checkMinutes=values[it]) else preloadConfig.copy(checkEpisodes=values[it]))
            }
        }
        batchButton=setting(preloadGroup,"") { savePreload(preloadConfig.copy(batchModels=!preloadConfig.batchModels)) }
        refreshPreloadLabels()
        val statsButton=setting(preloadGroup,"本次运行的预加载统计") {
            val stats=preloadStatistics.snapshot()
            tvTools.info("预加载统计","完整缓存接管：${stats.complete} 次\n部分缓存接管：${stats.partial} 次\n未使用预加载：${stats.uncached} 次\n自动队列换集：${stats.natural} 次\n当前待播缓存：${upcoming.count()} 项 / ${upcoming.cachedBytes()/1048576} MB\n\n实际首帧启动：${stats.renderedStarts} 次 / 平均 ${if(stats.renderedStarts>0) "${stats.startupMs/stats.renderedStarts} ms" else "暂无"}\n播放中缓冲：${stats.bufferingCount} 次 / ${stats.bufferingMs} ms\n自然换集首帧间隔：暂不可测（连续渲染缺少逐集首帧回调）\n\n首帧仅计显式启动且未暂停/跳转的请求，续播定位及其他 seek 启动不计入，含取流时间；缓冲排除启动、暂停、播放抑制和跳转，累计已结束的有效区间（抑制前后分段计次）。本次运行有效，不设播放时长成功门槛。",null)
        }
        val preloadButtons=listOf(enabledButton,modeButton,thresholdButton,intervalButton,batchButton,statsButton)
        preloadButtons.forEachIndexed { index,v ->
            v.nextFocusUpId=preloadButtons.getOrNull(index-1)?.id ?: left.last().id
            v.nextFocusDownId=preloadButtons.getOrNull(index+1)?.id ?: v.id
            v.nextFocusLeftId=v.id; v.nextFocusRightId=v.id
        }
        left.last().nextFocusDownId=enabledButton.id; right.last().nextFocusDownId=enabledButton.id
        nav.forEach { it.nextFocusDownId=accountEntry.id }; accountEntry.nextFocusUpId=nav[tab].id; accountEntry.nextFocusDownId=qualityButton.id
        parent.addView(text("红果TV  /  ${BuildConfig.VERSION_NAME}    ·    收藏与进度保存在本机",12f,muted).apply { setPadding(dp(2),dp(18),0,dp(10)) })
        nav[tab].requestFocus()
    }
    private fun runSearch(value: String) {
        val clean=value.trim(); if(clean.isEmpty() || clean.length>80) return
        com.hongguotv.nativeapp.rayneo.SearchKeyboard.close(searchInput)
        (searchInput as? EditText)?.let { (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken,0) }
        library.rememberSearch(clean,ContentType.SHORT); query=clean; tab=1; page=1; catalogFocus=""; showCatalog(true)
    }
    private fun showSearchHistory(anchor: View) {
        val history=library.searches().distinctBy { it.query }
        if(history.isEmpty()) { tvTools.info("搜索历史","搜索过的剧名会保存在这里，最多保留 20 条。",anchor); return }
        tvTools.choose("搜索历史",history.map { it.query },anchor,{ index -> runSearch(history[index].query) },"清空历史") {
            tvTools.confirm("清空搜索历史","清除本机保存的搜索词？",anchor) { library.clearSearches() }
        }
    }
    private fun manageHistory(series: Series,anchor: View?) {
        tvTools.choose(series.title,listOf("继续观看",if(library.watched(series.id)) "恢复未看状态" else "标记整剧已看","删除这条记录"),anchor,{ index ->
            when(index) {
                0 -> openDetail(series,true)
                1 -> { library.setWatched(series.id,!library.watched(series.id)); catalogFocus=series.id; showCatalog() }
                2 -> tvTools.confirm("删除观看记录","删除《${series.title}》的本机观看进度？收藏会保留。",anchor) { library.removeHistory(series.id); showCatalog() }
            }
        })
    }
    private fun openDetail(series: Series,resume: Boolean=false,autoplay: Boolean=true) {
        pendingDetail=series; detailAutoplay=autoplay
        invalidatePage(); releaseCatalogViews(); screen="detail"; directPlayback=resume; detail=null; synopsisExpanded=false
        val body=base(series.title); message(body,"正在加载剧集…")
        val capturedAccount=accountRepository.currentSession()
        val chosenHistory=cloudHistory[series.id].takeIf { accountReturn && accountReturnSection=="history" && capturedAccount!=null && cloudOwner==capturedAccount.user.id }
        playbackAccount=capturedAccount
        detailLocalSelection=!accountReturn
        val selectedProgress=chosenHistory?.let { WatchProgress(it.series,it.episodeId,it.episodeIndex,it.position,it.duration,false,it.readAt) } ?: detailProgress(series.id)
        val needsAccountLookup=!detailLocalSelection && selectedProgress==null && capturedAccount!=null
        work({
            // Account-only entries may have no cached progress yet. A failed lookup must
            // remain retryable instead of being mistaken for confirmed absent history.
            val remote=if(needsAccountLookup) accountRepository.accountVideoProgress(capturedAccount!!)
                .firstOrNull { it.seriesId==series.id } else null
            repository.detail(series.id) to remote
        }, { loaded ->
            val result=loaded.first
            loaded.second?.let { row -> if(accountRepository.currentSession()?.user?.id==capturedAccount?.user?.id) {
                cloudOwner=capturedAccount?.user?.id.orEmpty()
                if((cloudHistory[series.id]?.readAt ?: 0L)<=row.readAt) {
                    cloudHistory[series.id]=AccountHistoryItem(series,row.episodeId,row.episodeIndex,row.position,0,row.readAt)
                }
            } }
            val progress=selectedProgress ?: detailProgress(series.id)
            detail=result.copy(series=result.series.copy(bookType=series.bookType.takeIf { it in 2..5 } ?: result.series.bookType))
            library.observeFavorite(series.id,result.episodes.size,System.currentTimeMillis(),acknowledge=true)
            val target=ResumePlayback.resolve(result.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true,progress?.episodeIndex)
            if(target==null) { episodeIndex=0; group=0; showDetail(false); return@work }
            episodeIndex=target.index; group=episodeIndex/20
            if(resume) playEpisode(target.index,target.position,autoplay=detailAutoplay) else showDetail(false)
        }, { problem -> error(body,problem) { openDetail(series,resume,detailAutoplay) } })
    }
    private fun showDetail(focusEpisode: Boolean) {
        invalidatePage(); releaseCatalogViews(); screen="detail"; val data=detail ?: return showCatalog()
        val body=base("剧集详情")
        val scroll=ScrollView(this).apply { isVerticalScrollBarEnabled=false }; val content=column(); scroll.addView(content); body.addView(scroll,lp(-1,0).apply { weight=1f })
        val hero=row(); hero.gravity=Gravity.TOP; hero.setPadding(0,dp(12),0,dp(12))
        val image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }; hero.addView(image,lp(dp(123),dp(174)).apply { rightMargin=dp(24) }); loadCover(image,data.series.cover)
        val info=column(); hero.addView(info,lp(0,-2).apply { weight=1f })
        info.addView(text(data.series.title,26f).apply { setTypeface(null,Typeface.BOLD); maxLines=2; ellipsize=TextUtils.TruncateAt.END })
        info.addView(text("${data.episodes.size} 集  ·  ${data.series.tags}",13f,muted).apply { maxLines=1; setPadding(0,dp(8),0,dp(8)) })
        val description=data.series.description.ifBlank { "选择剧集开始观看" }
        val synopsis=text(description,15f,muted).apply { maxLines=if(synopsisExpanded) 20 else 2; ellipsize=TextUtils.TruncateAt.END }
        info.addView(synopsis)
        val actions=row(); actions.setPadding(0,dp(12),0,0); info.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(actions) },lp(-1,-2))
        val progress=detailProgress(data.series.id)
        val resumeTarget=ResumePlayback.resolve(data.episodes,progress?.episodeId,progress?.position ?: 0,progress?.completed==true,progress?.episodeIndex)
        val watched=library.watched(data.series.id)
        if(resumeTarget==null) info.addView(text("原观看记录暂时无法匹配分集，已保留记录。请重试或手动选集。",15f,muted))
        val play=addButton(actions,if(resumeTarget==null) "重新加载分集" else if(watched) "重新观看" else if(progress!=null && !progress.completed) "继续第 ${resumeTarget.index+1} 集" else "开始观看") {
            if(resumeTarget==null) openDetail(data.series,resume=true)
            else playEpisode(if(watched) 0 else resumeTarget.index,if(watched) 0 else resumeTarget.position)
        }
        lateinit var favorite: TextView
        favorite=addButton(actions,if(library.favorite(data.series.id)) "已收藏" else "收藏") {
            favoriteMonitor.stop()
            val saved=library.toggle(data.series)
            if(saved) library.observeFavorite(data.series.id,data.episodes.size,System.currentTimeMillis(),acknowledge=true)
            favorite.text=if(saved) "已收藏" else "收藏"
        }
        lateinit var synopsisButton: TextView
        synopsisButton=addButton(actions,if(synopsisExpanded) "收起简介" else "完整简介") {
            synopsisExpanded=!synopsisExpanded
            synopsis.maxLines=if(synopsisExpanded) 20 else 2
            synopsisButton.text=if(synopsisExpanded) "收起简介" else "完整简介"
            synopsisButton.post { synopsisButton.requestRectangleOnScreen(android.graphics.Rect(0,0,synopsisButton.width,synopsisButton.height),false) }
        }
        content.addView(hero)
        val groupRow=row(); groupRow.setPadding(0,dp(3),0,dp(5))
        val maxGroup=data.episodes.lastIndex/20; group=group.coerceIn(0,maxGroup)
        val previous=addButton(groupRow,"‹ 上一组") { if(group>0) { group--; showDetail(true) } }.apply { isEnabled=group>0; isFocusable=group>0; alpha=if(group>0) 1f else .4f }
        groupRow.addView(text("第 ${group*20+1}—${minOf((group+1)*20,data.episodes.size)} 集",16f).apply { gravity=Gravity.CENTER },lp(dp(120),dp(42)))
        addButton(groupRow,"下一组 ›") { if(group<maxGroup) { group++; showDetail(true) } }.apply { isEnabled=group<maxGroup; isFocusable=group<maxGroup; alpha=if(group<maxGroup) 1f else .4f }
        lateinit var jump: TextView
        jump=addButton(groupRow,"跳转集数") { tvTools.episodePicker(data.episodes.size,episodeIndex,jump) { target -> episodeIndex=target; group=target/20; showDetail(true) } }
        content.addView(HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled=false; addView(groupRow) },lp(-1,-2))
        val episodes=mutableListOf<View>()
        (group*20 until minOf((group+1)*20,data.episodes.size)).toList().chunked(5).forEach { numbers ->
            val line=row()
            numbers.forEach { index -> val item=button("${index+1}",index==episodeIndex) { playEpisode(index,if(progress?.episodeId==data.episodes[index] && !progress.completed) progress.position else 0) }; item.contentDescription="第 ${index+1} 集"; line.addView(item,lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }); episodes+=item }
            repeat(5-numbers.size) { line.addView(Space(this),lp(0,dp(42)).apply { weight=1f; rightMargin=dp(6) }) }
            content.addView(line,lp(-1,dp(49)))
        }
        episodes.forEachIndexed { i,v -> v.nextFocusLeftId=if(i%5==0) v.id else episodes[i-1].id; v.nextFocusRightId=if(i%5==4 || i==episodes.lastIndex) v.id else episodes[i+1].id; if(i>=5) v.nextFocusUpId=episodes[i-5].id; v.nextFocusDownId=if(i+5<episodes.size) episodes[i+5].id else v.id }
        lateinit var related: RelatedWorksView
        related=RelatedWorksView(this,episodes,::loadCover,::relatedReservationLabel,::openRelated) { loadRelated(data,related) }
        content.addView(related,lp(-1,-2))
        relatedSection=related
        loadRelated(data,related)
        if(focusEpisode) episodes.getOrNull((episodeIndex-group*20).coerceIn(0,episodes.lastIndex))?.requestFocus() else play.requestFocus()
    }
    private fun loadRelated(data: Detail,section: RelatedWorksView) {
        section.loading()
        val ticket=++relatedLoadTicket
        work({ repository.related(data.series.id) }, { rows ->
            if(ticket==relatedLoadTicket && detail?.series?.id==data.series.id && relatedSection===section)
                section.show(RelatedWorks.ordered(data.series,rows))
        }, { if(ticket==relatedLoadTicket && detail?.series?.id==data.series.id && relatedSection===section) section.failed() })
        if(accountRepository.currentSession()!=null) loadReservations() else relatedReservations.clear()
    }
    private fun loadReservations() {
        val ticket=++relatedReserveTicket
        val session=accountRepository.currentSession() ?: run { relatedReservations.clear(); return }
        io.submit {
            val rows=runCatching { accountRepository.accountReservations().reservations }.getOrDefault(emptyList())
            main.post {
                if(isDestroyed || ticket!=relatedReserveTicket) return@post
                if(accountRepository.currentSession()?.user?.id!=session.user.id) return@post
                relatedReservations.clear(); rows.forEach { relatedReservations[it.series.id]=it }
                relatedSection?.refreshLabels()
            }
        }
    }
    private fun relatedReservationLabel(work: RelatedWork)=
        if(relatedReservations[work.series.id]?.subscribed==true) "已预约 · 取消预约" else "待上线 · 预约"
    private fun openRelated(work: RelatedWork,anchor: View) {
        if(!work.upcoming) { openDetail(work.series); return }
        val session=accountRepository.currentSession() ?: run {
            tvTools.info("预约需要登录","请先在设置中登录红果账号后再预约。",anchor); return
        }
        val current=relatedReservations[work.series.id]
        val active=current?.subscribed!=true
        val itemType=current?.itemType ?: 14
        anchor.isEnabled=false
        io.submit {
            val ok=runCatching { accountRepository.setReservation(work.series.id,active,itemType) }.getOrDefault(false)
            main.post {
                if(isDestroyed) return@post
                if(anchor.isAttachedToWindow) anchor.isEnabled=true
                if(!ok) { notices.show("预约操作失败，请稍后重试",1); return@post }
                if(accountRepository.currentSession()?.user?.id!=session.user.id) return@post
                if(current==null) relatedReservations[work.series.id]=AccountReservation(work.series,itemType=itemType,subscribed=active)
                else relatedReservations[work.series.id]=current.copy(subscribed=active)
                relatedSection?.refreshLabels()
                notices.show(if(active) "已预约：${work.series.title}" else "已取消预约：${work.series.title}",1)
            }
        }
    }
    private fun playEpisode(index: Int,position: Long=0,autoplay: Boolean=true,recovering: Boolean=false) {
        cancelNextSeason(); seasonSuppressed=false; seasonPreparationAttempted=false
        seasonGate.reset();seasonSeekToEnd=false
        updater.interrupt()
        val data=detail ?: return
        val targetId=data.episodes.getOrNull(index)
        if(!recovering && position==0L && targetId!=null && !playError && playbackQueue?.nextMediaId==targetId) {
            saveProgress(); requestedAutoplay=autoplay; pausedForLifecycle=!autoplay; sleepStopped=false
            syncBoundaryPause()
            if(playbackQueue?.seekNext(targetId)==true) {
                if(autoplay) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                player?.playWhenReady=foreground && autoplay; return
            }
        }
        if(recovering && index==episodeIndex && video?.reusable==true && player!=null) {
            cancelRecovery(); bufferingWatchdog.reset()
            player?.stop(); video?.cancelReads()
            playError=false; playbackReady=false; readySince=0
            startPosition=position; requestedAutoplay=autoplay; pausedForLifecycle=!autoplay
            controls.removeAllViews(); progressBar.visibility=View.VISIBLE; hidePlaybackOverlay()
            player?.seekTo(position); player?.prepare(); player?.playWhenReady=foreground && autoplay
            return
        }
        val playbackStarted=android.os.SystemClock.elapsedRealtime()
        val reuse=screen=="player" && player!=null && playerView!=null && !recovering && !playError
        library.setWatched(data.series.id,false)
        if(screen!="player" || recovering) { preloadClock.reset(); preloadCycle=false }
        val selectedIndex=index.coerceIn(0,data.episodes.lastIndex)
        val prefetchedModel=if(!recovering) takeBatchModel(data.episodes[selectedIndex],recoveryQuality?.takeIf { it.first==data.episodes[selectedIndex] }?.second ?: library.maxQuality) else null
        val prefetched=if(preloadConfig.enabled && !recovering) upcoming.claim("${data.episodes[selectedIndex]}:${recoveryQuality?.takeIf { it.first==data.episodes[selectedIndex] }?.second ?: library.maxQuality}") else null
        bufferingWatchdog.reset(); preloadGate.update(false,0,0); upcoming.yieldToForeground()
        if(preloadConfig.enabled) upcoming.update(data.episodes.drop(selectedIndex+1).map { "$it:${library.maxQuality}" }) else cancelPrefetch()
        saveProgress(); library.flush(); invalidatePage(); favoriteMonitor.stop(); releaseCatalogViews()
        if(reuse) {
            cancelRecovery(); tvTools.close()
            episodePanel?.dismiss(); episodePanel=null; speedDialog?.dismiss(); speedDialog=null
            playerListener?.let { player?.removeListener(it) }; playerListener=null
            player?.stop(); playbackQueue?.clear() ?: player?.clearMediaItems()
            if(playbackQueue==null) video?.close(); video=null; closeTransport(playbackHttp); playbackHttp=null
            main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        } else releasePlayer(keepUpcoming=true)
        artwork.cancelPage(clearMemory=true)
        playbackHttp=null; video=null
        playbackMeasurement?.close(android.os.SystemClock.elapsedRealtime())
        val measurement=com.hongguotv.core.PlaybackSessionMeasurement(preloadStatistics,playbackStarted)
        playbackMeasurement=measurement
        var firstReady=true; var firstFrame=true
        completedEpisodes.clear(); rejectedQueueItems.clear(); currentEpisodeDuration=0
        if(!recovering) { recovery.reset(); episodeRequestBudget=PlaybackRequestBudget() }
        autoRecovery=false; readySince=0; startPosition=position.coerceAtLeast(0); requestedAutoplay=autoplay
        if(autoplay) sleepStopped=false
        screen="player"; episodeIndex=selectedIndex; group=episodeIndex/20
        panel=false; playError=false; playbackReady=false; quality=""; pausedForLifecycle=!autoplay
        player?.playWhenReady=requestedAutoplay
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if(!reuse) {
            root.removeAllViews(); root.setBackgroundColor(Color.TRANSPARENT)
            stereoVideo=StereoVideoView(this).also { it.setResizeMode(frameResizeMode()); scene.addView(it,0,FrameLayout.LayoutParams(-1,-1)) }
            val view=(layoutInflater.inflate(R.layout.rayneo_player,root,false) as PlayerView).apply {
                useController=false; resizeMode=frameResizeMode(); isFocusable=false
                setKeepContentOnPlayerReset(true); setShutterBackgroundColor(Color.TRANSPARENT); setShowBuffering(PlayerView.SHOW_BUFFERING_ALWAYS)
            }
            playerView=view; root.addView(view,FrameLayout.LayoutParams(-1,-1))
            hud=column().apply { setPadding(dp(widthDp()*.05f),dp(20),dp(widthDp()*.05f),(resources.displayMetrics.heightPixels*.05f).toInt()); background=GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,intArrayOf(Color.TRANSPARENT,Color.argb(240,11,15,21))) }
            playbackTitle=text("",23f).apply { maxLines=1; ellipsize=TextUtils.TruncateAt.END; setTypeface(null,Typeface.BOLD) }; hud.addView(playbackTitle)
            playbackText=text("",15f,muted).apply { setPadding(0,dp(10),0,dp(9)) }; hud.addView(playbackText)
            progressBar=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply { max=1000; progressTintList=android.content.res.ColorStateList.valueOf(accent); progressBackgroundTintList=android.content.res.ColorStateList.valueOf(surface) }; hud.addView(progressBar,lp(-1,dp(4)))
            hud.addView(text("确认 暂停/播放    左右 快退/快进    ↓ 选集    ↑ 更多    返回 收起 / 退出",13f,muted).apply { setPadding(0,dp(12),0,0) })
            controls=column().apply { setPadding(0,dp(12),0,0); visibility=View.GONE }; hud.addView(controls)
            root.addView(ScrollView(this).apply { isVerticalScrollBarEnabled=false; addView(hud) },FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM))
        }
        playbackTitle.text="${data.series.title}  ·  第 ${episodeIndex+1} 集"
        playbackText.text="正在获取播放地址…"; progressBar.progress=0; progressBar.visibility=View.VISIBLE; hud.visibility=View.VISIBLE
        controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        syncMediaSession()
        val requestedId=data.episodes[episodeIndex]; val maxQuality=recoveryQuality?.takeIf { it.first==requestedId }?.second ?: library.maxQuality; val ticket=generation
        val requestBudget=episodeRequestBudget
        var acquiredCacheKind=0
        work({
            val cached=if(prefetched!=null) try { prefetched.await(1500) } catch(e: Exception) {
                RequestScope.ensureActive(); null
            } finally { prefetched.close() } else null
            cached?.also { acquiredCacheKind=if(it.isCacheComplete()) 2 else 1; it.promote() } ?: RemoteVideo(repository.http,prefetchedModel ?: repository.stream(requestedId,maxQuality,requestBudget),preloadGate).let {
                try { it.prepare() } catch(e: Exception) { it.close(); throw e }
            }
        }, { remote ->
            playbackPreparation=null
            preloadStatistics.acquired(acquiredCacheKind)
            video=remote; quality=remote.info.quality
            val p=player ?: run {
                val load=DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers=androidx.media3.exoplayer.DefaultRenderersFactory(this).setEnableDecoderFallback(true)
                ExoPlayer.Builder(this,renderers).setLoadControl(load).build().also {
                    player=it; playerView?.player=it; stereoVideo?.bind(it)
                    it.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),true)
                    it.setHandleAudioBecomingNoisy(true)
                }
            }
            p.setPlaybackSpeed(library.playbackSpeed)
            val listener=object: Player.Listener {
                private fun active()=player===p && generation==ticket && screen=="player"
                private fun measure() { measurement.state(android.os.SystemClock.elapsedRealtime(),p.playbackState==Player.STATE_BUFFERING,p.playWhenReady && foreground && !pausedForLifecycle && p.playbackSuppressionReason==Player.PLAYBACK_SUPPRESSION_REASON_NONE) }
                override fun onPlaybackStateChanged(state: Int) {
                    if(!active()) return
                    measure()
                    if(state!=Player.STATE_BUFFERING) bufferingWatchdog.reset()
                    if(state==Player.STATE_READY) {
                        if(!p.currentTracks.isTypeSelected(C.TRACK_TYPE_VIDEO)) {
                            playerFailure(PlaybackException("资源没有可解码的视频轨道",null,PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED))
                            return
                        }
                        playbackReady=true
                        if(p.duration>0) currentEpisodeDuration=p.duration
                        if(readySince==0L) readySince=android.os.SystemClock.elapsedRealtime()
                        if(firstReady) { firstReady=false; readySince=android.os.SystemClock.elapsedRealtime(); android.util.Log.i("HongguoTV","Playback ready episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${readySince-playbackStarted}") }
                        if(hud.visibility==View.VISIBLE) updatePlaybackText()
                    }
                    syncMediaSession()
                    if(state==Player.STATE_ENDED) handlePlaybackEnded(data,p)
                }
                override fun onRenderedFirstFrame() {
                    if(active() && firstFrame) {
                        firstFrame=false
                        if(!p.playWhenReady || !foreground) measurement.interrupted(android.os.SystemClock.elapsedRealtime())
                        measurement.firstFrame(android.os.SystemClock.elapsedRealtime())
                        android.util.Log.i("HongguoTV","Playback first frame episode=${episodeIndex+1} preloaded=${prefetched!=null} reused=$reuse elapsedMs=${android.os.SystemClock.elapsedRealtime()-playbackStarted}")
                    }
                }
                override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) { if(active()) measure() }
                override fun onIsPlayingChanged(playing: Boolean) { if(active()) { measure(); if(hud.visibility==View.VISIBLE) showHud(); syncMediaSession() } }
                override fun onPlaybackParametersChanged(parameters: PlaybackParameters) { if(active()) syncMediaSession() }
                override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo,newPosition: Player.PositionInfo,reason: Int) {
                    if(!active()) return
                    if(reason==Player.DISCONTINUITY_REASON_SEEK || reason==Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) measurement.seek(android.os.SystemClock.elapsedRealtime())
                    if(reason==Player.DISCONTINUITY_REASON_SEEK || reason==Player.DISCONTINUITY_REASON_SEEK_ADJUSTMENT) {
                        seasonGate.invalidate()
                        seasonSeekToEnd=p.duration>0 && newPosition.positionMs>=p.duration-1000
                    }
                    val oldId=oldPosition.mediaItem?.mediaId
                    val newId=newPosition.mediaItem?.mediaId
                    if(oldId!=newId && oldId!=null && newId!=null) {
                        val oldIndex=data.episodes.indexOf(oldId)
                        if(oldIndex>=0) {
                            if(reason==Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                                if(completeEpisode(oldIndex,oldPosition.positionMs,currentEpisodeDuration)) stopForSleep()
                            } else saveProgressAt(oldIndex,oldPosition.positionMs,currentEpisodeDuration,oldId in completedEpisodes)
                        }
                        acceptQueueTransition(newId)
                    }
                    syncMediaSession()
                }
                override fun onMediaItemTransition(item: MediaItem?,reason: Int) {
                    if(active() && item!=null) {
                        if(reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && playbackQueue?.remoteFor(item.mediaId)!=null) {
                            preloadStatistics.naturalTransition()
                        }
                        acceptQueueTransition(item.mediaId)
                    }
                }
                override fun onPlayWhenReadyChanged(playWhenReady: Boolean,reason: Int) {
                    if(active() && !playWhenReady && reason!=Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) cancelNextSeason()
                    if(active() && !playWhenReady) abandonSeasonContinuation()
                    if(active()) { if(!playWhenReady) measurement.interrupted(android.os.SystemClock.elapsedRealtime()); measure() }
                    if(!active() || playWhenReady || reason!=Player.PLAY_WHEN_READY_CHANGE_REASON_END_OF_MEDIA_ITEM) return
                    if(completeEpisode(episodeIndex,p.currentPosition,currentEpisodeDuration)) stopForSleep()
                    else { requestedAutoplay=false; pausedForLifecycle=true; showPanel(); syncMediaSession() }
                }

            }
            playerListener=listener; p.addListener(listener)
            playbackAnalytics?.let(p::removeAnalyticsListener)
            val analytics=object: androidx.media3.exoplayer.analytics.AnalyticsListener {
                private fun eventMediaId(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime): String? {
                    val timeline=eventTime.timeline
                    return if(eventTime.windowIndex in 0 until timeline.windowCount) timeline.getWindow(eventTime.windowIndex,Timeline.Window()).mediaItem.mediaId else null
                }
                override fun onPlayerError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,error: PlaybackException) {
                    if(player!==p || generation!=ticket) return
                    val failed=eventMediaId(eventTime)
                    if(failed!=null && failed!=p.currentMediaItem?.mediaId && (failed==playbackQueue?.nextMediaId || failed in rejectedQueueItems)) {
                        rejectedQueueItems+=failed; playbackQueue?.dropNext()
                        p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && !sleepStopped
                    } else playerFailure(error)
                }
                override fun onLoadError(eventTime: androidx.media3.exoplayer.analytics.AnalyticsListener.EventTime,
                    loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
                    mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData,error: java.io.IOException,wasCanceled: Boolean) {
                    if(wasCanceled || player!==p || generation!=ticket) return
                    val timeline=eventTime.timeline
                    if(eventTime.windowIndex !in 0 until timeline.windowCount) return
                    val id=timeline.getWindow(eventTime.windowIndex,Timeline.Window()).mediaItem.mediaId
                    if(id==playbackQueue?.nextMediaId && id!=p.currentMediaItem?.mediaId) {
                        rejectedQueueItems+=id
                        playbackQueue?.dropNext()
                        android.util.Log.w("HongguoTV","Queued source failed; current episode retained")
                    }
                }
            }
            playbackAnalytics=analytics; p.addAnalyticsListener(analytics)
            val queue=playbackQueue ?: SeamlessPlaybackQueue(p).also { playbackQueue=it }
            queue.installCurrent(requestedId,remote)
            syncBoundaryPause()
            p.seekTo(position.coerceAtLeast(0)); p.prepare(); p.playWhenReady=foreground && requestedAutoplay && !pausedForLifecycle && episodePanel==null
        }, { problem -> playerFailure(problem) })
        playbackPreparation=prefetched
    }
    private fun syncBoundaryPause() {
        player?.pauseAtEndOfMediaItems=!library.autoNext || sleepTimer.episodesLeft==1
    }
    private fun completeEpisode(index: Int,position: Long,duration: Long): Boolean {
        val id=detail?.episodes?.getOrNull(index) ?: return false
        if(!completedEpisodes.add(id)) return false
        preloadClock.episodeCompleted()
        saveProgressAt(index,maxOf(position,duration),duration,true)
        return sleepTimer.episodeEnded()
    }
    private fun acceptQueueTransition(id: String) {
        val data=detail ?: return; val p=player ?: return
        val index=data.episodes.indexOf(id)
        if(index<0 || index==episodeIndex) return
        val remote=playbackQueue?.remoteFor(id) ?: return
        seasonGate.reset();seasonSeekToEnd=false
        preloadStatistics.acquired(2)
        episodeIndex=index; group=index/20; video=remote; quality=remote.info.quality
        upcoming.consumed("$id:${library.maxQuality}",remote)
        playbackQueue?.advanceTo(id)
        recovery.reset(); episodeRequestBudget=PlaybackRequestBudget(); bufferingWatchdog.reset()
        startPosition=p.currentPosition.coerceAtLeast(0); readySince=android.os.SystemClock.elapsedRealtime()
        currentEpisodeDuration=p.duration.coerceAtLeast(0); playbackReady=p.playbackState==Player.STATE_READY
        pendingSeek=null; main.removeCallbacks(seekRunnable); playError=false
        playbackTitle.text="${data.series.title}  ·  第 ${index+1} 集"
        progressBar.progress=0; controls.removeAllViews(); controls.visibility=View.GONE; transportPlay=null; sleepButton=null
        hidePlaybackOverlay(); syncBoundaryPause(); updatePlaybackText(); syncMediaSession()
        if(preloadConfig.enabled) upcoming.update(data.episodes.drop(index+1).map { "$it:${library.maxQuality}" })
        android.util.Log.i("HongguoTV","Seamless transition episode=${index+1} playerRetained=true")
    }
    private fun maybeQueueNext() {
        val p=player ?: return; val data=detail ?: return; val queue=playbackQueue ?: return
        if(!preloadConfig.enabled || !library.autoNext || !foreground || sleepStopped) { queue.dropNext(); return }
        if(!p.isPlaying || playError || !playbackReady || queue.nextMediaId!=null) return
        val id=data.episodes.getOrNull(episodeIndex+1) ?: return
        if(id in rejectedQueueItems) return
        val key="$id:${library.maxQuality}"
        val remote=upcoming.claimCompleted(key) ?: return
        if(!queue.appendNext(id,remote) { upcoming.releasePinned(key,remote) }) {
            upcoming.releasePinned(key,remote); remote.close()
        } else android.util.Log.i("HongguoTV","Queued next episode=${episodeIndex+2} cachedBytes=${remote.cachedSize()}")
    }
    private fun closeTransport(http: okhttp3.OkHttpClient?) {
        if(http==null) return
        // Closing an idle TLS connection can write close_notify; keep it off the UI thread.
        networkCleanup.execute { http.dispatcher.cancelAll(); http.connectionPool.evictAll(); http.dispatcher.executorService.shutdown() }
    }
    private fun cancelPrefetch() { playbackQueue?.dropNext(); preloadGate.update(false,0,0); preloadCycle=false; preloadClock.reset(); upcoming.close(); upcoming.configure(preloadConfig) }
    private fun maybePrefetch() {
        if(!preloadConfig.enabled) { cancelPrefetch(); return }
        val p=player ?: return; val data=detail ?: return
        val now=android.os.SystemClock.elapsedRealtime()
        preloadClock.sample(now,foreground && p.isPlaying,p.playbackParameters.speed)
        if(!library.autoNext || connectivity.isActiveNetworkMetered) { cancelPrefetch(); return }
        val active=foreground && p.isPlaying && !playError && !sleepStopped && episodeIndex<data.episodes.lastIndex && networkAvailable()
        preloadGate.update(active,p.bufferedPosition-p.currentPosition,(p.duration-p.currentPosition).coerceAtLeast(0))
        if(!active || !preloadGate.allowed()) return
        val remaining=(p.duration-p.currentPosition).coerceAtLeast(0)
        if(readySince==0L || now-readySince<1500 || p.bufferedPosition-p.currentPosition<minOf(5000L,remaining) || !networkAvailable()) return
        if(preloadClock.due(preloadConfig)) {
            upcoming.configure(preloadConfig)
            upcoming.update(data.episodes.drop(episodeIndex+1).map { "$it:${library.maxQuality}" })
            preloadClock.checked(); preloadCycle=true
        }
        if(preloadCycle) preloadCycle=upcoming.pump()
    }
    private fun networkAvailable(): Boolean = connectivity.getNetworkCapabilities(connectivity.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    private fun currentPosition(): Long = pendingSeek ?: player?.currentPosition?.takeIf { playbackReady } ?: startPosition
    private fun cancelRecovery() { main.removeCallbacks(retryRunnable); retryPending=false; autoRecovery=false }
    private fun scheduleRecovery() {
        if(!foreground || !playError || !autoRecovery || retryPending) return
        if(!recovery.remaining) { autoRecovery=false; playbackText.text="已自动重试 3 次，仍无法播放。可手动重试或切换清晰度。\n$recoveryReason"; return }
        if(!networkAvailable()) { playbackText.text="网络已断开，联网后自动续播 · 第 ${episodeIndex+1} 集 ${formatTime(retryPosition)}\n$recoveryReason"; return }
        val delay=recovery.delayMillis ?: return
        playbackText.text="${if(retryAutoplay) "准备恢复播放" else "准备恢复暂停位置"} · ${delay/1000f} 秒后重试 ${recovery.attempts+1}/3 · ${formatTime(retryPosition)}\n$recoveryReason"
        retryPending=true; main.postDelayed(retryRunnable,delay)
    }
    private fun abandonSeasonContinuation() {
        val pending=seasonGate.isPending
        seasonGate.invalidate()
        if(pending && screen=="player" && player?.playbackState==Player.STATE_ENDED && !playError && episodePanel==null) {
            requestedAutoplay=false
            showHud();showPanel();syncMediaSession()
        }
    }
    /** STATE_ENDED 的唯一出口：先本集连播，再已准备下一季，最后才回退查找。 */
    private fun handlePlaybackEnded(data: Detail, ended: ExoPlayer) {
        val stop=completeEpisode(episodeIndex,ended.currentPosition,currentEpisodeDuration)
        if(stop) { stopForSleep(); return }
        if(foreground && requestedAutoplay && !pausedForLifecycle && !sleepStopped && library.autoNext &&
            episodeIndex<data.episodes.lastIndex) { playEpisode(episodeIndex+1); return }
        if(finishSeason(data)) return
        if(continueNextSeason(ended)) return
        requestedAutoplay=false; ended.pause(); playbackText.text="本集已结束"; showPanel()
    }

    /**
     * 跨季连播的唯一准入判断：已准备、回退查找、异步回调与提示都必须经过这里。
     * 关闭跨季设置、取消提示、拖动进度或离开播放页后，旧查询只能在回调里失效。
     */
    private fun seasonContinuationAllowed(source: Detail, ended: ExoPlayer?=null): Boolean =
        foreground && screen=="player" && !isDestroyed && (ended==null || player===ended) &&
            requestedAutoplay && !pausedForLifecycle && !sleepStopped && library.autoNext &&
            library.seasonAutoplay && !seasonSuppressed && !seasonSeekToEnd &&
            episodePanel==null && !playError && episodeIndex==source.episodes.lastIndex

    /** 当前集的提示、查询与后续连播一次性作废；新的 playEpisode 开始时才恢复。 */
    private fun suppressSeasonContinuation() {
        seasonSuppressed=true
        cancelNextSeason()
        seasonGate.invalidate()
    }

    private fun continueNextSeason(ended: ExoPlayer): Boolean {
        val source=detail ?: return false
        fun eligible()=seasonContinuationAllowed(source,ended)
        if(!eligible()) return false
        if(seasonGate.isPending) return true
        val token=seasonGate.begin() ?: return false
        val ticket=generation
        fun endedControls() {
            requestedAutoplay=false;ended.pause();playbackText.text="本季已结束";showPanel();syncMediaSession()
        }
        playbackText.text="本季已结束 · 正在查找下一季…";showHud()
        work({ RelatedWorks.verifiedNextSeason(source.series,repository::related,repository::detail) }, { next ->
            if(generation==ticket && seasonGate.valid(token)) {
                if(sleepTimer.poll()) { stopForSleep();return@work }
                val proceed=eligible()
                seasonGate.consume(token)
                if(next!=null && proceed) {
                    saveProgress(completed=true);library.flush();releasePlayer();invalidatePage()
                    detail=next;pendingDetail=next.series;episodeIndex=0;group=0
                    library.observeFavorite(next.series.id,next.episodes.size,System.currentTimeMillis(),acknowledge=true)
                    notices.show("继续播放：${next.series.title}",1)
                    playEpisode(0)
                } else endedControls()
            }
        }, { _ -> if(generation==ticket && seasonGate.consume(token)) endedControls() })
        return true
    }

    private fun cancelNextSeason() {
        seasonGeneration++
        seasonPending=false
        nextSeasonDetail=null
        seasonBanner?.let { (it.parent as? ViewGroup)?.removeView(it) }
        seasonBanner=null
        seasonLookup?.cancel(); seasonLookup=null
    }

    private fun maybePrepareSeason() {
        val current=detail ?: return
        val p=player ?: return
        if(!seasonContinuationAllowed(current,p) || seasonPreparationAttempted) return
        if(p.duration>0 && p.currentPosition>=p.duration/2 && p.isPlaying)
            offerNextSeason(current)
    }

    private fun updateSeasonBanner() {
        seasonBanner?.visibility=if(screen=="player" && !panel && episodePanel==null && !tvTools.showing && !playError && !sleepStopped) View.VISIBLE else View.GONE
    }

    private fun offerNextSeason(current: Detail) {
        if(!seasonContinuationAllowed(current) || seasonPreparationAttempted) return
        seasonPreparationAttempted=true
        seasonPending=true
        val token=++seasonGeneration
        val scope=RequestScope().also { seasonLookup=it }
        io.submit {
            try {
                val next=scope.run { RelatedWorks.verifiedNextSeason(current.series,repository::related,repository::detail) }
                main.post {
                    if(token!=seasonGeneration || isDestroyed || detail?.series?.id!=current.series.id) return@post
                    if(!seasonContinuationAllowed(current)) { seasonPending=false; nextSeasonDetail=null; return@post }
                    nextSeasonDetail=next
                    if(next!=null) {
                        val banner=text("本季结束后播放《${next.series.title}》 · 取消请按 → 操作菜单",13f).apply {
                            setPadding(dp(14),dp(9),dp(14),dp(9))
                            setSingleLine(true)
                            ellipsize=TextUtils.TruncateAt.END
                            background=TvStyle.shape(this@MainActivity,Color.argb(175,12,18,28))
                        }
                        seasonBanner=banner
                        root.addView(banner,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply {
                            setMargins(dp(24),0,dp(24),dp(20))
                        })
                        updateSeasonBanner()
                    }
                }
            } catch(e: Exception) {
                main.post {
                    if(token!=seasonGeneration) return@post
                    seasonPending=false; nextSeasonDetail=null
                }
            }
        }
    }

    /** 已准备并校验下一季时无缝接续；返回 false 表示没有可用准备，让结束流程回退到自动查找。 */
    private fun finishSeason(current: Detail): Boolean {
        if(!seasonContinuationAllowed(current,player)) return false
        val next=nextSeasonDetail ?: return false
        if(next.series.id==current.series.id || next.episodes.isEmpty() ||
            detail?.series?.id!=current.series.id) return false
        seasonBanner?.visibility=View.GONE
        saveProgress(completed=true); library.flush(); releasePlayer(); invalidatePage()
        detail=next; pendingDetail=next.series; episodeIndex=0; group=0
        library.observeFavorite(next.series.id,next.episodes.size,System.currentTimeMillis(),acknowledge=true)
        notices.show("继续播放：${next.series.title}",1)
        playEpisode(0)
        return true
    }

    private fun playerFailure(problem: Throwable) {
        seasonGate.invalidate()
        if(playError) return
        recoveryReason=when {
            generateSequence(problem){it.cause}.any { it is NoCompatibleStreamException } -> "本集未提供兼容的视频编码或清晰度"
            problem is java.net.SocketTimeoutException -> "视频读取或缓冲超时"
            problem is java.net.UnknownHostException -> "内容服务地址解析失败"
            problem.message?.contains("未返回有效数据")==true -> "播放接口暂时返回空内容"
            problem is PlaybackException && problem.errorCode in 3000..4999 -> "视频解码失败"
            else -> "播放请求失败，请检查网络或稍后重试"
        }
        retryPosition=currentPosition().coerceAtLeast(0); retryAutoplay=requestedAutoplay && !sleepStopped
        saveProgress(); cancelRecovery(); preloadGate.update(false,0,0); preloadGate.failed(problem); upcoming.yieldToForeground()
        val lowerQualities=PlaybackFailure.lowerQualities(video?.info)
        if(PlaybackRecoveryPolicy.needsNewAddress(problem)) { video=null } else video?.cancelReads()
        playError=true; player?.pause(); progressBar.visibility=View.GONE; hud.visibility=View.VISIBLE; controls.removeAllViews(); controls.visibility=View.VISIBLE; panel=true
        val decoding=problem is PlaybackException && problem.errorCode in 3000..4999
        val retryable=!PlaybackFailure.terminal(problem) && (if(problem is PlaybackException) problem.errorCode in 2000..2999 || problem.errorCode==PlaybackException.ERROR_CODE_TIMEOUT else problem is java.io.IOException)
        playbackText.text=if(PlaybackFailure.terminal(problem)) recoveryReason else if(decoding) "电视无法解码当前视频，可尝试更低清晰度。" else "播放失败，可重试或切换清晰度。"
        val actions=row(); controls.addView(actions)
        addButton(actions,"立即重试") { playEpisode(episodeIndex,retryPosition,retryAutoplay) }.requestFocus()
        lowerQualities.forEach { target -> addButton(actions,"尝试 ${target}P") {
            detail?.episodes?.getOrNull(episodeIndex)?.let { recoveryQuality=it to target }
            playEpisode(episodeIndex,retryPosition,retryAutoplay)
        } }
        addButton(actions,"返回选集") { returnToDetail(true) }
        if(retryable && !sleepStopped && generateSequence(problem){it.cause}.none { it is PlaybackBudgetExceededException }) {
            addButton(controls,"停止自动重试") { cancelRecovery(); playbackText.text="自动重试已停止，观看位置已保留。" }
            autoRecovery=true; scheduleRecovery()
        }
        android.util.Log.w("HongguoTV","Playback failure: ${problem.javaClass.simpleName}"+(if(problem is PlaybackException) " code=${problem.errorCodeName}" else ""))
        controls.addView(text(com.hongguotv.core.PlaybackDiagnostics.line(problem,android.os.Build.MODEL,
            android.os.Build.VERSION.RELEASE,android.os.Build.VERSION.SDK_INT,BuildConfig.VERSION_NAME,decoding,recovery.attempts),12f,muted))
    }
    private fun hidePlaybackOverlay() {
        main.removeCallbacks(hideHud)
        panel=false; controls.visibility=View.GONE; controls.clearFocus(); hud.visibility=View.GONE
    }
    private fun showHud() {
        if(screen!="player") return
        hud.visibility=View.VISIBLE; updatePlaybackText(); main.removeCallbacks(hideHud)
        if(!panel && player?.isPlaying==true) main.postDelayed(hideHud,4500)
    }
    private fun updatePlaybackText() {
        transportPlay?.text=if(player?.playWhenReady==true) "暂停" else "播放"
        sleepButton?.text="定时 ${sleepTimer.label()}"
        if(screen!="player" || playError) return
        if(sleepStopped) { playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"; return }
        if(seasonGate.isPending) { playbackText.text="本季已结束 · 正在查找下一季…"; return }
        if(!playbackReady) { playbackText.text=(if(!requestedAutoplay) "准备后保持暂停 · " else "")+if(video==null) "正在获取播放地址…" else "正在缓冲…"; return }
        val p=player ?: return
        val duration=p.duration.coerceAtLeast(0); val position=pendingSeek ?: p.currentPosition
        val state=when { p.playbackState==Player.STATE_BUFFERING -> "缓冲中"; p.playbackState==Player.STATE_ENDED -> "本集已结束"; !p.playWhenReady -> "已暂停"; else -> "正在播放" }
        playbackText.text="$state  ·  ${formatTime(position)} / ${formatTime(duration)}  ·  $quality  ·  ${PlaybackSpeed.label(p.playbackParameters.speed)}"+(if(sleepTimer.active) "  ·  定时 ${sleepTimer.label()}" else "")
        progressBar.progress=if(duration>0) (position*1000/duration).toInt().coerceIn(0,1000) else 0
    }
    private fun requestPlayback(play: Boolean) {
        abandonSeasonContinuation()
        requestedAutoplay=play; pausedForLifecycle=!play
        if(play) sleepStopped=false
        if(!play) { player?.pause(); retryAutoplay=false; saveProgress() }
        else if(playError) { playEpisode(episodeIndex,retryPosition,true); return }
        else if(episodePanel==null) {
            syncBoundaryPause()
            if(player?.playbackState==Player.STATE_ENDED || (!library.autoNext && detail?.episodes?.getOrNull(episodeIndex) in completedEpisodes)) {
                detail?.episodes?.getOrNull(episodeIndex)?.let(completedEpisodes::remove)
                player?.seekTo(0)
            }
            player?.play()
        }
        if(!play) window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        showHud(); updatePlaybackText(); syncMediaSession(); if(panel && episodePanel==null) showPanel()
    }
    private fun togglePlayback()=requestPlayback(!(if(playbackReady) player?.playWhenReady ?: requestedAutoplay else requestedAutoplay))
    private fun seekTo(position: Long) {
        val p=player ?: return; if(!playbackReady || p.duration<=0) return
        suppressSeasonContinuation()
        main.removeCallbacks(seekRunnable); pendingSeek=null
        detail?.episodes?.getOrNull(episodeIndex)?.let(completedEpisodes::remove)
        p.seekTo(position.coerceIn(0,(p.duration-500).coerceAtLeast(0))); updatePlaybackText(); syncMediaSession(); showHud()
    }
    private fun skipEpisode(direction: Int) {
        if(!foreground || screen!="player") return
        val count=detail?.episodes?.size ?: return
        val target=episodeIndex+direction
        if(target in 0 until count) playEpisode(target)
    }
    private fun syncMediaSession() {
        if(!foreground || screen!="player") { mediaSession.deactivate(); return }
        val data=detail ?: return; val p=player
        val state=when {
            playError -> android.media.session.PlaybackState.STATE_ERROR
            sleepStopped || !requestedAutoplay || (p!=null && !p.playWhenReady) -> android.media.session.PlaybackState.STATE_PAUSED
            p==null || !playbackReady || p.playbackState==Player.STATE_BUFFERING -> android.media.session.PlaybackState.STATE_BUFFERING
            p.playbackState==Player.STATE_ENDED -> android.media.session.PlaybackState.STATE_STOPPED
            p.isPlaying -> android.media.session.PlaybackState.STATE_PLAYING
            else -> android.media.session.PlaybackState.STATE_PAUSED
        }
        mediaSession.update(data.series.title,episodeIndex,p?.duration?.coerceAtLeast(0) ?: 0,currentPosition(),library.playbackSpeed,state,episodeIndex>0,episodeIndex<data.episodes.lastIndex)
    }
    private fun stopForSleep() {
        cancelNextSeason()
        seasonGate.invalidate()
        sleepStopped=true; requestedAutoplay=false; retryAutoplay=false; pausedForLifecycle=true
        cancelRecovery(); player?.pause(); saveProgress()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        playbackText.text="定时停止已生效 · 观看位置已保存 · 按播放可继续"
        showHud(); if(!playError && episodePanel==null) showPanel(); syncMediaSession()
        notices.show("定时停止已生效",1)
    }
    private fun showSleepPicker(anchor: TextView) {
        val labels=listOf("关闭定时停止","15 分钟后","30 分钟后","60 分钟后","90 分钟后","本集播完","再播完 3 集","再播完 5 集")
        tvTools.choose("定时停止 · ${sleepTimer.label()}",labels,anchor,{ choice ->
            when(choice) { 0 -> sleepTimer.cancel(); in 1..4 -> sleepTimer.afterMinutes(listOf(15,30,60,90)[choice-1]); else -> sleepTimer.afterEpisodes(listOf(1,3,5)[choice-5]) }
            syncBoundaryPause(); if(anchor.tag!="playback-settings") anchor.text="定时 ${sleepTimer.label()}"; updatePlaybackText(); showHud()
        })
    }
    private fun frameResizeMode()=when(library.frameMode) {
        VideoFrameMode.FIT -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FIT
        VideoFrameMode.ZOOM -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        VideoFrameMode.FILL -> androidx.media3.ui.AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
    private fun showFramePicker(anchor: TextView,inPlayer: Boolean) {
        tvTools.choose("全局画面模式",listOf("完整画面 · 保留比例与全部内容","等比铺满 · 会裁掉部分画面与字幕","拉伸铺满 · 画面比例会改变"),anchor,{ index ->
            library.frameMode=VideoFrameMode.entries[index]; playerView?.resizeMode=frameResizeMode(); stereoVideo?.setResizeMode(frameResizeMode())
            if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "画面 " else "画面模式  ·  ")+library.frameMode.label
        })
    }
    private fun seek(direction: Int,repeat: Int) {
        val p=player ?: return; if(!playbackReady) return
        val duration=p.duration; if(duration<=0) return
        suppressSeasonContinuation()
        val step=if(repeat>4) 30000 else 10000
        pendingSeek=((pendingSeek ?: p.currentPosition)+direction*step).coerceIn(0,(duration-500).coerceAtLeast(0))
        main.removeCallbacks(seekRunnable); main.postDelayed(seekRunnable,250); updatePlaybackText(); showHud()
    }
    private fun showPanel() {
        seasonBanner?.visibility=View.GONE
        if(playError) return
        panel=true; showHud(); controls.visibility=View.VISIBLE; controls.removeAllViews()
        if(nextSeasonDetail!=null) addButton(controls,"取消本次跨季连播") {
            suppressSeasonContinuation(); showPanel()
        }
        val transport=row(); controls.addView(transport)
        val play=addButton(transport,if(player?.playWhenReady==true) "暂停" else "播放") { togglePlayback() }.also { transportPlay=it }
        addButton(transport,"上一集") { if(episodeIndex>0) playEpisode(episodeIndex-1) }.apply { isEnabled=episodeIndex>0; isFocusable=episodeIndex>0; alpha=if(episodeIndex>0) 1f else .4f }
        addButton(transport,"下一集") { if(episodeIndex<(detail?.episodes?.lastIndex ?: 0)) playEpisode(episodeIndex+1) }.apply { val enabled=episodeIndex<(detail?.episodes?.lastIndex ?: 0); isEnabled=enabled; isFocusable=enabled; alpha=if(enabled) 1f else .4f }
        val options=row().apply { setPadding(0,dp(8),0,0) }; controls.addView(options)
        lateinit var episodes: TextView
        episodes=addButton(options,"选集") { showEpisodePanel(episodes) }
        lateinit var more: TextView
        more=addButton(options,"播放设置") {
            tvTools.choose("播放设置",listOf("倍速 ${PlaybackSpeed.label(library.playbackSpeed)}","清晰度 ${library.maxQuality}P","画面 ${library.frameMode.label}","定时 ${sleepTimer.label()}","从头播放"),more,{ choice ->
                main.post {
                    if(screen=="player" && more.isAttachedToWindow) when(choice) {
                        0 -> showSpeedPicker(more)
                        1 -> showQualityPicker(more,true)
                        2 -> showFramePicker(more,true)
                        3 -> showSleepPicker(more)
                        4 -> { seekTo(0); hidePanel() }
                    }
                }
            })
        }.apply { tag="playback-settings" }
        play.requestFocus()
    }
    private fun showSpeedPicker(anchor: TextView,inPlayer: Boolean=true) {
        if(speedDialog!=null) return
        val speeds=PlaybackSpeed.options
        val selected=speeds.indexOf(library.playbackSpeed)
        val dialog=RayNeoDialogs.builder(this).setTitle("全局默认倍速")
            .setSingleChoiceItems(speeds.map(PlaybackSpeed::label).toTypedArray(),selected) { popup,index ->
                library.playbackSpeed=speeds[index]
                if(inPlayer) player?.setPlaybackSpeed(library.playbackSpeed)
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "倍速 " else "默认倍速  ·  ")+PlaybackSpeed.label(library.playbackSpeed)
                if(inPlayer) updatePlaybackText()
                popup.dismiss()
            }.setNegativeButton("取消",null).create()
        speedDialog=dialog
        dialog.setOnDismissListener {
            speedDialog=null
            if(anchor.isAttachedToWindow) { anchor.requestFocus(); if(inPlayer) showHud() }
        }
        dialog.show(); dialog.listView.setSelection(selected); dialog.listView.requestFocus()
    }
    private fun showQualityPicker(anchor: TextView,inPlayer: Boolean) {
        val options=PlaybackQuality.options
        val dialog=RayNeoDialogs.builder(this).setTitle("全局默认清晰度")
            .setSingleChoiceItems(options.map { "${it}P" }.toTypedArray(),options.indexOf(library.maxQuality)) { popup,index ->
                val target=options[index]; val changed=library.maxQuality!=target || (inPlayer && recoveryQuality!=null)
                library.maxQuality=target; recoveryQuality=null; popup.dismiss()
                if(anchor.tag!="playback-settings") anchor.text=(if(inPlayer) "清晰度 " else "默认清晰度  ·  ")+"${target}P"
                if(inPlayer && changed) playEpisode(episodeIndex,currentPosition(),player?.playWhenReady ?: requestedAutoplay)
            }.setNegativeButton("取消",null).create()
        dialog.setOnDismissListener { if(anchor.isAttachedToWindow) anchor.requestFocus() }
        dialog.show()
    }
    private fun showEpisodePanel(anchor: View) {
        abandonSeasonContinuation()
        val data=detail ?: return
        if(episodePanel!=null) return
        val ticket=generation
        val overlayWasVisible=hud.visibility==View.VISIBLE
        requestedAutoplay=player?.playWhenReady ?: requestedAutoplay
        player?.pause(); saveProgress()
        var resumeOnClose=true
        val next=EpisodePanel(this,data.episodes.size,episodeIndex,
            { current,view,located -> tvTools.episodePicker(data.episodes.size,current,view,located) },
            { selected ->
                if(selected==episodeIndex) episodePanel?.dismiss()
                else { resumeOnClose=false; episodePanel?.dismiss(); playEpisode(selected) }
            },
            {
                episodePanel=null
                if(resumeOnClose && foreground && generation==ticket && screen=="player") {
                    if(requestedAutoplay && !sleepStopped && !pausedForLifecycle) player?.play()
                    if(anchor.isAttachedToWindow) anchor.requestFocus()
                    if(overlayWasVisible) showHud() else hidePlaybackOverlay()
                }
            })
        episodePanel=next; next.show()
    }
    private fun hidePanel() { if(playError) return; panel=false; controls.visibility=View.GONE; controls.clearFocus(); showHud() }
    private fun formatTime(millis: Long): String { val seconds=(millis.coerceAtLeast(0)/1000); return "%02d:%02d".format(seconds/60,seconds%60) }
    private fun saveProgress(completed: Boolean=false) {
        val p=player ?: return; val data=detail ?: return
        if(!playbackReady || episodeIndex !in data.episodes.indices) return
        saveProgressAt(episodeIndex,p.currentPosition,p.duration,completed || p.playbackState==Player.STATE_ENDED || data.episodes[episodeIndex] in completedEpisodes)
    }
    private fun saveProgressAt(index: Int,position: Long,duration: Long,completed: Boolean) {
        val data=detail ?: return; if(index !in data.episodes.indices) return
        val progress=WatchProgress(data.series,data.episodes[index],index,position.coerceAtLeast(0),duration.coerceAtLeast(0),completed,System.currentTimeMillis())
        val previous=library.progress(data.series.id)
        if(previous!=null && previous.copy(updatedAt=progress.updatedAt)==progress) return
        library.save(progress)
        accountOwners.edit().putString(data.series.id,playbackAccount?.user?.id.orEmpty()).apply()
        accountHistorySync.offer(progress,data.series.bookType,data.episodes.size,playbackAccount)
        playbackAccount?.let { session -> if(accountRepository.currentSession()?.user?.id==session.user.id) {
            cloudOwner=session.user.id
            cloudHistory[data.series.id]=AccountHistoryItem(data.series,progress.episodeId,progress.episodeIndex,progress.position,progress.duration,progress.updatedAt)
        } }

    }
    private fun releasePlayer(keepUpcoming: Boolean=false) {
        cancelNextSeason()
        seasonGate.invalidate()
        playbackMeasurement?.close(android.os.SystemClock.elapsedRealtime()); playbackMeasurement=null
        bufferingWatchdog.reset()
        if(!keepUpcoming) cancelPrefetch()
        cancelRecovery(); mediaSession.deactivate(); tvTools.close()
        val wasForeground=foreground; foreground=false
        val oldPanel=episodePanel; episodePanel=null; oldPanel?.dismiss(); foreground=wasForeground
        speedDialog?.dismiss(); speedDialog=null
        main.removeCallbacks(hideHud); main.removeCallbacks(seekRunnable); pendingSeek=null
        playerListener?.let { player?.removeListener(it) }; playerListener=null
        stereoVideo?.let { it.release(); scene.removeView(it) }; stereoVideo=null
        playbackAnalytics?.let { player?.removeAnalyticsListener(it) }; playbackAnalytics=null
        playerView?.player=null; playerView=null
        player?.release(); player=null
        if(playbackQueue!=null) playbackQueue?.releaseAfterPlayerRelease() else video?.close()
        playbackQueue=null; video=null; playbackReady=false; transportPlay=null; sleepButton=null
        completedEpisodes.clear(); rejectedQueueItems.clear(); currentEpisodeDuration=0
        closeTransport(playbackHttp); playbackHttp=null
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun returnToDetail(forceDetail: Boolean=false) {
        sleepTimer.cancel(); sleepStopped=false; saveProgress(); library.flush(); invalidatePage(); releasePlayer()
        if(directPlayback && !forceDetail) { if(accountReturn) showAccount(accountReturnSection,pendingDetail?.id) else showCatalog() } else { directPlayback=false; showDetail(true) }
    }
    private fun goBack() {
        if(!library.isLoaded) { finish(); return }
        when(screen) {
            "player" -> if(hud.visibility==View.VISIBLE && !playError) { suppressSeasonContinuation(); hidePlaybackOverlay() } else returnToDetail()
            "account" -> accountScreen?.goBack() ?: run { tab=5; showCatalog() }
            "detail" -> if(accountReturn) showAccount(accountReturnSection,pendingDetail?.id) else showCatalog()
            else -> if(collection!=null) { leaveCollection(); showCatalog() } else if(nav.any { it.hasFocus() }) { if(tab!=0) { switchTab(0); nav[0].requestFocus() } else finish() } else nav.getOrNull(tab)?.requestFocus()
        }
    }
    @Deprecated("TV remote back is handled through the activity")
    override fun onBackPressed()=goBack()
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if(screen=="player") {
            val key=event.keyCode
            if(key==KeyEvent.KEYCODE_BACK) { if(event.action==KeyEvent.ACTION_UP) goBack(); return true }
            if(key in listOf(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,KeyEvent.KEYCODE_MEDIA_PLAY,KeyEvent.KEYCODE_MEDIA_PAUSE,KeyEvent.KEYCODE_MEDIA_NEXT,KeyEvent.KEYCODE_MEDIA_PREVIOUS,KeyEvent.KEYCODE_MEDIA_STOP,KeyEvent.KEYCODE_MEDIA_FAST_FORWARD,KeyEvent.KEYCODE_MEDIA_REWIND)) {
                if(event.action==KeyEvent.ACTION_DOWN && event.repeatCount==0) when(key) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> requestPlayback(true)
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> requestPlayback(false)
                    KeyEvent.KEYCODE_MEDIA_NEXT -> skipEpisode(1)
                    KeyEvent.KEYCODE_MEDIA_PREVIOUS -> skipEpisode(-1)
                    KeyEvent.KEYCODE_MEDIA_STOP -> returnToDetail()
                    KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> seek(1,0)
                    KeyEvent.KEYCODE_MEDIA_REWIND -> seek(-1,0)
                    else -> togglePlayback()
                }
                return true
            }
            if(!panel) {
                if(key in listOf(KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN,KeyEvent.KEYCODE_MENU)) {
                    if(event.action==KeyEvent.ACTION_DOWN) when(key) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> seek(-1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_RIGHT -> seek(1,event.repeatCount)
                        KeyEvent.KEYCODE_DPAD_CENTER,KeyEvent.KEYCODE_ENTER -> if(event.repeatCount==0) togglePlayback()
                        KeyEvent.KEYCODE_DPAD_DOWN -> if(event.repeatCount==0) showEpisodePanel(hud)
                        KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_MENU -> if(event.repeatCount==0) showPanel()
                        else -> showHud()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if(!library.isLoaded) return
        when(screen) {
            "player" -> { val position=currentPosition(); val autoplay=player?.playWhenReady==true && !pausedForLifecycle; playEpisode(episodeIndex,position,autoplay) }
            "detail" -> if(detail!=null) showDetail(false)
            "account" -> showAccount()
            else -> showCatalog()
        }
    }
    override fun onResume() {
        super.onResume(); foreground=true; main.removeCallbacks(tick); main.post(tick)
        updater.resume()
        if(library.isLoaded) {
            if(screen=="account") showAccount()
            else if(initialCatalogPending) showCatalog(load=true)
            else if(restoreStoppedPage) {
                restoreStoppedPage=false
                when(screen) {
                    "player" -> playEpisode(episodeIndex,startPosition,autoplay=false)
                    "detail" -> if(detail!=null) showDetail(false) else pendingDetail?.let { openDetail(it,directPlayback,autoplay=false) } ?: showCatalog()
                    "account" -> showAccount()
                    else -> showCatalog(load=!localCatalog() && catalog.isEmpty() && (tab==0 || tab==2 || (tab==1 && query.isNotBlank())))
                }
            } else {
                val waiting=deferredWork; deferredWork=null; waiting?.apply?.invoke()
            }
            if(screen=="catalog") favoriteMonitor.check()
            if(screen=="player") syncMediaSession()
            accountHistorySync.resume(library.history())
        } else libraryLoadError?.let(::showLibraryError)
    }
    override fun onPause() {
        cancelNextSeason()
        abandonSeasonContinuation()
        bufferingWatchdog.reset()
        accountScreen?.close(); accountHistorySync.flush()
        updater.pause(); notices.close()
        foreground=false; main.removeCallbacks(tick); cancelRecovery(); cancelPrefetch(); mediaSession.deactivate()
        sleepTimer.cancel(); favoriteMonitor.stop(); detailAutoplay=false; requestedAutoplay=false; pausedForLifecycle=true
        player?.pause(); saveProgress(); library.flush(); super.onPause()
    }
    override fun onStop() {
        com.hongguotv.nativeapp.rayneo.SearchKeyboard.close(searchInput)
        restoreStoppedPage=true; generation++; cancelWork(); tvTools.close()
        if(screen=="player") { startPosition=currentPosition(); saveProgress(); releasePlayer() }
        artwork.cancelPage(clearMemory=true); library.flush(); super.onStop()
    }
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if(::artwork.isInitialized) artwork.trimMemory(level)
        if(level>=android.content.ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) cancelPrefetch()
    }
    override fun onDestroy() {
        accountScreen?.destroy(); accountScreen=null; accountHistorySync.close()
        generation++; cancelWork()
        if(networkRegistered) connectivity.unregisterNetworkCallback(networkCallback)
        favoriteMonitor.destroy(); tvTools.destroy(); updater.destroy(); saveProgress(); releasePlayer(); library.close()
        artwork.close(); releaseCatalogViews(); mediaSession.release(); main.removeCallbacksAndMessages(null)
        closeTransport(prefetchHttp); io.shutdownNow(); prefetchWorker.shutdownNow(); networkCleanup.shutdown()
        Thread({ repository.http.dispatcher.cancelAll(); repository.http.connectionPool.evictAll(); repository.http.dispatcher.executorService.shutdown() },"hongguotv-network-cleanup").start()
        super.onDestroy()
    }
}
