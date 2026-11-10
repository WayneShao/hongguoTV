// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.*
import org.json.JSONObject
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Serial, account-partitioned durable outbox. All preference IO and HTTP run off the UI thread. */
internal class AccountHistorySync(context: Context,private val repository: AccountRepository,private val intents: AccountIntents,private val onCloud: (AccountContext,List<AccountHistoryItem>)->Unit = { _,_ -> }) {
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val app=context.applicationContext
    private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"hongguotv-account-history") }
    private val prefs by lazy { app.getSharedPreferences("account-history-outbox-v1",Context.MODE_PRIVATE) }
    private val claims by lazy { app.getSharedPreferences("account-history-owners-v1",Context.MODE_PRIVATE) }
    private val legacyAttempts=mutableMapOf<String,MutableMap<String,Long>>()
    private data class Accepted(val context: AccountContext,val revision: Long)
    private val accepted=mutableMapOf<String,MutableMap<String,Accepted>>()
    private val storedRevisions=mutableMapOf<String,MutableMap<String,Long>>()
    @Volatile private var scheduled: ScheduledFuture<*>?=null
    private var scheduledContext: AccountContext?=null
    @Volatile private var closed=false
    private fun owner(session: AuthSession)=MessageDigest.getInstance("SHA-256").digest(session.user.id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun active(context: AccountContext)=!closed && context.owner!=null && repository.isCurrent(context) && intents.isReady() && intents.bindHistory(context)
    private fun allowed(context: AccountContext,id: String,token: Accepted)=active(context) && repository.isCurrent(token.context) && intents.historyAllowed(context,id,token.revision)
    private fun enqueue(action: ()->Unit) { if(!closed) try { worker.execute(action) } catch(_: java.util.concurrent.RejectedExecutionException) { } }
    private fun load(key: String): MutableMap<String,HistoryCheckpoint> {
        val raw=runCatching { JSONObject(prefs.getString(key,"{}").orEmpty()) }.getOrDefault(JSONObject())
        storedRevisions[key]=raw.keys().asSequence().associateWith { raw.optJSONObject(it)?.optLong("actionRevision",0L) ?: 0L }.toMutableMap()
        return raw.keys().asSequence().mapNotNull { id -> runCatching { id to HistoryCheckpoint.restore(raw.getJSONObject(id)) }.getOrNull() }.toMap().toMutableMap()
    }
    private fun persist(key: String,rows: Map<String,HistoryCheckpoint>): Boolean = prefs.edit().putString(key,
        JSONObject().also { o -> rows.values.sortedByDescending { it.progress.updatedAt }.take(500).forEach {
            o.put(it.progress.series.id,it.stored().put("actionRevision",storedRevisions[key]?.get(it.progress.series.id) ?: 0L))
        } }.toString()).commit()
    /** Only a new accepted checkpoint supplies a rewatch token; null remains guest even after login. */
    fun offer(progress: BackupProgress,bookType: Int,episodeCount: Int,context: AccountContext?,actionRevision: Long) {
        if(context==null || !active(context)) return
        val token=Accepted(context,actionRevision)
        enqueue {
            val id=progress.series.id
            if(!allowed(context,id,token) || !intents.durableHistory(context,id,actionRevision) || !allowed(context,id,token)) return@enqueue
            val key=owner(context.session); claims.edit().putString(id,key).commit()
            val rows=load(key); val checkpoint=HistoryCheckpoint(progress,bookType,episodeCount)
            if(!allowed(context,id,token)) return@enqueue
            if((rows[id]?.progress?.updatedAt ?: 0L) <= progress.updatedAt) {
                rows[id]=checkpoint
                accepted.getOrPut(key) { mutableMapOf() }[id]=token
                storedRevisions.getOrPut(key) { mutableMapOf() }[id]=actionRevision
            }
            if(persist(key,rows)) schedule(context)
        }
    }
    /** Pull cloud resume data and retry only this account's existing outbox. Guest history is never uploaded. */
    fun resume(history: List<BackupProgress>) {
        val context=repository.captureAccount() ?: return
        if(!active(context)) return
        val generation=intents.revision(context)
        val known=history.map { it.series }.associateBy { it.id }
        enqueue {
            fun current()=active(context) && intents.revision(context)==generation
            if(!current()) return@enqueue
            try {
                val cloud=repository.accountHistory(requireAllTypes=true,session=context.session).history.associateBy { it.series.id }.toMutableMap()
                if(!current()) return@enqueue
                val progress=repository.accountVideoProgress(context.session)
                if(!current()) return@enqueue
                progress.forEach { remote ->
                    val old=cloud[remote.seriesId]
                    val series=old?.series ?: known[remote.seriesId] ?: return@forEach
                    if(remote.readAt>(old?.readAt ?: 0L)) cloud[remote.seriesId]=AccountHistoryItem(series,remote.episodeId,remote.episodeIndex,
                        remote.position,if(old?.episodeId==remote.episodeId) old.duration else 0,remote.readAt)
                }
                val result=cloud.values.sortedByDescending { it.readAt }
                main.post { if(current()) onCloud(context,result.filterNot { intents.suppressHistory(context,it.series.id) }) }
            } catch(problem: Exception) { Log.w("HongguoTV","Cloud history pull deferred: "+problem.javaClass.simpleName) }
            if(active(context)) schedule(context,0)
        }
    }
    fun adoptRemote(ids: List<String>,context: AccountContext) {
        if(!active(context) || ids.isEmpty()) return
        val generation=intents.revision(context)
        enqueue {
            if(!active(context) || intents.revision(context)!=generation) return@enqueue
            val key=owner(context.session)
            claims.edit().also { edit -> ids.filterNot { intents.suppressHistory(context,it) }.forEach { edit.putString(it,key) } }.commit()
        }
    }
    /** Caller accepts synchronous suppression before requesting physical removal. */
    fun cancelHistory(context: AccountContext,ids: Set<String>) {
        enqueue {
            // Cancellation only removes this captured legacy partition; it never authorizes HTTP
            // and must also work before a provisional account has resolved its canonical owner.
            if(closed || !repository.isCurrent(context) || !intents.isReady()) return@enqueue
            val key=owner(context.session); val rows=load(key)
            ids.filter { intents.suppressHistory(context,it) }.forEach { rows.remove(it); accepted[key]?.remove(it) }
            persist(key,rows)
        }
    }
    fun flush() {
        val context=repository.captureAccount() ?: return
        if(!active(context)) return
        enqueue { scheduled?.cancel(false); scheduled=null; schedule(context,0) }
    }
    private fun schedule(context: AccountContext,delay: Long=15_000) {
        if(!active(context)) return
        if(scheduled!=null && scheduledContext?.let { repository.isCurrent(it) }!=true) { scheduled?.cancel(false); scheduled=null }
        if(scheduled!=null) return
        scheduledContext=context
        scheduled=worker.schedule({ scheduled=null; scheduledContext=null; drain(context) },delay,TimeUnit.MILLISECONDS)
    }
    private fun drain(context: AccountContext) {
        if(!active(context)) return
        val session=context.session
        val key=owner(session); val rows=load(key)
        if(rows.isEmpty()) return
        // Snapshot before any blocking read. An old row must never inherit a later rewatch.
        // Missing v1 tokens mean zero, never whichever revision happens to be current now.
        val revisions=rows.keys.associateWith { intents.historyRevision(context,it) }
        val tokens=rows.keys.associateWith { id ->
            val revision=storedRevisions[key]?.get(id) ?: 0L
            accepted[key]?.get(id) ?: Accepted(context,revision)
        }
        fun permitted(id: String)=tokens[id]?.let {
            it.revision==revisions[id] && it.revision==(storedRevisions[key]?.get(id) ?: 0L) && allowed(context,id,it)
        } == true
        fun durable(id: String)=permitted(id) && intents.durableHistory(context,id,tokens.getValue(id).revision) && permitted(id)
        if(rows.keys.none { permitted(it) }) return
        val attempts=legacyAttempts.getOrPut(key) { mutableMapOf() }
        attempts.keys.retainAll(rows.keys)
        if(HistorySyncPlanner.select(rows.values.filter { permitted(it.progress.series.id) },attempts,System.currentTimeMillis()).isEmpty()) { schedule(context,60_000); return }
        try {
            run {
                val remote=repository.accountHistory(requireAllTypes=true,session=session).history.associateBy { it.series.id }
                if(!active(context) || rows.keys.none { permitted(it) }) return
                val progress=repository.accountVideoProgress(session).associateBy { it.seriesId }
                if(!active(context) || rows.keys.none { permitted(it) }) return
                rows.entries.removeAll { (id,row) ->
                    val historyTime=remote[id]?.readAt ?: 0L
                    val progressTime=progress[id]?.readAt ?: 0L
                    permitted(id) && (maxOf(historyTime,progressTime)>row.progress.updatedAt ||
                        (historyTime>=row.progress.updatedAt && progressTime>=row.progress.updatedAt))
                }
                rows.replaceAll { id,row -> if(permitted(id)) row.copy(bookType=remote[id]?.series?.bookType ?: row.bookType) else row }
                if(!active(context)) return
                check(persist(key,rows))
            }
            // Send known records first; one broken legacy lookup must not starve valid progress.
            HistorySyncPlanner.select(rows.values.filter { permitted(it.progress.series.id) },attempts,System.currentTimeMillis()).chunked(20).forEach { batch ->
                val ready=batch.mapNotNull { row ->
                    try {
                    if(!permitted(row.progress.series.id)) return@mapNotNull null
                    val resolved=if(HistorySyncPlanner.known(row)) row else {
                        attempts[row.progress.series.id]=System.currentTimeMillis()
                        val detail=repository.detail(row.progress.series.id)
                        if(!permitted(row.progress.series.id)) return@mapNotNull null
                        val type=row.bookType.takeIf { it in 2..5 } ?: repository.historyBookType(row.progress.series).takeIf { it in 2..5 }
                            ?: return@mapNotNull null
                        if(!permitted(row.progress.series.id)) return@mapNotNull null
                        row.copy(bookType=type,episodeCount=detail.episodes.size)
                    }
                    resolved.json() // Validate before batching so a malformed record cannot reject its peers.
                    row to resolved
                    } catch(problem: Exception) {
                        RequestScope.ensureActive()
                        Log.w("HongguoTV","Account history record deferred: "+problem.javaClass.simpleName)
                        null
                    }
                }
                // The helper hides two independent endpoints. Refilter after the first response,
                // including synchronous deletions accepted while that HTTP call was blocked.
                val first=ready.filter { durable(it.first.progress.series.id) }.filter { permitted(it.first.progress.series.id) }
                val firstRows=first.map { it.second }
                if(firstRows.isNotEmpty() && active(context)) repository.uploadHistory(session,firstRows)
                val second=first.filter { durable(it.first.progress.series.id) }.filter { permitted(it.first.progress.series.id) }
                val secondRows=second.map { it.second }
                if(secondRows.isNotEmpty() && active(context)) repository.uploadVideoProgress(session,secondRows)
                if(!active(context)) return
                second.forEach { (original,_) ->
                    val id=original.progress.series.id
                    if(permitted(id) && rows[id]==original && accepted[key]?.get(id).let { it==null || it==tokens[id] }) {
                        rows.remove(id)
                        accepted[key]?.remove(id)
                    }
                }
                if(!active(context)) return
                check(persist(key,rows))
            }
            attempts.keys.retainAll(rows.keys)
            if(rows.isNotEmpty()) { Log.i("HongguoTV","Account history sync pending: remaining records"); schedule(context,if(HistorySyncPlanner.select(rows.values.filter { permitted(it.progress.series.id) },attempts,System.currentTimeMillis()).isEmpty()) 60_000 else 15_000) }
            else Log.i("HongguoTV","Account history sync completed")
        } catch(problem: Exception) {
            Log.w("HongguoTV","Account history sync pending: "+problem.javaClass.simpleName)
            schedule(context,60_000)
        }
    }
    fun close() {
        if(closed) return
        closed=true
        scheduled?.cancel(false)
        // Persisted entries survive process death; do not keep Activity teardown waiting for network.
        worker.shutdown()
    }
}
