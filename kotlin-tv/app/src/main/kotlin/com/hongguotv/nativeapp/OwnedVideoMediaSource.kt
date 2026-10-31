// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.upstream.Allocator
import com.hongguotv.core.RemoteVideo

/** Stable playlist ownership survives stop/prepare retries. All lifecycle calls use playback looper. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class OwnedVideoMediaSource(
    val remote: RemoteVideo,
    child: MediaSource,
    private val onClosed: () -> Unit = {},
) : WrappingMediaSource(child) {
    private var prepared = false
    private var retiring = false
    private var closed = false
    private val periods = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<MediaPeriod, Boolean>())

    override fun prepareSourceInternal() {
        check(!closed) { "Disposed video source cannot be prepared" }
        prepared = true
        super.prepareSourceInternal()
    }

    override fun createPeriod(id: MediaSource.MediaPeriodId, allocator: Allocator, startPositionUs: Long): MediaPeriod =
        super.createPeriod(id, allocator, startPositionUs).also { periods.add(it) }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        // Release the extractor/loading thread before releasing its underlying file.
        super.releasePeriod(mediaPeriod)
        periods.remove(mediaPeriod)
        closeIfUnused()
    }

    override fun releaseSourceInternal() {
        super.releaseSourceInternal()
        prepared = false
        closeIfUnused()
    }

    /** Call after the player's remove command on its playback looper, or after player.release. */
    fun retire() {
        retiring = true
        closeIfUnused()
    }

    private fun closeIfUnused() {
        if (retiring && !prepared && periods.isEmpty() && !closed) {
            closed = true
            try { remote.close() } finally { onClosed() }
        }
    }

    companion object {
        fun create(mediaId: String, remote: RemoteVideo, cacheOnly: Boolean, onClosed: () -> Unit): OwnedVideoMediaSource {
            val item = MediaItem.Builder().setMediaId(mediaId).setUri("hongguotv://episode/$mediaId")
                .setMimeType(MimeTypes.VIDEO_MP4).build()
            val child = ProgressiveMediaSource.Factory { VideoDataSource(remote, cacheOnly) }.createMediaSource(item)
            return OwnedVideoMediaSource(remote, child, onClosed)
        }
    }
}
