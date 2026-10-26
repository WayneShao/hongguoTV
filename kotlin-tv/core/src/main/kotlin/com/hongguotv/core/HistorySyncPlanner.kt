// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/** Bounded work per pass; legacy failures cool down without starving untried records. */
object HistorySyncPlanner {
    const val LEGACY_RETRY_MS=5*60_000L
    private val numericId=Regex("[0-9]{1,30}")
    fun known(row: HistoryCheckpoint)=row.bookType in 2..5 && row.episodeCount>0 &&
        row.progress.episodeIndex in 0 until row.episodeCount &&
        numericId.matches(row.progress.series.id) && numericId.matches(row.progress.episodeId)
    fun select(rows: Collection<HistoryCheckpoint>,attempts: Map<String,Long>,now: Long): List<HistoryCheckpoint> {
        val known=rows.filter(::known).sortedByDescending { it.progress.updatedAt }.take(20)
        val legacy=rows.filterNot(::known).filter { row ->
            val last=attempts[row.progress.series.id]
            last==null || now<last || now-last>=LEGACY_RETRY_MS
        }.sortedWith(compareBy<HistoryCheckpoint> { attempts[it.progress.series.id] ?: Long.MIN_VALUE }
            .thenByDescending { it.progress.updatedAt }).take(3)
        return known+legacy
    }
}
