package com.hongguotv.core

import java.io.Closeable
import java.io.IOException
import java.util.concurrent.*

/** Owns a preparation until claim transfers the result, including completion/cancellation races. */
class PendingPreparation<T: Closeable>(executor: Executor,load: (()->Boolean)->T): Closeable {
    private val scope=RequestScope()
    private var closed=false
    private var result: T?=null
    private var consumed=false
    @Volatile private var claimed=false
    fun claim() { claimed=true }
    private val task=FutureTask<Unit> {
        val value=scope.run { load { claimed } }
        synchronized(this) { if(closed) value.close() else result=value }
    }
    init { executor.execute(task) }
    val done get()=task.isDone
    @Synchronized fun peek(): T?=result
    fun await(): T {
        try { task.get() }
        catch(e: InterruptedException) { close(); Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消") }
        catch(e: ExecutionException) { throw (e.cause as? Exception ?: IOException("预加载失败",e)) }
        catch(e: CancellationException) { throw IOException("预加载已取消",e) }
        return synchronized(this) {
            if(closed || consumed) throw IOException("预加载已取消")
            val value=result ?: throw IOException("预加载未返回资源")
            result=null; consumed=true; value
        }
    }
    override fun close() {
        synchronized(this) { if(closed) return; closed=true; result?.close(); result=null }
        scope.cancel(); task.cancel(true)
    }
}

/** Serial preparation until complete episodes span five minutes, with a byte budget. */
class RollingPrefetch<T: Closeable>(
    private val executor: Executor,
    private val load: (String,()->Boolean,Long)->T,
    private val duration: (T)->Long?,
    private val bytes: (T)->Long,
    private val clock: ()->Long,
    private val budget: Long=128L*1024*1024,
    private val horizonMs: Long=300_000,
    private val ttl: Long=3_600_000,
    private val memory: (T)->Long={0L},
    private val memoryBudget: Long=32L*1024*1024,
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
