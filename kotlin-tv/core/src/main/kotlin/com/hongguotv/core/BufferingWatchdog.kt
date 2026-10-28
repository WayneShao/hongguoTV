package com.hongguotv.core

/** Monotonic foreground-only deadline. Long polling gaps reset the clock. */
class BufferingWatchdog(private val timeoutMs: Long=30_000) {
    private var since: Long?=null
    private var lastPoll: Long?=null
    private var lastBytes=0L
    private var lastBuffer=0L
    fun reset() { since=null; lastPoll=null; lastBytes=0; lastBuffer=0 }
    fun poll(now: Long,buffering: Boolean,bytes: Long=0,bufferPosition: Long=0): Boolean {
        if(!buffering) { reset(); return false }
        val previous=lastPoll
        if(previous==null || now<previous || now-previous>3_000 || bytes>lastBytes || bufferPosition>lastBuffer) since=now
        lastBytes=bytes;lastBuffer=bufferPosition
        lastPoll=now
        if(now-(since ?: now)<timeoutMs) return false
        reset(); return true
    }
}
