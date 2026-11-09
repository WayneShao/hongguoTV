package com.hongguotv.core

/** A valid response had no usable video variant. Retrying transport cannot fix it. */
class NoCompatibleStreamException : java.io.IOException("本集未提供可用的视频编码或清晰度，请返回选集")

class PlaybackModelException(message: String): java.io.IOException(message)

object PlaybackFailure {
    fun terminal(error: Throwable): Boolean = generateSequence(error) { it.cause }.any {
        it is NoCompatibleStreamException || it is PlaybackModelException || it is PlaybackBudgetExceededException ||
            it is PlaybackDeadlineException ||
            (it is java.io.InterruptedIOException && it.message=="请求已取消")
    }
    fun lowerQualities(info: StreamInfo?): List<Int> = info?.availableQualities.orEmpty()
        .filter { it > 0 && info != null && info.selectedQuality > 0 && it < info.selectedQuality }
        .distinct().sortedDescending()
}
