package com.hongguotv.nativeapp

import android.app.Activity
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import org.json.JSONObject
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[21])
class AccountIntentsTest {
    private class Store : AccountIntentStorage {
        override val key="fixture-${UUID.randomUUID()}"
        @Volatile var value="{}"
        @Volatile var reject=false
        override fun read()=value
        override fun commit(value: String): Boolean {
            // A failed preference commit may still update in-process memory.
            this.value=value
            return !reject
        }
    }
    private fun session(id: String="7",credential: String="fixture",account: String="700")=
        AuthSession(mapOf("sessionid" to credential),"token-$credential",AuthUser(id,"fixture",accountId=account))
    private fun ready(intents: AccountIntents): Boolean {
        val complete=CountDownLatch(1)
        var success=false
        intents.loadAsync { success=it; complete.countDown() }
        assertTrue("intent loading completed",complete.await(5,TimeUnit.SECONDS))
        return success
    }
    @Test fun historySuppressionIsImmediateAndOldOffersCannotRevokeIt() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val context=repo.captureAccount()!!; val store=Store()
        val intents=AccountIntents(activity,repo,storage=store)
        assertTrue(ready(intents))
        val old=intents.rewatch(context,"11")
        assertEquals("rewatch revision is readable",old,intents.historyRevision(context,"11"))
        val deletion=intents.deleteHistory(context,setOf("11"))
        assertTrue(deletion>old)
        assertEquals("deletion revision replaces the offer revision",deletion,intents.historyRevision(context,"11"))
        assertTrue(intents.suppressHistory(context,"11"))
        assertFalse(intents.historyAllowed(context,"11",old))
        assertFalse(intents.historyAllowed(context,"11",deletion))
        val fresh=intents.rewatch(context,"11")
        assertTrue(fresh>deletion)
        assertEquals(fresh,intents.historyRevision(context,"11"))
        assertFalse(intents.suppressHistory(context,"11"))
        assertTrue(intents.historyAllowed(context,"11",fresh))
        assertFalse("an unissued revision never authorizes",intents.historyAllowed(context,"11",fresh+1))
        intents.close()
    }
    @Test fun durableDeletionSurvivesRecreationAndIsIsolatedFromAnotherAccount() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val original=repo.captureAccount()!!; val store=Store()
        val first=AccountIntents(activity,repo,storage=store); assertTrue(ready(first))
        first.deleteHistory(original,setOf("11")); first.close()
        val second=AccountIntents(activity,repo,storage=store); assertTrue(ready(second))
        assertTrue(second.suppressHistory(repo.captureAccount()!!,"11"))
        repo.restoreSession(session("8","other","800"))
        assertFalse(second.suppressHistory(repo.captureAccount()!!,"11"))
        assertFalse(second.historyAllowed(original,"11",Long.MAX_VALUE))
        second.close()
    }
    @Test fun unresolvedOwnerPromotesOnlyForTheExactCredentialIdentity() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session(account=""))
        val pending=repo.captureAccount()!!; val store=Store()
        val first=AccountIntents(activity,repo,storage=store); assertTrue(ready(first))
        first.deleteHistory(pending,setOf("11")); first.close()
        val second=AccountIntents(activity,repo,storage=store); assertTrue(ready(second))
        repo.restoreSession(session(account="700"))
        assertTrue(second.suppressHistory(repo.captureAccount()!!,"11"))
        repo.restoreSession(session(credential="different",account="800"))
        assertFalse(second.suppressHistory(repo.captureAccount()!!,"11"))
        second.close()
    }
    @Test fun selectedLegacyPartitionCannotBeReboundToAnotherCanonicalOwner() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val intents=AccountIntents(activity,repo,storage=Store()); assertTrue(ready(intents))
        assertTrue(intents.bindHistory(repo.captureAccount()!!))
        repo.restoreSession(session(credential="other",account="800"))
        assertFalse(intents.bindHistory(repo.captureAccount()!!))
        intents.close()
    }
    @Test fun historyDurabilityWaitsForCommitAndRechecksDeletion() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val context=repo.captureAccount()!!
        val writing=CountDownLatch(1); val release=CountDownLatch(1)
        val storage=object : AccountIntentStorage {
            override val key="blocked-history-${UUID.randomUUID()}"
            override fun read()="{}"
            override fun commit(value: String): Boolean {
                writing.countDown(); release.await(); return true
            }
        }
        val intents=AccountIntents(activity,repo,storage); assertTrue(ready(intents))
        val revision=intents.rewatch(context,"11")
        assertTrue(writing.await(5,TimeUnit.SECONDS))
        val started=CountDownLatch(1); val complete=CountDownLatch(1)
        var allowed=true
        val worker=Thread {
            started.countDown()
            try { allowed=intents.durableHistory(context,"11",revision) }
            finally { complete.countDown() }
        }
        worker.start(); assertTrue(started.await(5,TimeUnit.SECONDS))
        try {
            intents.deleteHistory(context,setOf("11"))
            assertFalse(intents.historyAllowed(context,"11",revision))
        } finally { release.countDown() }
        assertTrue(complete.await(5,TimeUnit.SECONDS)); assertFalse(allowed)
        intents.close()
    }
    @Test fun sameCredentialReloginDoesNotAuthorizeOldHistoryRevision() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val intents=AccountIntents(activity,repo,storage=Store()); assertTrue(ready(intents))
        val original=repo.captureAccount()!!
        val revision=intents.rewatch(original,"11")
        val generation=intents.revision(original)
        intents.deleteHistory(original,setOf("12"))
        assertTrue(intents.revision(original)>generation)
        repo.restoreSession(session("8","other","800"))
        repo.restoreSession(session())
        assertFalse(intents.historyAllowed(original,"11",revision))
        assertEquals(-1L,intents.rewatch(original,"11"))
        assertTrue(intents.historyAllowed(repo.captureAccount()!!,"11",revision))
        intents.close()
    }
    @Test fun failedRetiredCommitBlocksReplacementEvenWhenMemoryContainsTheNewValue() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository(); repo.restoreSession(session())
        val store=Store(); val first=AccountIntents(activity,repo,storage=store)
        assertTrue(ready(first)); store.reject=true
        first.deleteHistory(repo.captureAccount()!!,setOf("11")); first.close()
        val second=AccountIntents(activity,repo,storage=store)
        assertFalse(ready(second)); assertFalse(second.isReady())
        second.close()
    }
    private fun http(posts: AtomicInteger,body: (String)->String)=OkHttpClient.Builder().addInterceptor { chain ->
        val path=chain.request().url.encodedPath
        if(chain.request().method=="POST") posts.incrementAndGet()
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
            .body(body(path).toResponseBody("application/json".toMediaType())).build()
    }.build()
    private fun awaitStatus(intents: AccountIntents,context: AccountContext,id: String,expected: String,action: ()->Unit) {
        val reached=CountDownLatch(1)
        val listener={ if(intents.favoriteStatus(context,id)?.name==expected) reached.countDown() }
        intents.addListener(listener)
        try { action(); listener(); assertTrue("favorite reaches $expected",reached.await(5,TimeUnit.SECONDS)) }
        finally { intents.removeListener(listener) }
    }
    /** A queued sentinel is ordered behind all previously accepted work; a shutdown lane must terminate. */
    private fun drainLane(intents: AccountIntents,field: String) {
        val executor=AccountIntents::class.java.getDeclaredField(field)
            .apply { isAccessible=true }.get(intents) as ExecutorService
        if(executor.isShutdown) {
            assertTrue("$field lane terminated",executor.awaitTermination(5,TimeUnit.SECONDS))
            return
        }
        val done=CountDownLatch(1)
        executor.execute { done.countDown() }
        assertTrue("$field lane drained",done.await(5,TimeUnit.SECONDS))
    }
    /** The HTTP lane is shut down before the replacement reload, so this waits for the old task itself. */
    private fun drainHttp(intents: AccountIntents)=drainLane(intents,"http")
    private fun drainWriter(intents: AccountIntents)=drainLane(intents,"writer")
    @Test fun positiveRawMembershipConfirmsAddButPartialAbsenceDoesNotConfirmRemoval() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get(); val posts=AtomicInteger()
        var present=true
        val repo=AccountRepository(http(posts) { path -> if(path.endsWith("info/v1/"))
            """{"code":0,"data":{"video_shelf_info":${if(present) "[{\"book_id\":\"11\"}]" else "[]"}}}"""
            else """{"code":0}""" })
        repo.restoreSession(session()); val context=repo.captureAccount()!!
        val intents=AccountIntents(activity,repo,storage=Store()); assertTrue(ready(intents))
        awaitStatus(intents,context,"11","CONFIRMED") { intents.setFavorite(context,Series("11","fixture"),true) }
        present=false
        awaitStatus(intents,context,"11","SUBMITTED_UNCONFIRMED") { intents.setFavorite(context,Series("11","fixture"),false) }
        // awaitStatus observes the status before the bounded read-back finishes; wait for that task.
        drainHttp(intents)
        assertEquals("partial absence never confirms removal","SUBMITTED_UNCONFIRMED",intents.favoriteStatus(context,"11")?.name)
        assertTrue(intents.suppressFavorite(context,"11")); assertEquals(2,posts.get()); intents.close()
    }
    @Test fun ambiguousPostIsNotAutomaticallyRepeatedAfterRestartAndRequiresExplicitRetry() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get(); val posts=AtomicInteger()
        var fail=true
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            if(chain.request().method=="POST") { posts.incrementAndGet(); if(fail) throw java.io.IOException("ambiguous fixture") }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body((if(chain.request().method=="POST") """{"code":0}""" else """{"code":0,"data":{"video_shelf_info":[]}}""")
                    .toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=AccountRepository(client); repo.restoreSession(session()); val context=repo.captureAccount()!!; val store=Store()
        val first=AccountIntents(activity,repo,storage=store); assertTrue(ready(first))
        awaitStatus(first,context,"11","OUTCOME_UNKNOWN") { first.setFavorite(context,Series("11","fixture"),false) }
        first.close()
        val second=AccountIntents(activity,repo,storage=store); assertTrue(ready(second))
        assertEquals("OUTCOME_UNKNOWN",second.favoriteStatus(context,"11")?.name)
        assertEquals(1,posts.get()); fail=false
        awaitStatus(second,context,"11","SUBMITTED_UNCONFIRMED") { second.retryFavorite(context,"11") }
        assertEquals(2,posts.get()); second.close()
    }
    @Test fun replacementLoadsNewIntentWithoutWaitingForOldBlockedPostAndRejectsItsLateResult() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val blocked=CountDownLatch(1); val release=CountDownLatch(1); val finished=CountDownLatch(1); val posts=AtomicInteger()
        val repo=AccountRepository(http(posts) { path ->
            if(path.endsWith("update/v1/") && posts.get()==1) {
                blocked.countDown()
                while(true) { try { release.await(); break } catch(_: InterruptedException) { } }
                finished.countDown()
            }
            if(path.endsWith("info/v1/")) """{"code":0,"data":{"video_shelf_info":[]}}""" else """{"code":0}"""
        })
        repo.restoreSession(session()); val context=repo.captureAccount()!!; val store=Store()
        val first=AccountIntents(activity,repo,storage=store)
        try {
            assertTrue(ready(first))
            first.setFavorite(context,Series("11","fixture"),true)
            assertTrue(blocked.await(5,TimeUnit.SECONDS))
            first.setFavorite(context,Series("11","fixture"),false); first.deleteHistory(context,setOf("12")); first.close()
            val second=AccountIntents(activity,repo,storage=store)
            try {
                assertTrue(ready(second))
                assertTrue(second.suppressFavorite(context,"11")); assertTrue(second.suppressHistory(context,"12"))
                second.setFavorite(context,Series("11","fixture"),true)
                assertFalse(second.suppressFavorite(context,"11"))
                release.countDown(); assertTrue(finished.await(5,TimeUnit.SECONDS))
                // The interceptor latch fires before the old task processes its response, so wait
                // for the old HTTP lane itself to terminate before asserting on the replacement.
                drainHttp(first)
                assertFalse("late old response cannot undo newer intent",second.suppressFavorite(context,"11"))
            } finally {
                release.countDown(); second.close()
            }
            val third=AccountIntents(activity,repo,storage=store)
            try {
                assertTrue(ready(third))
                assertFalse(third.suppressFavorite(context,"11")); assertTrue(third.suppressHistory(context,"12"))
            } finally { third.close() }
        } finally {
            release.countDown(); first.close()
        }
    }
    @Test fun persistedSubmittingRestartBecomesUnknownAndNeitherSyncNorResumePosts() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get(); val posts=AtomicInteger()
        val repo=AccountRepository(http(posts) { path ->
            if(path.endsWith("info/v1/")) """{"code":0,"data":{"video_shelf_info":[]}}""" else """{"code":0}"""
        })
        repo.restoreSession(session()); val context=repo.captureAccount()!!
        val owner=context.owner ?: error("fixture must resolve an owner")
        val seeded=JSONObject().put("revision",9L).put("owners",JSONObject().put(owner,JSONObject()
            .put("history",JSONObject())
            .put("favorites",JSONObject().put("11",JSONObject()
                .put("revision",9L).put("added",false).put("status","SUBMITTING")))))
            .put("bindings",JSONObject()).toString()
        val store=Store().apply { value=seeded }
        val first=AccountIntents(activity,repo,storage=store)
        try {
            assertTrue(ready(first))
            drainWriter(first)
            assertEquals("seeded SUBMITTING never repeats after restart","OUTCOME_UNKNOWN",first.favoriteStatus(context,"11")?.name)
            assertFalse("conversion is durable and no SUBMITTING snapshot is left",store.value.contains("\"SUBMITTING\""))
            first.syncPending(context); first.resume(context); drainHttp(first)
            assertEquals("neither sync nor resume posts ambiguous work",0,posts.get())
            assertEquals("OUTCOME_UNKNOWN",first.favoriteStatus(context,"11")?.name)
        } finally { first.close() }
        val second=AccountIntents(activity,repo,storage=store)
        try {
            assertTrue(ready(second))
            assertEquals("OUTCOME_UNKNOWN",second.favoriteStatus(context,"11")?.name)
            second.syncPending(context); second.resume(context); drainHttp(second)
            assertEquals(0,posts.get()); assertEquals("OUTCOME_UNKNOWN",second.favoriteStatus(context,"11")?.name)
        } finally { second.close() }
    }
    @Test fun submittingCommitIsDurableBeforeTheFavoritePost() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get(); val posts=AtomicInteger()
        val submittingCommit=CountDownLatch(1); val allowCommit=CountDownLatch(1)
        val durableSubmitting=AtomicBoolean(false); val sawDurableBeforePost=AtomicBoolean(false)
        val storage=object : AccountIntentStorage {
            override val key="ordered-${UUID.randomUUID()}"
            @Volatile var value="{}"
            override fun read()=value
            override fun commit(value: String): Boolean {
                if(value.contains("\"SUBMITTING\"")) {
                    submittingCommit.countDown(); allowCommit.await()
                    durableSubmitting.set(true)
                }
                this.value=value
                return true
            }
        }
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val isPost=chain.request().method=="POST"
            if(isPost) {
                posts.incrementAndGet()
                if(durableSubmitting.get()) sawDurableBeforePost.set(true)
            }
            val body=if(isPost) """{"code":0}""" else """{"code":0,"data":{"video_shelf_info":[{"book_id":"11"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=AccountRepository(client); repo.restoreSession(session()); val context=repo.captureAccount()!!
        val intents=AccountIntents(activity,repo,storage)
        try {
            assertTrue(ready(intents))
            intents.setFavorite(context,Series("11","fixture"),true)
            assertTrue("SUBMITTING snapshot reaches storage",submittingCommit.await(5,TimeUnit.SECONDS))
            assertEquals("no POST before the SUBMITTING commit completes",0,posts.get())
            allowCommit.countDown(); drainHttp(intents)
            assertEquals(1,posts.get())
            assertTrue("POST observed the durable SUBMITTING snapshot",sawDurableBeforePost.get())
            assertEquals("CONFIRMED",intents.favoriteStatus(context,"11")?.name)
        } finally { allowCommit.countDown(); intents.close() }
    }
    @Test fun failedSubmittingCommitNeverSendsTheFavoritePost() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get(); val posts=AtomicInteger()
        val submittingCommit=CountDownLatch(1)
        val storage=object : AccountIntentStorage {
            override val key="rejected-${UUID.randomUUID()}"
            @Volatile var value="{}"
            override fun read()=value
            override fun commit(value: String): Boolean {
                if(value.contains("\"SUBMITTING\"")) { submittingCommit.countDown(); return false }
                this.value=value
                return true
            }
        }
        val repo=AccountRepository(http(posts) { path ->
            if(path.endsWith("info/v1/")) """{"code":0,"data":{"video_shelf_info":[]}}""" else """{"code":0}"""
        })
        repo.restoreSession(session()); val context=repo.captureAccount()!!
        val intents=AccountIntents(activity,repo,storage)
        try {
            assertTrue(ready(intents))
            intents.setFavorite(context,Series("11","fixture"),true)
            assertTrue("SUBMITTING snapshot reaches storage",submittingCommit.await(5,TimeUnit.SECONDS))
            drainHttp(intents); drainWriter(intents)
            assertEquals("failed durability never authorizes the POST",0,posts.get())
            assertEquals("FAILED",intents.favoriteStatus(context,"11")?.name)
            assertFalse("failed snapshot is not left SUBMITTING",storage.value.contains("\"SUBMITTING\""))
        } finally { intents.close() }
    }
    @Test fun resumeIsReadOnlyAndThrottledBetweenMembershipReads() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val posts=AtomicInteger(); val reads=AtomicInteger()
        val client=OkHttpClient.Builder().addInterceptor { chain ->
            val isPost=chain.request().method=="POST"
            if(isPost) posts.incrementAndGet()
            if(!isPost && chain.request().url.encodedPath.endsWith("info/v1/")) reads.incrementAndGet()
            val body=if(isPost) """{"code":0}""" else """{"code":0,"data":{"video_shelf_info":[{"book_id":"11"}]}}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("fixture")
                .body(body.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repo=AccountRepository(client); repo.restoreSession(session()); val context=repo.captureAccount()!!
        val intents=AccountIntents(activity,repo,storage=Store())
        try {
            assertTrue(ready(intents))
            awaitStatus(intents,context,"11","SUBMITTED_UNCONFIRMED") { intents.setFavorite(context,Series("11","fixture"),false) }
            drainHttp(intents)
            val readsAfterMutation=reads.get(); val postsAfterMutation=posts.get()
            intents.resume(context); drainHttp(intents)
            assertEquals("resume performs exactly one bounded read",readsAfterMutation+1,reads.get())
            assertEquals("resume never writes",postsAfterMutation,posts.get())
            assertEquals("SUBMITTED_UNCONFIRMED",intents.favoriteStatus(context,"11")?.name)
            intents.resume(context); drainHttp(intents)
            assertEquals("immediate resume is throttled",readsAfterMutation+1,reads.get())
            assertEquals(postsAfterMutation,posts.get())
        } finally { intents.close() }
    }
}
