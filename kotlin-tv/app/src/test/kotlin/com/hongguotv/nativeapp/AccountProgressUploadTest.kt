// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.os.Looper
import com.hongguotv.core.*
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.lang.reflect.Proxy
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Task 2 fixtures: durable manual upload policy.
 *
 * A single outbox row per series carries ordinary playback or a manual farther-progress policy.
 * Manual rows preserve the original viewing timestamp, submit with a separate timestamp no older
 * than either observed remote store, acknowledge each wire endpoint independently and are
 * retired/superseded without claiming an upload. All HTTP is intercepted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[21])
@LooperMode(LooperMode.Mode.PAUSED)
class AccountProgressUploadTest {
    private class Store : AccountIntentStorage {
        override val key="progress-upload-${UUID.randomUUID()}"
        @Volatile private var value="{}"
        override fun read()=value
        override fun commit(value: String): Boolean { this.value=value; return true }
    }
    private data class Call(val path: String,val body: String)
    private class Network(private val blockPath: String?=null) {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val calls=CopyOnWriteArrayList<Call>()
        private val blocked=AtomicBoolean()
        private val failures=ConcurrentHashMap<String,AtomicInteger>()
        @Volatile var history="""{"code":0,"data":{"data_list":[]}}"""
        @Volatile var progress="""{"code":0,"video_data":[]}"""
        fun fail(path: String,times: Int=1) { failures[path]=AtomicInteger(times) }
        private fun failed(path: String): Boolean {
            val counter=failures[path] ?: return false
            while(true) {
                val value=counter.get()
                if(value<=0) return false
                if(counter.compareAndSet(value,value-1)) return true
            }
        }
        fun history(episode: String,index: Int,position: Long,time: Long,id: String="123") {
            history="""{"code":0,"data":{"data_list":[{"book_id":"$id","title":"fixture","vid":"$episode","vid_index":${index+1},"current_play_position":$position,"read_timestamp_ms":$time}]}}"""
        }
        fun progress(episode: String,index: Int,position: Long,time: Long,id: String="123") {
            progress="""{"code":0,"video_data":[{"series_id":"$id","vid":"$episode","vid_index":${index+1},"current_play_time":$position,"last_watch_time":$time}]}"""
        }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val path=request.url.encodedPath
            calls.add(Call(path,body))
            if(path==blockPath && blocked.compareAndSet(false,true)) {
                entered.countDown()
                check(release.await(5,TimeUnit.SECONDS)) { "fixture request was not released" }
            }
            val response=if(failed(path)) """{"code":9,"message":"fixture"}""" else when(path) {
                HISTORY_READ -> history
                PROGRESS_READ -> progress
                else -> """{"code":0}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody()).build()
        }.build()
        fun count(path: String)=calls.count { it.path==path }
        fun writes()=calls.filter { it.path==HISTORY_WRITE || it.path==PROGRESS_WRITE }
        fun awaitBlocked() { assertTrue("blocked at $blockPath",entered.await(5,TimeUnit.SECONDS)) }
    }
    /**
     * Delegating preferences whose commit can be failed for a staged outbox payload.
     *
     * A failed commit never reaches the backing store, so the last durable row is the one the test
     * can observe. A predicate (rather than a one-shot toggle) keeps every later persist that would
     * carry the same acknowledgement failing, including the outer drain's own persist.
     */
    private class CommitGate(delegate: SharedPreferences) {
        @Volatile var fail: (String)->Boolean = { false }
        private val target=delegate
        val prefs: SharedPreferences=Proxy.newProxyInstance(
            SharedPreferences::class.java.classLoader,arrayOf(SharedPreferences::class.java)
        ) { _,method,args ->
            if(method.name=="edit") editor() else method.invoke(target,*(args ?: emptyArray<Any?>()))
        } as SharedPreferences
        private fun editor(): SharedPreferences.Editor {
            val base=target.edit()
            var staged: String?=null
            lateinit var proxy: SharedPreferences.Editor
            proxy=Proxy.newProxyInstance(SharedPreferences.Editor::class.java.classLoader,
                arrayOf(SharedPreferences.Editor::class.java)) { _,method,args ->
                val actual=args ?: emptyArray<Any?>()
                when(method.name) {
                    "putString" -> { staged=actual[1] as? String; method.invoke(base,*actual); proxy }
                    "commit" -> if(staged?.let(fail)==true) false else method.invoke(base,*actual)
                    "apply" -> if(staged?.let(fail)!=true) method.invoke(base,*actual) else null
                    "putStringSet","putInt","putLong","putFloat","putBoolean","remove","clear" -> {
                        method.invoke(base,*actual); proxy
                    }
                    else -> method.invoke(base,*actual)
                }
            } as SharedPreferences.Editor
            return proxy
        }
    }
    /** The sync reads `context.applicationContext`; both wrappers only redirect the outbox store. */
    private class SyncContext(base: Context,private val application: Context) : ContextWrapper(base) {
        override fun getApplicationContext(): Context = application
    }
    private class PrefsContext(base: Context,private val outbox: SharedPreferences) : ContextWrapper(base) {
        override fun getSharedPreferences(name: String?,mode: Int): SharedPreferences =
            if(name=="account-history-outbox-v1") outbox else super.getSharedPreferences(name,mode)
    }
    private class Fixture(val network: Network=Network(),account: String="700") : AutoCloseable {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(network.client)
        val store=Store()
        val intents: AccountIntents
        val context: AccountContext
        var sync: AccountHistorySync
        val prefs=activity.getSharedPreferences("account-history-outbox-v1",Context.MODE_PRIVATE)
        val claims=activity.getSharedPreferences("account-history-owners-v1",Context.MODE_PRIVATE)
        val gate=CommitGate(prefs)
        private val syncContext=SyncContext(activity,PrefsContext(activity.applicationContext,gate.prefs))
        val callbacks=CopyOnWriteArrayList<Boolean>()
        var callbacksOnMain=true
        init {
            prefs.edit().clear().commit(); claims.edit().clear().commit()
            repo.restoreSession(session(account=account))
            context=repo.captureAccount()!!
            intents=AccountIntents(activity,repo,store)
            val loaded=CountDownLatch(1)
            intents.loadAsync { loaded.countDown() }
            assertTrue(loaded.await(5,TimeUnit.SECONDS)); assertTrue(intents.isReady())
            sync=newSync()
        }
        fun newSync()=AccountHistorySync(syncContext,repo,intents)
        fun rows(key: String=context.legacyOwner)=JSONObject(prefs.getString(key,"{}")!!)
        fun seed(progress: BackupProgress=row(),revision: Long?=null,key: String=context.legacyOwner) {
            val checkpoint=HistoryCheckpoint(progress,2,CATALOG.size).stored()
            if(revision!=null) checkpoint.put("actionRevision",revision)
            prefs.edit().putString(key,JSONObject().put(progress.series.id,checkpoint).toString()).commit()
        }
        fun seedAll(rows: List<BackupProgress>) {
            val all=JSONObject()
            rows.forEach { progress ->
                all.put(progress.series.id,HistoryCheckpoint(progress,2,CATALOG.size).stored().put("actionRevision",0L))
            }
            prefs.edit().putString(context.legacyOwner,all.toString()).commit()
        }
        /** Explicit offer; a null revision means an explicit playback action arrives now. */
        fun offerNormal(progress: BackupProgress,revision: Long?=null) {
            sync.offer(progress,2,CATALOG.size,context,revision ?: intents.rewatch(context,progress.series.id))
        }
        fun offerMerge(progress: BackupProgress,episodes: List<String> = CATALOG,revision: Long?=null): Boolean {
            var result: Boolean?=null
            sync.offerMerge(HistoryCheckpoint(progress,2,episodes.size),episodes,context,
                revision ?: intents.rewatch(context,progress.series.id)) {
                if(Looper.myLooper()!=Looper.getMainLooper()) callbacksOnMain=false
                result=it
            }
            until { result!=null }
            return result!!
        }
        fun retireMerge(progress: BackupProgress,episodes: List<String> = CATALOG,revision: Long?=null): Boolean {
            var result: Boolean?=null
            sync.retireMerge(HistoryCheckpoint(progress,2,episodes.size),episodes,context,
                revision ?: intents.historyRevision(context,progress.series.id)) {
                if(Looper.myLooper()!=Looper.getMainLooper()) callbacksOnMain=false
                result=it
            }
            until { result!=null }
            return result!!
        }
        fun delete(vararg ids: String) {
            intents.deleteHistory(context,ids.toSet())
            sync.cancelHistory(context,ids.toSet())
        }
        fun restart() {
            val old=sync; old.close()
            assertTrue(executor(old).awaitTermination(5,TimeUnit.SECONDS))
            sync=newSync()
        }
        fun idle() {
            repeat(2) { executor(sync).submit {}.get(5,TimeUnit.SECONDS) }
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() {
            network.release.countDown()
            sync.close(); intents.close()
            assertTrue(executor(sync).awaitTermination(5,TimeUnit.SECONDS))
        }
    }
    companion object {
        private const val HISTORY_READ="/reading/bookapi/read_history/list/v"
        private const val PROGRESS_READ="/reading/bookapi/read_progress/list/v"
        private const val HISTORY_WRITE="/reading/bookapi/read_history/update/v"
        private const val PROGRESS_WRITE="/reading/bookapi/read_progress/upload/v"
        /** Actual catalog order: 999 is episode 1, 456 is episode 2, 111 is episode 3. */
        private val CATALOG=listOf("999","456","111")
        private fun session(id: String="7",account: String="700",credential: String="fixture")=
            AuthSession(mapOf("sessionid" to credential),"token-$credential",AuthUser(id,"fixture",accountId=account))
        private fun row(id: String="123",episode: String="111",index: Int=2,position: Long=5000L,
                        completed: Boolean=false,time: Long=100L)=
            BackupProgress(Series(id,"fixture",bookType=2),episode,index,position,60000L,completed,time)
        private fun executor(sync: AccountHistorySync)=AccountHistorySync::class.java.getDeclaredField("worker").let {
            it.isAccessible=true; it.get(sync) as ScheduledExecutorService
        }
        private fun until(done: ()->Boolean) {
            val deadline=System.currentTimeMillis()+15_000
            while(!done() && System.currentTimeMillis()<deadline) {
                shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5)
            }
            assertTrue("asynchronous callback completed",done())
        }
        private fun historyRow(call: Call)=JSONObject(call.body).getJSONArray("update_datas").getJSONObject(0)
        private fun progressRow(call: Call)=JSONObject(call.body).getJSONArray("books").getJSONObject(0)
        private fun historyIds(call: Call)=JSONObject(call.body).getJSONArray("update_datas").let { array ->
            (0 until array.length()).map { array.getJSONObject(it).getString("book_id") }
        }
        /** True when the staged outbox payload has a manual row with the requested ack flag. */
        private fun acked(staged: String,history: Boolean): Boolean=runCatching {
            val root=JSONObject(staged)
            root.keys().asSequence().any { id ->
                val manual=root.optJSONObject(id)?.optJSONObject("manual")
                manual!=null && (if(history) manual.optBoolean("historyAcked") else manual.optBoolean("progressAcked"))
            }
        }.getOrDefault(false)
        private val API_PATHS=setOf(HISTORY_READ,PROGRESS_READ,HISTORY_WRITE,PROGRESS_WRITE)
    }

    @Test fun localFartherWithOlderTimestampUploadsBothEndpointsUsingFreshSubmissionTime() {
        Fixture().use { f ->
            f.network.history("456",1,4000L,9000L)
            f.network.progress("456",1,4000L,9000L)
            assertTrue(f.offerMerge(row())) // episode 3, position 5000, original time 100
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals(1,f.network.count(PROGRESS_WRITE))
            val history=historyRow(f.network.calls.single { it.path==HISTORY_WRITE })
            val progress=progressRow(f.network.calls.single { it.path==PROGRESS_WRITE })
            assertTrue("wire timestamp is fresh",history.getLong("read_timestamp_ms")>=9000L)
            assertTrue("wire timestamp is fresh",progress.getLong("read_timestamp_ms")>=9000L)
            assertEquals(5000L,progress.getLong("current_play_time"))
            assertEquals(0,f.rows().length())
        }
    }

    @Test fun equalBothEndpointsSkipAndAnyFartherRemoteSupersedesWithoutWrites() {
        Fixture().use { f ->
            f.network.history("456",1,5000L,9000L)
            f.network.progress("456",1,5000L,9000L)
            assertTrue(f.offerMerge(row(episode="456",index=1,position=5000L)))
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.writes().size)
            assertEquals(0,f.rows().length())
        }
        Fixture().use { f ->
            // The progress store is behind, yet the history store alone is farther.
            f.network.history("111",2,6000L,9000L)
            f.network.progress("456",1,1000L,10L)
            assertTrue(f.offerMerge(row(episode="456",index=1,position=1000L)))
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.writes().size)
            assertEquals(0,f.rows().length())
        }
    }

    @Test fun equalEndpointSkipsWhileMissingEndpointSends() {
        Fixture().use { f ->
            f.network.history("456",1,5000L,9000L)
            assertTrue(f.offerMerge(row(episode="456",index=1,position=5000L)))
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.count(HISTORY_WRITE))
            assertEquals(1,f.network.count(PROGRESS_WRITE))
            assertEquals(0,f.rows().length())
        }
    }

    @Test fun partialAcknowledgementRestartSkipsAcknowledgedEndpointAndRefreshesSubmittedTime() {
        Fixture().use { f ->
            f.network.history("456",1,4000L,9000L)
            f.network.progress("456",1,4000L,9000L)
            f.network.fail(PROGRESS_WRITE)
            assertTrue(f.offerMerge(row()))
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals(1,f.network.count(PROGRESS_WRITE))
            val stored=f.rows().getJSONObject("123")
            val manual=stored.getJSONObject("manual")
            assertEquals("original watch time is preserved",100L,HistoryCheckpoint.restore(stored).progress.updatedAt)
            assertEquals(CATALOG.size,manual.getJSONArray("catalog").length())
            assertTrue(manual.getBoolean("historyAcked"))
            assertFalse(manual.getBoolean("progressAcked"))
            val submitted=manual.getLong("submittedAt")
            assertTrue(submitted>=9000L)
            assertTrue(historyRow(f.network.calls.last { it.path==HISTORY_WRITE }).getLong("read_timestamp_ms")>=submitted)
            // A new, still-behind remote timestamp must be reflected before the remaining write.
            val refreshed=System.currentTimeMillis()+120_000L
            f.network.history("456",1,4000L,refreshed)
            f.restart()
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals(2,f.network.count(PROGRESS_WRITE))
            val retried=progressRow(f.network.calls.last { it.path==PROGRESS_WRITE })
            assertTrue("submission time refreshed for still-needed endpoint",retried.getLong("read_timestamp_ms")>=refreshed)
            assertEquals(0,f.rows().length())
        }
    }

    @Test fun deletionOrSameCredentialReloginBetweenEndpointsStopsSecondWrite() {
        for(relogin in listOf(false,true)) Fixture(Network(HISTORY_WRITE)).use { f ->
            f.network.history("456",1,4000L,9000L)
            f.network.progress("456",1,4000L,9000L)
            assertTrue(f.offerMerge(row()))
            f.sync.flush(); f.network.awaitBlocked()
            if(relogin) { f.repo.logout(); f.repo.restoreSession(session()) } else f.delete("123")
            f.network.release.countDown(); f.idle()
            assertEquals(0,f.network.count(PROGRESS_WRITE))
            if(relogin) assertTrue("invalidated work is retained, not reported as uploaded",f.rows().has("123"))
            else assertFalse(f.rows().has("123"))
        }
    }

    @Test fun sameRevisionOrdinaryBehindCannotReplaceManualWhileFreshExplicitRevisionCan() {
        Fixture().use { f ->
            val revision=f.intents.rewatch(f.context,"123")
            assertTrue(f.offerMerge(row(),revision=revision))
            f.offerNormal(row(episode="456",index=1,position=9000L,time=9999L),revision)
            f.idle()
            val manual=HistoryCheckpoint.restore(f.rows().getJSONObject("123"))
            assertEquals("111",manual.progress.episodeId)
            assertEquals(100L,manual.progress.updatedAt)
            assertTrue(f.rows().getJSONObject("123").has("manual"))
            f.sync.flush(); f.idle()
            assertEquals("111",historyRow(f.network.calls.single { it.path==HISTORY_WRITE }).getString("vid"))
            assertEquals(0,f.rows().length())
            val fresh=f.intents.rewatch(f.context,"123")
            assertFalse("stale action revision is rejected",f.offerMerge(row(),revision=revision))
            f.offerNormal(row(episode="456",index=1,position=9000L,time=9999L),fresh)
            f.idle()
            val replaced=HistoryCheckpoint.restore(f.rows().getJSONObject("123"))
            assertEquals("456",replaced.progress.episodeId)
            assertFalse(f.rows().getJSONObject("123").has("manual"))
        }
    }

    @Test fun retireImportedWinnerDropsNewerTimestampOrdinaryRowButRejectsFartherQueuedRow() {
        Fixture().use { f ->
            f.seed(row(episode="456",index=1,position=1000L,time=9000L))
            assertTrue(f.retireMerge(row())) // episode 3 wins and never uploads
            assertTrue(f.callbacksOnMain)
            assertEquals(0,f.rows().length())
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.writes().size)
        }
        Fixture().use { f ->
            f.seed(row())
            assertFalse(f.retireMerge(row(episode="456",index=1,position=1000L,time=99999L)))
            assertTrue(f.rows().has("123"))
            assertEquals(100L,HistoryCheckpoint.restore(f.rows().getJSONObject("123")).progress.updatedAt)
            assertEquals(0,f.network.writes().size)
        }
    }

    @Test fun retiredImportFloorRejectsStaleSameRevisionOrdinaryOffer() {
        Fixture().use { f ->
            val revision=f.intents.historyRevision(f.context,"123")
            f.seed(row(episode="456",index=1,position=1000L,time=9000L))
            assertTrue(f.retireMerge(row(),revision=revision))
            assertEquals(0,f.rows().length())
            // Queued behind the main-thread apply: must not recreate the stale ordinary row.
            f.offerNormal(row(episode="456",index=1,position=1000L,time=99999L),revision)
            f.idle()
            assertFalse("stale same-revision offer cannot recreate a retired row",f.rows().has("123"))
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.writes().size)
            // A genuinely new explicit playback revision may supersede the reconciled winner.
            val fresh=f.intents.rewatch(f.context,"123")
            f.offerNormal(row(episode="456",index=1,position=1000L,time=99999L),fresh)
            f.idle()
            assertTrue(f.rows().has("123"))
        }
    }

    @Test fun beginMergePausesNormalDrainUntilEndMerge() {
        Fixture().use { f ->
            f.seed()
            val started=CountDownLatch(1)
            assertTrue(f.sync.beginMerge(f.context) { started.countDown() })
            assertTrue(started.await(5,TimeUnit.SECONDS))
            assertFalse("overlapping manual operation is rejected",f.sync.beginMerge(f.context) { })
            f.sync.flush(); f.idle()
            assertEquals(0,f.network.writes().size)
            assertTrue(f.rows().has("123"))
            f.sync.endMerge(f.context)
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals(1,f.network.count(PROGRESS_WRITE))
            assertEquals(0,f.rows().length())
        }
    }

    @Test fun beginMergeRunsQueuedWorkAndEndMergeReleasesAfterInvalidatedContext() {
        Fixture().use { f ->
            val ran=CountDownLatch(1)
            assertTrue(f.sync.beginMerge(f.context) { ran.countDown() })
            f.repo.logout()
            assertTrue("queued work runs even though the captured context expired",ran.await(5,TimeUnit.SECONDS))
            f.sync.endMerge(f.context)
            f.idle()
            f.repo.restoreSession(session())
            val next=f.repo.captureAccount()!!
            assertTrue("release is not lost with an expired captured context",f.sync.beginMerge(next) { })
            f.sync.endMerge(next)
        }
    }

    @Test fun oldWatchTimeManualRowIsNotStarvedByNewerTopTwentyNormalSelection() {
        Fixture().use { f ->
            f.seedAll((0 until 25).map { index ->
                row(id=(2000+index).toString(),episode="111",index=2,position=1000L,time=2000L+index)
            })
            assertTrue(f.offerMerge(row(id="900",episode="111",index=2,position=5000L,time=100L)))
            f.sync.flush(); f.idle()
            val sent=f.network.calls.filter { it.path==HISTORY_WRITE }.flatMap { historyIds(it) }
            assertTrue("manual row reserved a quota despite its old watch time","900" in sent)
        }
    }

    @Test fun failedHistoryAckCommitPreventsSecondEndpointAndRetainsPendingRow() {
        Fixture().use { f ->
            f.network.history("456",1,4000L,9000L)
            f.network.progress("456",1,4000L,9000L)
            assertTrue(f.offerMerge(row()))
            // Only the acknowledgement write is refused; the accept and submitted-time writes stay durable.
            f.gate.fail={ staged -> acked(staged,history=true) }
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals("a lost durable acknowledgement must not continue to the next endpoint",
                0,f.network.count(PROGRESS_WRITE))
            assertTrue("lost acknowledgement keeps the row pending",f.rows().has("123"))
            val stored=f.rows().getJSONObject("123")
            val manual=stored.getJSONObject("manual")
            assertFalse("the unacknowledged endpoint is not durably acknowledged",manual.getBoolean("historyAcked"))
            assertFalse(manual.getBoolean("progressAcked"))
            assertEquals("original watch time survives the refused acknowledgement",100L,
                HistoryCheckpoint.restore(stored).progress.updatedAt)
            assertTrue("no cloud or live HTTP beyond the worker endpoints",
                f.network.calls.none { it.path !in API_PATHS })
        }
    }

    @Test fun failedProgressAckCommitRetainsPendingRowInsteadOfClearingIt() {
        Fixture().use { f ->
            f.network.history("456",1,4000L,9000L)
            f.network.progress("456",1,4000L,9000L)
            assertTrue(f.offerMerge(row()))
            // The history acknowledgement commits; only the final progress acknowledgement is refused.
            f.gate.fail={ staged -> acked(staged,history=false) }
            f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE))
            assertEquals(1,f.network.count(PROGRESS_WRITE))
            assertTrue("a lost final acknowledgement must not clear the row",f.rows().has("123"))
            val stored=f.rows().getJSONObject("123")
            val manual=stored.getJSONObject("manual")
            assertTrue("the earlier acknowledgement stays durable",manual.getBoolean("historyAcked"))
            assertFalse(manual.getBoolean("progressAcked"))
            assertEquals("original watch time survives the refused acknowledgement",100L,
                HistoryCheckpoint.restore(stored).progress.updatedAt)
            assertTrue("no cloud or live HTTP beyond the worker endpoints",
                f.network.calls.none { it.path !in API_PATHS })
        }
    }
}
