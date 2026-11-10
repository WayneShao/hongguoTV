// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.Context
import android.util.Log
import com.hongguotv.core.*
import org.json.JSONArray
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
    /** A completed manual merge decision; the checkpoint keeps the original playback timestamp. */
    private data class Manual(val catalog: List<String>,val submittedAt: Long,val historyAcked: Boolean,val progressAcked: Boolean)
    private val accepted=mutableMapOf<String,MutableMap<String,Accepted>>()
    private val storedRevisions=mutableMapOf<String,MutableMap<String,Long>>()
    private val manuals=mutableMapOf<String,MutableMap<String,Manual>>()
    private val mergeLock=Any()
    @Volatile private var mergeReserved=false
    @Volatile private var scheduled: ScheduledFuture<*>?=null
    private var scheduledContext: AccountContext?=null
    @Volatile private var closed=false
    private fun owner(session: AuthSession)=MessageDigest.getInstance("SHA-256").digest(session.user.id.toByteArray()).joinToString("") { "%02x".format(it) }
    private fun active(context: AccountContext)=!closed && context.owner!=null && repository.isCurrent(context) && intents.isReady() && intents.bindHistory(context)
    private fun allowed(context: AccountContext,id: String,token: Accepted)=active(context) && repository.isCurrent(token.context) && intents.historyAllowed(context,id,token.revision)
    private fun enqueue(action: ()->Unit) { if(!closed) try { worker.execute(action) } catch(_: java.util.concurrent.RejectedExecutionException) { } }
    private fun manualOf(o: JSONObject): Manual {
        val array=o.optJSONArray("catalog") ?: JSONArray()
        val catalog=(0 until array.length()).mapNotNull { index -> array.optString(index).takeIf { it.isNotBlank() } }
        return Manual(catalog,o.optLong("submittedAt",0L),o.optBoolean("historyAcked",false),o.optBoolean("progressAcked",false))
    }
    private fun load(key: String): MutableMap<String,HistoryCheckpoint> {
        val raw=runCatching { JSONObject(prefs.getString(key,"{}").orEmpty()) }.getOrDefault(JSONObject())
        storedRevisions[key]=raw.keys().asSequence().associateWith { raw.optJSONObject(it)?.optLong("actionRevision",0L) ?: 0L }.toMutableMap()
        manuals[key]=raw.keys().asSequence().mapNotNull { id ->
            raw.optJSONObject(id)?.optJSONObject("manual")?.let { id to manualOf(it) }
        }.toMap().toMutableMap()
        return raw.keys().asSequence().mapNotNull { id -> runCatching { id to HistoryCheckpoint.restore(raw.getJSONObject(id)) }.getOrNull() }.toMap().toMutableMap()
    }
    /** Manual rows keep their reserved slots; accepted work must survive the committed representation. */
    private fun persist(key: String,rows: Map<String,HistoryCheckpoint>): Boolean {
        val metadata=manuals.getOrPut(key) { mutableMapOf() }
        metadata.keys.retainAll(rows.keys)
        val ordered=rows.values.sortedWith(compareByDescending<HistoryCheckpoint> { it.progress.series.id in metadata.keys }
            .thenByDescending { it.progress.updatedAt }).take(500)
        val root=JSONObject()
        ordered.forEach { row ->
            val id=row.progress.series.id
            val stored=row.stored().put("actionRevision",storedRevisions[key]?.get(id) ?: 0L)
            metadata[id]?.let { m ->
                stored.put("manual",JSONObject().put("catalog",JSONArray(m.catalog)).put("submittedAt",m.submittedAt)
                    .put("historyAcked",m.historyAcked).put("progressAcked",m.progressAcked))
            }
            root.put(id,stored)
        }
        return prefs.edit().putString(key,root.toString()).commit()
    }
    private fun floorKey(key: String)="floor:$key"
    private fun floorRevision(key: String,id: String): Long? = runCatching {
        JSONObject(prefs.getString(floorKey(key),"{}").orEmpty()).optJSONObject(id)?.optLong("revision",0L)
    }.getOrNull()
    private fun clearFloor(key: String,id: String) {
        val raw=prefs.getString(floorKey(key),null) ?: return
        val root=runCatching { JSONObject(raw) }.getOrNull() ?: return
        if(root.has(id)) { root.remove(id); prefs.edit().putString(floorKey(key),root.toString()).commit() }
    }
    private fun persistFloor(key: String,id: String,revision: Long,winner: BackupProgress,catalog: List<String>,bookType: Int,episodeCount: Int): Boolean {
        val root=runCatching { JSONObject(prefs.getString(floorKey(key),"{}").orEmpty()) }.getOrDefault(JSONObject())
        root.put(id,HistoryCheckpoint(winner,bookType,episodeCount).stored().put("revision",revision).put("catalog",JSONArray(catalog)))
        return prefs.edit().putString(floorKey(key),root.toString()).commit()
    }
    /** Only a new accepted checkpoint supplies a rewatch token; null remains guest even after login. */
    fun offer(progress: BackupProgress,bookType: Int,episodeCount: Int,context: AccountContext?,actionRevision: Long) {
        if(context==null || !active(context)) return
        val token=Accepted(context,actionRevision)
        enqueue {
            val id=progress.series.id
            if(!allowed(context,id,token) || !intents.durableHistory(context,id,actionRevision) || !allowed(context,id,token)) return@enqueue
            val key=owner(context.session); claims.edit().putString(id,key).commit()
            val rows=load(key)
            if(!allowed(context,id,token)) return@enqueue
            // A retired import floor blocks only the same explicit revision; a later rewatch supersedes it.
            floorRevision(key,id)?.let { if(actionRevision<=it) return@enqueue }
            val manualRevision=manuals[key]?.get(id)?.let { storedRevisions[key]?.get(id) ?: 0L }
            if(manualRevision!=null && actionRevision<=manualRevision) return@enqueue
            if((rows[id]?.progress?.updatedAt ?: 0L) <= progress.updatedAt) {
                rows[id]=HistoryCheckpoint(progress,bookType,episodeCount)
                manuals[key]?.remove(id)
                accepted.getOrPut(key) { mutableMapOf() }[id]=token
                storedRevisions.getOrPut(key) { mutableMapOf() }[id]=actionRevision
                clearFloor(key,id)
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
            ids.filter { intents.suppressHistory(context,it) }.forEach {
                rows.remove(it); accepted[key]?.remove(it); manuals[key]?.remove(it); storedRevisions[key]?.remove(it); clearFloor(key,it)
            }
            persist(key,rows)
        }
    }
    /** Reserve the single explicit merge slot; the queued work shares the existing history worker. */
    fun beginMerge(context: AccountContext,work: ()->Unit): Boolean {
        synchronized(mergeLock) {
            if(closed || mergeReserved) return false
            mergeReserved=true
        }
        scheduled?.cancel(false); scheduled=null; scheduledContext=null
        enqueue {
            try { work() } catch(_: Exception) { }
        }
        return true
    }
    /** Release even when the captured context expired; resume with the normal deferred delay. */
    fun endMerge(context: AccountContext) {
        synchronized(mergeLock) {
            if(!mergeReserved) return
            mergeReserved=false
        }
        if(closed) return
        enqueue {
            if(closed || mergeReserved) return@enqueue
            val current=repository.captureAccount() ?: return@enqueue
            if(active(current)) schedule(current,15_000)
        }
    }
    /** Retain an explicit manual winner without ever letting timestamp ordering replace it. */
    fun offerMerge(checkpoint: HistoryCheckpoint,episodes: List<String>,context: AccountContext,revision: Long,callback: (Boolean)->Unit) {
        if(closed) { main.post { callback(false) }; return }
        enqueue {
            val okay=runCatching { acceptManual(checkpoint,episodes,context,Accepted(context,revision)) }.getOrDefault(false)
            main.post { callback(okay) }
        }
    }
    private fun acceptManual(checkpoint: HistoryCheckpoint,episodes: List<String>,context: AccountContext,token: Accepted): Boolean {
        val id=checkpoint.progress.series.id
        if(!active(context)) return false
        if(episodes.size!=checkpoint.episodeCount || checkpoint.bookType !in 2..5) return false
        val normalized=ProgressMergePlanner.normalize(checkpoint.progress,episodes) ?: return false
        val key=owner(context.session)
        if(!allowed(context,id,token) || !intents.durableHistory(context,id,token.revision) || !allowed(context,id,token)) return false
        claims.edit().putString(id,key).commit()
        val rows=load(key)
        val candidate=HistoryCheckpoint(normalized,checkpoint.bookType,episodes.size)
        rows[id]=candidate
        storedRevisions.getOrPut(key) { mutableMapOf() }[id]=token.revision
        accepted.getOrPut(key) { mutableMapOf() }[id]=token
        manuals.getOrPut(key) { mutableMapOf() }[id]=Manual(episodes,0L,false,false)
        clearFloor(key,id)
        if(!persist(key,rows)) return false
        // True means this exact work is committed, not merely present in memory. Series text may be
        // sanitized by the outbox encoder, so compare the progress fields that define the work.
        val stored=runCatching { JSONObject(prefs.getString(key,"{}")!!).optJSONObject(id) }.getOrNull() ?: return false
        if(stored.optJSONObject("manual")==null) return false
        val committed=runCatching { HistoryCheckpoint.restore(stored) }.getOrNull() ?: return false
        if(committed.bookType!=candidate.bookType || committed.episodeCount!=candidate.episodeCount) return false
        if(committed.progress.copy(series=candidate.progress.series)!=candidate.progress) return false
        schedule(context)
        return true
    }
    /** Retire a queued loser and persist a per-revision winner floor for late ordinary offers. */
    fun retireMerge(checkpoint: HistoryCheckpoint,episodes: List<String>,context: AccountContext,revision: Long,callback: (Boolean)->Unit) {
        if(closed) { main.post { callback(false) }; return }
        enqueue {
            val okay=runCatching { retire(checkpoint,episodes,context,revision) }.getOrDefault(false)
            main.post { callback(okay) }
        }
    }
    private fun retire(checkpoint: HistoryCheckpoint,episodes: List<String>,context: AccountContext,revision: Long): Boolean {
        val id=checkpoint.progress.series.id
        if(!active(context)) return false
        if(episodes.size!=checkpoint.episodeCount || checkpoint.bookType !in 2..5) return false
        val winner=ProgressMergePlanner.normalize(checkpoint.progress,episodes) ?: return false
        if(!intents.historyAllowed(context,id,revision)) return false
        val key=owner(context.session)
        val rows=load(key)
        rows[id]?.let { queued ->
            val comparison=ProgressMergePlanner.compare(queued.progress,winner,episodes) ?: return false
            if(comparison>0) return false
            rows.remove(id); manuals[key]?.remove(id); accepted[key]?.remove(id); storedRevisions[key]?.remove(id)
            if(!persist(key,rows)) return false
        }
        return persistFloor(key,id,revision,winner,episodes,checkpoint.bookType,checkpoint.episodeCount)
    }
    fun flush() {
        if(mergeReserved) return
        val context=repository.captureAccount() ?: return
        if(!active(context)) return
        enqueue {
            if(closed || mergeReserved) return@enqueue
            scheduled?.cancel(false); scheduled=null; schedule(context,0)
        }
    }
    private fun schedule(context: AccountContext,delay: Long=15_000) {
        if(mergeReserved || !active(context)) return
        if(scheduled!=null && scheduledContext?.let { repository.isCurrent(it) }!=true) { scheduled?.cancel(false); scheduled=null }
        if(scheduled!=null) return
        scheduledContext=context
        scheduled=worker.schedule({ scheduled=null; scheduledContext=null; drain(context) },delay,TimeUnit.MILLISECONDS)
    }
    private fun drain(context: AccountContext) {
        if(mergeReserved || !active(context)) return
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
        val manualIds=manuals[key]?.keys?.toSet().orEmpty()
        fun isManual(id: String)=id in manualIds
        val hasManual=rows.keys.any { isManual(it) && permitted(it) }
        val hasOrdinary=rows.keys.any { !isManual(it) && permitted(it) }
        if(!hasManual && !hasOrdinary) return
        val attempts=legacyAttempts.getOrPut(key) { mutableMapOf() }
        attempts.keys.retainAll(rows.keys)
        if(!hasManual && HistorySyncPlanner.select(rows.values.filter { !isManual(it.progress.series.id) && permitted(it.progress.series.id) },attempts,System.currentTimeMillis()).isEmpty()) { schedule(context,60_000); return }
        try {
            run {
                val remote=repository.accountHistory(requireAllTypes=true,session=session).history.associateBy { it.series.id }
                if(!active(context) || mergeReserved || rows.keys.none { permitted(it) }) return
                val progress=repository.accountVideoProgress(session).associateBy { it.seriesId }
                if(!active(context) || mergeReserved || rows.keys.none { permitted(it) }) return
                // Manual slots drain before normal top-20 selection, independent of watch-time order.
                rows.values.filter { isManual(it.progress.series.id) && permitted(it.progress.series.id) }.forEach { row ->
                    if(!active(context) || mergeReserved) return
                    drainManual(context,key,session,row,remote[row.progress.series.id],progress[row.progress.series.id],rows,
                        { id -> permitted(id) },{ id -> durable(id) })
                }
                if(!active(context) || mergeReserved) return
                rows.entries.removeAll { (id,row) ->
                    !isManual(id) && permitted(id) && (maxOf(remote[id]?.readAt ?: 0L,progress[id]?.readAt ?: 0L)>row.progress.updatedAt ||
                        ((remote[id]?.readAt ?: 0L)>=row.progress.updatedAt && (progress[id]?.readAt ?: 0L)>=row.progress.updatedAt))
                }
                rows.replaceAll { id,row -> if(!isManual(id) && permitted(id)) row.copy(bookType=remote[id]?.series?.bookType ?: row.bookType) else row }
                if(!active(context) || mergeReserved) return
                check(persist(key,rows))
            }
            // Send known records first; one broken legacy lookup must not starve valid progress.
            HistorySyncPlanner.select(rows.values.filter { !isManual(it.progress.series.id) && permitted(it.progress.series.id) },attempts,System.currentTimeMillis()).chunked(20).forEach { batch ->
                if(mergeReserved || !active(context)) return
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
                if(firstRows.isNotEmpty() && active(context) && !mergeReserved) repository.uploadHistory(session,firstRows)
                val second=first.filter { durable(it.first.progress.series.id) }.filter { permitted(it.first.progress.series.id) }
                val secondRows=second.map { it.second }
                if(secondRows.isNotEmpty() && active(context) && !mergeReserved) repository.uploadVideoProgress(session,secondRows)
                if(!active(context) || mergeReserved) return
                second.forEach { (original,_) ->
                    val id=original.progress.series.id
                    if(permitted(id) && rows[id]==original && accepted[key]?.get(id).let { it==null || it==tokens[id] }) {
                        rows.remove(id)
                        accepted[key]?.remove(id)
                    }
                }
                if(!active(context) || mergeReserved) return
                check(persist(key,rows))
            }
            attempts.keys.retainAll(rows.keys)
            if(rows.isNotEmpty()) {
                Log.i("HongguoTV","Account history sync pending: remaining records")
                val remainingManual=rows.keys.any { isManual(it) && permitted(it) }
                val remaining=rows.values.filter { !isManual(it.progress.series.id) && permitted(it.progress.series.id) }
                schedule(context,if(remainingManual) 15_000 else if(HistorySyncPlanner.select(remaining,attempts,System.currentTimeMillis()).isEmpty()) 60_000 else 15_000)
            } else Log.i("HongguoTV","Account history sync completed")
        } catch(problem: Exception) {
            Log.w("HongguoTV","Account history sync pending: "+problem.javaClass.simpleName)
            schedule(context,60_000)
        }
    }
    /** One manual row: validate both cloud stores, send only missing/behind endpoints, ack each durably. */
    private fun drainManual(context: AccountContext,key: String,session: AuthSession,row: HistoryCheckpoint,
        remoteHistory: AccountHistoryItem?,remoteProgress: AccountRepository.VideoProgress?,rows: MutableMap<String,HistoryCheckpoint>,
        permitted: (String)->Boolean,durable: (String)->Boolean) {
        val id=row.progress.series.id
        val meta=manuals[key]?.get(id) ?: return
        val catalog=meta.catalog
        val local=ProgressMergePlanner.normalize(row.progress,catalog) ?: return
        val historyRemote=remoteHistory?.let { BackupProgress(local.series,it.episodeId,it.episodeIndex,it.position,it.duration,false,it.readAt) }
        val progressRemote=remoteProgress?.let { BackupProgress(local.series,it.episodeId,it.episodeIndex,it.position,local.duration,false,it.readAt) }
        val decision=ProgressMergePlanner.plan(local,listOfNotNull(historyRemote,progressRemote),catalog)
        if(decision.direction==ProgressMergePlanner.Direction.IMPORT) {
            if(rows[id]==row && permitted(id)) {
                rows.remove(id); manuals[key]?.remove(id); accepted[key]?.remove(id); storedRevisions[key]?.remove(id); persist(key,rows)
            }
            return
        }
        if(decision.direction==ProgressMergePlanner.Direction.SKIP) return
        val historyComparison=historyRemote?.let { ProgressMergePlanner.compare(local,it,catalog) }
        val progressComparison=progressRemote?.let { ProgressMergePlanner.compare(local,it,catalog) }
        if(historyRemote!=null && historyComparison==null) return
        if(progressRemote!=null && progressComparison==null) return
        var historyDone=meta.historyAcked || historyComparison==0
        var progressDone=meta.progressAcked || progressComparison==0
        val historySend=!historyDone && (historyRemote==null || (historyComparison ?: 0)>0)
        val progressSend=!progressDone && (progressRemote==null || (progressComparison ?: 0)>0)
        if(!historySend && !progressSend) {
            if(historyDone && progressDone) {
                rows.remove(id); manuals[key]?.remove(id); accepted[key]?.remove(id); storedRevisions[key]?.remove(id); persist(key,rows)
            }
            return
        }
        val submitted=maxOf(meta.submittedAt,System.currentTimeMillis(),remoteHistory?.readAt ?: 0L,remoteProgress?.readAt ?: 0L)
        var current=meta.copy(submittedAt=submitted)
        manuals[key]?.set(id,current)
        if(!persist(key,rows)) return
        val wire=row.copy(progress=local.copy(updatedAt=submitted))
        if(historySend) {
            if(!active(context) || !permitted(id) || !durable(id) || rows[id]!=row) return
            try { repository.uploadHistory(session,listOf(wire)) } catch(_: Exception) { return }
            historyDone=true
            current=current.copy(historyAcked=true)
            manuals[key]?.set(id,current)
            if(!persist(key,rows)) return
        }
        if(!active(context) || !permitted(id) || !durable(id) || rows[id]!=row) return
        if(progressSend) {
            try { repository.uploadVideoProgress(session,listOf(wire)) } catch(_: Exception) { return }
            progressDone=true
            current=current.copy(progressAcked=true)
            manuals[key]?.set(id,current)
            if(!persist(key,rows)) return
        }
        if(historyDone && progressDone) {
            rows.remove(id); manuals[key]?.remove(id); accepted[key]?.remove(id); storedRevisions[key]?.remove(id); persist(key,rows)
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
