package com.hongguotv.core

/** One foreground episode, including endpoint fallback and automatic recovery. */
class PlaybackBudgetExceededException: java.io.IOException("本集播放地址请求已达上限，请稍后手动重试")
/** 本次地址获取超过绝对截止时间；再次请求才重新计时。 */
class PlaybackDeadlineException: java.io.IOException("本集播放地址获取超时，请稍后手动重试")
class PlaybackRequestBudget(private val maxRequests: Int=8, private val maxRetries: Int=2) {
    private var requests=0
    private var retries=0
    private var codecAttempted=false
    @Synchronized fun claimCodecFallback(): Boolean {
        if(codecAttempted || requests>=maxRequests) return false
        codecAttempted=true;return true
    }
    private var tabAttempted=false
    @Synchronized fun claimTabFallback(): Boolean {
        if(tabAttempted || requests>=maxRequests) return false
        tabAttempted=true;return true
    }
    @Synchronized fun request() { if(requests>=maxRequests) throw PlaybackBudgetExceededException();requests++ }
    @Synchronized fun retry(): Boolean { if(retries>=maxRetries || requests>=maxRequests) return false; retries++;return true }
    fun <T> run(block: ()->T): T {
        val old=current.get();current.set(this)
        // 每次获取重新计时，复用的预算只保留请求与重试计数。
        try { return RequestScope.withDeadline(ACQUISITION_TIMEOUT_MILLIS) { block() } }
        finally { if(old==null) current.remove() else current.set(old) }
    }
    companion object {
        internal val current=ThreadLocal<PlaybackRequestBudget>()
        /** 单次播放地址获取的绝对总时限，覆盖接口、备用资源、注册与等待。 */
        const val ACQUISITION_TIMEOUT_MILLIS = 8_000L
    }
}
