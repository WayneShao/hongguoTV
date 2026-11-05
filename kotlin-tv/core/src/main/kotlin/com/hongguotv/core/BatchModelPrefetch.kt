package com.hongguotv.core

import java.io.Closeable
import java.util.concurrent.Executor

/** Bounded speculative metadata fetch; fetch must use RequestScope and never register devices. */
class BatchModelPrefetch(
    private val executor: Executor,
    private val gate: PreloadGate,
    private val identity: () -> String?,
    private val quality: () -> Int,
    private val fetch: (String,List<String>,Int) -> Map<String,StreamInfo>,
    val cache: BatchModelCache = BatchModelCache(),
    private val clock: () -> Long = { System.nanoTime()/1_000_000 },
) : Closeable {
    private var enabled=false
    private var closed=false
    private var ids=emptyList<String>()
    private var context: Pair<String?,Int>?=null
    private var running: RequestScope?=null
    private var generation=0L
    private var nextAttempt=0L
    private var attemptedUntil=0L
    @Synchronized fun configure(settings: PreloadSettings) {
        closed=false
        enabled=settings.enabled && settings.batchModels
        if(!enabled) reset()
    }
    @Synchronized fun update(keys: List<String>) {
        val next=keys.map { it.substringBefore(':' ) }.filter { it.matches(Regex("[0-9]{1,30}")) }.distinct().take(3)
        val ctx=identity() to quality()
        if(ids!=next || context!=ctx) { reset(clearCache=context!=ctx);ids=next;context=ctx }
    }
    private fun reset(clearCache: Boolean=true) { running?.cancel();running=null;generation++;if(clearCache) cache.clear();attemptedUntil=0;if(clearCache) nextAttempt=0 }
    @Synchronized fun pump(): Boolean {
        val ctx=identity() to quality()
        if(context!=ctx) {reset();context=ctx}
        val owner=ctx.first ?: return false
        if(closed || !enabled || ids.isEmpty() || running!=null || clock()<attemptedUntil || clock()<nextAttempt || !gate.allowed()) return false
        val request=RequestScope();val token=generation;val requested=ids.toList();val q=ctx.second
        running=request
        try { executor.execute {
            try {
                val result=request.run {
                    RequestScope.ensureActive()
                    if(!gate.allowed()) throw java.io.InterruptedIOException()
                    fetch(owner,requested,q)
                }
                synchronized(this) {
                    if(token==generation && running===request && identity()==owner && quality()==q && gate.allowed()) {
                        result.filterKeys { it in requested }.forEach { (id,value) -> cache.put(owner,id,q,value) }
                        attemptedUntil=clock()+120_000
                    }
                }
            } catch(_: Exception) {
                synchronized(this) { if(token==generation) nextAttempt=clock()+30_000 }
            } finally { synchronized(this) {if(running===request) running=null} }
        } } catch(_: RuntimeException) { running=null;nextAttempt=clock()+30_000;return false }
        return true
    }
    @Synchronized fun yieldToForeground() { running?.cancel();running=null;generation++;nextAttempt=maxOf(nextAttempt,clock()+4_000) }
    @Synchronized override fun close() {closed=true;reset()}
}
