// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import java.math.BigInteger

/** Deterministic retention shared by runtime, disk reconstruction and backup merging. */
object CompletionHistory {
    const val MAX_SERIES=200
    const val MAX_IDS_PER_SERIES=2_000
    const val MAX_IDS_TOTAL=10_000
    private val newestFirst=Comparator<String> { a,b -> rank(b).compareTo(rank(a)) }
    private fun rank(id: String)=runCatching { id.toBigInteger() }.getOrDefault(BigInteger.ZERO)
    private fun immutable(values: Collection<String>): Set<String> = java.util.Collections.unmodifiableSet(LinkedHashSet(values))
    /**
     * Keeps the highest numeric ids up to [limit]. Runtime pruning and disk reconstruction share this
     * selection so an oversized store retains exactly what the runtime would. Never aliases [ids].
     */
    fun select(ids: Set<String>,limit: Int = MAX_IDS_PER_SERIES): Set<String> =
        if(ids.size<=limit) immutable(ids) else immutable(ids.sortedWith(newestFirst).take(limit))
    /**
     * Keeps only series with retained history, at most [MAX_SERIES] series, at most [MAX_IDS_PER_SERIES]
     * ids each (highest numeric first) and at most [MAX_IDS_TOTAL] ids overall, spending the global
     * budget in history order. Input sets are copied; callers keep ownership of their maps and sets.
     */
    fun prune(historyRecentFirst: List<BackupProgress>,completed: Map<String, Set<String>>): Map<String, Set<String>> {
        if(completed.isEmpty()) return emptyMap()
        var budget=MAX_IDS_TOTAL
        val retained=linkedMapOf<String, Set<String>>()
        historyRecentFirst.asSequence().map { it.series.id }.distinct().take(MAX_SERIES).forEach { id ->
            if(budget<=0) return retained
            val ids=completed[id] ?: return@forEach
            if(ids.isEmpty()) return@forEach
            val kept=select(ids,minOf(MAX_IDS_PER_SERIES,budget))
            if(kept.isEmpty()) return@forEach
            retained[id]=kept; budget-=kept.size
        }
        return retained
    }
}

/** Main-thread-owned library. JSON is parsed once, and getters never parse or sort. */
class LibraryIndex(data: BackupData, updates: Map<String, FavoriteUpdate> = emptyMap()) {
    private var favoriteRows = data.favorites.distinctBy { it.id }.take(500)
    private var favoriteIds = favoriteRows.map { it.id }.toSet()
    private var laterRows = data.later.distinctBy { it.id }.take(100)
    private var laterIds = laterRows.map { it.id }.toSet()
    private var historyRows = data.history.sortedByDescending { it.updatedAt }.distinctBy { it.series.id }.take(200)
    private val progressById = historyRows.associateBy { it.series.id }.toMutableMap()
    private var completedIds = CompletionHistory.prune(historyRows,data.completed)
    private var watchedIds = data.watched.toSet()
    private var hiddenIds = data.hidden.take(500).toSet()
    private var searchRows = data.searches.take(20)
    private var updateRows = updates.filterKeys { it in favoriteIds }
    private var updateCount = updateRows.values.count { it.added > 0 }
    var settings = data.settings

    fun favorites() = favoriteRows
    fun favorite(id: String) = id in favoriteIds
    fun later() = laterRows
    fun queued(id: String) = id in laterIds
    fun history() = historyRows
    fun progress(id: String) = progressById[id]
    fun completed(id: String): Set<String> = completedIds[id].orEmpty()
    fun completed(id: String,episodeId: String) = episodeId in completed(id)
    fun completedAll(): Map<String, Set<String>> = java.util.Collections.unmodifiableMap(completedIds)
    fun completedCount(id: String,episodeIds: List<String>) = episodeIds.count { it in completed(id) }
    fun watched(id: String) = id in watchedIds
    fun watched() = watchedIds
    fun hidden() = hiddenIds
    fun searches() = searchRows
    fun updates() = updateRows
    fun favoriteUpdate(id: String) = updateRows[id]
    fun updatedFavorites() = updateCount

    fun toggle(series: Series): Boolean {
        val added = !favorite(series.id)
        favoriteRows = (if (added) listOf(series) + favoriteRows else favoriteRows.filterNot { it.id == series.id }).take(500)
        favoriteIds = favoriteRows.map { it.id }.toSet()
        updateRows = updateRows.filterKeys { it in favoriteIds }
        updateCount = updateRows.values.count { it.added > 0 }
        return added
    }
    fun toggleLater(series: Series): Boolean {
        val added = !queued(series.id)
        laterRows = (if (added) listOf(series) + laterRows else laterRows.filterNot { it.id == series.id }).take(100)
        laterIds = laterRows.map { it.id }.toSet()
        return added
    }
    /**
     * A paused player's identical checkpoint does not refresh its timestamp or trigger a write. A
     * completion-only repair keeps the prior timestamp, and a mark that retention immediately prunes
     * reports no change instead of claiming a durable write on every identical checkpoint.
     */
    fun save(progress: BackupProgress): Boolean {
        val previous = progressById[progress.series.id]
        val mark = progress.completed && !completed(progress.series.id,progress.episodeId)
        val sameContent = previous != null && previous.copy(updatedAt = progress.updatedAt) == progress
        if (sameContent && !mark) return false
        val record = if (sameContent) progress.copy(updatedAt = previous!!.updatedAt) else progress
        val rows = (listOf(record) + historyRows.filterNot { it.series.id == record.series.id }).take(200).sortedByDescending { it.updatedAt }
        val candidate = if (mark) completedIds + (record.series.id to (completed(record.series.id) + record.episodeId)) else completedIds
        val next = CompletionHistory.prune(rows,candidate)
        if (next == completedIds && sameRecords(rows,historyRows)) return false
        historyRows = rows
        progressById.clear()
        rows.forEach { progressById[it.series.id] = it }
        completedIds = next
        return true
    }
    private fun sameRecords(a: List<BackupProgress>,b: List<BackupProgress>) =
        a.size==b.size && a.associateBy { it.series.id }==b.associateBy { it.series.id }
    fun setWatched(id: String, value: Boolean): Boolean {
        if (watched(id) == value) return false
        watchedIds = if (value) watchedIds + id else watchedIds - id
        return true
    }
    fun hide(id: String) { hiddenIds = (hiddenIds + id).toList().takeLast(500).toSet() }
    fun unhideAll() { hiddenIds = emptySet() }
    fun rememberSearch(query: String, type: ContentType) { searchRows = SearchHistory.remember(searchRows, query, type) }
    fun clearSearches() { searchRows = emptyList() }
    fun removeHistory(id: String) {
        historyRows = historyRows.filterNot { it.series.id == id }
        progressById.remove(id)
        watchedIds = watchedIds - id
        completedIds = CompletionHistory.prune(historyRows,completedIds - id)
    }
    fun clearHistory() { historyRows = emptyList(); progressById.clear(); watchedIds = emptySet(); completedIds = emptyMap() }
    fun observeFavorite(id: String, count: Int, now: Long, acknowledge: Boolean): Boolean {
        if (!favorite(id)) return false
        val previous = updateRows[id]
        val next = previous?.observe(count, now, acknowledge) ?: FavoriteUpdate.first(count, now)
        if (previous == next) return false
        updateRows = updateRows + (id to next)
        updateCount += (if (next.added > 0) 1 else 0) - (if ((previous?.added ?: 0) > 0) 1 else 0)
        return true
    }
    fun snapshot() = BackupData(favoriteRows, historyRows, watchedIds, searchRows, settings, laterRows, hiddenIds, completedIds)
}

/** Keeps the native-library-v1 disk keys and rich local metadata compatible with older APKs. */
object LibraryDisk {
    fun decode(values: Map<String, *>): LibraryIndex {
        fun rows(key: String) = runCatching { JSONArray(values[key] as? String ?: "[]") }.getOrDefault(JSONArray())
        fun series(key: String): List<Series> {
            val data = rows(key)
            return (0 until data.length()).mapNotNull { runCatching { Series.fromJson(data.getJSONObject(it)) }.getOrNull() }
        }
        fun ids(key: String) = (values[key] as? Set<*>)?.filterIsInstance<String>()?.toSet().orEmpty()
        val history = rows("progress").let { rows ->
            (0 until rows.length()).mapNotNull { i -> runCatching {
                val o = rows.getJSONObject(i)
                BackupProgress(Series.fromJson(o.getJSONObject("series")), o.getString("episodeId"), o.getInt("episodeIndex"),
                    o.getLong("position"), o.getLong("duration"), o.optBoolean("completed"), o.getLong("updatedAt"))
            }.getOrNull() }
        }
        val rawUpdates = runCatching { JSONObject(values["favoriteUpdates"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val updates = rawUpdates.keys().asSequence().mapNotNull { id -> FavoriteUpdate.decode(rawUpdates.optJSONObject(id))?.let { id to it } }.toMap()
        val rawCompleted = runCatching { JSONObject(values["completedEpisodes"] as? String ?: "{}") }.getOrDefault(JSONObject())
        val completed = rawCompleted.keys().asSequence().mapNotNull { id -> runCatching {
            val array = rawCompleted.getJSONArray(id)
            val ids = CompletionHistory.select((0 until array.length()).mapNotNull { index ->
                (array.opt(index) as? String)?.takeIf { it.matches(Regex("[0-9]{1,30}")) }
            }.toSet())
            id.takeIf { it.matches(Regex("[0-9]{1,30}")) && ids.isNotEmpty() }?.let { it to ids }
        }.getOrNull() }.toMap()
        val settings = BackupSettings(PlaybackQuality.normalize((values["quality"] as? Number)?.toInt() ?: PlaybackQuality.DEFAULT),
            PlaybackSpeed.normalize((values["playbackSpeed"] as? Number)?.toFloat() ?: 1f), values["autoNext"] as? Boolean ?: true,
            ContentType.fromStored(values["contentType"] as? String), VideoFrameMode.fromStored(values["frameMode"] as? String),
            values["seasonAutoplay"] as? Boolean ?: false)
        return LibraryIndex(BackupData(series("favorites"), history, ids("watched"),
            SearchHistory.decode(values["searches"] as? String ?: "[]"), settings, series("later"), ids("hidden"), completed), updates)
    }
    fun series(rows: List<Series>) = JSONArray(rows.map { it.toJson() }).toString()
    fun history(rows: List<BackupProgress>) = JSONArray(rows.map { p ->
        JSONObject().put("series", p.series.toJson()).put("episodeId", p.episodeId).put("episodeIndex", p.episodeIndex)
            .put("position", p.position).put("duration", p.duration).put("completed", p.completed).put("updatedAt", p.updatedAt)
    }).toString()
    fun updates(rows: Map<String, FavoriteUpdate>) = JSONObject().also { result -> rows.forEach { (id, update) -> result.put(id, update.json()) } }.toString()
    fun completed(rows: Map<String, Set<String>>) = JSONObject().also { result -> rows.forEach { (id, ids) -> result.put(id, JSONArray(ids.sorted())) } }.toString()
}
