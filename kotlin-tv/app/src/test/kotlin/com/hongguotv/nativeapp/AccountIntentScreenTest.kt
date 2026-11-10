package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/** View fixtures exercise captured identity and local intent, not live cloud persistence. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
class AccountIntentScreenTest {
    private class Store(var value: String="{}") : AccountIntentStorage {
        override val key="screen-fixture-${UUID.randomUUID()}"
        override fun read()=value
        override fun commit(value: String): Boolean { this.value=value; return true }
    }
    private fun session(account: String="700")=AuthSession(mapOf("sessionid" to "fixture-$account"),
        "token-$account",AuthUser("user-$account","fixture",accountId=account))
    private fun hold(latch: CountDownLatch) {
        while(true) { try { latch.await(); return } catch(_: InterruptedException) { /* cancelled transport may still finish */ } }
    }
    private fun await(latch: CountDownLatch)=assertTrue("fixture latch completed",latch.await(5,TimeUnit.SECONDS))
    private fun drain(owner: Any,field: String) {
        val executor=owner.javaClass.getDeclaredField(field).apply { isAccessible=true }.get(owner) as ExecutorService
        executor.submit {}.get(5,TimeUnit.SECONDS)
    }
    private companion object {
        const val FAVORITES_JSON="""{"code":0,"data":{"video_shelf_info":[{"book_id":"11","book_name":"Hidden favorite"},{"book_id":"12","book_name":"Visible favorite"}]}}"""
        const val HISTORY_JSON="""{"code":0,"data":{"data_list":[{"book_id":"11","book_name":"Hidden history","vid":"91","vid_index":2,"read_timestamp_ms":10},{"book_id":"12","book_name":"Visible history","vid":"92","vid_index":3,"read_timestamp_ms":20}]}}"""
    }
    private inner class Fixture(
        initial: AuthSession?=session(),
        seed: (AccountContext)->String={ "{}" },
        private val respond: (Request)->String={ request -> when {
            request.url.encodedPath.contains("read_history/list") -> HISTORY_JSON
            request.url.encodedPath.contains("read_progress/list") -> """{"code":0,"video_data":[]}"""
            request.url.encodedPath.contains("bookshelf/video/list") -> FAVORITES_JSON
            request.url.encodedPath.contains("info/v1/") -> """{"code":0,"data":{"video_shelf_info":[]}}"""
            else -> """{"code":0}"""
        } }
    ) : AutoCloseable {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val host=LinearLayout(activity)
        val posts=AtomicInteger(); val reads=AtomicInteger()
        val repo=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            if(request.method=="POST") posts.incrementAndGet() else reads.incrementAndGet()
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(respond(request).toResponseBody("application/json".toMediaType())).build()
        }.build())
        val intents: AccountIntents
        var changes=0; var legacyOpens=0
        val opened=mutableListOf<AccountContext>()
        val merged=mutableListOf<Triple<List<String>,AccountContext,Long>>()
        val screen: AccountScreen
        init {
            AuthStore.clear(activity)
            if(initial!=null) AuthStore.save(activity,initial)
            repo.restoreSession(initial)
            val storage=Store(repo.captureAccount()?.let(seed) ?: "{}")
            intents=AccountIntents(activity,repo,storage)
            val ready=CountDownLatch(1); var loaded=false
            intents.loadAsync { loaded=it; ready.countDown() }; await(ready); assertTrue(loaded)
            screen=AccountScreen(activity,repo,{ title,click -> Button(activity).apply {
                text=title; setOnClickListener { click() }
            } },{}, { changes++ },{ _,_,_ -> legacyOpens++ },intents,
                { _,_,_,context -> opened.add(context) },
                { items,context,revision -> merged.add(Triple(items.map { it.id },context,revision)) })
            activity.setContentView(host)
        }
        fun texts(root: View=host): List<TextView> = if(root is ViewGroup)
            (0 until root.childCount).flatMap { texts(root.getChildAt(it)) } else listOfNotNull(root as? TextView)
        fun click(text: String)=texts().first { it.text==text }.performClick()
        fun has(text: String)=texts().any { it.text.toString().contains(text) }
        fun show(section: String?=null) { screen.show(host,section); if(section!=null) settleScreen() }
        fun settleScreen() { drain(screen,"worker"); shadowOf(Looper.getMainLooper()).idle() }
        fun settleIntents() { drain(intents,"http"); shadowOf(Looper.getMainLooper()).idle() }
        fun replace(next: AuthSession) { repo.logout(); repo.restoreSession(next); AuthStore.save(activity,next) }
        override fun close() {
            screen.destroy(); intents.close(); controller.pause().stop().destroy()
        }
    }
    private fun seeded(context: AccountContext,statuses: List<Pair<String,AccountIntents.FavoriteStatus>>,removed: Set<String> = emptySet()): String {
        val entries=JSONObject()
        statuses.forEachIndexed { index,(id,status) -> entries.put(id,JSONObject().put("revision",index+1)
            .put("added",id !in removed).put("status",status.name)) }
        return JSONObject().put("revision",statuses.size).put("owners",JSONObject().put(context.owner!!,
            JSONObject().put("history",JSONObject()).put("favorites",entries))).put("bindings",JSONObject()).toString()
    }
    /** AccountContext is a captured snapshot, not a data class; compare identity fields, never references. */
    private fun sameAccount(expected: AccountContext,actual: AccountContext) {
        assertEquals(expected.epoch,actual.epoch)
        assertEquals(expected.owner,actual.owner)
        assertEquals(expected.legacyOwner,actual.legacyOwner)
        assertEquals(expected.credentialOwner,actual.credentialOwner)
        assertEquals(expected.session.user.id,actual.session.user.id)
        assertEquals(expected.session.token,actual.session.token)
        assertEquals(expected.session.cookies,actual.session.cookies)
    }
    @Test fun staleExpiredListAndProfileFailuresCannotLogoutIdenticalReloginOrAnotherAccount() {
        listOf("云端收藏","云端预约","刷新账号资料").forEach { source -> listOf("700","800").forEach { replacement ->
            val started=CountDownLatch(1); val release=CountDownLatch(1)
            Fixture(respond={
                started.countDown(); hold(release)
                """{"code":1,"message":"登录已过期，请先登录"}"""
            }).use { f ->
                try {
                    f.screen.show(f.host)
                    f.click(source)
                    await(started); val original=f.repo.captureAccount()!!
                    // The expired failure is already queued for main before replacing the session.
                    release.countDown(); drain(f.screen,"worker")
                    val next=session(replacement); f.replace(next)
                    assertFalse(f.repo.isCurrent(original))
                    shadowOf(Looper.getMainLooper()).idle()
                    assertEquals(next,f.repo.currentSession()); assertEquals(0,f.changes)
                    assertFalse(f.has("请重新扫码"))
                    AuthStore.restore(f.activity,f.repo); assertEquals(next,f.repo.currentSession())
                } finally { release.countDown() }
            }
        } }
    }
    @Test fun homeShowsTruthfulStatusesAndOnlyAmbiguousOrFailedActionsHaveExplicitRetry() {
        val statuses=listOf("11" to AccountIntents.FavoriteStatus.PENDING,
            "12" to AccountIntents.FavoriteStatus.OUTCOME_UNKNOWN,
            "13" to AccountIntents.FavoriteStatus.SUBMITTED_UNCONFIRMED,
            "14" to AccountIntents.FavoriteStatus.CONFIRMED,"15" to AccountIntents.FavoriteStatus.FAILED)
        Fixture(seed={ seeded(it,statuses,setOf("13")) }).use { f ->
            f.show()
            assertTrue(f.has("等待提交")); assertTrue(f.has("结果未知")); assertTrue(f.has("已提交取消收藏，云端尚未确认"))
            assertTrue(f.has("云端已确认")); assertTrue(f.has("提交失败"))
            val retries=f.texts().filter { it.text.toString().startsWith("重试") }.map { it.text.toString() }
            assertEquals(3,retries.size)
            assertFalse(retries.any { it.contains("（11）") || it.contains("（14）") })
            assertEquals(0,f.posts.get()); assertEquals(0,f.reads.get())
            f.click("刷新收藏同步状态"); f.settleIntents()
            assertEquals(0,f.posts.get()); assertTrue(f.has("已提交取消收藏，云端尚未确认"))
            f.click("重试取消收藏（13）"); f.settleIntents()
            assertEquals(1,f.posts.get()); assertTrue(f.has("已提交取消收藏，云端尚未确认"))
            assertTrue(f.intents.suppressFavorite(f.repo.captureAccount()!!,"13"))
        }
    }
    @Test fun submittingIsVisibleButNeverOfferedAnAutomaticOrExplicitDuplicatePost() {
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        Fixture(respond={ request ->
            if(request.method=="POST") { started.countDown(); hold(release) }
            if(request.method=="POST") """{"code":0}""" else """{"code":0,"data":{"video_shelf_info":[]}}"""
        }).use { f ->
            try {
                f.show(); f.intents.setFavorite(f.repo.captureAccount()!!,"11",true); await(started)
                shadowOf(Looper.getMainLooper()).idle()
                assertTrue(f.has("正在提交，结果尚未确认"))
                assertFalse(f.texts().any { it.text.toString().startsWith("重试") })
                assertEquals(1,f.posts.get())
            } finally { release.countDown(); f.settleIntents() }
        }
    }
    @Test fun hiddenDeletesAreFilteredAndPassiveFavoriteMergeIsLocalOnlyAndCaptured() {
        Fixture(seed={ seeded(it,listOf("11" to AccountIntents.FavoriteStatus.SUBMITTED_UNCONFIRMED),setOf("11")) }).use { f ->
            val context=f.repo.captureAccount()!!; f.intents.deleteHistory(context,setOf("11"))
            val revision=f.intents.revision(context)
            f.show("history")
            assertFalse(f.has("Hidden history")); assertTrue(f.has("Visible history"))
            f.show("favorites")
            assertFalse(f.has("Hidden favorite")); assertTrue(f.has("Visible favorite"))
            assertEquals(listOf("12"),f.merged.single().first)
            sameAccount(context,f.merged.single().second)
            assertTrue(f.repo.isCurrent(f.merged.single().second))
            assertEquals(revision,f.merged.single().third)
            assertEquals(0,f.posts.get())
            f.click("Visible favorite")
            assertEquals(1,f.opened.size); sameAccount(context,f.opened.single())
            assertTrue(f.repo.isCurrent(f.opened.single())); assertEquals(0,f.legacyOpens)
        }
    }
    @Test fun changedPullRevisionPreventsPassiveMerge() {
        val started=CountDownLatch(1); val release=CountDownLatch(1)
        Fixture(respond={ started.countDown(); hold(release); FAVORITES_JSON }).use { f ->
            try {
                f.screen.show(f.host,"favorites"); await(started)
                val context=f.repo.captureAccount()!!
                // Accepting a deletion never requires the blocked HTTP lane.
                f.intents.deleteHistory(context,setOf("12"))
                release.countDown(); f.settleScreen()
                assertTrue(f.merged.isEmpty()); assertEquals(0,f.posts.get())
                assertFalse(f.has("Visible favorite")); assertTrue(f.has("请刷新列表"))
            } finally { release.countDown() }
        }
    }
    @Test fun blockedHistoryPullCannotResurrectDeletedThenRewatchedPayloadUntilExplicitRefresh() {
        val started=CountDownLatch(1); val release=CountDownLatch(1); val first=AtomicInteger()
        Fixture(respond={ request ->
            val path=request.url.encodedPath
            if(path.contains("read_history/list")) {
                if(first.getAndIncrement()==0) { started.countDown(); hold(release) }
                HISTORY_JSON
            } else if(path.contains("read_progress/list")) """{"code":0,"video_data":[]}"""
            else """{"code":0,"data":{"video_shelf_info":[]}}"""
        }).use { f ->
            try {
                f.screen.show(f.host,"history"); await(started)
                val context=f.repo.captureAccount()!!
                // Delete then rewatch makes suppression false again, but the in-flight pull is still old.
                f.intents.deleteHistory(context,setOf("11"))
                f.intents.rewatch(context,"11")
                assertFalse(f.intents.suppressHistory(context,"11"))
                release.countDown(); f.settleScreen()
                // The old payload must not be rendered, clickable or cached under the new revision.
                assertFalse(f.has("Hidden history")); assertFalse(f.has("Visible history"))
                assertTrue(f.has("请刷新列表"))
                f.intents.deleteHistory(context,setOf("12")); shadowOf(Looper.getMainLooper()).idle()
                assertFalse(f.has("Hidden history")); assertFalse(f.has("Visible history"))
                assertTrue(f.has("请刷新列表")); assertTrue(f.opened.isEmpty())
                // Only an explicit fresh pull under the new generation may expose the rows again.
                f.click("刷新列表"); f.settleScreen()
                assertTrue(f.has("Hidden history")); assertFalse(f.has("Visible history"))
            } finally { release.countDown() }
        }
    }
    @Test fun retainedRowsRejectNewIntentGenerationAndIdenticalRelogin() {
        Fixture().use { f ->
            f.show("history")
            val old=f.texts().first { it.text.toString().startsWith("Visible history") }
            val context=f.repo.captureAccount()!!
            f.intents.deleteHistory(context,setOf("12")); old.performClick()
            assertTrue(f.opened.isEmpty())
            shadowOf(Looper.getMainLooper()).idle(); assertFalse(f.has("Visible history"))
            f.show("favorites")
            val favorite=f.texts().first { it.text=="Visible favorite" }
            f.replace(session()); favorite.performClick()
            assertTrue(f.opened.isEmpty()); assertEquals(0,f.legacyOpens)
        }
    }
    @Test fun queuedStatusCallbackAndRetryButtonCannotActOnReplacementSession() {
        Fixture(seed={ seeded(it,listOf("11" to AccountIntents.FavoriteStatus.OUTCOME_UNKNOWN)) }).use { f ->
            f.show()
            val retry=f.texts().first { it.text=="重试收藏（11）" }
            val context=f.repo.captureAccount()!!
            // Queue an update on the old page; replacement happens before main-thread delivery.
            f.intents.deleteHistory(context,setOf("12")); f.replace(session("800"))
            shadowOf(Looper.getMainLooper()).idle()
            retry.performClick(); f.settleIntents()
            assertEquals(0,f.posts.get())
            assertTrue(f.intents.favoriteEntries(f.repo.captureAccount()!!).isEmpty())
        }
    }
    @Test fun staleReservationRowAndControlCannotMutateOrOpenAfterAccountChanges() {
        val reservations="""{"code":0,"data":{"subscribe_items":[{"item_id":"11","name":"Upcoming fixture","is_online":false,"has_subscribed":true}]}}"""
        Fixture(respond={ reservations }).use { f ->
            f.show("reservations")
            val stale=f.texts().first { it.text.toString().startsWith("Upcoming fixture") }
            f.replace(session()); stale.performClick()
            assertFalse(f.has("取消预约")); assertTrue(f.opened.isEmpty())
            f.show("reservations"); f.click("Upcoming fixture · 待上线")
            val control=f.texts().first { it.text=="取消预约" }
            f.replace(session("800")); control.performClick(); f.settleScreen()
            assertEquals(0,f.posts.get()); assertTrue(f.opened.isEmpty())
        }
    }
    @Test fun closingDetachesPageButLeavesActivityCoordinatorReadyAndGuestCannotReadOrMerge() {
        Fixture().use { f ->
            f.show(); f.screen.close()
            assertTrue(f.intents.isReady())
            f.intents.deleteHistory(f.repo.captureAccount()!!,setOf("11"))
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(f.intents.isReady())
            assertNull(AccountScreen::class.java.getDeclaredField("intentListener").apply { isAccessible=true }.get(f.screen))
        }
        Fixture(initial=null).use { f ->
            f.show("favorites"); assertTrue(f.has("请先登录"))
            assertEquals(0,f.reads.get()); assertEquals(0,f.posts.get()); assertTrue(f.merged.isEmpty())
        }
    }
}
