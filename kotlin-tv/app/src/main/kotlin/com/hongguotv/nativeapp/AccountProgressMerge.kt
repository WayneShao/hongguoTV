// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import android.os.Looper
import com.hongguotv.core.*

/** One explicit operation; all HTTP and outbox work shares the existing history worker. */
internal class AccountProgressMerge(
    private val repository: AccountRepository,
    private val intents: AccountIntents,
    private val sync: AccountHistorySync,
    private val snapshot: () -> List<BackupProgress>,
    private val eligible: (AccountContext,String) -> Boolean,
    private val apply: (AccountContext,BackupProgress) -> Boolean,
    private val claim: (AccountContext,String) -> Unit,
    private val resolve: (String) -> Detail = repository::detail,
) {
    data class Result(val localUpdated: Int=0,val pending: Int=0,val skipped: Int=0,val error: String?=null) {
        fun message() = "本地更新 $localUpdated，待上报 $pending，跳过 $skipped" +
            (error?.let { "；$it" } ?: "。待上报记录将由后台提交。")
    }
    private class Operation(val context: AccountContext,val generation: Long,val done: (Result)->Unit) {
        var updated=0; var pending=0; var skipped=0
    }
    private data class Item(val id: String,val detail: Detail?,val remote: List<BackupProgress>)
    private val main=Handler(Looper.getMainLooper())
    @Volatile private var closed=false
    @Volatile private var operation: Operation?=null
    private fun current(op: Operation)=!closed && operation===op && repository.isCurrent(op.context) &&
        intents.revision(op.context)==op.generation

    fun start(context: AccountContext,done: (Result)->Unit): Boolean {
        check(Looper.myLooper()==Looper.getMainLooper())
        if(closed || operation!=null || context.owner==null || !repository.isCurrent(context) ||
            !intents.isReady() || !intents.bindHistory(context)) return false
        val op=Operation(context,intents.revision(context),done)
        val local=snapshot().toList()
        operation=op
        if(!sync.beginMerge(context) { read(op,local) }) { operation=null; return false }
        return true
    }
    private fun read(op: Operation,local: List<BackupProgress>) {
        try {
            check(current(op))
            val history=repository.accountHistory(requireAllTypes=true,session=op.context.session).history
            check(current(op))
            val progress=repository.accountVideoProgress(op.context.session)
            check(current(op))
            val ids=(local.map { it.series.id }+history.map { it.series.id }+progress.map { it.seriesId }).distinct()
            val items=ids.map { id ->
                check(current(op))
                val detail=runCatching {
                    val resolved=resolve(id)
                    if(resolved.series.id!=id || resolved.episodes.isEmpty()) return@runCatching null
                    val type=sequenceOf(resolved.series.bookType,
                        history.firstOrNull { it.series.id==id }?.series?.bookType,
                        local.firstOrNull { it.series.id==id }?.series?.bookType)
                        .filterNotNull().firstOrNull { it in 2..5 }
                        ?: repository.historyBookType(resolved.series)
                    if(type !in 2..5) null else resolved.copy(series=resolved.series.copy(bookType=type))
                }.getOrNull()
                check(current(op))
                val rows=if(detail==null) emptyList() else {
                    history.filter { it.series.id==id }.map {
                        BackupProgress(detail.series,it.episodeId,it.episodeIndex,it.position,it.duration,false,it.readAt)
                    } + progress.filter { it.seriesId==id }.map { p ->
                        val duration=history.firstOrNull { it.series.id==id && it.episodeId==p.episodeId }?.duration ?: 0L
                        BackupProgress(detail.series,p.episodeId,p.episodeIndex,p.position,duration,false,p.readAt)
                    }
                }
                Item(id,detail,rows)
            }
            main.post { process(op,items,0) }
        } catch(_: Exception) {
            main.post { finish(op,"账号或同步状态已变化，或云端读取失败，请重试。") }
        }
    }
    private fun process(op: Operation,items: List<Item>,index: Int) {
        if(!current(op)) { finish(op,"账号或观看记录已变化，请重新合并。"); return }
        if(index==items.size) { finish(op); return }
        val item=items[index]
        fun next() { process(op,items,index+1) }
        val detail=item.detail
        if(detail==null || !eligible(op.context,item.id) || intents.suppressHistory(op.context,item.id)) {
            op.skipped++; next(); return
        }
        val local=snapshot().firstOrNull { it.series.id==item.id }
        val decision=ProgressMergePlanner.plan(local,item.remote,detail.episodes)
        if(decision.direction==ProgressMergePlanner.Direction.SKIP) { op.skipped++; next(); return }
        val winner=decision.progress ?: local?.let { ProgressMergePlanner.normalize(it,detail.episodes) }
        if(winner==null) { op.skipped++; next(); return }
        val token=intents.historyRevision(op.context,item.id)
        val checkpoint=HistoryCheckpoint(winner,detail.series.bookType,detail.episodes.size)
        val accepted: (Boolean)->Unit = accepted@ { okay ->
            if(!current(op)) { finish(op,"账号或观看记录已变化，请重新合并。"); return@accepted }
            if(!okay || !eligible(op.context,item.id) || !intents.historyAllowed(op.context,item.id,token)) {
                op.skipped++; next(); return@accepted
            }
            // A guest/local edit can occur without an account-generation change while disk commits.
            if(snapshot().firstOrNull { it.series.id==item.id }!=local) {
                process(op,items,index); return@accepted
            }
            if(decision.direction==ProgressMergePlanner.Direction.IMPORT && apply(op.context,winner)) op.updated++
            claim(op.context,item.id)
            if(decision.direction==ProgressMergePlanner.Direction.UPLOAD) op.pending++
            next()
        }
        if(decision.direction==ProgressMergePlanner.Direction.UPLOAD)
            sync.offerMerge(checkpoint,detail.episodes,op.context,token,accepted)
        else sync.retireMerge(checkpoint,detail.episodes,op.context,token,accepted)
    }
    private fun finish(op: Operation,error: String?=null) {
        if(operation!==op) return
        operation=null
        sync.endMerge(op.context)
        if(!closed) op.done(Result(op.updated,op.pending,op.skipped,error))
    }
    fun close() {
        closed=true
        operation?.let { operation=null; sync.endMerge(it.context) }
    }
}
