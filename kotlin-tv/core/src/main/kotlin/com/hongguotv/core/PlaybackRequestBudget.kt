package com.hongguotv.core

/** One foreground episode, including endpoint fallback and automatic recovery. */
class PlaybackBudgetExceededException: java.io.IOException("本集播放地址请求已达上限，请稍后手动重试")
class PlaybackRequestBudget(private val maxRequests: Int=8, private val maxRetries: Int=2) {
    private var requests=0
    private var retries=0
    @Synchronized fun request() { if(requests>=maxRequests) throw PlaybackBudgetExceededException();requests++ }
    @Synchronized fun retry(): Boolean { if(retries>=maxRetries || requests>=maxRequests) return false; retries++;return true }
    fun <T> run(block: ()->T): T {
        val old=current.get();current.set(this)
        try { return block() } finally { if(old==null) current.remove() else current.set(old) }
    }
    companion object { internal val current=ThreadLocal<PlaybackRequestBudget>() }
}
