package com.hongguotv.core

/** Process-session counters only. Acquisition is not proof of a rendered frame. */
class PreloadStatistics {
    companion object { val session=PreloadStatistics() }
    data class Snapshot(val uncached: Int,val partial: Int,val complete: Int,val natural: Int)
    private var uncached=0;private var partial=0;private var complete=0;private var natural=0
    @Synchronized fun acquired(cacheKind: Int) { when(cacheKind) { 2 -> complete++;1 -> partial++;else -> uncached++ } }
    @Synchronized fun naturalTransition() { natural++ }
    @Synchronized fun snapshot()=Snapshot(uncached,partial,complete,natural)
}
