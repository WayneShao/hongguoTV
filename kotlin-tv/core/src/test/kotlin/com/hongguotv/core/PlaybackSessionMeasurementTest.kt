package com.hongguotv.core
import org.junit.Assert.*
import org.junit.Test
class PlaybackSessionMeasurementTest {
    @Test fun shortPlaybackCountsActualFrameAndExcludesStartupBuffering() {
        val stats=PreloadStatistics();val m=PlaybackSessionMeasurement(stats,100)
        m.state(110,true,true);m.state(180,false,true)
        assertEquals(0,stats.snapshot().renderedStarts)
        m.firstFrame(190);m.firstFrame(200);m.close(250)
        assertEquals(1,stats.snapshot().renderedStarts)
        assertEquals(90L,stats.snapshot().startupMs)
        assertEquals(0,stats.snapshot().bufferingCount)
    }
    @Test fun rebufferEndsAtPauseAndSeekingIsExcluded() {
        val stats=PreloadStatistics();val m=PlaybackSessionMeasurement(stats,0)
        m.firstFrame(10);m.state(20,true,true);m.state(30,true,true)
        m.state(70,true,false)
        m.seek(80);m.state(90,true,true);m.state(100,false,true)
        m.state(110,true,true);m.close(140);m.close(150)
        assertEquals(2,stats.snapshot().bufferingCount)
        assertEquals(80L,stats.snapshot().bufferingMs)
    }
    @Test fun suppressionSplitsBufferingAndExcludesSuppressedTime() {
        val stats=PreloadStatistics();val m=PlaybackSessionMeasurement(stats,0)
        m.firstFrame(10)
        m.state(20,true,true)
        m.state(50,true,false) // suppressed while still buffering
        m.state(150,true,true) // suppression lifted, still buffering
        m.state(190,false,true) // ready
        assertEquals(2,stats.snapshot().bufferingCount)
        assertEquals(70L,stats.snapshot().bufferingMs)
    }
    @Test fun interruptedStartupIsNotReportedAsLatency() {
        val stats=PreloadStatistics();val m=PlaybackSessionMeasurement(stats,0)
        m.interrupted(100);m.firstFrame(1000)
        assertEquals(0,stats.snapshot().renderedStarts)
    }
}
