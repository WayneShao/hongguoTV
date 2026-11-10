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
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
@LooperMode(LooperMode.Mode.PAUSED)
class AccountHistoryIntentTest {
    private class Store : AccountIntentStorage {
        override val key="history-fixture-${UUID.randomUUID()}"
        @Volatile private var value="{}"
        override fun read()=value
        override fun commit(value: String): Boolean { this.value=value; return true }
    }
    private data class Call(val path: String,val body: String,val cookie: String?)
    private class Network(private val block: String?=null) {
        val entered=CountDownLatch(1)
        val release=CountDownLatch(1)
        val calls=CopyOnWriteArrayList<Call>()
        private val blocked=AtomicBoolean()
        var remote=false
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val request=chain.request()
            val body=Buffer().also { request.body?.writeTo(it) }.readUtf8()
            val path=request.url.encodedPath
            calls.add(Call(path,body,request.header("Cookie")))
            if(path==block && blocked.compareAndSet(false,true)) {
                entered.countDown()
                check(release.await(5,TimeUnit.SECONDS)) { "fixture read was not released" }
            }
            val response=when(path) {
                HISTORY_READ -> if(remote) """{"code":0,"data":{"data_list":[{"book_id":"123","title":"fixture","vid":"456","read_timestamp_ms":20}]}}"""
                    else """{"code":0,"data":{"data_list":[]}}"""
                PROGRESS_READ -> if(remote) """{"code":0,"video_data":[{"series_id":"123","vid":"456","vid_index":1,"current_play_time":3000,"last_watch_time":30}]}"""
                    else """{"code":0,"video_data":[]}"""
                else -> """{"code":0}"""
            }
            Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(response.toResponseBody()).build()
        }.build()
        fun count(path: String)=calls.count { it.path==path }
        fun awaitBlocked() { assertTrue("blocked at $block",entered.await(5,TimeUnit.SECONDS)) }
    }
    private class Fixture(val network: Network=Network(),account: String="700") : AutoCloseable {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(network.client)
        val store=Store()
        val intents: AccountIntents
        val context: AccountContext
        val cloud=CopyOnWriteArrayList<Pair<AccountContext,List<AccountHistoryItem>>>()
        var sync: AccountHistorySync
        val prefs=activity.getSharedPreferences("account-history-outbox-v1",Context.MODE_PRIVATE)
        val claims=activity.getSharedPreferences("account-history-owners-v1",Context.MODE_PRIVATE)
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
        fun newSync()=AccountHistorySync(activity,repo,intents) { owner,rows -> cloud.add(owner to rows) }
        fun rows(key: String=context.legacyOwner)=JSONObject(prefs.getString(key,"{}")!!)
        fun seed(progress: BackupProgress=row(),revision: Long?=null,key: String=context.legacyOwner) {
            val checkpoint=HistoryCheckpoint(progress,2,10).stored()
            if(revision!=null) checkpoint.put("actionRevision",revision)
            prefs.edit().putString(key,JSONObject().put(progress.series.id,checkpoint).toString()).commit()
        }
        fun offer(progress: BackupProgress=row()): Long {
            val revision=intents.rewatch(context,progress.series.id)
            sync.offer(progress,2,10,context,revision)
            return revision
        }
        fun delete(vararg ids: String) {
            intents.deleteHistory(context,ids.toSet())
            sync.cancelHistory(context,ids.toSet())
        }
        fun idle()=awaitWorker(sync)
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
        private fun session(id: String="7",account: String="700",credential: String="fixture")=
            AuthSession(mapOf("sessionid" to credential),"token-$credential",AuthUser(id,"fixture",accountId=account))
        private fun row(id: String="123",time: Long=100L)=
            BackupProgress(Series(id,"fixture",bookType=2),"456",0,2000,60000,false,time)
        private fun executor(sync: AccountHistorySync)=AccountHistorySync::class.java.getDeclaredField("worker").let {
            it.isAccessible=true; it.get(sync) as ScheduledExecutorService
        }
        /** Two fences include a zero-delay drain scheduled by a preceding flush/resume task. */
        private fun awaitWorker(sync: AccountHistorySync) { repeat(2) { executor(sync).submit {}.get(5,TimeUnit.SECONDS) } }
    }

    @Test fun deletionWhileHistoryReadIsBlockedStopsProgressReadAndBothUploads() {
        Fixture(Network(HISTORY_READ)).use { f ->
            f.seed(); f.sync.flush(); f.network.awaitBlocked()
            f.delete("123")
            assertTrue(f.intents.suppressHistory(f.context,"123"))
            f.network.release.countDown(); f.idle()
            assertEquals(0,f.network.count(PROGRESS_READ))
            assertEquals(0,f.network.count(HISTORY_WRITE)); assertEquals(0,f.network.count(PROGRESS_WRITE))
            assertFalse(f.rows().has("123"))
        }
    }
    @Test fun deletionWhileProgressReadIsBlockedStopsBothUploads() {
        Fixture(Network(PROGRESS_READ)).use { f ->
            f.seed(); f.sync.flush(); f.network.awaitBlocked(); f.delete("123")
            f.network.release.countDown(); f.idle()
            assertEquals(0,f.network.count(HISTORY_WRITE)); assertEquals(0,f.network.count(PROGRESS_WRITE))
            assertFalse(f.rows().has("123"))
        }
    }
    @Test fun deletionBetweenEndpointsFiltersOnlyDeletedRowsFromSecondBatch() {
        Fixture(Network(HISTORY_WRITE)).use { f ->
            f.offer(); f.offer(row("124")); f.sync.flush(); f.network.awaitBlocked()
            f.delete("123"); f.network.release.countDown(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE)); assertEquals(1,f.network.count(PROGRESS_WRITE))
            val body=JSONObject(f.network.calls.single { it.path==PROGRESS_WRITE }.body).getJSONArray("books")
            assertEquals(1,body.length()); assertEquals("124",body.getJSONObject(0).getString("book_id"))
            assertEquals(0,f.rows().length())
        }
    }
    @Test fun delayedPreDeletionOfferCannotRevokeSuppressionOrRecreateAnOutboxRow() {
        Fixture(Network(HISTORY_READ)).use { f ->
            val old=f.offer(); f.sync.flush(); f.network.awaitBlocked()
            f.delete("123")
            f.sync.offer(row(time=200L),2,10,f.context,old)
            f.network.release.countDown(); f.idle()
            assertTrue(f.intents.suppressHistory(f.context,"123"))
            assertEquals(0,f.rows().length()); assertEquals(0,f.network.count(HISTORY_WRITE))
        }
    }
    @Test fun newAcceptedCheckpointAfterDeletionCanUploadWithoutAuthorizingTheOldRow() {
        Fixture(Network(HISTORY_READ)).use { f ->
            f.seed(); f.sync.flush(); f.network.awaitBlocked()
            f.delete("123"); f.offer(row(time=200L))
            f.network.release.countDown(); f.idle()
            f.sync.flush(); f.idle()
            assertFalse(f.intents.suppressHistory(f.context,"123"))
            assertEquals(1,f.network.count(HISTORY_WRITE)); assertEquals(1,f.network.count(PROGRESS_WRITE))
            val sent=JSONObject(f.network.calls.single { it.path==HISTORY_WRITE }.body).getJSONArray("update_datas").getJSONObject(0)
            assertEquals(200L,sent.getLong("read_timestamp_ms")); assertEquals(0,f.rows().length())
        }
    }
    @Test fun persistedRewatchTokenSurvivesSyncRecreationWithoutRelabelingLegacyRows() {
        Fixture().use { f ->
            f.delete("123")
            val revision=f.offer(); f.idle()
            assertEquals(revision,f.rows().getJSONObject("123").getLong("actionRevision"))
            f.sync.close(); assertTrue(executor(f.sync).awaitTermination(5,TimeUnit.SECONDS))
            f.sync=f.newSync(); f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE)); assertEquals(1,f.network.count(PROGRESS_WRITE))
            assertEquals(0,f.rows().length())
            // A missing token is zero, not the current nonzero per-ID rewatch revision.
            f.seed(); f.sync.flush(); f.idle()
            assertEquals(1,f.network.count(HISTORY_WRITE)); assertTrue(f.rows().has("123"))
        }
    }
    @Test fun guestOfferRestoreAndPassiveResumeNeverBecomeUploadsOrClaims() {
        Fixture().use { f ->
            f.repo.logout()
            f.sync.offer(row(),2,10,null,0L)
            f.repo.restoreSession(session())
            f.sync.resume(listOf(row())); f.idle()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0,f.rows().length()); assertFalse(f.claims.contains("123"))
            assertEquals(0,f.network.count(HISTORY_WRITE)); assertEquals(0,f.network.count(PROGRESS_WRITE))
        }
    }
    @Test fun sameCredentialReloginRejectsOldSelectedPlaybackAndBlockedDispatch() {
        Fixture(Network(HISTORY_READ)).use { f ->
            val revision=f.offer(); f.sync.flush(); f.network.awaitBlocked()
            f.repo.logout(); f.repo.restoreSession(session())
            assertFalse(f.repo.isCurrent(f.context))
            f.sync.offer(row(time=200L),2,10,f.context,revision)
            f.network.release.countDown(); f.idle()
            assertEquals(0,f.network.count(PROGRESS_READ))
            assertEquals(0,f.network.count(HISTORY_WRITE)); assertEquals(0,f.network.count(PROGRESS_WRITE))
            assertEquals(100L,HistoryCheckpoint.restore(f.rows().getJSONObject("123")).progress.updatedAt)
        }
    }
    @Test fun resumeRevalidatesGenerationInsideDeferredMainDelivery() {
        Fixture().use { f ->
            f.network.remote=true
            f.sync.resume(emptyList()); f.idle()
            assertTrue(f.cloud.isEmpty())
            f.delete("123"); shadowOf(Looper.getMainLooper()).idle()
            assertTrue(f.cloud.isEmpty())
            f.sync.resume(emptyList()); f.idle(); shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1,f.cloud.size); assertTrue(f.cloud.single().second.isEmpty())
        }
    }
    @Test fun closeDoesNotWaitForBlockedReadOrDeliverLateCloudData() {
        Fixture(Network(PROGRESS_READ)).use { f ->
            f.network.remote=true
            f.sync.resume(emptyList()); f.network.awaitBlocked()
            f.sync.close() // Must return before the fixture releases HTTP.
            assertEquals(1L,f.network.release.count)
            f.network.release.countDown()
            assertTrue(executor(f.sync).awaitTermination(5,TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle(); assertTrue(f.cloud.isEmpty())
        }
    }
    @Test fun selectedLegacyPartitionOnlyAndCanonicalBindingRemainRequired() {
        Fixture().use { f ->
            f.seed(key="unrelated-owner")
            f.sync.flush(); f.idle()
            assertTrue(f.rows("unrelated-owner").has("123")); assertTrue(f.network.calls.isEmpty())
            assertTrue(f.intents.bindHistory(f.context))
            f.repo.restoreSession(session(account="800",credential="replacement"))
            val replacement=f.repo.captureAccount()!!
            val revision=f.intents.rewatch(replacement,"123")
            f.sync.offer(row(),2,10,replacement,revision); f.sync.resume(listOf(row())); f.sync.flush(); f.idle()
            assertEquals(0,f.rows().length()); assertTrue(f.network.calls.isEmpty())
        }
    }
    @Test fun unresolvedDeletionRemovesSelectedLegacyRowAndStaysSuppressedAfterOwnerResolution() {
        Fixture(account="").use { f ->
            assertNull(f.context.owner)
            f.seed(); f.sync.resume(listOf(row())); f.sync.flush(); f.idle()
            assertTrue(f.rows().has("123")); assertTrue(f.network.calls.isEmpty())
            f.delete("123"); f.idle()
            assertEquals(0,f.rows().length()); assertTrue(f.network.calls.isEmpty())
            // A profile-only resolution preserves the epoch and promotes the provisional deletion.
            f.repo.restoreSession(session())
            val resolved=f.repo.captureAccount()!!
            assertTrue(f.repo.isCurrent(f.context)); assertNotNull(resolved.owner)
            assertTrue(f.intents.suppressHistory(resolved,"123"))
            f.sync.resume(listOf(row())); f.idle()
            assertEquals(0,f.network.count(HISTORY_WRITE)); assertEquals(0,f.network.count(PROGRESS_WRITE))
            assertEquals(0,f.rows().length())
        }
    }
}
