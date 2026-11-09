package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class RelatedSeasonSafetyTest {
    private fun candidate(title: String, bookType: Int = 0) =
        RelatedWork(Series("next", title, bookType = bookType), "", false, 0, 1004)

    @Test fun malformedCurrentSeasonCannotAutoplay() {
        for ((current, next) in listOf(
            "长剧第十十季" to "长剧第二十一季",
            "长剧第一二季" to "长剧第三季",
            "长剧第十百季" to "长剧第一百一十一季",
            "长剧2季" to "长剧3季",
            "长剧第1000季" to "长剧第1001季",
        )) assertNull(current, RelatedWorks.nextSeason(Series("current", current), listOf(candidate(next))))
    }

    @Test fun malformedCandidateSeasonCannotAutoplay() {
        for ((current, next) in listOf(
            "长剧第一季" to "长剧第一二季",
            "长剧第十九季" to "长剧第十十季",
        )) assertNull(next, RelatedWorks.nextSeason(Series("current", current), listOf(candidate(next))))
    }

    @Test fun fullWidthSeasonNumbersRetainNormalization() {
        val next = RelatedWorks.verifiedNextSeason(
            Series("current", "长剧 第２季"),
            { listOf(candidate("长剧第３季")) },
            { Detail(Series(it, "长剧第3季"), listOf("episode")) },
        )
        assertEquals("next", next?.series?.id)
    }

    @Test fun conflictingKnownTypesCannotAutoplay() {
        for (candidateType in listOf(0, 2, 4, 1)) {
            val next = RelatedWorks.verifiedNextSeason(
                Series("current", "长剧第二季", bookType = 2),
                { listOf(candidate("长剧第三季", candidateType)) },
                { Detail(Series(it, "长剧第三季", bookType = 4), listOf("episode")) },
            )
            assertNull("candidate type $candidateType", next)
        }
    }

    @Test fun matchingKnownTypesAndAuthoritativeUnknownTypesRemainPlayable() {
        for ((source, target) in listOf(0 to 0, 2 to 0, 0 to 4, 2 to 2)) {
            val next = RelatedWorks.verifiedNextSeason(
                Series("current", "长剧第二季", bookType = source),
                { listOf(candidate("长剧第三季")) },
                { Detail(Series(it, "长剧第三季", bookType = target), listOf("episode")) },
            )
            assertEquals("next", next?.series?.id)
        }
    }
}
