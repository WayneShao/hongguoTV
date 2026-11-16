// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlaybackException
import androidx.media3.exoplayer.mediacodec.MediaCodecDecoderException
import androidx.media3.exoplayer.mediacodec.MediaCodecRenderer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PlaybackFailureSnapshotTest {
    private fun context(selectedVideo: Int = 0, format: Format? = null) =
        PlaybackFailureSnapshot.Context(sourceCount = 1, currentIndex = 0, mediaItemCount = 1,
            playerState = 2, positionMs = 1_000L, selectedVideoCount = selectedVideo, currentFormat = format)

    private fun hevc() = Format.Builder().setSampleMimeType("video/hevc")
        .setCodecs("hvc1.1.6.L120").setWidth(3840).setHeight(2160).build()

    private fun snapshot(failure: PlaybackFailureSnapshot.Failure): String {
        val out = java.io.StringWriter()
        val writer = java.io.PrintWriter(out)
        writer.println("failure origin=${failure.origin.name} cause=${failure.cause.name} synthetic=${failure.synthetic}" +
            " code=${failure.errorCode} name=${failure.errorName}" +
            " eventCurrent=${failure.eventCurrent} eventNext=${failure.eventNext}")
        writer.println("context sources=${failure.context.sourceCount} queueNextPresent=${failure.context.hasNext}" +
            " currentIndex=${failure.context.currentIndex} items=${failure.context.mediaItemCount}" +
            " state=${failure.context.playerState} playerError=${failure.context.playerErrorCode} pos=${failure.context.positionMs}")
        writer.println("video selected=${failure.context.selectedVideoCount} source=${failure.formatSource.name}" +
            " mime=${failure.mime} codecs=${failure.codecs} w=${failure.width} h=${failure.height}")
        writer.println("exo type=${failure.exoType} rendererIndex=${failure.rendererIndex} support=${failure.rendererFormatSupport}")
        writer.println("decoder num=${failure.decoderErrorCode} codec=${failure.codecName} diag=${failure.diagnostic}")
        writer.println("platform code=${failure.platformErrorCode} recoverable=${failure.platformRecoverable} transient=${failure.platformTransient}")
        writer.flush()
        return out.toString()
    }

    private fun capture(origin: PlaybackFailureSnapshot.Origin, problem: Throwable,
                        ctx: PlaybackFailureSnapshot.Context, sdk: Int = 33): Pair<PlaybackFailureSnapshot, PlaybackFailureSnapshot.Failure> {
        val snapshot = PlaybackFailureSnapshot()
        snapshot.failure(origin, problem, ctx, eventCurrent = true, eventNext = false, sdkInt = sdk)
        return snapshot to snapshot.lastFailure()!!
    }

    @Test fun syntheticNoVideoGuardIsDistinctFromRealRenderer4005() {
        val syntheticError = PlaybackException("资源没有可解码的视频轨道", null,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED)
        val (_, synthetic) = capture(PlaybackFailureSnapshot.Origin.NO_VIDEO_GUARD, syntheticError, context())
        assertTrue(synthetic.synthetic)
        assertEquals(4005, synthetic.errorCode)
        assertEquals(PlaybackFailureSnapshot.Cause.OTHER, synthetic.cause)
        assertEquals(PlaybackFailureSnapshot.FormatSource.NONE, synthetic.formatSource)
        assertEquals(0, synthetic.context.selectedVideoCount)

        val realError = ExoPlaybackException.createForRenderer(RuntimeException("decoder"),
            "MediaCodecVideoRenderer", 0, hevc(), C.FORMAT_UNSUPPORTED_SUBTYPE, false, 4005)
        val (_, real) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR, realError, context(selectedVideo = 1))
        assertFalse(real.synthetic)
        assertEquals(4005, real.errorCode)
        assertEquals(PlaybackFailureSnapshot.Cause.EXO, real.cause)
        assertEquals(PlaybackFailureSnapshot.FormatSource.RENDERER, real.formatSource)
        assertEquals(ExoPlaybackException.TYPE_RENDERER, real.exoType)
        assertEquals(0, real.rendererIndex)
        assertEquals(C.FORMAT_UNSUPPORTED_SUBTYPE, real.rendererFormatSupport)
        assertEquals("video/hevc", real.mime)
        assertEquals(3840, real.width)
        assertEquals(2160, real.height)
    }

    @Test fun causeClassificationKeepsOnlyTypedDecoderEvidence() {
        val (_, init) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            MediaCodecRenderer.DecoderInitializationException(hevc(), RuntimeException("secret info"), false, 1234),
            context(selectedVideo = 1))
        assertEquals(PlaybackFailureSnapshot.Cause.DECODER_INIT, init.cause)
        assertEquals("1234", init.diagnostic)

        val (_, runtime) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            MediaCodecDecoderException(RuntimeException("secret info"), null), context(selectedVideo = 1))
        assertEquals(PlaybackFailureSnapshot.Cause.DECODER_RUNTIME, runtime.cause)
        assertEquals(0, runtime.decoderErrorCode)
        assertEquals("-", runtime.codecName)

        val (_, parser) = capture(PlaybackFailureSnapshot.Origin.PREPARATION_ERROR,
            org.json.JSONException("bad token=SECRET"), context())
        assertEquals(PlaybackFailureSnapshot.Cause.PARSER, parser.cause)

        val (_, media3Parser) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            androidx.media3.common.ParserException.createForMalformedContainer("secret", null), context())
        assertEquals(PlaybackFailureSnapshot.Cause.PARSER, media3Parser.cause)

        val (_, plain) = capture(PlaybackFailureSnapshot.Origin.PREPARATION_ERROR,
            java.io.IOException("https://example.invalid?token=SECRET"), context())
        assertEquals(PlaybackFailureSnapshot.Cause.OTHER, plain.cause)
        assertFalse(snapshot(plain).contains("SECRET"))
    }

    @Test fun formatAndCodecNamesAreSanitizedAndBounded() {
        val dirty = Format.Builder().setSampleMimeType("video/avc?token=SECRET&sig=abc")
            .setCodecs("avc1.640028, mp4a.40.2; secret").setWidth(1920).setHeight(1080).build()
        val (_, failure) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            PlaybackException("x", null, 4001), context(selectedVideo = 1, format = dirty))
        assertEquals(PlaybackFailureSnapshot.FormatSource.CURRENT, failure.formatSource)
        val line = snapshot(failure)
        assertFalse(line.contains("?"))
        assertFalse(line.contains("&"))
        assertFalse(line.contains(","))
        assertTrue(failure.mime.length <= 96)
        assertEquals("video/avc_token_secret_sig_abc", failure.mime)
        assertEquals("avc1.640028__mp4a.40.2__secret", failure.codecs)
        assertEquals("c2.secret_codec_1", PlaybackFailureSnapshot.sanitize("c2.secret codec?1"))
        assertEquals("-", PlaybackFailureSnapshot.sanitize(null))
        assertEquals("-", PlaybackFailureSnapshot.numericDiagnostic("Decoder init failed: hide this"))
        assertEquals("42", PlaybackFailureSnapshot.numericDiagnostic("N/A, error=42"))
        assertEquals(96, PlaybackFailureSnapshot.sanitize("x".repeat(200)).length)
    }

    @Test fun eventsRetainOnlyLastWindowWithNavigationFlags() {
        val snapshot = PlaybackFailureSnapshot()
        repeat(40) { index ->
            snapshot.event(PlaybackFailureSnapshot.Path.READY, index + 1, 20,
                preloadEnabled = false, batchModels = true, cacheComplete = index % 2 == 0, nextPresent = index == 39)
        }
        assertEquals(32, snapshot.snapshotEvents().size)
        assertEquals(40L, snapshot.eventCount())
        assertEquals(9, snapshot.snapshotEvents().first().ordinal)
        assertEquals(40, snapshot.snapshotEvents().last().ordinal)
        assertTrue(snapshot.snapshotEvents().last().nextPresent)
        assertFalse(snapshot.snapshotEvents().last().cacheComplete)
        assertFalse(snapshot.snapshotEvents().last().preloadEnabled)
        assertTrue(snapshot.snapshotEvents().last().batchModels)

        val out = java.io.StringWriter()
        snapshot.writeTo(java.io.PrintWriter(out))
        val text = out.toString()
        assertTrue(text, text.contains("playback-failure-snapshot v1 cap=32 events=40 failures=0 failCap=8"))
        assertTrue(text, text.contains("latest event=READY ord=40 count=20 preload=false batchModels=true cacheComplete=false queueNextPresent=true"))
        assertTrue(text, text.contains("failure=none"))
        assertFalse(text, text.contains("ord=8 "))
    }

    @Test fun failureRingFreezesTimelineAndKeepsFirstFailure() {
        val snapshot = PlaybackFailureSnapshot()
        repeat(40) { index ->
            snapshot.event(PlaybackFailureSnapshot.Path.READY, index + 1, 40, false, false, false, index == 39)
        }
        snapshot.failure(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            PlaybackException("x", null, 4005), context(selectedVideo = 1),
            eventCurrent = true, eventNext = false, sdkInt = 33, atMs = 100L)
        snapshot.event(PlaybackFailureSnapshot.Path.FIRST_FRAME, 41, 40, false, false, false, true)
        snapshot.failure(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            PlaybackException("y", null, 4002), context(), false, false, sdkInt = 33, atMs = 200L)

        val rows = snapshot.snapshotFailures()
        assertEquals(2, rows.size)
        assertEquals(1L, rows[0].seq)
        assertEquals(4005, rows[0].errorCode)
        assertEquals(32, rows[0].timeline.size)
        assertEquals(40, rows[0].timeline.last().ordinal)
        assertEquals(2L, rows[1].seq)
        assertEquals(41, rows[1].timeline.last().ordinal)
        assertEquals(4002, snapshot.lastFailure()!!.errorCode)

        repeat(10) { index ->
            snapshot.failure(PlaybackFailureSnapshot.Origin.PREPARATION_ERROR,
                PlaybackException("z", null, 4000 + index), context(), false, false, sdkInt = 33, atMs = 300L + index)
        }
        assertEquals(8, snapshot.snapshotFailures().size)
        assertEquals(12L, snapshot.failureCount())
        assertEquals(5L, snapshot.snapshotFailures().first().seq)
        assertEquals(12L, snapshot.snapshotFailures().last().seq)
    }

    @Test fun persistedFailureSurvivesNewInstanceWithFullTimeline() {
        val file = java.io.File.createTempFile("hongguo-snapshot", ".txt")
        try {
            file.writeText("junk that must be ignored")
            val junk = PlaybackFailureSnapshot(file)
            val junkOut = java.io.StringWriter()
            junk.writeTo(java.io.PrintWriter(junkOut))
            assertFalse(junkOut.toString().contains("junk"))

            val first = PlaybackFailureSnapshot(file)
            repeat(32) { index ->
                first.event(PlaybackFailureSnapshot.Path.READY, index + 1, 40, false, true, false, index == 31)
            }
            first.failure(PlaybackFailureSnapshot.Origin.NO_VIDEO_GUARD,
                PlaybackException("secret token", null, 4005), context(), true, false, sdkInt = 33, atMs = 777L)

            val second = PlaybackFailureSnapshot(file)
            val out = java.io.StringWriter()
            second.writeTo(java.io.PrintWriter(out))
            val text = out.toString()
            assertTrue(text, text.contains("previous-begin"))
            assertTrue(text, text.contains("origin=NO_VIDEO_GUARD"))
            assertTrue(text, text.contains("atMs=777"))
            assertTrue(text, text.contains("code=4005"))
            assertTrue(text, text.contains("failureEvent seq=1 event=READY ord=32"))
            assertFalse(text, text.contains("secret"))
            assertFalse(text, text.contains("junk"))
        } finally {
            file.delete()
        }
    }

    @Test fun dumpShowsLiveCurrentStateWithoutAnyFailure() {
        val snapshot = PlaybackFailureSnapshot()
        snapshot.event(PlaybackFailureSnapshot.Path.FIRST_FRAME, 2, 20,
            preloadEnabled = false, batchModels = false, cacheComplete = false, nextPresent = false)
        snapshot.current(PlaybackFailureSnapshot.Live(sourceCount = 3, nextPresent = true,
            preloadEnabled = false, batchModels = true, cacheComplete = true, playerState = 2,
            ordinal = 2, episodeCount = 20, currentIndex = 1, selectedVideoCount = 1))
        val out = java.io.StringWriter()
        snapshot.writeTo(java.io.PrintWriter(out))
        val text = out.toString()
        assertTrue(text, text.contains("current sources=3 queueNextPresent=true preload=false" +
            " batchModels=true cacheComplete=true state=2 ord=2 count=20 currentIndex=1 selectedVideo=1"))
        assertTrue(text, text.contains("failure=none"))
    }

    @Test fun nullFormatAndNullMimeNeverPrintNull() {
        val empty = Format.Builder().build()
        val (snapshot, failure) = capture(PlaybackFailureSnapshot.Origin.PLAYER_ERROR,
            PlaybackException("x", null, 4001), context(selectedVideo = 1, format = empty))
        assertEquals("-", failure.mime)
        assertEquals("-", failure.codecs)
        val out = java.io.StringWriter()
        snapshot.writeTo(java.io.PrintWriter(out))
        assertFalse(out.toString(), out.toString().contains("null", ignoreCase = true))
    }
}
