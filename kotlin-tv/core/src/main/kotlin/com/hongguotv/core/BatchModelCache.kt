package com.hongguotv.core

/** Metadata only. Identity is an opaque fingerprint, never logged. */
class BatchModelCache(private val clock: () -> Long = { System.nanoTime()/1_000_000 }) {
    private data class Entry(val stream: StreamInfo, val expires: Long)
    private val entries = linkedMapOf<Triple<String,String,Int>,Entry>()
    @Synchronized fun clear() = entries.clear()
    @Synchronized fun put(identity: String, id: String, quality: Int, stream: StreamInfo) {
        prune()
        val key=Triple(identity,id,quality)
        entries.remove(key)
        entries[key]=Entry(stream,clock()+120_000)
        while(entries.size>6) entries.remove(entries.keys.first())
    }
    @Synchronized fun take(identity: String,id: String,quality: Int): StreamInfo? {
        prune()
        return entries.remove(Triple(identity,id,quality))?.stream
    }
    @Synchronized fun invalidate(identity: String,id: String,quality: Int) { entries.remove(Triple(identity,id,quality)) }
    private fun prune() { val now=clock(); entries.entries.removeAll { it.value.expires<=now } }
}
