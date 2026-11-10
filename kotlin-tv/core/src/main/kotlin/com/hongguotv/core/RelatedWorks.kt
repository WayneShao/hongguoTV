// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.io.IOException
import java.text.Normalizer
import org.json.JSONObject

data class RelatedWork(
    val series: Series,
    val season: String,
    val upcoming: Boolean,
    val seasonIndex: Int = 0,
    val contentType: Int = 0,
) {
    val label
        get() =
            listOf(season, if (upcoming) "待上线 · 预约" else "已上线")
                .filter { it.isNotBlank() }
                .joinToString(" · ")
}

/**
 * Reading plan's related-work cell, verified with public season metadata. Only short-drama types
 * are actionable; novels/audio are intentionally excluded.
 */
object RelatedWorks {
    private val seasonSuffix = Regex("第?([0-9零〇一二两三四五六七八九十百]+)季$")

    private fun seasonNumber(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n > 0 } }
        val digits = "零一二三四五六七八九"
        var total = 0
        var digit = 0
        for (c in value) when (c) {
            '十', '百' -> { total += maxOf(1, digit) * if (c == '十') 10 else 100; digit = 0 }
            '两' -> digit = 2
            '〇' -> digit = 0
            else -> { digit = digits.indexOf(c); if (digit < 0) return null }
        }
        return (total + digit).takeIf { it > 0 }
    }

    private fun stem(title: String) = title.trim().replace(seasonSuffix, "")
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    private fun titleKey(title: String) = Normalizer.normalize(title, Normalizer.Form.NFKC)
        .replace(Regex("[\\p{P}\\p{Z}\\s]"), "")

    fun ordered(current: Series, rows: List<RelatedWork>): List<RelatedWork> = rows.sortedWith(
        compareBy<RelatedWork> { if (stem(it.series.title) == stem(current.title)) 0 else 1 }
            .thenBy { stem(it.series.title) }
            .thenBy { it.seasonIndex.takeIf { n -> n > 0 }
                ?: seasonSuffix.find(it.series.title.trim())?.groupValues?.get(1)?.let(::seasonNumber)
                ?: 1 }
    )

    /**
     * Conservative: both titles must carry explicit adjacent season numbers, the title stems must
     * match, optional season metadata must agree, and exactly one playable candidate may remain.
     * Browsing may infer an unnumbered season; autoplay never does.
     */
    fun nextSeason(current: Series, rows: List<RelatedWork>): RelatedWork? {
        val source = SeasonContinuation.name(current.title) ?: return null
        return rows.filter { row ->
            val candidate = SeasonContinuation.name(row.series.title) ?: return@filter false
            !row.upcoming && row.series.id != current.id && candidate.season == source.season + 1 &&
                (row.seasonIndex <= 0 || row.seasonIndex == candidate.season) &&
                candidate.base == source.base
        }.singleOrNull()
    }

    /**
     * Related rows carry a verified playable content type even when the website detail/search
     * response omits book_type. Only `content_type=1004` is authoritative provenance: unknowns
     * (`bookType=0`) may continue solely on that proof plus detail re-verification, known
     * incompatible types are rejected, and previews never autoplay. Ambiguous same-name seasons
     * are still rejected upstream.
     */
    fun verifiedNextSeason(
        current: Series,
        related: (String) -> List<RelatedWork>,
        detail: (String) -> Detail,
    ): Detail? {
        if (current.preview || !playableBookType(current.bookType)) return null
        val candidate = nextSeason(current, related(current.id)) ?: return null
        if (candidate.upcoming || candidate.series.preview || candidate.contentType != 1004 ||
            !playableBookType(candidate.series.bookType)) return null
        RequestScope.ensureActive()
        val verified = detail(candidate.series.id)
        return verified.takeIf {
            it.series.id == candidate.series.id &&
                !it.series.preview &&
                playableBookType(it.series.bookType) &&
                listOf(current.bookType, candidate.series.bookType, it.series.bookType)
                    .filter { type -> type != 0 }.distinct().size <= 1 &&
                titleKey(it.series.title) == titleKey(candidate.series.title) &&
                it.episodes.isNotEmpty() &&
                it.episodes.all(String::isNotBlank)
        }
    }

    /** Zero means the website omitted the type; only short-drama book types are playable. */
    private fun playableBookType(type: Int) = type == 0 || type in 2..5

    fun parse(raw: String, currentId: String): List<RelatedWork> {
        val root = JSONObject(raw)
        if (root.optInt("code", -1) != 0) throw IOException("相关作品接口暂不可用")
        val cells = root.optJSONArray("data") ?: throw IOException("相关作品数据格式已变化")
        return cells
            .objects()
            .filter { it.optInt("show_type") == 547 || it.str("cell_name") == "相关作品" }
            .flatMap { cell -> cell.optJSONArray("video_data")?.objects().orEmpty() }
            .mapNotNull { row ->
                val id = row.str("series_id")
                val title = row.str("title")
                val type = row.optInt("content_type")
                if (
                    id == currentId ||
                        !id.matches(Regex("[0-9]{1,30}")) ||
                        title.isBlank() ||
                        type !in listOf(1004, 1007)
                )
                    return@mapNotNull null
                val upcoming = type == 1007
                if (!upcoming && (row.optBoolean("disable_play") || row.optInt("episode_cnt") <= 0))
                    return@mapNotNull null
                val season =
                    row.optJSONObject("tag_info")?.str("text").orEmpty().ifBlank {
                        row.optInt("season_index").takeIf { it > 0 }?.let { "第${it}季" }.orEmpty()
                    }
                RelatedWork(
                    Series(
                        id,
                        title,
                        CoverArt.forDisplay(row.str("cover")),
                        row.str("video_desc"),
                        if (upcoming) "待上线" else "全 ${row.optInt("episode_cnt")} 集",
                    ),
                    season,
                    upcoming,
                    row.optInt("season_index").coerceAtLeast(0),
                    type,
                )
            }
            .distinctBy { it.series.id }
            .sortedWith(compareBy<RelatedWork> { it.seasonIndex.takeIf { n -> n > 0 } ?: Int.MAX_VALUE })
    }
}
