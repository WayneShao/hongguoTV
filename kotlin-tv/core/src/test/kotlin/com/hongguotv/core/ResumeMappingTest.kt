package com.hongguotv.core
import org.junit.Test
import org.junit.Assert.*
class ResumeMappingTest {
    private val episodes=listOf("a","b","c")
    @Test fun exactIdWinsOverCloudIndex() { assertEquals(ResumeTarget(1,1200),ResumePlayback.target(episodes,"b",1200,false,2)) }
    @Test fun differentIdUsesValidCloudIndex() { assertEquals(ResumeTarget(2,1200),ResumePlayback.target(episodes,"other",1200,false,2)) }
    @Test fun invalidIndexCannotResumeUnrelatedEpisode() { assertNull(ResumePlayback.resolve(episodes,"other",1200,false,99)) }
    @Test fun missingCheckpointStartsFirstEpisode() { assertEquals(ResumeTarget(0,0),ResumePlayback.resolve(episodes,null,0,false)) }
    @Test fun shortenedCatalogCannotResetEpisodeTwelve() { assertNull(ResumePlayback.resolve(episodes,"episode12",60000,false,11)) }
    @Test fun localEpisodeTwelveSurvivesAnAccountWithNoHistory() {
        val local=BackupProgress(Series("1","test",""),"episode12",11,60000,120000,false,1)
        val selected=ResumePlayback.progress(true,local,null)!!
        assertEquals(ResumeTarget(11,60000),ResumePlayback.resolve((1..20).map { "episode$it" },selected.episodeId,selected.position,selected.completed,selected.episodeIndex))
    }
    @Test fun explicitLocalSelectionIgnoresAccountFiltering() {
        val local=BackupProgress(Series("1","test",""),"episode12",11,60000,120000,false,1)
        assertSame(local,ResumePlayback.progress(true,local,null))
        assertNull(ResumePlayback.progress(false,local,null))
        val cloud=local.copy(episodeId="a",episodeIndex=0,updatedAt=2)
        assertSame(local,ResumePlayback.progress(true,local,cloud))
        assertSame(cloud,ResumePlayback.progress(false,local,cloud))
    }
    @Test fun completedFallbackMovesToFollowingEpisode() { assertEquals(ResumeTarget(2,0),ResumePlayback.target(episodes,"other",1200,true,1)) }
}
