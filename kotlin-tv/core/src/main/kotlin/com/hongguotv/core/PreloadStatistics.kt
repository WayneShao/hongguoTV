package com.hongguotv.core

/** Aggregate process-session telemetry; never stores episode or user identifiers. */
class PreloadStatistics {
    companion object { val session=PreloadStatistics() }
    data class Snapshot(val uncached: Int,val partial: Int,val complete: Int,val natural: Int,
        val renderedStarts: Int,val startupMs: Long,val bufferingCount: Int,val bufferingMs: Long)
    private var uncached=0;private var partial=0;private var complete=0;private var natural=0
    private var renderedStarts=0;private var startupMs=0L;private var bufferingCount=0;private var bufferingMs=0L
    @Synchronized fun acquired(cacheKind: Int) { when(cacheKind) { 2 -> complete++;1 -> partial++;else -> uncached++ } }
    @Synchronized fun naturalTransition() { natural++ }
    @Synchronized internal fun rendered(duration: Long) { renderedStarts++;startupMs+=duration.coerceAtLeast(0) }
    @Synchronized internal fun buffered(duration: Long) { bufferingCount++;bufferingMs+=duration.coerceAtLeast(0) }
    @Synchronized fun snapshot()=Snapshot(uncached,partial,complete,natural,renderedStarts,startupMs,bufferingCount,bufferingMs)
}

/** One explicit playback request. Caller supplies monotonic milliseconds. */
class PlaybackSessionMeasurement(private val statistics: PreloadStatistics,private val started: Long) {
    private var rendered=false
    private var startupEligible=true
    private var bufferingSince: Long?=null
    private var seekPending=false
    fun firstFrame(now: Long) {
        if(!rendered) { rendered=true;if(startupEligible) statistics.rendered(now-started) }
    }
    fun state(now: Long,buffering: Boolean,eligible: Boolean) {
        if(!eligible) { finishBuffer(now);return }
        if(!buffering) { finishBuffer(now);seekPending=false;return }
        if(rendered && !seekPending && bufferingSince==null) bufferingSince=now
    }
    fun interrupted(now: Long) { startupEligible=false;finishBuffer(now) }
    fun seek(now: Long) { startupEligible=false;finishBuffer(now);seekPending=true }
    fun close(now: Long) { finishBuffer(now);startupEligible=false }
    private fun finishBuffer(now: Long) {
        bufferingSince?.let { statistics.buffered(now-it) };bufferingSince=null
    }
}
