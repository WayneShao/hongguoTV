// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Normalizer

/** Conservative adjacent numbered seasons. Search failures propagate; callers retain ended controls. */
object SeasonContinuation {
    internal data class Name(val base: String,val season: Int)
    /** The leading 第 is optional: both “第40季” and “40季” are explicit season names. */
    private val marker=Regex("第?([0-9零〇一二两三四五六七八九十百]+)季")
    private fun normalize(title: String)=Normalizer.normalize(title,Normalizer.Form.NFKC).replace(Regex("[\\p{P}\\p{Z}\\s]"),"")
    private fun number(value: String): Int? {
        value.toIntOrNull()?.let { return it.takeIf { n -> n in 1..999 } }
        val normalized=value.replace('两','二').replace('〇','零')
        if(!Regex("(?:[一二三四五六七八九]|十[一二三四五六七八九]?|[二三四五六七八九]十[一二三四五六七八九]?|[一二三四五六七八九]百(?:零[一二三四五六七八九]|[一二三四五六七八九]十[一二三四五六七八九]?)?)").matches(normalized)) return null
        val digits=mapOf('零' to 0,'〇' to 0,'一' to 1,'二' to 2,'两' to 2,'三' to 3,'四' to 4,'五' to 5,'六' to 6,'七' to 7,'八' to 8,'九' to 9)
        var sum=0;var digit=0;var lastUnit=1000
        for(c in value) {
            if(c in digits) digit=digits.getValue(c)
            else {
                val unit=when(c) { '十' -> 10; '百' -> 100; else -> return null }
                if(unit>=lastUnit) return null
                sum+=(if(digit==0) 1 else digit)*unit;digit=0;lastUnit=unit
            }
        }
        return (sum+digit).takeIf { it in 1..999 }
    }
    /** Exactly one marker, and it must terminate the title; embedded numbers are never a season. */
    internal fun name(title: String): Name? {
        val normalized=normalize(title)
        if(normalized.isEmpty()) return null
        val matches=marker.findAll(normalized).toList()
        if(matches.size!=1) return null
        val match=matches.single()
        if(match.range.last!=normalized.lastIndex) return null
        val base=normalized.substring(0,match.range.first).takeIf { it.isNotBlank() } ?: return null
        return Name(base,number(match.groupValues[1]) ?: return null)
    }
    /**
     * Title first. A positive `seasonIndex` within the title parser's 1..999 range is trusted only
     * for a title without any season marker, and always conflicts with an explicit marker instead
     * of overriding it. Silence stays unknown: a bare title and no metadata is never assumed to be
     * season one. An index outside the parser's range is not metadata this app can verify.
     */
    internal fun identity(series: Series): Name? {
        val parsed=name(series.title)
        if(series.seasonIndex<=0) return parsed
        if(series.seasonIndex>999) return null
        if(parsed==null) {
            val bare=normalize(series.title)
            if(bare.isEmpty() || '季' in bare) return null
            return Name(bare,series.seasonIndex)
        }
        return parsed.takeIf { it.season==series.seasonIndex }
    }
    private fun titleKey(title: String)=normalize(title)
    /**
     * Detail may omit season metadata. Adopt the candidate's value only when id and title both
     * prove the same work; a detail page that already carries metadata, or a differently written
     * title, is checked as-is and never overwritten.
     */
    internal fun merged(candidate: Series, detail: Series): Series? {
        if(detail.id!=candidate.id) return null
        if(candidate.seasonIndex>0 && detail.seasonIndex<=0 && titleKey(detail.title)==titleKey(candidate.title))
            return detail.copy(seasonIndex=candidate.seasonIndex)
        return detail
    }
    fun find(current: Series,search: (String,Int)->CatalogPage,detail: (String)->Detail): Detail? {
        if(current.preview || current.bookType !in 2..5) return null
        val source=identity(current) ?: return null
        fun eligible(row: Series): Boolean {
            val target=identity(row) ?: return false
            return row.id.isNotBlank() && row.id!=current.id && !row.preview && row.bookType==current.bookType &&
                target.base==source.base && target.season==source.season+1
        }
        val candidates=linkedMapOf<String,Series>()
        for(page in 1..3) {
            RequestScope.ensureActive()
            val result=search(source.base,page)
            result.items.filter(::eligible).forEach { candidates[it.id]=it }
            if(!result.hasMore) break
        }
        val candidate=candidates.values.singleOrNull() ?: return null
        RequestScope.ensureActive()
        val verified=detail(candidate.id)
        val series=merged(candidate,verified.series) ?: return null
        return verified.copy(series=series).takeIf {
            eligible(it.series) && it.episodes.isNotEmpty() && it.episodes.all(String::isNotBlank)
        }
    }
}

/** Main-thread token: invalidation survives pause/resume, and failures cannot repeat a lookup. */
class SeasonContinuationGate {
    private var epoch=0L
    private var attempted=false
    private var pending=false
    val isPending get()=pending
    fun reset() { invalidate();attempted=false }
    fun invalidate() { epoch++;pending=false }
    fun begin(): Long? { if(attempted) return null;attempted=true;pending=true;return epoch }
    fun valid(token: Long)=pending && token==epoch
    fun consume(token: Long): Boolean { if(!valid(token)) return false;pending=false;return true }
}
