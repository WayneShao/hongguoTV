package com.hongguotv.core

import com.hongguotv.core.ProgressMergePlanner.Direction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProgressMergePlannerTest {
    private fun series(id: String = "1") = Series(id, "Test $id", "")
    private fun progress(
        episode: String,
        episodeIndex: Int = 0,
        position: Long = 0,
        duration: Long = 10_000,
        completed: Boolean = false,
        updatedAt: Long = 1_000,
        seriesId: String = "1",
    ) = BackupProgress(series(seriesId), episode, episodeIndex, position, duration, completed, updatedAt)

    /** Numeric order would put "10" last, but the catalog order is authoritative. */
    private val catalog = listOf("10", "9", "2")

    @Test fun `actual catalog order beats numeric episode ids and stale cached indexes`() {
        val ten = progress("10", episodeIndex = 0, position = 2_000)
        val nine = progress("9", episodeIndex = 0, position = 2_000)
        val two = progress("2", episodeIndex = 0, position = 2_000)

        assertTrue(ProgressMergePlanner.compare(nine, ten, catalog)!! > 0)
        assertTrue(ProgressMergePlanner.compare(two, nine, catalog)!! > 0)
        assertEquals(2, ProgressMergePlanner.normalize(two, catalog)!!.episodeIndex)
    }

    @Test fun `older local further in the catalog wins over newer cloud progress`() {
        val local = progress("2", episodeIndex = 0, position = 3_000, updatedAt = 100)
        val remote = progress("10", episodeIndex = 1, position = 9_000, updatedAt = 9_999)

        val decision = ProgressMergePlanner.plan(local, listOf(remote), catalog)

        assertEquals(Direction.UPLOAD, decision.direction)
        assertEquals(100, decision.progress!!.updatedAt)
        assertEquals("2", decision.progress!!.episodeId)
        assertEquals(2, decision.progress!!.episodeIndex)
    }

    @Test fun `same episode compares position and completion with completion first`() {
        val ahead = progress("9", position = 6_000)
        val behind = progress("9", position = 5_000)

        assertTrue(ProgressMergePlanner.compare(ahead, behind, catalog)!! > 0)
        assertTrue(ProgressMergePlanner.compare(behind, ahead, catalog)!! < 0)

        val completedWithResetPosition = progress("9", position = 0, completed = true, updatedAt = 10)
        val incompleteAhead = progress("9", position = 9_000, updatedAt = 99_999)

        assertTrue(ProgressMergePlanner.compare(completedWithResetPosition, incompleteAhead, catalog)!! > 0)
        val decision = ProgressMergePlanner.plan(completedWithResetPosition, listOf(incompleteAhead), catalog)
        assertEquals(Direction.UPLOAD, decision.direction)
        assertEquals(0, decision.progress!!.position)
        assertTrue(decision.progress!!.completed)
        assertEquals(10, decision.progress!!.updatedAt)

        // Episode order is checked before completion.
        val laterEpisode = progress("2", position = 0, updatedAt = 1)
        assertTrue(ProgressMergePlanner.compare(laterEpisode, completedWithResetPosition, catalog)!! > 0)
        assertEquals(Direction.IMPORT,
            ProgressMergePlanner.plan(completedWithResetPosition, listOf(laterEpisode), catalog).direction)
    }

    @Test fun `explicit completion flags win and positions are never inferred or clamped`() {
        val atDuration = progress("9", position = 10_000, duration = 10_000, completed = false)
        val atDurationNormalized = ProgressMergePlanner.normalize(atDuration, catalog)!!
        assertFalse(atDurationNormalized.completed)
        assertEquals(10_000, atDurationNormalized.position)

        val overshoot = progress("9", position = 12_000, duration = 10_000, completed = false)
        val overshootNormalized = ProgressMergePlanner.normalize(overshoot, catalog)!!
        assertFalse(overshootNormalized.completed)
        assertEquals(12_000, overshootNormalized.position)

        val explicitlyCompleted = progress("9", position = 0, duration = 10_000, completed = true)
        val completedNormalized = ProgressMergePlanner.normalize(explicitlyCompleted, catalog)!!
        assertTrue(completedNormalized.completed)
        assertEquals(0, completedNormalized.position)

        // Comparison uses the actual saved play position, not a duration-clamped value.
        assertTrue(ProgressMergePlanner.compare(overshootNormalized,
            progress("9", position = 10_000), catalog)!! > 0)
    }

    @Test fun `timestamps never decide farther and equality never rewrites`() {
        val local = progress("9", position = 7_000, updatedAt = 1)
        val remote = progress("9", position = 7_000, updatedAt = 9_999_999)

        assertEquals(0, ProgressMergePlanner.compare(local, remote, catalog)!!)
        val equal = ProgressMergePlanner.plan(local, listOf(remote), catalog)
        assertEquals(Direction.EQUAL, equal.direction)
        assertNull(equal.progress)

        val fartherOld = progress("2", position = 0, updatedAt = 1)
        val nearerNew = progress("9", position = 9_999, updatedAt = 9_999_999)
        assertEquals(Direction.UPLOAD,
            ProgressMergePlanner.plan(fartherOld, listOf(nearerNew), catalog).direction)
    }

    @Test fun `farthest cloud candidate wins even when candidates disagree`() {
        val first = progress("10", position = 9_000, updatedAt = 9_000)
        val middle = progress("9", position = 1_000, updatedAt = 8_000)
        val last = progress("2", position = 10, updatedAt = 1)
        val local = progress("9", position = 8_000, updatedAt = 50)

        val decision = ProgressMergePlanner.plan(local, listOf(first, last, middle), catalog)

        assertEquals(Direction.IMPORT, decision.direction)
        assertEquals("2", decision.progress!!.episodeId)
        assertEquals(1, decision.progress!!.updatedAt)

        val early = progress("2", position = 100, updatedAt = 999)
        val late = progress("2", position = 200, updatedAt = 1)
        assertEquals(200, ProgressMergePlanner.plan(null, listOf(early, late), catalog).progress!!.position)
    }

    @Test fun `invalid input returns null or skip instead of guessing a default index`() {
        val valid = progress("9", position = 5_000)
        val missing = progress("404", position = 99_000)

        assertNull(ProgressMergePlanner.normalize(missing, catalog))
        assertNull(ProgressMergePlanner.compare(valid, missing, catalog))

        // One invalid supplied candidate invalidates the whole series.
        assertEquals(Direction.SKIP,
            ProgressMergePlanner.plan(valid, listOf(progress("2", position = 9_000), missing), catalog).direction)
        assertEquals(Direction.SKIP,
            ProgressMergePlanner.plan(valid, listOf(progress("9", seriesId = "2")), catalog).direction)
        assertEquals(Direction.SKIP,
            ProgressMergePlanner.plan(valid, listOf(progress("2", seriesId = "2"), valid), catalog).direction)

        assertNull(ProgressMergePlanner.normalize(progress("9", position = -1), catalog))
        assertNull(ProgressMergePlanner.normalize(progress("9", duration = -1), catalog))
        assertNull(ProgressMergePlanner.normalize(progress("9", updatedAt = -1), catalog))
        assertNull(ProgressMergePlanner.normalize(valid, listOf("9", "9")))
        assertNull(ProgressMergePlanner.normalize(valid, listOf("9", " ")))
        assertEquals(Direction.SKIP, ProgressMergePlanner.plan(valid, emptyList(), listOf("9", "9")).direction)
        assertEquals(Direction.SKIP, ProgressMergePlanner.plan(null, listOf(valid), listOf("9", "9")).direction)

        val oversizedCatalog = (0..200_000).map { it.toString() }
        assertNull(ProgressMergePlanner.normalize(progress("0"), oversizedCatalog))
    }

    @Test fun `normalization and planning are idempotent`() {
        val source = progress("2", episodeIndex = 0, position = 12_000, duration = 10_000,
            completed = false, updatedAt = 123)
        val normalized = ProgressMergePlanner.normalize(source, catalog)!!

        assertEquals(normalized, ProgressMergePlanner.normalize(normalized, catalog))
        assertEquals(0, ProgressMergePlanner.compare(normalized, normalized, catalog)!!)
        assertEquals(Direction.EQUAL, ProgressMergePlanner.plan(normalized, listOf(normalized), catalog).direction)
        assertEquals(normalized, ProgressMergePlanner.plan(null, listOf(source), catalog).progress)
    }

    @Test fun `only local uploads and only cloud imports`() {
        val local = progress("9", position = 100, updatedAt = 77)
        val upload = ProgressMergePlanner.plan(local, emptyList(), catalog)
        assertEquals(Direction.UPLOAD, upload.direction)
        assertEquals(local.copy(episodeIndex = 1), upload.progress)

        val cloud = progress("2", episodeIndex = 0, position = 200, updatedAt = 88)
        val download = ProgressMergePlanner.plan(null, listOf(cloud), catalog)
        assertEquals(Direction.IMPORT, download.direction)
        assertEquals(cloud.copy(episodeIndex = 2), download.progress)

        val neither = ProgressMergePlanner.plan(null, emptyList(), catalog)
        assertEquals(Direction.EQUAL, neither.direction)
        assertNull(neither.progress)
    }
}
