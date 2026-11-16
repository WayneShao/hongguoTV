// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.Format
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import java.io.PrintWriter

/**
 * Bounded, instance-local failure snapshot for release-device diagnosis.
 *
 * Stores only fixed enums, numbers and sanitized format names. Media ids, episode
 * ids/titles, URLs, headers, signatures and Throwable messages are never stored.
 * Each captured failure freezes the immediately preceding event timeline and is
 * also written atomically to an app-private file, because a single 1080p switch
 * failure is enough evidence and must survive crash, force-stop and restart.
 */
internal class PlaybackFailureSnapshot(private val persistFile: java.io.File? = null) {
    internal enum class Origin { NONE, NO_VIDEO_GUARD, PLAYER_ERROR, PREPARATION_ERROR }
    internal enum class Path { QUEUED_SEEK, REPLACE, INITIAL, QUEUE_TRANSITION, READY, FIRST_FRAME }
    internal enum class Cause { NONE, EXO, DECODER_INIT, DECODER_RUNTIME, PLATFORM_CODEC, PARSER, OTHER }
    internal enum class FormatSource { NONE, CURRENT, RENDERER }

    internal data class Context(
        val sourceCount: Int = -1,
        val hasNext: Boolean = false,
        val currentIndex: Int = -1,
        val mediaItemCount: Int = -1,
        val playerState: Int = -1,
        val playerErrorCode: Int = Int.MIN_VALUE,
        val positionMs: Long = -1L,
        val selectedVideoCount: Int = 0,
        val currentFormat: Format? = null,
    )

    internal data class Event(
        val path: Path,
        val ordinal: Int,
        val episodeCount: Int,
        val preloadEnabled: Boolean,
        val batchModels: Boolean,
        val cacheComplete: Boolean,
        val nextPresent: Boolean,
    )

    /** Dump-time live state; refreshed from the main thread so it is never stale. */
    internal data class Live(
        val sourceCount: Int = -1,
        val nextPresent: Boolean = false,
        val preloadEnabled: Boolean = false,
        val batchModels: Boolean = false,
        val cacheComplete: Boolean = false,
        val playerState: Int = -1,
        val ordinal: Int = -1,
        val episodeCount: Int = -1,
        val currentIndex: Int = -1,
        val selectedVideoCount: Int = 0,
    )

    internal data class Failure(
        val origin: Origin,
        val cause: Cause,
        val synthetic: Boolean,
        val errorCode: Int,
        val errorName: String,
        val eventCurrent: Boolean,
        val eventNext: Boolean,
        val context: Context,
        val formatSource: FormatSource,
        val mime: String,
        val codecs: String,
        val width: Int,
        val height: Int,
        val exoType: Int,
        val rendererIndex: Int,
        val rendererFormatSupport: Int,
        val decoderErrorCode: Int,
        val codecName: String,
        val platformErrorCode: Int,
        val platformRecoverable: Boolean,
        val platformTransient: Boolean,
        val diagnostic: String,
        val seq: Long = 0L,
        val atMs: Long = 0L,
        val timeline: List<Event> = emptyList(),
    )

    private val lock = Any()
    private val events = ArrayDeque<Event>(EVENT_CAPACITY)
    private var eventTotal = 0L
    private val failures = ArrayDeque<Failure>(FAILURE_CAPACITY)
    private var failureTotal = 0L
    private var live: Live? = null
    private val previous: String? = readPrevious()

    fun event(path: Path, ordinal: Int, episodeCount: Int, preloadEnabled: Boolean, batchModels: Boolean,
              cacheComplete: Boolean, nextPresent: Boolean) {
        synchronized(lock) {
            if (events.size == EVENT_CAPACITY) events.removeFirst()
            events.addLast(Event(path, ordinal, episodeCount, preloadEnabled, batchModels, cacheComplete, nextPresent))
            eventTotal++
        }
    }

    /**
     * Captures one failure into the bounded ring and persists it. [sdkInt] and [atMs]
     * are injectable so API gating and output are deterministic in unit tests.
     */
    fun failure(origin: Origin, problem: Throwable, context: Context,
                eventCurrent: Boolean, eventNext: Boolean,
                sdkInt: Int = android.os.Build.VERSION.SDK_INT,
                atMs: Long = android.os.SystemClock.elapsedRealtime()) {
        val timeline = synchronized(lock) { events.toList().takeLast(TIMELINE_CAPACITY) }
        val record = classify(origin, problem, context, eventCurrent, eventNext, sdkInt)
        synchronized(lock) {
            val seq = failureTotal + 1
            if (failures.size == FAILURE_CAPACITY) failures.removeFirst()
            failures.addLast(record.copy(seq = seq, atMs = atMs, timeline = timeline))
            failureTotal = seq
        }
        persist()
    }

    fun snapshotEvents(): List<Event> = synchronized(lock) { events.toList() }
    fun snapshotFailures(): List<Failure> = synchronized(lock) { failures.toList() }
    fun lastFailure(): Failure? = synchronized(lock) { failures.lastOrNull() }
    fun failureCount(): Long = synchronized(lock) { failureTotal }
    fun eventCount(): Long = synchronized(lock) { eventTotal }

    /** Latest observed live state, so a dump without any failure still shows the queued path. */
    fun current(value: Live) {
        synchronized(lock) { live = value }
    }

    fun liveState(): Live? = synchronized(lock) { live }

    /** Called by Activity.dump. Never reads Throwable.message and never throws on odd formats. */
    fun writeTo(writer: PrintWriter) {
        writer.print(render(includePrevious = true))
        writer.flush()
    }

    private fun render(includePrevious: Boolean): String {
        val rows: List<Event>
        val failureRows: List<Failure>
        val totalEvents: Long
        val totalFailures: Long
        val now: Live?
        val prev: String?
        synchronized(lock) {
            rows = events.toList()
            failureRows = failures.toList()
            totalEvents = eventTotal
            totalFailures = failureTotal
            now = live
            prev = if (includePrevious) previous else null
        }
        val sb = StringBuilder(2048)
        sb.append("playback-failure-snapshot v1 cap=").append(EVENT_CAPACITY)
            .append(" events=").append(totalEvents)
            .append(" failures=").append(totalFailures)
            .append(" failCap=").append(FAILURE_CAPACITY).append('\n')
        if (now == null) {
            sb.append("current=none\n")
        } else {
            sb.append("current sources=").append(now.sourceCount)
                .append(" queueNextPresent=").append(now.nextPresent)
                .append(" preload=").append(now.preloadEnabled)
                .append(" batchModels=").append(now.batchModels)
                .append(" cacheComplete=").append(now.cacheComplete)
                .append(" state=").append(now.playerState)
                .append(" ord=").append(now.ordinal)
                .append(" count=").append(now.episodeCount)
                .append(" currentIndex=").append(now.currentIndex)
                .append(" selectedVideo=").append(now.selectedVideoCount)
                .append('\n')
        }
        prev?.let { text ->
            sb.append("previous-begin\n")
            text.lineSequence().forEach { line ->
                if (line.isNotBlank()) {
                    sb.append("previous| ")
                    line.forEach { ch -> sb.append(if (ch.code in 32..126) ch else '?') }
                    sb.append('\n')
                }
            }
            sb.append("previous-end\n")
        }
        rows.lastOrNull()?.let { row -> appendEvent(sb, "latest ", row) }
        rows.forEach { row -> appendEvent(sb, "", row) }
        if (failureRows.isEmpty()) {
            sb.append("failure=none\n")
        } else {
            failureRows.forEach { failure ->
                sb.append("failure origin=").append(failure.origin.name)
                    .append(" cause=").append(failure.cause.name)
                    .append(" synthetic=").append(failure.synthetic)
                    .append(" seq=").append(failure.seq)
                    .append(" atMs=").append(failure.atMs)
                    .append(" code=").append(failure.errorCode)
                    .append(" name=").append(failure.errorName)
                    .append(" eventCurrent=").append(failure.eventCurrent)
                    .append(" eventNext=").append(failure.eventNext)
                    .append('\n')
                sb.append("context seq=").append(failure.seq)
                    .append(" sources=").append(failure.context.sourceCount)
                    .append(" queueNextPresent=").append(failure.context.hasNext)
                    .append(" currentIndex=").append(failure.context.currentIndex)
                    .append(" items=").append(failure.context.mediaItemCount)
                    .append(" state=").append(failure.context.playerState)
                    .append(" playerError=").append(failure.context.playerErrorCode)
                    .append(" pos=").append(failure.context.positionMs)
                    .append('\n')
                sb.append("video selected=").append(failure.context.selectedVideoCount)
                    .append(" source=").append(failure.formatSource.name)
                    .append(" mime=").append(failure.mime)
                    .append(" codecs=").append(failure.codecs)
                    .append(" w=").append(failure.width)
                    .append(" h=").append(failure.height)
                    .append('\n')
                sb.append("exo type=").append(failure.exoType)
                    .append(" rendererIndex=").append(failure.rendererIndex)
                    .append(" support=").append(failure.rendererFormatSupport)
                    .append('\n')
                sb.append("decoder num=").append(failure.decoderErrorCode)
                    .append(" codec=").append(failure.codecName)
                    .append(" diag=").append(failure.diagnostic)
                    .append('\n')
                sb.append("platform code=").append(failure.platformErrorCode)
                    .append(" recoverable=").append(failure.platformRecoverable)
                    .append(" transient=").append(failure.platformTransient)
                    .append('\n')
                failure.timeline.forEach { row ->
                    appendEvent(sb, "failureEvent seq=${failure.seq} ", row)
                }
            }
        }
        return sb.toString()
    }

    private fun appendEvent(sb: StringBuilder, prefix: String, row: Event) {
        sb.append(prefix).append("event=").append(row.path.name)
            .append(" ord=").append(row.ordinal)
            .append(" count=").append(row.episodeCount)
            .append(" preload=").append(row.preloadEnabled)
            .append(" batchModels=").append(row.batchModels)
            .append(" cacheComplete=").append(row.cacheComplete)
            .append(" queueNextPresent=").append(row.nextPresent)
            .append('\n')
    }

    /** Writes the whole bounded snapshot to app storage; atomic rename means readers never see a partial file. */
    private fun persist() {
        val file = persistFile ?: return
        runCatching {
            val text = render(includePrevious = false)
            file.parentFile?.mkdirs()
            val tmp = java.io.File(file.parentFile, file.name + ".tmp")
            java.io.FileOutputStream(tmp).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.flush()
                out.fd.sync()
            }
            if (!tmp.renameTo(file)) {
                file.delete()
                if (!tmp.renameTo(file)) tmp.delete()
            }
        }
    }

    /** Only re-emits a file that starts with our own header; junk and truncation are ignored. */
    private fun readPrevious(): String? {
        val file = persistFile ?: return null
        return runCatching {
            if (!file.isFile) return null
            val bytes = file.inputStream().use { input ->
                val buffer = ByteArray(PREVIOUS_MAX_BYTES)
                var count = 0
                while (count < buffer.size) {
                    val read = input.read(buffer, count, buffer.size - count)
                    if (read < 0) break
                    count += read
                }
                buffer.copyOf(count)
            }
            val text = String(bytes, Charsets.UTF_8)
            if (!text.startsWith(HEADER)) null
            else if (text.length > PREVIOUS_MAX_CHARS) text.take(PREVIOUS_MAX_CHARS) else text
        }.getOrNull()
    }

    private fun classify(origin: Origin, problem: Throwable, context: Context,
                         eventCurrent: Boolean, eventNext: Boolean, sdkInt: Int): Failure {
        val chain = generateSequence(problem) { it.cause }.take(12).toList()
        val cause = when {
            chain.any { it is MediaCodecRenderer.DecoderInitializationException } -> Cause.DECODER_INIT
            chain.any { it is MediaCodecDecoderException } -> Cause.DECODER_RUNTIME
            chain.any { isPlatformCodec(it) } -> Cause.PLATFORM_CODEC
            chain.any { it is ParserException || it is org.json.JSONException } -> Cause.PARSER
            chain.any { it is ExoPlaybackException } -> Cause.EXO
            else -> Cause.OTHER
        }
        val playback = chain.filterIsInstance<PlaybackException>().firstOrNull()
        val exo = chain.filterIsInstance<ExoPlaybackException>().firstOrNull()
        val decoderRuntime = chain.filterIsInstance<MediaCodecDecoderException>().firstOrNull()
        val decoderInit = chain.filterIsInstance<MediaCodecRenderer.DecoderInitializationException>().firstOrNull()
        val platform = if (sdkInt >= 23) chain.firstOrNull { isPlatformCodec(it) } as? android.media.MediaCodec.CodecException else null
        val failing = exo?.rendererFormat
        val visibleFormat = failing ?: context.currentFormat
        val source = when {
            failing != null -> FormatSource.RENDERER
            context.currentFormat != null -> FormatSource.CURRENT
            else -> FormatSource.NONE
        }
        return Failure(
            origin = origin,
            cause = cause,
            synthetic = origin == Origin.NO_VIDEO_GUARD,
            errorCode = playback?.errorCode ?: Int.MIN_VALUE,
            errorName = sanitize(playback?.errorCodeName),
            eventCurrent = eventCurrent,
            eventNext = eventNext,
            context = context,
            formatSource = source,
            mime = sanitize(visibleFormat?.sampleMimeType),
            codecs = sanitize(visibleFormat?.codecs),
            width = visibleFormat?.width ?: -1,
            height = visibleFormat?.height ?: -1,
            exoType = exo?.type ?: Int.MIN_VALUE,
            rendererIndex = exo?.rendererIndex ?: -1,
            rendererFormatSupport = exo?.rendererFormatSupport ?: Int.MIN_VALUE,
            decoderErrorCode = decoderRuntime?.errorCode ?: Int.MIN_VALUE,
            codecName = sanitize(decoderRuntime?.codecInfo?.name ?: decoderInit?.codecInfo?.name),
            platformErrorCode = platform?.let { runCatching { it.errorCode }.getOrNull() } ?: Int.MIN_VALUE,
            platformRecoverable = platform?.let { runCatching { it.isRecoverable }.getOrDefault(false) } ?: false,
            platformTransient = platform?.let { runCatching { it.isTransient }.getOrDefault(false) } ?: false,
            diagnostic = numericDiagnostic(decoderInit?.diagnosticInfo ?: decoderRuntime?.diagnosticInfo),
        )
    }

    private fun isPlatformCodec(error: Throwable): Boolean = error is android.media.MediaCodec.CodecException

    companion object {
        const val EVENT_CAPACITY = 32
        const val FAILURE_CAPACITY = 8
        const val TIMELINE_CAPACITY = 32
        private const val HEADER = "playback-failure-snapshot v1"
        private const val PREVIOUS_MAX_BYTES = 131072
        private const val PREVIOUS_MAX_CHARS = 131072
        private val NUMERIC = Regex("""(?i)(?:errorCode|error|code|err)=(-?\d{1,6})""")
        private val BRACKET = Regex("""\[(-?\d{1,6})]""")
        private val MEDIA3 = Regex("""MediaCodecRenderer_(-?\d{1,6})""")

        /** Fixed ASCII alphabet only, so no URL/query/token bytes can survive. */
        internal fun sanitize(value: String?, max: Int = 96): String {
            if (value.isNullOrEmpty()) return "-"
            val out = StringBuilder(minOf(value.length, max))
            for (ch in value) {
                if (out.length == max) break
                val safe = ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' || ch == '.' || ch == '_' || ch == '/' || ch == '-'
                out.append(if (safe) ch else '_')
            }
            return if (out.isEmpty()) "-" else out.toString()
        }

        /** Decoder init diagnostics are free-form; keep only a known numeric token. */
        internal fun numericDiagnostic(value: String?): String {
            if (value == null) return "-"
            NUMERIC.find(value)?.let { return sanitize(it.groupValues[1]) }
            BRACKET.find(value)?.let { return sanitize(it.groupValues[1]) }
            MEDIA3.find(value)?.let { return sanitize(it.groupValues[1]) }
            return "-"
        }
    }
}
