package com.hongguotv.core

/** Monotonic foreground-only deadline. Long polling gaps reset the clock. */
class BufferingWatchdog(private val timeoutMs: Long=30_000) {
    private var since: Long?=null
    private var lastPoll: Long?=null
    fun reset() { since=null; lastPoll=null }
    fun poll(now: Long,buffering: Boolean): Boolean {
        if(!buffering) { reset(); return false }
        val previous=lastPoll
        if(previous==null || now<previous || now-previous>3_000) since=now
        lastPoll=now
        if(now-(since ?: now)<timeoutMs) return false
        reset(); return true
    }
}
