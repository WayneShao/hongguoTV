package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class HistorySyncPlannerTest {
    private fun row(id: Int,known: Boolean)=HistoryCheckpoint(BackupProgress(Series(id.toString(),"fixture",bookType=2),"1",0,1000,10000,false,id.toLong()),2,if(known) 10 else 0)
    @Test fun boundsLargeBacklogAndPrioritizesKnownRecords() {
        val rows=(1..500).map { row(it,it<=200) }
        val plan=HistorySyncPlanner.select(rows,emptyMap(),1000)
        assertEquals(23,plan.size)
        assertTrue(plan.take(20).all(HistorySyncPlanner::known))
        assertEquals("200",plan.first().progress.series.id)
    }
    @Test fun failingLegacyRecordsDoNotStarveUntriedRecords() {
        val rows=(1..10).map { row(it,false) }
        val first=HistorySyncPlanner.select(rows,emptyMap(),1000)
        val attempts=first.associate { it.progress.series.id to 1000L }
        val next=HistorySyncPlanner.select(rows,attempts,2000)
        assertEquals(3,next.size)
        assertTrue(next.none { it in first })
        assertTrue(HistorySyncPlanner.select(first,attempts,2000).isEmpty())
        assertEquals(3,HistorySyncPlanner.select(first,attempts,301000).size)
    }
    @Test fun malformedKnownRecordAlsoCoolsDown() {
        val invalid=row(1,true).let { it.copy(progress=it.progress.copy(episodeIndex=99)) }
        assertFalse(HistorySyncPlanner.known(invalid))
        assertTrue(HistorySyncPlanner.select(listOf(invalid),mapOf("1" to 1000L),2000).isEmpty())
    }
    @Test fun cooldownNeverBlocksKnownCheckpointsAndHandlesClockReset() {
        val known=row(1,true); val legacy=row(2,false)
        assertEquals(listOf(known),HistorySyncPlanner.select(listOf(known,legacy),mapOf("1" to 1000L,"2" to 1000L),2000))
        assertEquals(2,HistorySyncPlanner.select(listOf(known,legacy),mapOf("2" to 1000L),500).size)
    }
}
