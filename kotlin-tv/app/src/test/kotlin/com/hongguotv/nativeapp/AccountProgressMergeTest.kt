// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
@LooperMode(LooperMode.Mode.PAUSED)
class AccountProgressMergeTest {
    private class Store: AccountIntentStorage {
        override val key="merge-${UUID.randomUUID()}"
        var value="{}"
        override fun read()=value
        override fun commit(value: String): Boolean { this.value=value; return true }
    }
    private class Network(val blocked: Boolean=false) {
        val entered=CountDownLatch(1); val release=CountDownLatch(1)
        val once=AtomicBoolean(); val writes=AtomicInteger()
        var history="""{"code":0,"data":{"data_list":[]}}"""
        var progress="""{"code":0,"video_data":[]}"""
        var search="""{"code":0,"search_tabs":[{"tab_type":11,"data":[]}]}"""
        val searches=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val path=chain.request().url.encodedPath
            if(blocked && path==HISTORY && once.compareAndSet(false,true)) {
                entered.countDown(); check(release.await(5,TimeUnit.SECONDS))
            }
            val body=when(path) {
                HISTORY -> history
                PROGRESS -> progress
                "/detail" -> {
                    val id=chain.request().url.queryParameter("series_id")!!
                    """<script>_ROUTER_DATA={"loaderData":{"detail_page":{"seriesDetail":{"series_id":"$id","series_name":"fixture","vid_list":["999","456","111"]}}}};</script>"""
                }
                "/reading/bookapi/search/tab/v" -> { searches.incrementAndGet(); search }
                else -> { if(chain.request().method=="POST") writes.incrementAndGet(); """{"code":0}""" }
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture").body(body.toResponseBody()).build()
        }.build()
        fun cloud(id: String="123",episode: String="456",position: Long=3_000,time: Long=10) {
            history="""{"code":0,"data":{"data_list":[{"book_id":"$id","title":"fixture","vid":"$episode","vid_index":1,"current_play_position":$position,"read_timestamp_ms":$time}]}}"""
        }
    }
    private class Fixture(val network: Network=Network(),val realDetail: Boolean=false): AutoCloseable {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(network.client)
        val intents: AccountIntents
        val context: AccountContext
        val library=Library(activity)
        val owners=mutableMapOf<String,String>()
        val sync: AccountHistorySync
        val merge: AccountProgressMerge
        var result: AccountProgressMerge.Result?=null
        var resolutions=0
        init {
            listOf("native-library-v1","account-history-outbox-v1","account-history-owners-v1").forEach {
                activity.getSharedPreferences(it,Context.MODE_PRIVATE).edit().clear().commit()
            }
            repo.restoreSession(session()); context=repo.captureAccount()!!
            intents=AccountIntents(activity,repo,Store())
            val ready=CountDownLatch(1); intents.loadAsync { ready.countDown() }; check(ready.await(5,TimeUnit.SECONDS))
            library.loadAsync { }
            until { library.isLoaded }
            sync=AccountHistorySync(activity,repo,intents)
            fun manualEligible(ctx: AccountContext,id: String)=owners[id].let { it==null || it==ctx.owner }
            merge=AccountProgressMerge(repo,intents,sync,snapshot={ library.history() },
                eligible={ ctx,id -> manualEligible(ctx,id) },
                apply={ _,p -> library.mergeProgress(p) },claim={ ctx,id -> owners[id]=ctx.owner!! },
                resolve={ id -> resolutions++; if(realDetail) repo.detail(id) else Detail(Series(id,"fixture",bookType=2),listOf("999","456","111")) },
                automaticEligible={ ctx,id -> manualEligible(ctx,id) && (library.progress(id)==null || owners[id]!=null) })
        }
        fun start(): Boolean { result=null; return merge.start(context) { result=it } }
        fun startAuto(): Boolean { result=null; return merge.startAutomatically(context) { result=it } }
        fun finish() { until { result!=null } }
        fun rows()=JSONObject(activity.getSharedPreferences("account-history-outbox-v1",Context.MODE_PRIVATE).getString(context.legacyOwner,"{}")!!)
        override fun close() { network.release.countDown(); merge.close(); sync.close(); intents.close(); library.close(); activity.finish() }
    }
    companion object {
        private const val HISTORY="/reading/bookapi/read_history/list/v"
        private const val PROGRESS="/reading/bookapi/read_progress/list/v"
        private fun session()=AuthSession(mapOf("sessionid" to "fixture"),"fixture-token",AuthUser("7","fixture",accountId="700"))
        private fun row(id: String="123",episode: String="999",position: Long=1_000,time: Long=900)=
            BackupProgress(Series(id,"fixture",bookType=2),episode,0,position,60_000,false,time)
        private fun until(done: ()->Boolean) {
            val deadline=System.currentTimeMillis()+15_000
            while(!done() && System.currentTimeMillis()<deadline) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            assertTrue("asynchronous operation completed",done())
        }
    }
    @Test fun cloudWinnerImportsDespiteOlderTimeAndRetiresNewerOrdinaryOutbox() {
        Fixture().use { f ->
            f.network.cloud(); f.library.save(row())
            val token=f.intents.historyRevision(f.context,"123")
            f.sync.offer(row(),2,3,f.context,token)
            assertTrue(f.start()); f.finish()
            assertEquals("456",f.library.progress("123")!!.episodeId)
            assertEquals(10L,f.library.progress("123")!!.updatedAt)
            assertEquals(1,f.result!!.localUpdated)
            assertEquals(0,f.rows().length()); assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun realDetailWithUnknownTypeUsesCloudHistoryTypeForImport() {
        Fixture(realDetail=true).use { f ->
            f.network.cloud()
            assertEquals(0,f.repo.detail("123").series.bookType)
            assertTrue(f.start()); f.finish()
            assertEquals("456",f.library.progress("123")!!.episodeId)
            assertEquals(2,f.library.progress("123")!!.series.bookType)
            assertEquals(1,f.result!!.localUpdated); assertEquals(0,f.result!!.skipped)
            assertEquals(0,f.network.searches.get()); assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun realDetailWithUnknownTypeUsesLocalTypeForUpload() {
        Fixture(realDetail=true).use { f ->
            f.library.save(row().let { it.copy(series=it.series.copy(bookType=3)) })
            assertTrue(f.start()); f.finish()
            assertEquals(1,f.result!!.pending); assertEquals(0,f.result!!.skipped)
            assertEquals(3,HistoryCheckpoint.restore(f.rows().getJSONObject("123")).bookType)
            assertEquals(0,f.network.searches.get())
        }
    }
    @Test fun progressOnlyRealDetailResolvesTypeThroughExactIdSearch() {
        Fixture(realDetail=true).use { f ->
            f.network.progress="""{"code":0,"video_data":[{"series_id":"123","vid":"111","current_play_time":5000,"last_watch_time":20}]}"""
            f.network.search="""{"code":0,"search_tabs":[{"tab_type":11,"data":[{"video_data":[{"series_id":"124","title":"fixture","book_type":2}]},{"video_data":[{"series_id":"123","title":"fixture","book_type":4}]}]}]}"""
            assertTrue(f.start()); f.finish()
            assertEquals("111",f.library.progress("123")!!.episodeId)
            assertEquals(4,f.library.progress("123")!!.series.bookType)
            assertEquals(1,f.result!!.localUpdated); assertEquals(1,f.network.searches.get())
            assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun unavailableTypeSkipsOnlyThatSeriesAndDoesNotFailTheWholeMerge() {
        for(failedSearch in listOf(false,true)) Fixture(realDetail=true).use { f ->
            f.network.cloud(id="124")
            f.network.progress="""{"code":0,"video_data":[{"series_id":"123","vid":"111","current_play_time":5000,"last_watch_time":20}]}"""
            if(failedSearch) f.network.search="""{"code":9}"""
            assertTrue(f.start()); f.finish()
            assertNull(f.result!!.error); assertEquals(1,f.result!!.localUpdated); assertEquals(1,f.result!!.skipped)
            assertEquals("456",f.library.progress("124")!!.episodeId); assertNull(f.library.progress("123"))
            assertFalse(f.owners.containsKey("123")); assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun explicitGuestAdoptionUploadsButForeignOwnedAndSuppressedRowsDoNot() {
        Fixture().use { f ->
            f.library.save(row("123")); f.library.save(row("124")); f.library.save(row("125"))
            f.owners["124"]="other-account"
            f.intents.deleteHistory(f.context,setOf("125"))
            assertTrue(f.start()); f.finish()
            assertEquals(1,f.result!!.pending)
            assertTrue(f.rows().has("123")); assertFalse(f.rows().has("124")); assertFalse(f.rows().has("125"))
            assertEquals(f.context.owner,f.owners["123"])
            assertEquals("other-account",f.owners["124"])
            assertTrue(f.intents.suppressHistory(f.context,"125"))
        }
    }
    @Test fun automaticStartSkipsUnownedGuestRowsButManualStillIncludesThem() {
        Fixture().use { f ->
            f.library.save(row("123"))
            assertTrue(f.startAuto()); f.finish()
            assertEquals(0,f.result!!.localUpdated); assertEquals(1,f.result!!.skipped)
            assertEquals(0,f.rows().length()); assertTrue(f.owners.isEmpty())
            assertTrue(f.start()); f.finish()
            assertEquals(1,f.result!!.pending)
            assertTrue(f.rows().has("123"))
            assertEquals(f.context.owner,f.owners["123"])
        }
    }
    @Test fun automaticStartImportsCloudOnlyAndOwnedLocalWhileSkippingForeignAndDeleted() {
        Fixture().use { f ->
            f.network.cloud(id="124")
            f.library.save(row("125")); f.owners["125"]=f.context.owner!!
            f.library.save(row("126")); f.owners["126"]="other-account"
            f.library.save(row("127")); f.intents.deleteHistory(f.context,setOf("127"))
            assertTrue(f.startAuto()); f.finish()
            assertEquals("456",f.library.progress("124")!!.episodeId)
            assertEquals(1,f.result!!.localUpdated); assertEquals(1,f.result!!.pending); assertEquals(2,f.result!!.skipped)
            assertTrue(f.rows().has("125")); assertFalse(f.rows().has("126")); assertFalse(f.rows().has("127"))
            assertEquals("other-account",f.owners["126"])
            assertTrue(f.intents.suppressHistory(f.context,"127"))
        }
    }
    @Test fun progressOnlyRemoteResolvesCatalogAndImportsWithoutInferringCompletion() {
        Fixture().use { f ->
            f.network.progress="""{"code":0,"video_data":[{"series_id":"123","vid":"111","vid_index":1,"current_play_time":60000,"last_watch_time":20}]}"""
            assertTrue(f.start()); f.finish()
            val p=f.library.progress("123")!!
            assertEquals("111",p.episodeId); assertEquals(2,p.episodeIndex)
            assertEquals(60_000L,p.position); assertFalse(p.completed)
            assertEquals(emptySet<String>(),f.library.completed("123")); assertEquals(1,f.resolutions)
        }
    }
    @Test fun requiredReadFailureDoesNotImportClaimOrQueueAnything() {
        Fixture().use { f ->
            f.network.cloud(); f.network.progress="""{"code":9}"""; f.library.save(row("124"))
            assertTrue(f.start()); f.finish()
            assertNotNull(f.result!!.error); assertNull(f.library.progress("123"))
            assertTrue(f.owners.isEmpty()); assertEquals(0,f.rows().length()); assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun bothCloudCandidatesAreComparedTogetherInsteadOfPickingTheNewerTimestamp() {
        Fixture().use { f ->
            f.network.cloud(episode="456",time=900)
            f.network.progress="""{"code":0,"video_data":[{"series_id":"123","vid":"111","vid_index":1,"current_play_time":5000,"last_watch_time":1}]}"""
            assertTrue(f.start()); f.finish()
            assertEquals("111",f.library.progress("123")!!.episodeId)
            assertEquals(1L,f.library.progress("123")!!.updatedAt)
            assertEquals(1,f.result!!.localUpdated); assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun blockedPullRereadsLocalAndRejectsOverlappingClick() {
        Fixture(Network(true)).use { f ->
            f.network.cloud(); f.library.save(row())
            assertTrue(f.start()); assertTrue(f.network.entered.await(5,TimeUnit.SECONDS)); assertFalse(f.start())
            f.library.save(row(episode="111",position=5_000,time=1_000))
            f.network.release.countDown(); f.finish()
            assertEquals("111",f.library.progress("123")!!.episodeId)
            assertEquals(1,f.result!!.pending)
            assertEquals("111",HistoryCheckpoint.restore(f.rows().getJSONObject("123")).progress.episodeId)
        }
    }
    @Test fun deletionOrIdenticalReloginDuringBlockedReadCannotImportOrUpload() {
        for(relogin in listOf(false,true)) Fixture(Network(true)).use { f ->
            f.network.cloud(); assertTrue(f.start()); assertTrue(f.network.entered.await(5,TimeUnit.SECONDS))
            if(relogin) { f.repo.logout(); f.repo.restoreSession(session()) } else f.intents.deleteHistory(f.context,setOf("123"))
            f.network.release.countDown(); f.finish()
            assertNull(f.library.progress("123")); assertTrue(f.owners.isEmpty()); assertEquals(0,f.rows().length())
            assertEquals(0,f.network.writes.get())
        }
    }
    @Test fun closedControllerDiscardsBlockedImportWithoutClosingAcceptedOutbox() {
        Fixture(Network(true)).use { f ->
            f.network.cloud(); assertTrue(f.start()); assertTrue(f.network.entered.await(5,TimeUnit.SECONDS))
            f.merge.close(); f.network.release.countDown()
            val executor=AccountHistorySync::class.java.getDeclaredField("worker").also { it.isAccessible=true }.get(f.sync) as java.util.concurrent.ScheduledExecutorService
            executor.submit {}.get(5,TimeUnit.SECONDS); shadowOf(Looper.getMainLooper()).idle()
            assertNull(f.library.progress("123")); assertEquals(0,f.rows().length()); assertNull(f.result)
        }
    }
}
