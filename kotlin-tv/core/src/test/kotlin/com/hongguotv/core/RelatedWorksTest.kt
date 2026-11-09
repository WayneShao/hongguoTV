package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class RelatedWorksTest {
    private fun row(
        id: String,
        title: String,
        type: Int = 1004,
        episodes: Int = 10,
        season: Int = 0,
        tag: String = "",
        disable: Boolean = false,
    ) = JSONObject().apply {
        put("series_id", id).put("title", title).put("content_type", type)
        put("episode_cnt", episodes).put("season_index", season)
        if (tag.isNotBlank()) put("tag_info", JSONObject().put("text", tag))
        if (disable) put("disable_play", true)
    }

    private fun raw(vararg rows: JSONObject, showType: Int = 547, cellName: String? = null) = JSONObject()
        .put("code", 0)
        .put(
            "data",
            JSONArray().put(
                JSONObject().apply {
                    put("show_type", showType)
                    if (cellName != null) put("cell_name", cellName)
                    put("video_data", JSONArray(rows.toList()))
                }
            ),
        ).toString()

    @Test fun parseKeepsOnlyActionableShortDramasFromTheRelatedCell() {
        val rows = RelatedWorks.parse(
            raw(
                row("222", "长剧第二季", season = 2, tag = "第2季"),
                row("111", "长剧第一季", season = 1),
                row("abc", "非法标识"),
                row("333", "图文作品", type = 1005),
                row("444", "已下架", disable = true),
                row("555", "空剧集", episodes = 0),
                row("666", "待上线第三季", type = 1007, episodes = 0, season = 3),
            ),
            currentId = "111",
        )
        assertEquals(listOf("222", "666"), rows.map { it.series.id })
        assertEquals("第2季", rows.first().season)
        assertFalse(rows.first().upcoming)
        assertTrue(rows.last().upcoming)
        assertEquals("第3季", rows.last().season)
    }

    @Test fun parseAcceptsCellByNameAndFallsBackToSeasonIndex() {
        val rows = RelatedWorks.parse(
            raw(row("222", "长剧第二季", season = 2), showType = 1, cellName = "相关作品"),
            currentId = "111",
        )
        assertEquals("第2季", rows.single().season)
        assertEquals("第2季 · 已上线", rows.single().label)
    }

    @Test fun parseRejectsBrokenPlanEnvelopes() {
        assertThrows(java.io.IOException::class.java) {
            RelatedWorks.parse(JSONObject().put("code", 500).toString(), "111")
        }
        assertThrows(java.io.IOException::class.java) {
            RelatedWorks.parse(JSONObject().put("code", 0).toString(), "111")
        }
    }

    @Test fun orderedPutsTheSameSeriesFirstAndSortsItsSeasons() {
        val current = Series("1", "长剧第三季")
        val rows = listOf(
            RelatedWork(Series("4", "短剧第一季"), "第1季", false, 1),
            RelatedWork(Series("3", "长剧第二季"), "第2季", false, 2),
            RelatedWork(Series("2", "长剧第一季"), "第1季", false, 1),
        )
        assertEquals(listOf("2", "3", "4"), RelatedWorks.ordered(current, rows).map { it.series.id })
    }

    @Test fun nextSeasonRequiresConsecutiveUniquePlayableMatchOfTheSameSeries() {
        val current = Series("1", "重返1998第十二季")
        fun next(vararg rows: RelatedWork) = RelatedWorks.nextSeason(current, rows.toList())
        assertEquals(
            "next",
            next(RelatedWork(Series("next", "重返1998第十三季"), "第13季", false, 13))?.series?.id,
        )
        assertNull(next(RelatedWork(Series("next", "重返1998第十三季"), "第13季", true, 13)))
        assertNull(next(RelatedWork(Series("next", "重返1999第十三季"), "第13季", false, 13)))
        assertNull(next(RelatedWork(Series("next", "重返1998第十四季"), "第14季", false, 14)))
        assertNull(
            next(
                RelatedWork(Series("a", "重返1998第十三季"), "第13季", false, 13),
                RelatedWork(Series("b", "重返1998第十三季"), "第13季", false, 13),
            ),
        )
    }

    @Test fun nextSeasonReadsArabicAndChineseFinalSeasons() {
        assertEquals(
            "40",
            RelatedWorks.nextSeason(
                Series("39", "长剧第39季"),
                listOf(RelatedWork(Series("40", "长剧第40季"), "第40季", false, 0)),
            )?.series?.id,
        )
        assertEquals(
            "40",
            RelatedWorks.nextSeason(
                Series("39", "长剧第三十九季"),
                listOf(RelatedWork(Series("40", "长剧第四十季"), "第40季", false, 0)),
            )?.series?.id,
        )
        assertNull(
            RelatedWorks.nextSeason(
                Series("39", "长剧第39季"),
                listOf(RelatedWork(Series("40", "长剧第40季"), "第40季", false, 41)),
            ),
        )
    }

    @Test fun unnumberedSeriesContinuesAtSeasonTwo() {
        assertEquals(
            "next",
            RelatedWorks.nextSeason(
                Series("1", "长剧"),
                listOf(RelatedWork(Series("next", "长剧第二季"), "第2季", false, 2)),
            )?.series?.id,
        )
    }

    private fun sample(name: String) = javaClass.getResource("/$name")!!.readText()

    @Test fun verifiedNextSeasonUsesRelatedContentTypeWhenWebsiteBookTypeIsUnknown() {
        val current = Series("7693090206953507864", "末世选择，从解析神明开始成神第二季", bookType = 0)
        val rows = RelatedWorks.parse(sample("related-resume-sample.json"), current.id)
        assertEquals("7702023183850818585", RelatedWorks.nextSeason(current, rows)?.series?.id)
        var loaded: String? = null
        val next = RelatedWorks.verifiedNextSeason(current, { rows }, { id ->
            loaded = id
            Detail(
                Series(id, "末世选择，从解析神明开始成神第三季", bookType = 0),
                (1..116).map { "episode-$it" },
            )
        })
        assertEquals("7702023183850818585", next?.series?.id)
        assertEquals(116, next?.episodes?.size)
        assertEquals("7702023183850818585", loaded)
    }

    @Test fun verifiedNextSeasonRejectsAmbiguousSameNameSeasonBeforeLoadingDetail() {
        val current = Series("7693090206953507864", "末世选择，从解析神明开始成神第二季", bookType = 0)
        val rows = RelatedWorks.parse(sample("related-resume-sample.json"), current.id) + listOf(
            RelatedWork(
                Series("7702023183850818586", "末世选择，从解析神明开始成神第三季"),
                "第3季",
                false,
                3,
            ),
        )
        var details = 0
        val next = RelatedWorks.verifiedNextSeason(current, { rows }, { id ->
            details++
            Detail(Series(id, "末世选择，从解析神明开始成神第三季"), listOf("episode-1"))
        })
        assertNull(next)
        assertEquals(0, details)
    }

    @Test fun verifiedNextSeasonRequiresMatchingDetailIdTitleAndEpisodes() {
        val current = Series("1", "长剧第二季")
        val rows = listOf(RelatedWork(Series("2", "长剧第三季"), "第3季", false, 3))
        fun verify(detail: Detail) = RelatedWorks.verifiedNextSeason(current, { rows }, { detail })
        val good = Detail(Series("2", "长剧第三季"), listOf("episode-1"))
        assertEquals("2", verify(good)?.series?.id)
        assertNull(verify(Detail(Series("3", "长剧第三季"), listOf("episode-1"))))
        assertNull(verify(Detail(Series("2", "另一部短剧第三季"), listOf("episode-1"))))
        assertNull(verify(Detail(Series("2", "长剧第三季"), emptyList())))
        assertNull(verify(Detail(Series("2", "长剧第三季"), listOf(""))))
        assertNull(verify(Detail(good.series.copy(preview = true), good.episodes)))
    }

    @Test fun realProbeSampleKeepsAllFortyFourRowsAndRejectsDuplicateNextSeason() {
        val current = Series("7673067074104593432", "谁说纨绔不能当状元", bookType = 0)
        val rows = RelatedWorks.parse(sample("related-real-44.json"), current.id)
        assertEquals(44, rows.size)
        assertEquals(44, RelatedWorks.ordered(current, rows).size)
        assertEquals(2, rows.count { it.series.title == "谁说纨绔不能当状元第二季" })
        assertNull(RelatedWorks.nextSeason(current, rows))
        val fromSecond = current.copy(title = "谁说纨绔不能当状元第二季")
        assertNull(RelatedWorks.nextSeason(fromSecond, rows))
        val fromSixth = current.copy(title = "谁说纨绔不能当状元第六季")
        assertEquals(
            "7697917394085235774",
            RelatedWorks.nextSeason(fromSixth, rows)?.series?.id,
        )
    }
}
