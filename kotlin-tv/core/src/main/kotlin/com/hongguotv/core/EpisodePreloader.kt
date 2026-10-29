package com.hongguotv.core

import java.io.Closeable
import java.io.File
import java.util.concurrent.Executor
import okhttp3.OkHttpClient

/** One tested owner for speculative preparation, partial-cache refill and foreground transfer. */
class EpisodePreloader(
    executor: Executor,
    private val http: OkHttpClient,
    private val directory: () -> File,
    private val resolve: (String) -> StreamInfo,
    val gate: PreloadGate,
    clock: () -> Long,
) : Closeable {
    @Volatile private var active: RemoteVideo?=null
    @Volatile private var activeClaim: ClaimSignal?=null
    private val window=RollingPrefetch<RemoteVideo>(executor,
        load={key,claim,budget ->
            gate.await(claim)
            try {
                val remote=RemoteVideo(http,resolve(key),gate,background=true)
                activeClaim=claim; active=remote
                try {
                    gate.await(claim)
                    remote.prepare()
                    fill(remote,claim,budget)
                } catch(error: Exception) { remote.close(); throw error }
                finally { if(active===remote) {active=null;activeClaim=null} }
            } catch(error: Exception) {
                RequestScope.ensureActive()
                gate.failed(error)
                throw error
            }
        },
        duration={if(it.cachedSize()>=it.total) it.durationMs() else null},
        bytes={it.cachedSize()}, clock=clock, memory={it.retainedBytes()},
        resumable={it.canResumePreload()}, resumeAllowed={gate.allowed()},
        resume={remote,claim,budget ->
            activeClaim=claim; active=remote
            try { fill(remote,claim,budget) }
            finally { if(active===remote) {active=null;activeClaim=null} }
        })
    init { gate.onDemand={yieldToForeground()} }
    private fun fill(remote: RemoteVideo,claim: ClaimSignal,budget: Long): RemoteVideo {
        claim.onClaim { remote.promote() }
        return remote.preload(directory(),claim,budget) { error ->
            // End this attempt immediately with verified bytes intact. The window resumes it
            // after the shared cooldown; a claim need not wait for the retry timer.
            gate.failed(error)
            false
        }
    }
    fun configure(config: PreloadSettings)=window.configure(config)
    fun update(keys: List<String>)=window.update(keys)
    fun pump(): Boolean=window.pump()
    fun claim(key: String): PendingPreparation<RemoteVideo>?=window.claim(key)
    fun count(): Int=window.count()
    fun yieldToForeground() { if(activeClaim?.invoke()!=true) active?.yieldToForeground() }
    override fun close() { gate.update(false,0,0); window.close() }
}
