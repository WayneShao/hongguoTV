package com.hongguotv.nativeapp

import android.os.Looper
import android.widget.FrameLayout
import com.hongguotv.core.*
import okhttp3.*
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

/** Focused real-Activity helper/checkpoint wiring, without onCreate, playback or live requests. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
class AccountActivityIntentTest {
    private fun field(name: String)=MainActivity::class.java.getDeclaredField(name).apply { isAccessible=true }
    private fun call(activity: MainActivity,name: String,vararg arguments: Any?): Any? {
        val method=MainActivity::class.java.declaredMethods.single { it.name==name && it.parameterCount==arguments.size }
        return method.apply { isAccessible=true }.invoke(activity,*arguments)
    }
    private fun session(account: String="700")=AuthSession(mapOf("sessionid" to "fixture"),"token",AuthUser("7","fixture",accountId=account))
    private fun drain(owner: Any,name: String) {
        val executor=owner.javaClass.getDeclaredField(name).apply { isAccessible=true }.get(owner) as ExecutorService
        executor.submit {}.get(5,TimeUnit.SECONDS)
    }
    private class Store : AccountIntentStorage {
        override val key="activity-fixture-${UUID.randomUUID()}"
        @Volatile var value="{}"
        override fun read()=value
        override fun commit(value: String): Boolean { this.value=value; return true }
    }
    private inner class Fixture(ready: Boolean=true,account: String="700") : AutoCloseable {
        val activity=Robolectric.buildActivity(MainActivity::class.java).get()
        val posts=AtomicInteger()
        val repo=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            if(request.method=="POST") posts.incrementAndGet()
            val body=when {
                request.url.encodedPath.contains("read_history/list") -> """{"code":0,"data":{"data_list":[]}}"""
                request.url.encodedPath.contains("read_progress/list") -> """{"code":0,"video_data":[]}"""
                request.url.encodedPath.endsWith("info/v1/") -> """{"code":0,"data":{"video_shelf_info":[]}}"""
                else -> """{"code":0}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody()).build()
        }.build())
        val store=Store()
        val library=Library(activity)
        val intents: AccountIntents
        val sync: AccountHistorySync
        val series=Series("11","Fixture",bookType=2)
        init {
            activity.getSharedPreferences("account-history-outbox-v1",0).edit().clear().commit()
            activity.getSharedPreferences("account-local-progress-owners",0).edit().clear().commit()
            Library::class.java.getDeclaredField("index").apply { isAccessible=true }.set(library,
                LibraryIndex(BackupData(emptyList(),emptyList(),emptySet(),emptyList(),BackupSettings())))
            field("library").set(activity,library)
            field("accountRepository\$delegate").set(activity,lazyOf(repo))
            repo.restoreSession(session(account))
            intents=AccountIntents(activity,repo,store)
            field("accountIntents\$delegate").set(activity,lazyOf(intents))
            sync=AccountHistorySync(activity,repo,intents)
            field("accountHistorySync\$delegate").set(activity,lazyOf(sync))
            // Eye builds use an in-host notice; TV builds use Toast.
            MainActivity::class.java.declaredFields.firstOrNull { it.name=="notices" }?.apply {
                isAccessible=true
                val constructor=type.getConstructor(FrameLayout::class.java)
                set(activity,constructor.newInstance(FrameLayout(activity)))
            }
            if(ready) {
                load(intents)
                field("accountReady").setBoolean(activity,true)
            }
        }
        fun context()=repo.captureAccount()!!
        fun select(context: AccountContext=context(),section: String="history",revision: Long=intents.historyRevision(context,"11")) {
            field("playbackAccount").set(activity,context)
            field("playbackRevision").setLong(activity,revision)
            field("detailLocalSelection").setBoolean(activity,false)
            field("accountReturnSection").set(activity,section)
        }
        fun save(position: Long=100)=call(activity,"saveProgressAt",0,position,1000L,false)
        fun detail() { field("detail").set(activity,Detail(series,listOf("91","92"))) }
        override fun close() {
            sync.close(); intents.close(); library.close()
            for(name in listOf("io","prefetchWorker","networkCleanup")) (field(name).get(activity) as ExecutorService).shutdownNow()
            shadowOf(Looper.getMainLooper()).idle()
        }
    }
    private fun load(intents: AccountIntents) {
        val done=CountDownLatch(1); var ready=false
        intents.loadAsync { ready=it; done.countDown() }
        assertTrue(done.await(5,TimeUnit.SECONDS)); assertTrue(ready)
    }

    @Test fun signedInActionsBeforeReadinessRemainUnacceptedAndLocalStateSurvivesRetirement() {
        Fixture(ready=false).use { f ->
            f.library.toggle(f.series)
            f.library.save(WatchProgress(f.series,"91",0,100,1000,false,1))
            assertNull(call(f.activity,"toggleFavorite",f.series))
            call(f.activity,"deleteLocalHistory",setOf("11"),false)
            assertTrue(f.library.favorite("11")); assertNotNull(f.library.progress("11"))
            assertEquals(0,f.posts.get()); assertEquals("{}",f.store.value)
            f.intents.close()
            val replacement=AccountIntents(f.activity,f.repo,f.store)
            try { load(replacement); assertFalse(replacement.suppressHistory(f.context(),"11")) }
            finally { replacement.close() }
        }
    }
    @Test fun guestLocalActionsDoNotWaitForAccountReadinessOrCreateCloudWork() {
        Fixture(ready=false).use { f ->
            f.repo.logout()
            assertEquals(true,call(f.activity,"toggleFavorite",f.series))
            f.library.save(WatchProgress(f.series,"91",0,100,1000,false,1))
            call(f.activity,"deleteLocalHistory",setOf("11"),false)
            assertTrue(f.library.favorite("11")); assertNull(f.library.progress("11"))
            assertEquals("{}",f.store.value); assertEquals(0,f.posts.get())
        }
    }
    @Test fun readyDeletionSuppressesImmediatelyAndRemovesOnlyTheCapturedOutboxPartition() {
        Fixture().use { f ->
            val context=f.context(); val progress=WatchProgress(f.series,"91",0,100,1000,false,1)
            f.library.save(progress)
            val prefs=f.activity.getSharedPreferences("account-history-outbox-v1",0)
            val row=JSONObject().put("11",HistoryCheckpoint(progress,2,2).stored().put("actionRevision",0L)).toString()
            prefs.edit().putString(context.legacyOwner,row).putString("unrelated-owner",row).commit()
            call(f.activity,"deleteLocalHistory",setOf("11"),false)
            assertTrue(f.intents.suppressHistory(context,"11")); assertNull(f.library.progress("11"))
            drain(f.sync,"worker")
            assertFalse(JSONObject(prefs.getString(context.legacyOwner,"{}")!!).has("11"))
            assertTrue(JSONObject(prefs.getString("unrelated-owner","{}")!!).has("11"))
            assertEquals(0,f.posts.get())
        }
    }
    @Test fun perSeriesPlaybackRevisionIgnoresUnrelatedFavoriteButRejectsDeletionAndRelogin() {
        Fixture().use { f ->
            val context=f.context(); f.select(context)
            assertEquals(true,call(f.activity,"playbackHistoryCurrent","11"))
            f.intents.setFavorite(context,"99",false)
            assertEquals(true,call(f.activity,"playbackHistoryCurrent","11"))
            f.intents.deleteHistory(context,setOf("11"))
            assertEquals(false,call(f.activity,"playbackHistoryCurrent","11"))
            assertEquals(true,call(f.activity,"staleHistorySelectionNow","11"))
            f.intents.rewatch(context,"11")
            assertEquals(false,call(f.activity,"playbackHistoryCurrent","11"))
            f.repo.logout(); f.repo.restoreSession(session())
            assertEquals(false,call(f.activity,"playbackContextCurrent"))
        }
    }
    @Test fun suppressedFavoriteCanStartManuallyButOnlyANewCheckpointGrantsRewatch() {
        Fixture().use { f ->
            val context=f.context(); f.intents.deleteHistory(context,setOf("11"))
            f.select(context,"favorites"); f.detail()
            assertEquals(true,call(f.activity,"playbackContextCurrent"))
            assertEquals(false,call(f.activity,"staleHistorySelectionNow","11"))
            assertEquals(false,call(f.activity,"playbackHistoryCurrent","11"))
            assertTrue(f.intents.suppressHistory(context,"11"))
            f.save()
            assertFalse(f.intents.suppressHistory(context,"11"))
            assertEquals(true,call(f.activity,"playbackHistoryCurrent","11"))
            assertEquals(100L,f.library.progress("11")!!.position)
        }
    }
    @Test fun retiredFinalCheckpointIsLocalOnlyAndCannotRevokeDeletion() {
        Fixture().use { f ->
            val context=f.context(); f.select(context); f.detail()
            f.intents.deleteHistory(context,setOf("11")); val revision=f.intents.revision(context)
            field("accountRetired").setBoolean(f.activity,true)
            f.save(200)
            assertEquals(200L,f.library.progress("11")!!.position)
            assertEquals(revision,f.intents.revision(context)); assertTrue(f.intents.suppressHistory(context,"11"))
            drain(f.sync,"worker"); assertEquals(0,f.posts.get())
        }
    }
    @Test fun originalUnresolvedEpochCanUseNewCanonicalProgressWithoutAdoptingAnotherLogin() {
        Fixture(account="").use { f ->
            val original=f.context(); assertNull(original.owner)
            f.select(original,"favorites")
            f.repo.restoreSession(session("700")); assertTrue(f.repo.isCurrent(original))
            val resolved=f.context(); val revision=f.intents.rewatch(resolved,"11")
            field("playbackRevision").setLong(f.activity,revision)
            f.library.save(WatchProgress(f.series,"91",0,300,1000,false,1))
            f.activity.getSharedPreferences("account-local-progress-owners",0).edit().putString("11",resolved.owner).commit()
            assertEquals(300L,(call(f.activity,"accountProgress","11") as WatchProgress).position)
            f.repo.logout(); f.repo.restoreSession(session("700"))
            assertNull(call(f.activity,"accountProgress","11"))
        }
    }
    @Test fun passiveMergeIsLocalFirstAndNeverSubmitsFavorites() {
        Fixture().use { f ->
            val context=f.context(); f.library.toggle(f.series)
            call(f.activity,"mergeAccountFavorites",listOf(f.series.copy(title="Cloud title"),Series("12","New")),context,f.intents.revision(context))
            assertEquals(listOf("11","12"),f.library.favorites().map { it.id })
            assertEquals("Fixture",f.library.favorites().first().title)
            drain(f.intents,"http"); assertEquals(0,f.posts.get())
        }
    }
    @Test fun manualMergeEligibilityIncludesUnassignedGuestsButRejectsForeignAndDeletedHistory() {
        Fixture().use { f ->
            val context=f.context()
            val owners=f.activity.getSharedPreferences("account-local-progress-owners",0)
            assertEquals(true,call(f.activity,"mergeProgressEligible",context,"11"))
            owners.edit().putString("11","other-owner").commit()
            assertEquals(false,call(f.activity,"mergeProgressEligible",context,"11"))
            owners.edit().putString("11",context.owner).commit()
            assertEquals(true,call(f.activity,"mergeProgressEligible",context,"11"))
            f.intents.deleteHistory(context,setOf("11"))
            assertEquals(false,call(f.activity,"mergeProgressEligible",context,"11"))
            f.repo.logout(); f.repo.restoreSession(session())
            assertEquals(false,call(f.activity,"mergeProgressEligible",context,"12"))
            assertEquals(0,f.posts.get())
        }
    }
    @Test fun mergedWinnerClaimsCurrentOwnerAndReplacesStaleCloudResumeWithoutChangingPlayerSelection() {
        Fixture().use { f ->
            val context=f.context(); f.select(context); f.detail()
            val winner=WatchProgress(f.series,"92",1,300,1000,false,1)
            assertEquals(true,call(f.activity,"applyMergedProgress",context,winner))
            call(f.activity,"claimMergedProgress",context,"11")
            assertEquals(context.owner,f.activity.getSharedPreferences("account-local-progress-owners",0).getString("11",null))
            assertEquals(winner,call(f.activity,"accountProgress","11"))
            assertEquals(context,field("playbackAccount").get(f.activity))
            assertEquals(0,f.posts.get())
        }
    }
}
