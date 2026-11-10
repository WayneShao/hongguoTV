// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
class LibraryProgressMergeTest {
    private fun row(id: String="123",episode: String="456",index: Int=1,completed: Boolean=false,time: Long=100) =
        BackupProgress(Series(id,"剧 $id","https://example.com/$id.jpg","","4 集","剧情"),episode,index,3_000,60_000,completed,time)
    private fun load(library: Library) {
        val ready=CountDownLatch(1)
        var failure: Throwable?=null
        library.loadAsync(onFailure={ failure=it; ready.countDown() }) { ready.countDown() }
        val end=System.currentTimeMillis()+15_000
        while(ready.count>0 && System.currentTimeMillis()<end) { ShadowLooper.idleMainLooper(); Thread.sleep(5) }
        failure?.let { throw it }
        assertTrue("library loaded",library.isLoaded)
    }
    private fun fixture(): Pair<Activity,Library> {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("native-library-v1",Context.MODE_PRIVATE).edit().clear().commit()
        return activity to Library(activity).also(::load)
    }
    @Test fun `cloud winner persists and genuine resume uses imported episode and position`() {
        val (activity,library)=fixture()
        library.save(row(episode="999",index=0,completed=true,time=900))
        library.setWatched("123",true)
        val imported=row(time=100)
        assertTrue(library.mergeProgress(imported))
        assertFalse("old whole-series flag must not force restart",library.watched("123"))
        library.close()
        val reopened=Library(activity).also(::load)
        try {
            assertEquals(imported,reopened.progress("123"))
            assertFalse(reopened.watched("123"))
            assertEquals(setOf("999"),reopened.completed("123"))
            val p=reopened.progress("123")!!
            val target=requireNotNull(ResumePlayback.resolve(listOf("999","456","111","222"),p.episodeId,p.position,p.completed,p.episodeIndex))
            assertEquals(1,target.index)
            assertEquals(3_000L,target.position)
        } finally { reopened.close() }
    }
    @Test fun `merge preserves exact completed marks without filling skipped episodes and is idempotent`() {
        val (_,library)=fixture()
        try {
            library.save(row(episode="999",index=0,completed=true))
            val imported=row(episode="222",index=3,completed=true,time=200)
            assertTrue(library.mergeProgress(imported))
            assertEquals(setOf("999","222"),library.completed("123"))
            assertFalse(library.completed("123","456"))
            assertFalse(library.completed("123","111"))
            val history=library.history()
            assertFalse(library.mergeProgress(imported.copy(updatedAt=999)))
            assertSame(history,library.history())
        } finally { library.close() }
    }
    @Test fun `merge uses existing bounded retention and closed library rejects late results`() {
        val (_,library)=fixture()
        repeat(205) { library.mergeProgress(row(id=(it+1).toString(),time=(it+1).toLong())) }
        assertEquals(200,library.history().size)
        library.close()
        assertFalse(library.mergeProgress(row(id="9999")))
        assertNull(library.progress("9999"))
    }
}
