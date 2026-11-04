package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PreloadStatisticsTest {
    @Test fun recordsRealHandoffsAndSeparatesNaturalFromManualQueuedPlayback() {
        val stats=PreloadStatistics()
        stats.acquired(0);stats.acquired(1);stats.acquired(2)
        stats.acquired(2);stats.naturalTransition()
        val snapshot=stats.snapshot()
        assertEquals(1,snapshot.uncached);assertEquals(1,snapshot.partial);assertEquals(2,snapshot.complete);assertEquals(1,snapshot.natural)
        assertEquals(0,PreloadStatistics().snapshot().complete)
    }
}
