package com.hongguotv.core

import java.io.IOException

/** 空响应子情形，用于失败日志定性：空体（raw_blank/tab_raw_blank）指向网关层，其余指向服务端软空。 */
internal enum class EmptyPlaybackReason(val code: String) {
    RAW_BLANK("raw_blank"), DATA_EMPTY("data_empty"), VIDEO_MODEL_EMPTY("video_model_empty"), LIST_EMPTY("list_empty"),
    TAB_RAW_BLANK("tab_raw_blank"), TAB_DATA_EMPTY("tab_data_empty"), TAB_LIST_EMPTY("tab_list_empty"), NO_FALLBACK_API("no_fallback_api");
}
/** completeRound 为 false 时是线路冷却限制下的半轮探测，在 PlayerDeviceSource 中折半计入空轮累计。 */
internal class EmptyPlaybackException(
    val completeRound: Boolean = true,
    val reason: EmptyPlaybackReason = EmptyPlaybackReason.RAW_BLANK,
    detail: String = reason.code,
): IOException("播放接口未返回有效数据 [$detail]")
internal class PlaybackHttpException(val status: Int): IOException("播放请求失败（HTTP $status）")
/**
 * Per read budget: at most three total attempts; identity rotation is a separate transaction.
 * retryBlank=false 用于已注册 POST 线路与共享预算的 LEGACY：空白响应立即交给下一条已注册线路或
 * 候选验证，把重试额度留给不同线路；传输类瞬时失败的重试语义不变。
 */
internal class PlaybackReadRetry(
    private val route: PlaybackTrace.Route? = null,
    private val retryBlank: Boolean = true,
    private val sleep: (Long)->Unit = Thread::sleep,
) {
    fun read(block: ()->String): String {
        var transientRetried=false
        for(attempt in 0..2) {
            RequestScope.ensureActive()
            try {
                return PlaybackTrace.withAttempt(attempt+1) { block() }.also {
                    if(it.isBlank()) {
                        route?.let { route -> PlaybackTrace.blank(route) }
                        throw EmptyPlaybackException()
                    }
                }
            } catch(e: Exception) {
                RequestScope.ensureActive()
                val empty=e is EmptyPlaybackException
                val temporary=e is java.net.SocketTimeoutException || e is java.net.SocketException ||
                    e is java.net.UnknownHostException || (e is PlaybackHttpException && e.status in setOf(500,502,503,504))
                if(attempt==2 || (empty && !retryBlank) || (!empty && (!temporary || transientRetried))) throw e
                val allowed=PlaybackRequestBudget.current.get()?.retry() ?: true
                route?.let { route -> PlaybackTrace.retry(route,attempt+1,PlaybackTrace.failureOf(e),allowed) }
                if(!allowed) throw e
                if(!empty) transientRetried=true
                RequestScope.sleepWithinDeadline(if(attempt==0) 700 else 1500, sleep)
            }
        }
        error("unreachable")
    }
}
