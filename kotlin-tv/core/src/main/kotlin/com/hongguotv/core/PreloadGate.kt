package com.hongguotv.core

import java.io.IOException
class VideoHttpException(val status: Int,val retryAfterSeconds: Long?=null): IOException("视频请求失败（HTTP $status）")
class VideoResourceChangedException: IOException("视频资源已变化，请重试")
/** Foreground demand, hysteresis and cooldown share one monotonic clock. */
class PreloadGate(private val clock: ()->Long={System.nanoTime()/1_000_000}) {
    private var high=false
    private var playing=false
    private var readers=0
    private var lastRead=Long.MIN_VALUE/2
    private var cooldown=0L
    private var chunkReady=0L
    @Volatile var onDemand: ()->Unit={}
    @Synchronized fun update(active: Boolean,bufferMs: Long,remainingMs: Long) {
        playing=active
        val remaining=remainingMs.coerceAtLeast(1)
        if(!active || bufferMs<minOf(8000,remaining)) high=false
        else if(bufferMs>=minOf(20000,remaining)) high=true
    }
    fun networkStarted() { synchronized(this) {readers++;lastRead=clock()};onDemand() }
    @Synchronized fun networkFinished() { readers=(readers-1).coerceAtLeast(0);lastRead=clock() }
    @Synchronized fun allowed(): Boolean = playing && high && readers==0 && clock()-lastRead>=4000 && clock()>=cooldown && clock()>=chunkReady
    @Synchronized fun chunkFinished() {chunkReady=clock()+300}
    @Synchronized fun failed(error: Throwable) {
        val http=generateSequence(error){it.cause}.filterIsInstance<VideoHttpException>().firstOrNull()
        val throttled=http?.status==429 || generateSequence(error){it.cause}.any { it is PlaybackHttpException && it.status==429 }
        val delay=if(throttled) maxOf(120_000L,(http?.retryAfterSeconds ?: 0).coerceIn(0,600)*1000) else 30_000L
        cooldown=maxOf(cooldown,clock()+delay);high=false
    }
    fun await(claimed: ()->Boolean) {
        while(!claimed() && !allowed()) {
            RequestScope.ensureActive()
            try {Thread.sleep(100)} catch(e: InterruptedException) {Thread.currentThread().interrupt();throw java.io.InterruptedIOException("请求已取消")}
        }
    }
}
object PlaybackRecoveryPolicy {
    fun needsNewAddress(error: Throwable): Boolean = generateSequence(error){it.cause}.any {
        it is VideoResourceChangedException || (it is VideoHttpException && it.status in setOf(401,403,404,410))
    }
}
