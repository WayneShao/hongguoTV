package com.hongguotv.nativeapp

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.PlayerId
import androidx.media3.exoplayer.source.BaseMediaSource
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.upstream.Allocator
import androidx.media3.exoplayer.upstream.DefaultAllocator
import com.hongguotv.core.RemoteVideo
import com.hongguotv.core.StreamInfo
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeamlessPlaybackQueueTest {
    private fun remote() = RemoteVideo(OkHttpClient(), StreamInfo("https://example.test/video", null, "480P"))
    private val caller = MediaSource.MediaSourceCaller { _, _ -> }

    private class Child : BaseMediaSource() {
        var released = false
        var periodReleased = false
        override fun getMediaItem() = MediaItem.fromUri("test://current")
        override fun prepareSourceInternal(listener: TransferListener?) { released = false }
        override fun releaseSourceInternal() { released = true }
        override fun maybeThrowSourceInfoRefreshError() {}
        override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, position: Long): MediaPeriod =
            Proxy.newProxyInstance(MediaPeriod::class.java.classLoader, arrayOf(MediaPeriod::class.java)) { _, _, _ -> null } as MediaPeriod
        override fun releasePeriod(period: MediaPeriod) { periodReleased = true }
    }

    @Test fun stopThenPrepareRetainsRemoteUntilExplicitRetirementAndFinalSourceRelease() {
        val remote = remote()
        val child = Child()
        var closed = 0
        val source = OwnedVideoMediaSource(remote, child) { closed++ }
        source.prepareSource(caller, null, PlayerId.UNSET)
        source.releaseSource(caller)
        assertTrue(child.released)
        assertTrue(remote.reusable)
        source.prepareSource(caller, null, PlayerId.UNSET)
        val period = source.createPeriod(MediaSource.MediaPeriodId(Any()), DefaultAllocator(true, 4096), 0)
        source.retire()
        assertTrue(remote.reusable)
        source.releasePeriod(period)
        assertTrue(child.periodReleased)
        assertTrue(remote.reusable)
        source.releaseSource(caller)
        assertFalse(remote.reusable)
        source.retire()
        assertEquals(1, closed)
    }

    @Test fun retirementAlsoWaitsForPeriodsIfSourceReleaseArrivesFirst() {
        val remote = remote()
        val source = OwnedVideoMediaSource(remote, Child())
        source.prepareSource(caller, null, PlayerId.UNSET)
        val period = source.createPeriod(MediaSource.MediaPeriodId(Any()), DefaultAllocator(true, 4096), 0)
        source.retire()
        source.releaseSource(caller)
        assertTrue(remote.reusable)
        source.releasePeriod(period)
        assertFalse(remote.reusable)
    }

    @Test fun neverPreparedSourceClosesOnce() {
        val remote = remote()
        var closed = 0
        val source = OwnedVideoMediaSource(remote, Child()) { closed++ }
        source.retire()
        source.retire()
        assertFalse(remote.reusable)
        assertEquals(1, closed)
    }

    @Test fun replacingPendingUnpreparedSourceDefersRetirementAndDisposalClosesResidualSource() {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "getPlaybackLooper", "getApplicationLooper" -> Looper.getMainLooper()
                else -> null
            }
        } as ExoPlayer
        val queue = SeamlessPlaybackQueue(player)
        val first = remote()
        val second = remote()
        queue.installCurrent("one", first)
        queue.installCurrent("two", second)
        assertTrue(first.reusable)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(first.reusable)
        assertTrue(second.reusable)
        assertEquals(listOf("two"), queue.mediaIds)
        assertSame(second, queue.remoteFor("two"))
        queue.releaseAfterPlayerRelease()
        assertFalse(second.reusable)
        assertFalse(calls.any { it in listOf("stop", "clearMediaItems", "prepare") })
    }

    @Test fun naturalAdvancePrunesOnlyOldSourceAndAcceptsOnlyOneCompleteSuccessor() {
        val bytes = java.nio.ByteBuffer.allocate(16).putInt(8).put("ftyp".toByteArray())
            .putInt(8).put("moov".toByteArray()).array()
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(206).message("partial")
                .header("Content-Range", "bytes 0-15/16").body(bytes.toResponseBody()).build()
        }.build()
        val directory = java.nio.file.Files.createTempDirectory("queue-next").toFile()
        val next = RemoteVideo(http, StreamInfo("https://example.test/next", null, "720P")).preload(directory, { false })
        val first = remote()
        val rejected = remote()
        var currentIndex = 0
        val playlist = mutableListOf<MediaSource>()
        val mutations = mutableListOf<String>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, args ->
            when (method.name) {
                "getPlaybackLooper", "getApplicationLooper" -> Looper.getMainLooper()
                "getCurrentMediaItemIndex" -> currentIndex
                "getCurrentMediaItem" -> playlist.getOrNull(currentIndex)?.mediaItem
                "setPreloadConfiguration" -> null
                "setMediaSource" -> { playlist.clear(); playlist.add(args!![0] as MediaSource); mutations.add(method.name); null }
                "addMediaSource" -> { playlist.add(args!![0] as MediaSource); mutations.add(method.name); null }
                "removeMediaItems" -> {
                    val start = args!![0] as Int
                    val end = args[1] as Int
                    playlist.subList(start, end).clear()
                    if (currentIndex >= end) currentIndex -= end - start
                    mutations.add(method.name)
                    null
                }
                else -> { mutations.add(method.name); null }
            }
        } as ExoPlayer
        val queue = SeamlessPlaybackQueue(player)
        try {
            queue.installCurrent("first", first)
            assertFalse(queue.appendNext("incomplete", rejected))
            assertTrue(rejected.reusable)
            assertTrue(queue.appendNext("next", next))
            assertFalse(queue.appendNext("third", rejected))
            assertEquals("next", queue.nextMediaId)
            mutations.clear()
            currentIndex = 1 // Actual player transition has already happened.
            queue.advanceTo("next")
            assertEquals(listOf("removeMediaItems"), mutations)
            assertEquals(listOf("next"), queue.mediaIds)
            assertEquals("720P", queue.qualityFor("next"))
            assertTrue(first.reusable)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(first.reusable)
            assertTrue(next.reusable)
        } finally {
            queue.releaseAfterPlayerRelease()
            rejected.close()
            directory.deleteRecursively()
        }
    }
}
