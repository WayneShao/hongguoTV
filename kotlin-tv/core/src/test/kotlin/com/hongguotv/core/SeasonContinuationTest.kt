package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class SeasonContinuationTest {
    private val current=Series("old","重返1998第十二季",bookType=2)
    private val next=Series("next","重返1998第十三季",bookType=2)
    private fun resolve(pages: List<CatalogPage>, detail: Detail=Detail(next,listOf("ep"))) =
        SeasonContinuation.find(current,{ _,page -> pages[page-1] },{ detail })

    @Test fun acceptsChineseAndArabicAdjacentSeasonsAndDeduplicatesIds() {
        assertEquals("next",resolve(listOf(CatalogPage(listOf(next,next),false)))?.series?.id)
        assertEquals("next",SeasonContinuation.find(current.copy(title="重返1998第12季"),
            { _,_ -> CatalogPage(listOf(next.copy(title="重返1998第13季")),false) },
            { Detail(next,listOf("ep")) })?.series?.id)
    }
    @Test fun laterPageAmbiguityIsRejected() {
        assertNull(resolve(listOf(CatalogPage(listOf(next),true),CatalogPage(listOf(next.copy(id="other")),false))))
    }
    @Test fun rejectsMalformedChineseNumbersAndAcceptsHundreds() {
        for(number in listOf("一二","零二","一百二","十十","一十")) {
            assertNull(SeasonContinuation.find(current.copy(title="故事第一季"),
                { _,_ -> CatalogPage(listOf(next.copy(title="故事第${number}季")),false) },
                { Detail(next.copy(title="故事第${number}季"),listOf("ep")) }))
        }
        val target=next.copy(title="故事第一百零二季")
        assertNotNull(SeasonContinuation.find(current.copy(title="故事第101季"),
            { _,_ -> CatalogPage(listOf(target),false) },{ Detail(target,listOf("ep")) }))
    }
    @Test fun rejectsWrongTypesPreviewsUnnumberedAndDifferentBase() {
        for(candidate in listOf(next.copy(bookType=0),next.copy(bookType=3),next.copy(preview=true),
            next.copy(title="重返1999第十三季"),next.copy(title="重返1998"),next.copy(title="重返1998第十四季"))) {
            assertNull(resolve(listOf(CatalogPage(listOf(candidate),false))))
        }
    }
    @Test fun detailMustReconfirmIdentityAndPlayableEpisodes() {
        for(detail in listOf(Detail(next.copy(id="wrong"),listOf("ep")),Detail(next.copy(title="其他第十三季"),listOf("ep")),
            Detail(next.copy(bookType=0),listOf("ep")),Detail(next,emptyList()))) {
            assertNull(resolve(listOf(CatalogPage(listOf(next),false)),detail))
        }
    }
    @Test fun interruptedSearchNeverAcceptsAnEarlierMatch() {
        assertThrows(java.io.IOException::class.java) {
            SeasonContinuation.find(current,{ _,page -> if(page==1) CatalogPage(listOf(next),true) else throw java.io.IOException() },{ Detail(next,listOf("ep")) })
        }
    }
    @Test fun lookupIsBoundedToThreePages() {
        var count=0
        assertNull(SeasonContinuation.find(current,{ _,_ -> count++; CatalogPage(emptyList(),true) },{ error("unexpected detail") }))
        assertEquals(3,count)
    }
    @Test fun seasonNamesMayOmitTheDiPrefix() {
        assertEquals("next",SeasonContinuation.find(current.copy(title="长剧39季"),
            { _,_ -> CatalogPage(listOf(next.copy(title="长剧40季")),false) },
            { Detail(next.copy(title="长剧40季"),listOf("ep")) })?.series?.id)
        assertEquals(40,SeasonContinuation.name("长剧40季")?.season)
        assertEquals(40,SeasonContinuation.name("长剧第四十季")?.season)
        assertEquals("长剧",SeasonContinuation.name("长剧第40季")?.base)
    }

    @Test fun malformedOrEmbeddedSeasonMarkersAreNotSeasons() {
        for(title in listOf("长剧第三季番外","长剧2季3季","长剧第3季外传","长剧季","长剧第")) {
            assertNull(title,SeasonContinuation.name(title))
        }
        // A bare title plus trusted metadata is accepted only when the title carries no marker.
        assertNull(SeasonContinuation.find(current.copy(title="重返1998第三季番外",seasonIndex=3),
            { _,_ -> error("unexpected search") },{ error("unexpected detail") }))
    }

    @Test fun explicitSeasonIndexResolvesBareTitlesAndIsNeverImplicitSeasonOne() {
        val unnumbered=current.copy(title="重返1998",seasonIndex=2)
        val following=next.copy(title="重返1998",seasonIndex=3)
        assertEquals("next",SeasonContinuation.find(unnumbered,
            { _,_ -> CatalogPage(listOf(following),false) },
            { Detail(following,listOf("ep")) })?.series?.id)
        // No metadata and no marker means no season at all; first season is not inferred.
        assertNull(SeasonContinuation.find(current.copy(title="重返1998"),
            { _,_ -> CatalogPage(listOf(following),false) },
            { Detail(following,listOf("ep")) }))
        assertNull(SeasonContinuation.find(unnumbered,
            { _,_ -> CatalogPage(listOf(following.copy(seasonIndex=0)),false) },
            { Detail(following.copy(seasonIndex=0),listOf("ep")) }))
    }

    @Test fun contradictoryTitleAndMetadataAreRejected() {
        assertNull(SeasonContinuation.find(current.copy(title="重返1998第二季",seasonIndex=3),
            { _,_ -> error("unexpected search") },{ error("unexpected detail") }))
        assertNull(SeasonContinuation.find(current,
            { _,_ -> CatalogPage(listOf(next.copy(title="重返1998第十三季",seasonIndex=14)),false) },
            { Detail(next,listOf("ep")) }))
    }

    @Test fun bareSeasonIndexOutsideParsedRangeIsNotTrusted() {
        // The title parser only accepts 1..999, so a larger bare-title index identifies nothing.
        assertNull(SeasonContinuation.find(current.copy(title="长剧",seasonIndex=1000),
            { _,_ -> error("unexpected search") },{ error("unexpected detail") }))
        assertNull(SeasonContinuation.find(current.copy(title="长剧",seasonIndex=1000),
            { _,_ -> CatalogPage(listOf(next.copy(title="长剧",seasonIndex=1001)),false) },
            { Detail(next.copy(title="长剧",seasonIndex=1001),listOf("ep")) }))
        // The supported boundary and single-digit values still work.
        assertEquals("next",SeasonContinuation.find(current.copy(title="长剧",seasonIndex=998),
            { _,_ -> CatalogPage(listOf(next.copy(title="长剧",seasonIndex=999)),false) },
            { Detail(next.copy(title="长剧",seasonIndex=999),listOf("ep")) })?.series?.id)
        assertEquals("next",SeasonContinuation.find(current.copy(title="长剧",seasonIndex=1),
            { _,_ -> CatalogPage(listOf(next.copy(title="长剧",seasonIndex=2)),false) },
            { Detail(next.copy(title="长剧",seasonIndex=2),listOf("ep")) })?.series?.id)
        // Zero stays unknown and never implies a season.
        assertNull(SeasonContinuation.find(current.copy(title="长剧",seasonIndex=0),
            { _,_ -> CatalogPage(listOf(next.copy(title="长剧",seasonIndex=1)),false) },
            { Detail(next.copy(title="长剧",seasonIndex=1),listOf("ep")) }))
        // Out-of-range metadata cannot contradict or outvote an explicit marker either.
        assertNull(SeasonContinuation.find(current.copy(title="长剧第三季",seasonIndex=1000),
            { _,_ -> error("unexpected search") },{ error("unexpected detail") }))
    }

    @Test fun detailMayOmitSeasonMetadataButNeverOverridesVerifiedCandidate() {
        val unnumbered=current.copy(title="重返1998",seasonIndex=2)
        val following=next.copy(title="重返1998",seasonIndex=3)
        fun resolve(detail: Detail)=SeasonContinuation.find(unnumbered,
            { _,_ -> CatalogPage(listOf(following),false) },{ detail })
        assertEquals(3,resolve(Detail(following.copy(seasonIndex=0),listOf("ep")))?.series?.seasonIndex)
        // Merging is allowed only for the same id and title.
        assertNull(resolve(Detail(following.copy(id="other",seasonIndex=0),listOf("ep"))))
        assertNull(resolve(Detail(following.copy(title="重返1999",seasonIndex=0),listOf("ep"))))
        // Contradicting metadata is never silently replaced.
        assertNull(resolve(Detail(following.copy(seasonIndex=2),listOf("ep"))))
        assertNull(resolve(Detail(following.copy(seasonIndex=0),emptyList())))
        assertNull(resolve(Detail(following.copy(seasonIndex=0),listOf(""))))
    }

    @Test fun staleTicketAndRepeatedEndCannotStartAgainUntilNewPlayback() {
        val gate=SeasonContinuationGate()
        val token=gate.begin()!!
        assertNull(gate.begin())
        gate.invalidate()
        assertFalse(gate.valid(token))
        assertNull(gate.begin())
        gate.reset()
        val fresh=gate.begin()!!
        assertFalse(gate.valid(token));assertTrue(gate.valid(fresh))
        assertTrue(gate.consume(fresh));assertFalse(gate.consume(fresh))
    }
}
