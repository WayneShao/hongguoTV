package com.hongguotv.core

/** One foreground episode, including endpoint fallback and automatic recovery. */
class PlaybackBudgetExceededException: java.io.IOException("本集播放地址请求已达上限，请稍后手动重试")
/** 本次地址获取超过绝对截止时间；再次请求才重新计时。 */
class PlaybackDeadlineException: java.io.IOException("本集播放地址获取超时，请稍后手动重试")
class PlaybackRequestBudget(
    private val maxRequests: Int=8,
    private val maxRetries: Int=2,
    /** 本次获取的总时限；默认值即线上语义，仅供确定性测试注入。 */
    private val timeoutMillis: Long=ACQUISITION_TIMEOUT_MILLIS,
) {
    private var requests=0
    private var retries=0
    private var codecAttempted=false
    /** Stable ephemeral correlation id for every stream() invocation reusing this budget. */
    internal val traceOrdinal: Long by lazy { PlaybackTrace.nextOrdinal() }
    internal data class TraceSnapshot(val requests: Int, val retries: Int)
    @Synchronized internal fun traceSnapshot(): TraceSnapshot = TraceSnapshot(requests,retries)
    @Synchronized fun claimCodecFallback(): Boolean {
        if(codecAttempted || requests>=maxRequests) return false
        codecAttempted=true;return true
    }
    private var tabAttempted=false
    @Synchronized fun claimTabFallback(): Boolean {
        if(tabAttempted || requests>=maxRequests) return false
        tabAttempted=true;return true
    }
    /** 只读检查；已有可播结果时先问它，避免明知会超限还发起一次必败的可选请求。 */
    @Synchronized fun canRequest(): Boolean = requests < maxRequests
    /** 只读预检：后续一次事务需要 count 个请求（新身份注册+模型验证），不足时绝不先花掉其中一个。 */
    @Synchronized fun canReserve(count: Int): Boolean = requests + count <= maxRequests
    @Synchronized fun request() { if(requests>=maxRequests) throw PlaybackBudgetExceededException();requests++ }
    @Synchronized fun retry(): Boolean { if(retries>=maxRetries || requests>=maxRequests) return false; retries++;return true }
    private var legacyEmptySeen=false
    /** LEGACY 用固定身份，同一预算内已给出确定性空结果后重复请求只会浪费下一次获取的预算。 */
    @Synchronized internal fun markLegacyEmpty() { legacyEmptySeen=true }
    @Synchronized internal fun legacyEmpty(): Boolean = legacyEmptySeen
    fun <T> run(block: ()->T): T {
        val old=current.get();current.set(this)
        // 每次获取重新计时，复用的预算只保留请求与重试计数。
        try { return RequestScope.withDeadline(timeoutMillis) { block() } }
        finally { if(old==null) current.remove() else current.set(old) }
    }
    companion object {
        internal val current=ThreadLocal<PlaybackRequestBudget>()
        /** 单次播放地址获取的绝对总时限，覆盖接口、备用资源、注册与等待。 */
        const val ACQUISITION_TIMEOUT_MILLIS = 8_000L
    }
}
