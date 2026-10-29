package com.hongguotv.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.*

/** Claim notification is serialized with registration, including claims before preparation completes. */
class ClaimSignal : () -> Boolean {
    @Volatile private var claimed=false
    private var callback: (() -> Unit)?=null
    private var disposed=false
    override fun invoke()=claimed
    @Synchronized fun onClaim(action: () -> Unit) {
        if(disposed) return
        if(claimed) action() else callback=action
    }
    @Synchronized internal fun claim() {
        if(claimed || disposed) return
        claimed=true
        callback?.invoke()
        callback=null
    }
    @Synchronized internal fun clear() { callback=null; disposed=true }
}

/** Owns a preparation until claim transfers the result, including completion/cancellation races. */
class PendingPreparation<T: Closeable>(executor: Executor,retained: T?=null,load: (ClaimSignal)->T): Closeable {
    private val scope=RequestScope()
    private var closed=false
    private var result: T?=retained
    private var consumed=false
    private val claimed=ClaimSignal()
    fun claim() { claimed.claim() }
    private val task=FutureTask<Unit> {
        val value=try { scope.run { load(claimed) } } catch(e: Throwable) {
            synchronized(this) { result?.close(); result=null }
            claimed.clear()
            throw e
        }
        synchronized(this) { if(closed) value.close() else result=value }
    }
    init { try { executor.execute(task) } catch(error: RuntimeException) { result?.close(); result=null; claimed.clear(); throw error } }
    val done get()=task.isDone
    @Synchronized fun peek(): T?=result
    fun await(timeoutMs: Long?=null): T {
        try { if(timeoutMs==null) task.get() else task.get(timeoutMs,TimeUnit.MILLISECONDS) }
        catch(e: TimeoutException) { close(); throw e }
        catch(e: InterruptedException) { close(); Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消") }
        catch(e: ExecutionException) { throw (e.cause as? Exception ?: IOException("预加载失败",e)) }
        catch(e: CancellationException) { throw IOException("预加载已取消",e) }
        return synchronized(this) {
            if(closed || consumed) throw IOException("预加载已取消")
            val value=result ?: throw IOException("预加载未返回资源")
            result=null; consumed=true; claimed.clear(); value
        }
    }
    override fun close() {
        synchronized(this) { if(closed) return; closed=true; result?.close(); result=null }
        claimed.clear(); scope.cancel(); task.cancel(true)
    }
}

/** Serial preparation until complete episodes span five minutes, with a byte budget. */
class RollingPrefetch<T: Closeable>(
    private val executor: Executor,
    private val load: (String,ClaimSignal,Long)->T,
    private val duration: (T)->Long?,
    private val bytes: (T)->Long,
    private val clock: ()->Long,
    private val budget: Long=128L*1024*1024,
    private val horizonMs: Long=300_000,
    private val ttl: Long=3_600_000,
    private val memory: (T)->Long={0L},
    private val memoryBudget: Long=32L*1024*1024,
    private val resumable: (T)->Boolean={false},
    private val resume: ((T,ClaimSignal,Long)->T)?=null,
    private val resumeAllowed: ()->Boolean={true},
): Closeable {
    private data class Entry<T: Closeable>(val task: PendingPreparation<T>,val created: Long)
    private val entries=linkedMapOf<String,Entry<T>>()
    private val failures=mutableMapOf<String,Pair<Int,Long>>()
    private var desired=emptyList<String>()
    private var enabled=true
    private var timeTarget=horizonMs
    private var countTarget: Int?=null
    @Synchronized fun configure(config: PreloadSettings) {
        enabled=config.enabled
        if(!enabled) close()
        timeTarget=config.minutes*60_000L
        countTarget=if(config.mode==PreloadMode.EPISODES) config.episodes else null
    }
    @Synchronized fun update(keys: List<String>) {
        desired=if(enabled) keys.distinct().take(countTarget ?: 100) else emptyList()
        entries.keys.toList().filter { it !in desired }.forEach { entries.remove(it)?.task?.close() }
        failures.keys.retainAll(desired.toSet())
    }
    @Synchronized fun pump(): Boolean {
        if(!enabled) return false
        val now=clock()
        entries.keys.toList().forEach { key ->
            val entry=entries.getValue(key)
            if(now-entry.created>ttl || (entry.task.done && entry.task.peek()==null)) {
                entry.task.close(); entries.remove(key)
                val count=failures[key]?.first ?: 0; failures[key]=(count+1) to now
            }
        }
        var used=entries.values.sumOf { it.task.peek()?.let(bytes) ?: 0L }
        desired.asReversed().forEach { key ->
            if(used>budget) entries.remove(key)?.let { used-=it.task.peek()?.let(bytes) ?: 0L; it.task.close(); failures[key]=3 to now }
        }
        if(entries.values.any { !it.task.done }) return true
        val usedMemory=entries.values.sumOf { it.task.peek()?.let(memory) ?: 0L }
        if(used>=budget || usedMemory>=memoryBudget) return false
        // Resume verified partial bytes before duration/count accounting. Unknown duration must
        // not strand an incomplete entry forever. The caller supplies its traffic gate.
        val partial=desired.firstOrNull { key -> entries[key]?.task?.peek()?.let(resumable)==true }
        if(partial!=null && resume!=null) {
            if(!resumeAllowed()) return true
            val old=entries.getValue(partial)
            val value=old.task.await()
            val allowance=budget-used+bytes(value)
            val memoryAllowance=memoryBudget-usedMemory+memory(value)
            entries[partial]=Entry(PendingPreparation(executor,value) { claimed ->
                try {
                    val resumed=resume.invoke(value,claimed,allowance)
                    if(bytes(resumed)>allowance || memory(resumed)>memoryAllowance) {
                        resumed.close(); throw IOException("预加载资源超过缓存上限")
                    }
                    resumed
                } catch(e: Exception) { value.close(); throw e }
            },old.created)
            return true
        }
        if(countTarget!=null && entries.size>=minOf(countTarget!!,desired.size)) return false
        var ahead=0L
        for(key in desired) {
            val value=entries[key]?.task?.peek() ?: continue
            if(countTarget!=null) continue
            val length=duration(value) ?: return false // Unknown duration: do not guess.
            ahead+=length
            if(ahead>=timeTarget) return false
        }
        val next=desired.firstOrNull { key -> key !in entries && failures[key].let { it==null || (it.first<3 && now-it.second>=15_000) } } ?: return desired.any { it !in entries && (failures[it]?.first ?: 0)<3 }
        entries[next]=Entry(PendingPreparation(executor) { claimed ->
            val value=load(next,claimed,budget-used)
            if(bytes(value)>budget-used || memory(value)>memoryBudget-usedMemory) { value.close(); throw IOException("预加载资源超过缓存上限") }
            value
        },now)
        return true
    }
    @Synchronized fun claim(key: String): PendingPreparation<T>? {
        val entry=entries.remove(key) ?: return null
        if(clock()-entry.created !in 0..ttl) { entry.task.close(); return null }
        entry.task.claim()
        return entry.task
    }
    @Synchronized fun count()=entries.size
    @Synchronized override fun close() { entries.values.forEach { it.task.close() }; entries.clear(); failures.clear(); desired=emptyList() }
}
