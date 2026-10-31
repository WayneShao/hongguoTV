// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.view.SurfaceHolder
import android.view.SurfaceView
import androidx.media3.common.*
import androidx.media3.exoplayer.*
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** Runs the production queue/data source/decoder in the actual application, on emulator only.
 * Frame release timestamps are decoder scheduling evidence, not physical display measurements. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeamlessPlaybackInstrumentation : Instrumentation() {
    private lateinit var activity: Activity
    private lateinit var surface: SurfaceView
    private var baseUrl = "http://fixture.byteicdn.com:18765"
    private var count = 21
    private var scenario = "frames"
    private var audioBufferMs = 0
    private var asyncCodec = false
    private val report = JSONObject()
    override fun onCreate(arguments: Bundle?) {
        super.onCreate(arguments)
        baseUrl = arguments?.getString("baseUrl") ?: baseUrl
        count = arguments?.getString("count")?.toInt() ?: count
        scenario = arguments?.getString("scenario") ?: scenario
        audioBufferMs = arguments?.getString("audioBufferMs")?.toInt() ?: 0
        asyncCodec = arguments?.getString("asyncCodec")=="true"
        start()
    }
    override fun onStart() {
        if (scenario.startsWith("main")) { runMainActivitySeamless(scenario); return }
        val result = Bundle()
        var resultCode = Activity.RESULT_CANCELED
        try {
            check(android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Emulator-only test" }
            activity = startActivitySync(Intent().setClassName(targetContext.packageName, "com.hongguotv.nativeapp.MainActivity")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
            val ready = CountDownLatch(1)
            runOnMainSync {
                surface = SurfaceView(activity)
                surface.holder.addCallback(object : SurfaceHolder.Callback {
                    override fun surfaceCreated(holder: SurfaceHolder) { ready.countDown() }
                    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}
                    override fun surfaceDestroyed(holder: SurfaceHolder) {}
                })
                activity.setContentView(surface)
            }
            check(ready.await(15, TimeUnit.SECONDS)) { "Surface did not become available" }
            report.put("model", android.os.Build.MODEL).put("api", android.os.Build.VERSION.SDK_INT)
            report.put("measurement", "VideoFrameMetadataListener releaseTimeNs; PTS reset identifies next episode; not physical panel")
            if(scenario!="frames-queue") report.put("baseline", runScenario(false))
            report.put("queue", runScenario(true))
            report.put("passed", true)
            result.putString("stream", report.toString(2))
            resultCode = Activity.RESULT_OK
        } catch (failure: Throwable) {
            report.put("passed", false).put("error", failure.stackTraceToString())
            result.putString("stream", report.toString(2))
            resultCode = Activity.RESULT_CANCELED
        } finally {
            File(targetContext.filesDir, "seamless-playback-report.json").writeText(report.toString(2))
            if (::activity.isInitialized) runOnMainSync { activity.finish() }
        }
        finish(resultCode, result)
    }
    private fun runScenario(queued: Boolean): JSONObject {
        val mode = if (queued) "queue" else "baseline"
        val out = JSONObject().put("mode", mode).put("items", count).put("minPcmBufferMs",if(queued) audioBufferMs else 0).put("forceAsyncCodec",queued && asyncCodec)
        report.put(mode, out)
        val frames = JSONArray(); val events = JSONArray(); val decoders = JSONArray()
        out.put("frameTransitions", frames).put("events", events).put("decoders", decoders)
        val completed = CountDownLatch(1)
        val error = AtomicReference<Throwable?>()
        val closed = AtomicInteger(); val underruns = AtomicInteger(); val rendered = AtomicInteger()
        val client = fixtureClient()
        val directory = File(targetContext.cacheDir, "seamless-$mode").also { it.mkdirs() }
        val remotes = ArrayList<RemoteVideo>()
        var player: ExoPlayer? = null
        var queue: SeamlessPlaybackQueue? = null
        var lastPts = -1L; var lastRelease = -1L; var lastWall = -1L
        val started = SystemClock.elapsedRealtime()
        fun event(kind: String, value: Any) { synchronized(events) { events.put(JSONObject().put("kind", kind).put("value", value).put("ms", SystemClock.elapsedRealtime()-started)) } }
        try {
            repeat(count) { i ->
                val remote = RemoteVideo(client, StreamInfo("$baseUrl/${i%3}", null, "480P"))
                remotes.add(remote)
                remote.preload(directory, { false }, onFailure={ throw it })
                check(remote.isCacheComplete()) { "Incomplete fixture $i total=${remote.total} cached=${remote.cachedSize()} usable=${directory.usableSpace} files=${directory.listFiles()?.joinToString { it.name+":"+it.length() }}" }
                remote.promote()
            }
            out.put("cachedBytes", remotes.sumOf { it.cachedSize() })
            runOnMainSync {
                val load = DefaultLoadControl.Builder().setBufferDurationsMs(15000,30000,1000,2000).setTargetBufferBytes(12*1024*1024).build()
                val renderers = if(queued && audioBufferMs>0) object : DefaultRenderersFactory(activity) {
                    override fun buildAudioSink(context: android.content.Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): androidx.media3.exoplayer.audio.AudioSink =
                        androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
                            .setEnableFloatOutput(enableFloatOutput)
                            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
                            .setAudioTrackBufferSizeProvider(androidx.media3.exoplayer.audio.DefaultAudioTrackBufferSizeProvider.Builder()
                                .setMinPcmBufferDurationUs(audioBufferMs*1000).build()).build()
                } else DefaultRenderersFactory(activity)
                if(queued && asyncCodec) renderers.forceEnableMediaCodecAsynchronousQueueing()
                val p = ExoPlayer.Builder(activity, renderers.setEnableDecoderFallback(true)).setLoadControl(load).build()
                p.volume = 0f // Keep the real host audio backend active without audible test output.
                player = p
                val q = SeamlessPlaybackQueue(p); queue=q
                p.setVideoSurfaceView(surface)
                p.setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(),false)
                p.setVideoFrameMetadataListener { presentationTimeUs, releaseTimeNs, _, _ ->
                    val wall=System.nanoTime()
                    synchronized(frames) {
                        if(lastPts>=0 && presentationTimeUs < lastPts-100000) {
                            frames.put(JSONObject().put("from", frames.length()).put("to", frames.length()+1)
                                .put("scheduledGapMs", (releaseTimeNs-lastRelease)/1e6)
                                .put("callbackGapMs", (wall-lastWall)/1e6)
                                .put("lastPtsUs", lastPts).put("firstPtsUs", presentationTimeUs))
                        }
                        lastPts=presentationTimeUs;lastRelease=releaseTimeNs;lastWall=wall
                    }
                    rendered.incrementAndGet()
                }
                p.addAnalyticsListener(object : AnalyticsListener {
                    override fun onVideoDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        synchronized(decoders) { decoders.put(JSONObject().put("name", decoderName).put("initMs", initializationDurationMs)) }
                    }
                    override fun onAudioUnderrun(eventTime: AnalyticsListener.EventTime, bufferSize: Int, bufferSizeMs: Long, elapsedSinceLastFeedMs: Long) {
                        underruns.incrementAndGet(); event("audioUnderrun", JSONObject().put("sinceFeedMs",elapsedSinceLastFeedMs)
                            .put("bufferSizeBytes",bufferSize).put("bufferSizeMs",bufferSizeMs)
                            .put("eventPositionMs",eventTime.eventPlaybackPositionMs).put("currentPositionMs",eventTime.currentPlaybackPositionMs)
                            .put("windowIndex",eventTime.windowIndex).put("realtimeMs",eventTime.realtimeMs))
                    }
                    override fun onAudioTrackInitialized(eventTime: AnalyticsListener.EventTime, config: androidx.media3.exoplayer.audio.AudioSink.AudioTrackConfig) {
                        event("audioTrackInitialized",JSONObject().put("sampleRate",config.sampleRate).put("encoding",config.encoding)
                            .put("channelConfig",config.channelConfig).put("bufferSizeBytes",config.bufferSize))
                    }
                    override fun onAudioInputFormatChanged(eventTime: AnalyticsListener.EventTime, format: Format, decoderReuseEvaluation: DecoderReuseEvaluation?) {
                        event("audioFormat",JSONObject().put("sampleRate",format.sampleRate).put("channels",format.channelCount)
                            .put("encoderDelay",format.encoderDelay).put("encoderPadding",format.encoderPadding)
                            .put("reuseResult",decoderReuseEvaluation?.result).put("discardReasons",decoderReuseEvaluation?.discardReasons)
                            .put("windowIndex",eventTime.windowIndex).put("eventPositionMs",eventTime.eventPlaybackPositionMs))
                    }
                    override fun onAudioDecoderInitialized(eventTime: AnalyticsListener.EventTime, decoderName: String, initializedTimestampMs: Long, initializationDurationMs: Long) {
                        event("audioDecoderInitialized",JSONObject().put("name",decoderName).put("initMs",initializationDurationMs))
                    }
                    override fun onAudioDecoderReleased(eventTime: AnalyticsListener.EventTime, decoderName: String) {event("audioDecoderReleased",decoderName)}
                    override fun onDroppedVideoFrames(eventTime: AnalyticsListener.EventTime, droppedFrames: Int, elapsedMs: Long) { event("droppedFrames",droppedFrames) }
                })
                var index=0
                p.addListener(object : Player.Listener {
                    override fun onPlayerError(problem: PlaybackException) { error.set(problem);completed.countDown() }
                    override fun onPlaybackStateChanged(state: Int) {
                        event("state", state)
                        if(state==Player.STATE_ENDED) {
                            if(!queued && index<count-1) {
                                index++
                                q.installCurrent("$mode-$index", remotes[index]) {closed.incrementAndGet()}
                                p.prepare();p.play()
                            } else completed.countDown()
                        }
                    }
                    override fun onMediaItemTransition(item: MediaItem?, reason: Int) {
                        event("mediaTransition", "${item?.mediaId}:$reason")
                        if(queued && reason==Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && item!=null) {
                            index++
                            q.advanceTo(item.mediaId)
                            if(index<count-1) check(q.appendNext("$mode-${index+1}", remotes[index+1]) {closed.incrementAndGet()})
                            check(q.mediaIds.size<=2)
                        }
                    }
                })
                q.installCurrent("$mode-0", remotes[0]) {closed.incrementAndGet()}
                if(queued && count>1) check(q.appendNext("$mode-1",remotes[1]) {closed.incrementAndGet()})
                p.prepare();p.play()
            }
            check(completed.await((count*120+30).toLong(), TimeUnit.SECONDS)) { "$mode playback timed out" }
            error.get()?.let { throw it }
            out.put("renderedFrames", rendered.get()).put("audioUnderruns",underruns.get())
            check(rendered.get()>count*10) { "Insufficient decoded frames" }
            check(frames.length()==count-1) { "Expected ${count-1} frame boundaries, got ${frames.length()}" }
        } finally {
            runOnMainSync { player?.release();queue?.releaseAfterPlayerRelease() }
            waitForIdleSync()
            out.put("closedSources",closed.get()).put("elapsedMs",SystemClock.elapsedRealtime()-started)
            remotes.forEach { it.close() }
            out.put("remainingCacheFiles", directory.listFiles()?.size ?: 0)
            client.dispatcher.executorService.shutdown();client.connectionPool.evictAll()
        }
        check(closed.get()==count) { "Source release leak ${closed.get()}/$count" }
        check(directory.listFiles().isNullOrEmpty()) { "Cache file leak" }
        return out
    }
}
