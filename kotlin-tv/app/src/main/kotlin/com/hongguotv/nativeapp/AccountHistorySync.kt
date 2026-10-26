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
internal class AccountHistorySync(context: Context,private val repository: AccountRepository,private val onCloud: (AuthSession,List<AccountHistoryItem>)->Unit = { _,_ -> }) {
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    private val app=context.applicationContext
    private val worker=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"hongguotv-account-history") }
    private val prefs by lazy { app.getSharedPreferences("account-history-outbox-v1",Context.MODE_PRIVATE) }
    private val claims by lazy { app.getSharedPreferences("account-history-owners-v1",Context.MODE_PRIVATE) }
    private val legacyAttempts=mutableMapOf<String,MutableMap<String,Long>>()
    @Volatile private var scheduled: ScheduledFuture<*>?=null
    @Volatile private var closed=false
    private fun owner(session: AuthSession)=MessageDigest.getInstance("SHA-256").digest(session.user.id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun load(key: String): MutableMap<String,HistoryCheckpoint> {
        val raw=runCatching { JSONObject(prefs.getString(key,"{}").orEmpty()) }.getOrDefault(JSONObject())
        return raw.keys().asSequence().mapNotNull { id -> runCatching { id to HistoryCheckpoint.restore(raw.getJSONObject(id)) }.getOrNull() }.toMap().toMutableMap()
    }
    private fun persist(key: String,rows: Map<String,HistoryCheckpoint>): Boolean = prefs.edit().putString(key,
        JSONObject().also { o -> rows.values.sortedByDescending { it.progress.updatedAt }.take(500).forEach { o.put(it.progress.series.id,it.stored()) } }.toString()).commit()
    /** Call with the session captured at the actual playback checkpoint, including null for guest viewing. */
    fun offer(progress: BackupProgress,bookType: Int,episodeCount: Int,session: AuthSession?=repository.currentSession()) {
        if(closed) return
        worker.execute {
            val id=progress.series.id
            if(session==null) { claims.edit().remove(id).commit(); return@execute }
            val key=owner(session); claims.edit().putString(id,key).commit()
            val rows=load(key); val checkpoint=HistoryCheckpoint(progress,bookType,episodeCount)
            if((rows[id]?.progress?.updatedAt ?: 0L) <= progress.updatedAt) rows[id]=checkpoint
            if(persist(key,rows)) schedule()
        }
    }
    /** Pull cloud resume data and retry only this account's existing outbox. Guest history is never uploaded. */
    fun resume(history: List<BackupProgress>) {
        val session=repository.currentSession() ?: return
        if(closed) return
        worker.execute {
            val key=owner(session); val rows=load(key)
            try {
                val cloud=repository.accountResumeHistory(session,history.map { it.series })
                main.post { if(!closed) onCloud(session,cloud.history) }
            } catch(problem: Exception) { Log.w("HongguoTV","Cloud history pull deferred: "+problem.javaClass.simpleName) }
            if(persist(key,rows)) schedule(0)
        }
    }
    fun adoptRemote(ids: List<String>,session: AuthSession) {
        if(closed || ids.isEmpty()) return
        worker.execute { val key=owner(session); claims.edit().also { edit -> ids.forEach { edit.putString(it,key) } }.commit() }
    }
    fun flush() { if(!closed) worker.execute { scheduled?.cancel(false); scheduled=null; schedule(0) } }
    private fun schedule(delay: Long=15_000) {
        if(closed || scheduled!=null) return
        scheduled=worker.schedule({ scheduled=null; drain() },delay,TimeUnit.MILLISECONDS)
    }
    private fun drain() {
        val session=repository.currentSession() ?: return
        val key=owner(session); val rows=load(key)
        if(rows.isEmpty()) return
        val attempts=legacyAttempts.getOrPut(key) { mutableMapOf() }
        attempts.keys.retainAll(rows.keys)
        if(HistorySyncPlanner.select(rows.values,attempts,System.currentTimeMillis()).isEmpty()) { schedule(60_000); return }
        try {
            run {
                val remote=repository.accountHistory(requireAllTypes=true,session=session).history.associateBy { it.series.id }
                val progress=repository.accountVideoProgress(session).associateBy { it.seriesId }
                rows.entries.removeAll { (id,row) ->
                    val historyTime=remote[id]?.readAt ?: 0L
                    val progressTime=progress[id]?.readAt ?: 0L
                    maxOf(historyTime,progressTime)>row.progress.updatedAt ||
                        (historyTime>=row.progress.updatedAt && progressTime>=row.progress.updatedAt)
                }
                rows.replaceAll { id,row -> row.copy(bookType=remote[id]?.series?.bookType ?: row.bookType) }
                check(persist(key,rows))
            }
            // Send known records first; one broken legacy lookup must not starve valid progress.
            HistorySyncPlanner.select(rows.values,attempts,System.currentTimeMillis()).chunked(20).forEach { batch ->
                val ready=batch.mapNotNull { row ->
                    try {
                    val resolved=if(HistorySyncPlanner.known(row)) row else {
                        attempts[row.progress.series.id]=System.currentTimeMillis()
                        val detail=repository.detail(row.progress.series.id)
                        val type=row.bookType.takeIf { it in 2..5 } ?: repository.historyBookType(row.progress.series).takeIf { it in 2..5 }
                            ?: return@mapNotNull null
                        row.copy(bookType=type,episodeCount=detail.episodes.size)
                    }
                    resolved.json() // Validate before batching so a malformed record cannot reject its peers.
                    resolved
                    } catch(problem: Exception) {
                        RequestScope.ensureActive()
                        Log.w("HongguoTV","Account history record deferred: "+problem.javaClass.simpleName)
                        null
                    }
                }
                if(ready.isNotEmpty()) repository.uploadPlaybackCheckpoint(session,ready)
                ready.forEach { rows.remove(it.progress.series.id) }
                check(persist(key,rows))
            }
            attempts.keys.retainAll(rows.keys)
            if(rows.isNotEmpty()) { Log.i("HongguoTV","Account history sync pending: remaining records"); schedule(if(HistorySyncPlanner.select(rows.values,attempts,System.currentTimeMillis()).isEmpty()) 60_000 else 15_000) }
            else Log.i("HongguoTV","Account history sync completed")
        } catch(problem: Exception) {
            Log.w("HongguoTV","Account history sync pending: "+problem.javaClass.simpleName)
            schedule(60_000)
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
