// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import java.text.Normalizer

/** Conservative adjacent numbered seasons. Search failures propagate; callers retain ended controls. */
object SeasonContinuation {
    private data class Name(val base: String,val season: Int)
    private val suffix=Regex("^(.*?)第([0-9零〇一二两三四五六七八九十百]+)季$")
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
    private fun name(title: String): Name? {
        val normalized=Normalizer.normalize(title,Normalizer.Form.NFKC).replace(Regex("[\\p{P}\\p{Z}\\s]"),"")
        val match=suffix.matchEntire(normalized) ?: return null
        val base=match.groupValues[1].takeIf { it.isNotBlank() } ?: return null
        return Name(base,number(match.groupValues[2]) ?: return null)
    }
    fun find(current: Series,search: (String,Int)->CatalogPage,detail: (String)->Detail): Detail? {
        if(current.preview || current.bookType !in 2..5) return null
        val source=name(current.title) ?: return null
        fun eligible(row: Series): Boolean {
            val target=name(row.title) ?: return false
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
        return verified.takeIf { it.series.id==candidate.id && eligible(it.series) && it.episodes.isNotEmpty() && it.episodes.all(String::isNotBlank) }
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
