// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.os.Handler
import androidx.media3.exoplayer.ExoPlayer
import com.hongguotv.core.RemoteVideo

/** Main/application-looper controller. At most current + one fully cached successor. */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SeamlessPlaybackQueue(private val player: ExoPlayer) {
    private val playbackHandler = Handler(player.playbackLooper)
    private val applicationHandler = Handler(player.applicationLooper)
    private val sources = linkedMapOf<String, OwnedVideoMediaSource>()
    // Keep removed sources until the player confirms their release (including never-prepared items).
    private val owned = java.util.Collections.synchronizedSet(mutableSetOf<OwnedVideoMediaSource>())
    private var disposed = false

    init { player.setPreloadConfiguration(ExoPlayer.PreloadConfiguration(3_000_000L)) }

    val mediaIds: List<String> get() = sources.keys.toList()
    val nextMediaId: String? get() = mediaIds.getOrNull(player.currentMediaItemIndex + 1)
    fun remoteFor(mediaId: String): RemoteVideo? = sources[mediaId]?.remote
    fun qualityFor(mediaId: String): String? = remoteFor(mediaId)?.info?.quality

    private fun source(mediaId: String, remote: RemoteVideo, cacheOnly: Boolean, onClosed: () -> Unit): OwnedVideoMediaSource {
        lateinit var result: OwnedVideoMediaSource
        result = OwnedVideoMediaSource.create(mediaId, remote, cacheOnly) {
            owned.remove(result)
            applicationHandler.post { onClosed() }
        }
        owned.add(result)
        return result
    }

    /** Installs ownership; caller performs initial seek/prepare. Never use this on natural transition. */
    fun installCurrent(mediaId: String, remote: RemoteVideo, onClosed: () -> Unit = {}) {
        check(!disposed)
        val old = sources.values.toList()
        val item = source(mediaId, remote, false, onClosed)
        sources.clear()
        sources[mediaId] = item
        player.setMediaSource(item)
        retireAfterPlayerCommand(old)
    }

    /** Ownership transfers only when true; a rejected candidate remains the caller's responsibility. */
    fun appendNext(mediaId: String, remote: RemoteVideo, onClosed: () -> Unit = {}): Boolean {
        if (disposed || sources.size != 1 || mediaId in sources || !remote.isCacheComplete()) return false
        val item = source(mediaId, remote, true, onClosed)
        sources[mediaId] = item
        player.addMediaSource(item)
        return true
    }

    fun dropNext() {
        if (disposed) return
        val currentIndex = player.currentMediaItemIndex
        val ids = mediaIds.drop(currentIndex + 1)
        if (ids.isEmpty()) return
        val removed = ids.mapNotNull { sources.remove(it) }
        player.removeMediaItems(currentIndex + 1, currentIndex + 1 + removed.size)
        retireAfterPlayerCommand(removed)
    }

    /** Manual episode/quality changes only; natural transitions use advanceTo. */
    fun clear() {
        if (disposed) return
        val removed = sources.values.toList()
        sources.clear()
        player.clearMediaItems()
        retireAfterPlayerCommand(removed)
    }

    fun seekNext(mediaId: String): Boolean {
        if (disposed || nextMediaId != mediaId) return false
        player.seekToNextMediaItem()
        return true
    }

    /** Prune only preceding items after an actual mediaId transition; no stop, clear, seek or prepare. */
    fun advanceTo(mediaId: String) {
        if (disposed || player.currentMediaItem?.mediaId != mediaId) return
        val index = mediaIds.indexOf(mediaId)
        if (index <= 0) return
        val removed = mediaIds.take(index).mapNotNull { sources.remove(it) }
        player.removeMediaItems(0, index)
        retireAfterPlayerCommand(removed)
    }

    private fun retireAfterPlayerCommand(items: List<OwnedVideoMediaSource>) {
        // FIFO barrier with ExoPlayerImplInternal: pending add/prepare must run before retirement.
        playbackHandler.post { items.forEach { it.retire() } }
    }

    /** Required order: player.release(), then this method. Also closes items never prepared. */
    fun releaseAfterPlayerRelease() {
        if (disposed) return
        disposed = true
        sources.clear()
        val remaining = synchronized(owned) { owned.toList() }
        remaining.forEach { it.retire() }
    }
}
