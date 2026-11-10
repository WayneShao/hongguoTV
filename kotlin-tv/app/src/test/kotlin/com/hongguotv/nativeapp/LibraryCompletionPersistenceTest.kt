package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import com.hongguotv.core.BackupData
import com.hongguotv.core.BackupProgress
import com.hongguotv.core.BackupSettings
import com.hongguotv.core.LibraryBackup
import com.hongguotv.core.Series
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class LibraryCompletionPersistenceTest {
    private fun activity(): Activity {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("native-library-v1", Context.MODE_PRIVATE).edit().clear().commit()
        return activity
    }
    private fun series(id: String) = Series(id, "剧 $id", "https://example.com/$id.jpg", "", "80 集", "剧情")
    private fun progress(seriesId: String, episodeId: String, completed: Boolean, updated: Long, position: Long = 100) =
        BackupProgress(series(seriesId), episodeId, 0, position, 60_000, completed, updated)
    private fun load(library: Library) {
        val ready = CountDownLatch(1)
        var failure: Throwable? = null
        library.loadAsync(onFailure = { failure = it; ready.countDown() }) { ready.countDown() }
        val deadline = System.currentTimeMillis() + 15_000
        while (ready.count > 0 && System.currentTimeMillis() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        failure?.let { throw it }
        assertTrue("library loaded", library.isLoaded)
    }
    private fun restart(previous: Library, activity: Activity): Library {
        previous.close()
        return Library(activity).also(::load)
    }

    @Test fun `completed ids persist across restart and incomplete checkpoints do not erase them`() {
        val activity = activity()
        val first = Library(activity); load(first)
        first.save(progress("1", "1001", true, 10))
        first.save(progress("1", "1003", true, 20))
        first.save(progress("1", "1003", false, 21, 0))
        first.flush()

        val second = restart(first, activity)
        assertEquals(setOf("1001", "1003"), second.completed("1"))
        assertFalse(second.completed("1", "1002"))
        assertEquals(2, second.completedCount("1", listOf("1001", "1002", "1003", "1004")))
        second.close()
    }

    @Test fun `history deletion and clear prune completions durably`() {
        val activity = activity()
        val first = Library(activity); load(first)
        first.save(progress("1", "1001", true, 10))
        first.save(progress("2", "2001", true, 20))

        val second = restart(first, activity)
        second.removeHistory("1")
        val third = restart(second, activity)
        assertTrue(third.completed("1").isEmpty())
        assertEquals(setOf("2001"), third.completed("2"))

        third.clearHistory()
        val fourth = restart(third, activity)
        assertTrue(fourth.completedAll().isEmpty())
        fourth.close()
    }

    @Test fun `android json coercion cannot smuggle completion ids past validation`() {
        val base = JSONObject(LibraryBackup.encode(BackupData(emptyList(), emptyList(), emptySet(), emptyList(), BackupSettings())))
        fun reject(rows: Any) {
            val payload = JSONObject(base.toString()).put("completedEpisodes", rows)
            assertTrue("must reject $rows", runCatching { LibraryBackup.decode(payload.toString()) }.isFailure)
        }
        reject(JSONObject().put("1", JSONArray(listOf(1001))))
        reject(JSONObject().put("1", JSONArray(listOf(true))))
        reject(JSONObject().put("1", JSONArray(listOf(JSONObject.NULL))))
        reject(JSONObject().put("../bad", JSONArray(listOf("1001"))))
    }
}
