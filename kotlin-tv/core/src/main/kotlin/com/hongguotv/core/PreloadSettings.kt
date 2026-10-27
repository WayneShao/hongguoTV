package com.hongguotv.core

enum class PreloadMode { TIME, EPISODES }
data class PreloadSettings(
    val mode: PreloadMode=PreloadMode.TIME,
    val minutes: Int=5,
    val episodes: Int=10,
    val checkMinutes: Int=1,
    val checkEpisodes: Int=1,
) {
    companion object {
        val timeTargets=listOf(1,2,3,5,10,15,20,30)
        val episodeTargets=listOf(1,2,3,5,10,15,20,30)
        val timeIntervals=listOf(1,2,3,5,10)
        val episodeIntervals=listOf(1,2,3,5,10)
    }
    fun normalized()=copy(minutes=minutes.takeIf { it in timeTargets } ?: 5,
        episodes=episodes.takeIf { it in episodeTargets } ?: 10,
        checkMinutes=checkMinutes.takeIf { it in timeIntervals } ?: 1,
        checkEpisodes=checkEpisodes.takeIf { it in episodeIntervals } ?: 1)
}
/** Playback time only; seeks, stalls, pauses and background time do not advance the check clock. */
class PreloadCheckClock {
    private var last: Long?=null
    private var played=0L
    private var completed=0
    private var first=true
    fun reset() { last=null;played=0;completed=0;first=true }
    fun sample(now: Long,playing: Boolean,speed: Float=1f) {
        val previous=last;last=if(playing) now else null
        if(playing && previous!=null && now-previous in 0..3000 && speed.isFinite() && speed>0)
            played+=((now-previous)*speed).toLong()
    }
    fun episodeCompleted() { completed++ }
    fun due(config: PreloadSettings): Boolean = first || when(config.mode) {
        PreloadMode.TIME -> played>=config.checkMinutes*60_000L
        PreloadMode.EPISODES -> completed>=config.checkEpisodes
    }
    fun checked() { first=false;played=0;completed=0 }
}
