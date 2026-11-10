package com.hongguotv.core

import org.junit.Assert.*
import org.junit.Test

class LibraryCompletionTest {
    private fun series(id: Int) = Series("$id", "剧 $id", "https://example.com/$id.jpg", "完整本机简介", "全 80 集", "剧情")
    private fun checkpoint(seriesId: Int, episodeId: String, completed: Boolean, updated: Long, position: Long = 100) =
        BackupProgress(series(seriesId), episodeId, 0, position, 60_000, completed, updated)
    private fun data(history: List<BackupProgress> = listOf(checkpoint(1, "1001", false, 1)),
        completed: Map<String, Set<String>> = emptyMap()) =
        BackupData(emptyList(), history, emptySet(), emptyList(), BackupSettings(), emptyList(), emptySet(), completed)

    @Test fun `two nonadjacent completed ids survive a reordered episode list`() {
        val state = LibraryIndex(data())
        assertTrue(state.save(checkpoint(1, "1001", true, 10)))
        assertTrue(state.save(checkpoint(1, "1003", true, 20)))
        val reordered = listOf("1004", "1003", "1002", "1001")
        assertEquals(setOf("1001", "1003"), state.completed("1"))
        assertEquals(listOf(false, true, false, true), reordered.map { it in state.completed("1") })
        assertFalse(state.completed("1", "1002"))
        assertEquals(2, state.completedCount("1", reordered))
    }

    @Test fun `incomplete checkpoint revisit and seek never erase or invent marks`() {
        val state = LibraryIndex(data())
        state.save(checkpoint(1, "1005", true, 10))
        state.save(checkpoint(1, "1005", false, 11, 0))
        assertTrue(state.completed("1", "1005"))
        state.save(checkpoint(1, "1005", false, 12, 9_000))
        assertTrue(state.completed("1", "1005"))
        state.save(checkpoint(1, "1006", false, 13))
        assertFalse(state.completed("1", "1006"))
    }

    @Test fun `repeated completed checkpoint is idempotent including identical progress`() {
        val state = LibraryIndex(data())
        assertTrue(state.save(checkpoint(1, "1002", true, 10)))
        val history = state.history()
        assertFalse(state.save(checkpoint(1, "1002", true, 10)))
        assertSame(history, state.history())
        assertEquals(setOf("1002"), state.completed("1"))

        // Legacy preferences already hold a completed row without the new partition. Re-accepting
        // the identical explicit checkpoint must still create the durable mark and report a change.
        val legacy = LibraryIndex(data(listOf(checkpoint(2, "2001", true, 5))))
        assertTrue(legacy.completed("2").isEmpty())
        assertTrue(legacy.save(checkpoint(2, "2001", true, 5)))
        assertTrue(legacy.completed("2", "2001"))
        assertEquals(5L, legacy.progress("2")!!.updatedAt)

        // A repair carrying a newer checkpoint timestamp still preserves the prior stored timestamp.
        val advancing = LibraryIndex(data(listOf(checkpoint(2, "2001", true, 5))))
        assertTrue(advancing.save(checkpoint(2, "2001", true, 99)))
        assertTrue(advancing.completed("2", "2001"))
        assertEquals(5L, advancing.progress("2")!!.updatedAt)
    }

    @Test fun `history deletion clear and capacity eviction atomically prune completions`() {
        val state = LibraryIndex(data((1..200).map { checkpoint(it, "${it}001", false, it.toLong()) }))
        state.save(checkpoint(1, "1001", true, 1))
        assertTrue(state.completed("1", "1001"))
        state.removeHistory("1")
        assertTrue(state.completed("1").isEmpty())

        state.save(checkpoint(2, "2001", true, 2))
        state.clearHistory()
        assertTrue(state.completedAll().isEmpty())

        val evicting = LibraryIndex(data((1..200).map { checkpoint(it, "${it}001", false, it.toLong()) }))
        evicting.save(checkpoint(1, "1001", true, 1))
        evicting.save(checkpoint(201, "201001", true, 1_000))
        assertNull(evicting.progress("1"))
        assertTrue(evicting.completed("1").isEmpty())
        assertEquals(setOf("201001"), evicting.completed("201"))
    }

    @Test fun `completion bounds are deterministic per series and across all series`() {
        val perSeries = (1..2500).map { (3000 + it).toString() }.toSet()
        val state = LibraryIndex(data(listOf(checkpoint(1, "1001", false, 5)), mapOf("1" to perSeries)))
        assertEquals(CompletionHistory.MAX_IDS_PER_SERIES, state.completed("1").size)
        assertTrue(state.completed("1", "5500"))
        assertFalse(state.completed("1", "3000"))

        val history = (1..200).map { checkpoint(it, "${it}001", false, it.toLong()) }
        val completed = (1..200).associate { s ->
            "$s" to (1..2000).map { e -> "$s${e.toString().padStart(4, '0')}" }.toSet()
        }
        val global = LibraryIndex(data(history, completed))
        assertEquals(CompletionHistory.MAX_IDS_TOTAL, global.completedAll().values.sumOf { it.size })
        assertEquals(CompletionHistory.MAX_IDS_PER_SERIES, global.completed("200").size)
        assertTrue(global.completed("1").isEmpty())
    }

    @Test fun `captured completion snapshots survive later mutations`() {
        val state = LibraryIndex(data())
        state.save(checkpoint(1, "1001", true, 10))
        val snapshot = state.snapshot()
        val membership = state.completed("1")
        state.save(checkpoint(1, "1002", true, 20))
        state.removeHistory("1")
        assertEquals(mapOf("1" to setOf("1001")), snapshot.completed)
        assertEquals(setOf("1001"), membership)
        assertTrue(state.completedAll().isEmpty())
    }

    @Test fun `disk round trip keeps exact sets and drops series without retained history`() {
        val original = data(completed = mapOf("1" to setOf("1001", "1003"), "9" to setOf("9001")))
        val decoded = LibraryDisk.decode(mapOf(
            "progress" to LibraryDisk.history(original.history),
            "completedEpisodes" to LibraryDisk.completed(original.completed)))
        assertEquals(setOf("1001", "1003"), decoded.completed("1"))
        assertTrue(decoded.completed("9").isEmpty())
        val restarted = LibraryDisk.decode(mapOf(
            "progress" to LibraryDisk.history(decoded.history()),
            "completedEpisodes" to LibraryDisk.completed(decoded.completedAll())))
        assertEquals(decoded.completedAll(), restarted.completedAll())
        assertTrue(LibraryDisk.decode(emptyMap<String, Any?>()).completedAll().isEmpty())
        assertTrue(LibraryDisk.decode(mapOf("completedEpisodes" to "not-json")).completedAll().isEmpty())
    }

    @Test fun `completion pruned at the id cap is not reported as a repeated durable write`() {
        val full = (1001..3000).map { it.toString() }.toSet()
        val state = LibraryIndex(data(listOf(checkpoint(1, "1", false, 5)), mapOf("1" to full)))
        assertEquals(CompletionHistory.MAX_IDS_PER_SERIES, state.completed("1").size)
        assertFalse(state.completed("1", "1"))

        // The advancing checkpoint itself is real progress and must still be written.
        assertTrue(state.save(checkpoint(1, "1", true, 10)))
        assertEquals(10L, state.progress("1")!!.updatedAt)
        assertFalse(state.completed("1", "1"))

        // The identical completed checkpoint changes neither progress nor the bounded partition.
        val history = state.history()
        assertFalse(state.save(checkpoint(1, "1", true, 10)))
        assertSame(history, state.history())
        assertEquals(10L, state.progress("1")!!.updatedAt)

        // A genuinely newer checkpoint (advanced position) still advances the stored timestamp.
        assertTrue(state.save(checkpoint(1, "1", true, 20, position = 200)))
        assertEquals(20L, state.progress("1")!!.updatedAt)
        assertEquals(200L, state.progress("1")!!.position)
        assertFalse(state.completed("1", "1"))
    }

    @Test fun `prune clamps arbitrary history and copies caller owned completion sets`() {
        val history = (1..300).map { checkpoint(it, "${it}001", false, it.toLong()) }
        val completed = (1..300).associate { "$it" to mutableSetOf("${it}001") }
        val pruned = CompletionHistory.prune(history, completed)
        assertEquals(CompletionHistory.MAX_SERIES, pruned.size)
        assertTrue("1" in pruned)

        completed.getValue("1").add("1002")
        completed.getValue("2").clear()
        assertEquals(setOf("1001"), pruned["1"])
        assertEquals(setOf("2001"), pruned["2"])

        val source = mutableSetOf("1001")
        val index = LibraryIndex(data(listOf(checkpoint(1, "1001", false, 5)), mapOf("1" to source)))
        source.add("1002")
        assertEquals(setOf("1001"), index.completed("1"))
        assertEquals(index.completed("1"), index.completed("1"))
        assertEquals(index.completedAll(), index.completedAll())
        assertTrue(runCatching { @Suppress("UNCHECKED_CAST") (index.completed("1") as MutableSet<String>).add("1002") }.isFailure)
        assertEquals(setOf("1001"), index.completed("1"))
    }

    @Test fun `oversized disk completion store selects the same highest ids as the runtime`() {
        val ids = (1..2500).map { it.toString() }.toSet()
        val history = listOf(checkpoint(1, "1", false, 5))
        val decoded = LibraryDisk.decode(mapOf(
            "progress" to LibraryDisk.history(history),
            "completedEpisodes" to LibraryDisk.completed(mapOf("1" to ids))))
        val runtime = LibraryIndex(data(history, mapOf("1" to ids)))
        assertEquals(runtime.completedAll(), decoded.completedAll())
        assertEquals(CompletionHistory.MAX_IDS_PER_SERIES, decoded.completed("1").size)
        assertTrue(decoded.completed("1", "2500"))
        assertFalse(decoded.completed("1", "1"))
    }
}
