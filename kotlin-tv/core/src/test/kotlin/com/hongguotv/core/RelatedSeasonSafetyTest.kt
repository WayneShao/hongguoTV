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

    @Test fun omittedPrefixAdjacentSeasonsCanAutoplay() {
        val next = RelatedWorks.verifiedNextSeason(
            Series("current", "长剧39季", bookType = 2),
            { listOf(candidate("长剧40季")) },
            { Detail(Series(it, "长剧40季"), listOf("episode")) },
        )
        assertEquals("next", next?.series?.id)
        assertEquals(40, SeasonContinuation.name("长剧第40季")?.season)
    }

    @Test fun trustedSeasonIndexDisambiguatesBareRelatedTitles() {
        val current = Series("current", "长剧", bookType = 2, seasonIndex = 2)
        val row = RelatedWork(Series("next", "长剧", bookType = 2, seasonIndex = 3), "", false, 3, 1004)
        val next = RelatedWorks.verifiedNextSeason(
            current,
            { listOf(row) },
            { Detail(Series(it, "长剧"), listOf("episode")) },
        )
        assertEquals("next", next?.series?.id)
        assertEquals(3, next?.series?.seasonIndex)
        // Without metadata there is no implicit first season.
        assertNull(RelatedWorks.verifiedNextSeason(
            Series("current", "长剧", bookType = 2),
            { listOf(RelatedWork(Series("next", "长剧", bookType = 2), "", false, 0, 1004)) },
            { Detail(Series(it, "长剧"), listOf("episode")) },
        ))
    }

    @Test fun outOfRangeBareSeasonMetadataCanNeitherLookupNorAutoplay() {
        fun row(index: Int) =
            RelatedWork(Series("next", "长剧", bookType = 2, seasonIndex = index), "", false, index, 1004)
        // 1..999 is the range the title parser can verify; larger metadata is not trusted.
        assertNull(RelatedWorks.nextSeason(Series("current", "长剧", bookType = 2, seasonIndex = 1000), listOf(row(1001))))
        assertNull(RelatedWorks.nextSeason(Series("current", "长剧", bookType = 2, seasonIndex = 999), listOf(row(1000))))
        assertNull(RelatedWorks.verifiedNextSeason(
            Series("current", "长剧", bookType = 2, seasonIndex = 1000),
            { listOf(row(1001)) },
            { Detail(Series(it, "长剧", seasonIndex = 1001), listOf("episode")) },
        ))
        // The supported boundary still resolves and autoplays.
        assertEquals("next", RelatedWorks.nextSeason(
            Series("current", "长剧", bookType = 2, seasonIndex = 998), listOf(row(999)),
        )?.series?.id)
        assertEquals("next", RelatedWorks.verifiedNextSeason(
            Series("current", "长剧", bookType = 2, seasonIndex = 998),
            { listOf(row(999)) },
            { Detail(Series(it, "长剧", seasonIndex = 999), listOf("episode")) },
        )?.series?.id)
        // Out-of-range metadata contradicted by markers is rejected as well.
        assertNull(RelatedWorks.nextSeason(
            Series("current", "长剧第三季", bookType = 2, seasonIndex = 1000), listOf(row(1001)),
        ))
    }

    @Test fun conflictingTitleMetadataAndPreviewAreNotAutoplayed() {
        val current = Series("current", "长剧第二季", bookType = 2)
        assertNull(RelatedWorks.verifiedNextSeason(
            current,
            { listOf(RelatedWork(Series("next", "长剧第三季", bookType = 2, seasonIndex = 4), "", false, 4, 1004)) },
            { Detail(Series(it, "长剧第三季"), listOf("episode")) },
        ))
        assertNull(RelatedWorks.verifiedNextSeason(
            current.copy(seasonIndex = 3),
            { listOf(candidate("长剧第三季")) },
            { Detail(Series(it, "长剧第三季"), listOf("episode")) },
        ))
        assertNull(RelatedWorks.verifiedNextSeason(
            current,
            { listOf(RelatedWork(Series("next", "长剧第三季", bookType = 2, preview = true), "", false, 3, 1004)) },
            { Detail(Series(it, "长剧第三季"), listOf("episode")) },
        ))
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
