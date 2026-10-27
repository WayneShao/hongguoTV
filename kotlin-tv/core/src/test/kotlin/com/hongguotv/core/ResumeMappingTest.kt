package com.hongguotv.core
import org.junit.Test
import org.junit.Assert.*
class ResumeMappingTest {
    private val episodes=listOf("a","b","c")
    @Test fun exactIdWinsOverCloudIndex() { assertEquals(ResumeTarget(1,1200),ResumePlayback.target(episodes,"b",1200,false,2)) }
    @Test fun differentIdUsesValidCloudIndex() { assertEquals(ResumeTarget(2,1200),ResumePlayback.target(episodes,"other",1200,false,2)) }
    @Test fun invalidIndexCannotResumeUnrelatedEpisode() { assertEquals(ResumeTarget(0,0),ResumePlayback.target(episodes,"other",1200,false,99)) }
    @Test fun completedFallbackMovesToFollowingEpisode() { assertEquals(ResumeTarget(2,0),ResumePlayback.target(episodes,"other",1200,true,1)) }
}
