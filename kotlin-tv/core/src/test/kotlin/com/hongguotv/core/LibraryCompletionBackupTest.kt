package com.hongguotv.core

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class LibraryCompletionBackupTest {
    private fun series(id: String) = Series(id, "测试 $id", "https://example.com/cover.jpg", "", "已完结", "剧情")
    private fun progress(id: String, time: Long, completed: Boolean = false) =
        BackupProgress(series(id), "100$id", 0, time, 10_000, completed, time)
    private fun data() = BackupData(listOf(series("1")), listOf(progress("1", 100)), setOf("1"),
        listOf(RecentSearch("你好", ContentType.COMIC)), BackupSettings())

    @Test fun `old backups without completions decode empty and new backups round trip exact sets`() {
        val legacy = JSONObject(LibraryBackup.encode(data()))
        assertFalse(legacy.has("completedEpisodes"))
        assertTrue(LibraryBackup.decode(legacy.toString()).completed.isEmpty())

        val marked = data().copy(completed = mapOf("1" to setOf("1001", "1003")))
        val encoded = LibraryBackup.encode(marked)
        assertTrue(JSONObject(encoded).has("completedEpisodes"))
        assertEquals(marked, LibraryBackup.decode(encoded))
    }

    @Test fun `merge unions actual id sets for retained series without inferring from history`() {
        val local = data().copy(history = listOf(progress("1", 300), progress("2", 200)),
            completed = mapOf("1" to setOf("1001"), "2" to setOf("2001")))
        val incoming = data().copy(history = listOf(progress("1", 100)),
            completed = mapOf("1" to setOf("1003"), "3" to setOf("3001")))
        val merged = LibraryBackup.merge(local, incoming, false)
        assertEquals(listOf("1", "2"), merged.history.map { it.series.id })
        assertEquals(setOf("1001", "1003"), merged.completed["1"])
        assertEquals(setOf("2001"), merged.completed["2"])
        assertFalse("3" in merged.completed)

        // A completed history row alone is not evidence of which episodes were actually finished.
        val inferred = data().copy(history = listOf(progress("1", 100, completed = true)), completed = emptyMap())
        assertTrue(LibraryBackup.merge(data(), inferred, false).completed.isEmpty())
    }

    @Test fun `merge and encode enforce shared retention bounds with history eviction`() {
        val history = (1..200).map { progress(it.toString(), it.toLong()) }
        val completed = (1..200).associate { s -> s.toString() to (1..3).map { e -> "${s}00$e" }.toSet() }
        val merged = LibraryBackup.merge(data().copy(history = history, completed = completed), data(), false)
        assertEquals(600, merged.completed.values.sumOf { it.size })

        val evicted = LibraryBackup.merge(data().copy(history = history, completed = completed),
            data().copy(history = listOf(progress("201", 999))), false)
        assertEquals(200, evicted.history.size)
        assertFalse("1" in evicted.completed)
        assertEquals(200, JSONObject(LibraryBackup.encode(merged)).getJSONObject("completedEpisodes").length())
    }

    @Test fun `oversized or malformed completion imports fail closed`() {
        val base = LibraryBackup.encode(data().copy(completed = mapOf("1" to setOf("1001"))))
        fun reject(change: (JSONObject) -> Unit) {
            val payload = JSONObject(base); change(payload)
            assertTrue(runCatching { LibraryBackup.decode(payload.toString()) }.isFailure)
        }
        reject { it.put("completedEpisodes", JSONObject().put("1", JSONArray((1..2001).map { n -> "100$n" }))) }
        reject { it.put("completedEpisodes", JSONObject().put("1", JSONArray(listOf("1001", "../bad")))) }
        reject { it.put("completedEpisodes", JSONArray()) }
        reject { it.put("completedEpisodes", JSONObject().also { map -> (1..201).forEach { s -> map.put("$s", JSONArray(listOf("${s}001"))) } }) }
        reject {
            val tooMany = JSONObject()
            (1..101).forEach { s -> tooMany.put("$s", JSONArray((1..100).map { e -> "$s${e.toString().padStart(4, '0')}" })) }
            it.put("completedEpisodes", tooMany)
        }
    }

    @Test fun `maximum completion metadata stays inside the backup byte bound`() {
        val ids = (1..10_000).map { "1${it.toString().padStart(5, '0')}" }.toSet()
        val encoded = LibraryBackup.encode(data().copy(completed = mapOf("1" to ids)))
        assertTrue(encoded.toByteArray(Charsets.UTF_8).size <= LibraryBackup.MAX_BYTES)
        assertEquals(CompletionHistory.MAX_IDS_PER_SERIES, LibraryBackup.decode(encoded).completed.getValue("1").size)
    }

    @Test fun `completion imports reject invalid series keys and non string episode ids`() {
        val base = JSONObject(LibraryBackup.encode(data().copy(completed = mapOf("1" to setOf("1001")))))
        fun reject(rows: Any) {
            val payload = JSONObject(base.toString()).put("completedEpisodes", rows)
            assertTrue("must reject $rows", runCatching { LibraryBackup.decode(payload.toString()) }.isFailure)
        }
        reject(JSONObject().put("../bad", JSONArray(listOf("1001"))))
        reject(JSONObject().put("1", JSONArray(listOf(1001))))
        reject(JSONObject().put("1", JSONArray(listOf(null))))
        reject(JSONObject().put("1", JSONArray(listOf(true))))
        reject(JSONObject().put("1", JSONArray(listOf(JSONObject.NULL))))
    }
}
