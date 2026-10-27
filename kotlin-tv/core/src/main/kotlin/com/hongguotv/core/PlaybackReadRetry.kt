package com.hongguotv.core

import java.io.IOException

internal class EmptyPlaybackException: IOException("播放接口未返回有效数据，请稍后再试")
internal class PlaybackHttpException(val status: Int): IOException("播放请求失败（HTTP $status）")
/** Per read budget: at most three total attempts; identity rotation is a separate transaction. */
internal class PlaybackReadRetry(private val sleep: (Long)->Unit = Thread::sleep) {
    fun read(block: ()->String): String {
        var transientRetried=false
        for(attempt in 0..2) {
            RequestScope.ensureActive()
            try {
                return block().also { if(it.isBlank()) throw EmptyPlaybackException() }
            } catch(e: Exception) {
                RequestScope.ensureActive()
                val empty=e is EmptyPlaybackException
                val temporary=e is java.net.SocketTimeoutException || e is java.net.SocketException ||
                    e is java.net.UnknownHostException || (e is PlaybackHttpException && e.status in setOf(500,502,503,504))
                if(attempt==2 || (!empty && (!temporary || transientRetried))) throw e
                if(!empty) transientRetried=true
                try { sleep(if(attempt==0) 700 else 1500) }
                catch(e: InterruptedException) { Thread.currentThread().interrupt(); throw java.io.InterruptedIOException("请求已取消") }
            }
        }
        error("unreachable")
    }
}
