// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.core

/**
 * Pure progress comparison for manual cloud/local merges.
 *
 * Catalog order is authoritative. The cached [BackupProgress.episodeIndex] is ignored and
 * recomputed from the actual episode id; ids are never numerically sorted or defaulted.
 * Timestamps never decide which progress is farther. Explicit completion is authoritative;
 * position is compared as saved and is never clamped or used to infer completion.
 */
object ProgressMergePlanner {
    const val MAX_EPISODES = 10_000
    const val MAX_CANDIDATES = 10_000
    private const val MAX_ID_LENGTH = 200

    enum class Direction { IMPORT, UPLOAD, EQUAL, SKIP }
    data class Decision(val direction: Direction, val progress: BackupProgress? = null)

    /** Returns a copy with the catalog index recomputed, or null when any input is invalid. */
    fun normalize(progress: BackupProgress, episodes: List<String>): BackupProgress? {
        val index = catalog(episodes) ?: return null
        return normalize(progress, index)
    }

    /**
     * Positive when [left] is farther, negative when [right] is farther, zero when tied.
     * Ties ignore timestamps. Returns null for invalid progress, catalogs, or different series.
     */
    fun compare(left: BackupProgress, right: BackupProgress, episodes: List<String>): Int? {
        val index = catalog(episodes) ?: return null
        val a = normalize(left, index) ?: return null
        val b = normalize(right, index) ?: return null
        if (a.series.id != b.series.id) return null
        return rank(a, b)
    }

    fun plan(local: BackupProgress?, remote: List<BackupProgress>, episodes: List<String>): Decision {
        val index = catalog(episodes) ?: return Decision(Direction.SKIP)
        if (remote.size > MAX_CANDIDATES) return Decision(Direction.SKIP)

        val candidates = ArrayList<BackupProgress>(remote.size)
        remote.forEach { candidate ->
            candidates.add(normalize(candidate, index) ?: return Decision(Direction.SKIP))
        }
        val current = local?.let { normalize(it, index) ?: return Decision(Direction.SKIP) }

        // One series per decision: local and every supplied candidate must agree.
        val seriesId = current?.series?.id ?: candidates.firstOrNull()?.series?.id
        if (seriesId != null && candidates.any { it.series.id != seriesId }) return Decision(Direction.SKIP)

        if (current == null && candidates.isEmpty()) return Decision(Direction.EQUAL)
        if (current == null) return Decision(Direction.IMPORT, farthest(candidates))
        if (candidates.isEmpty()) return Decision(Direction.UPLOAD, current)

        val cloud = farthest(candidates)
        val comparison = rank(current, cloud)
        return when {
            comparison > 0 -> Decision(Direction.UPLOAD, current)
            comparison < 0 -> Decision(Direction.IMPORT, cloud)
            else -> Decision(Direction.EQUAL)
        }
    }

    private fun farthest(candidates: List<BackupProgress>): BackupProgress {
        var best = candidates.first()
        for (candidate in candidates) if (rank(candidate, best) > 0) best = candidate
        return best
    }

    private fun rank(left: BackupProgress, right: BackupProgress): Int {
        if (left.episodeIndex != right.episodeIndex) return left.episodeIndex.compareTo(right.episodeIndex)
        if (left.completed != right.completed) return if (left.completed) 1 else -1
        return left.position.compareTo(right.position)
    }

    private fun normalize(progress: BackupProgress, index: Map<String, Int>): BackupProgress? {
        if (!validId(progress.series.id) || !validId(progress.episodeId)) return null
        if (progress.position < 0 || progress.duration < 0 || progress.updatedAt < 0) return null
        val episodeIndex = index[progress.episodeId] ?: return null
        return progress.copy(episodeIndex = episodeIndex)
    }

    /** Maps actual catalog ids to their order, or null if the catalog is blank, duplicate, or oversized. */
    private fun catalog(episodes: List<String>): Map<String, Int>? {
        if (episodes.size > MAX_EPISODES) return null
        val index = HashMap<String, Int>(episodes.size)
        episodes.forEachIndexed { position, id ->
            if (!validId(id) || index.put(id, position) != null) return null
        }
        return index
    }

    private fun validId(value: String) =
        value.isNotBlank() && value.length <= MAX_ID_LENGTH && value.none(Char::isISOControl)
}
