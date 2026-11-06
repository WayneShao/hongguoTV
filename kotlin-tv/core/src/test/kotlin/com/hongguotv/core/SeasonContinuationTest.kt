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
