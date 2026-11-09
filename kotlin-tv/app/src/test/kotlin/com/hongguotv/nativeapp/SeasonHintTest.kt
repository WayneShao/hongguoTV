package com.hongguotv.nativeapp

import android.view.View
import android.widget.TextView
import com.hongguotv.core.ContentRepository
import com.hongguotv.core.Detail
import com.hongguotv.core.Series
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** The pre-end hint is a passive banner and must never hijack focus or the prepared season. */
@RunWith(RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28])
class SeasonHintTest {
    private fun field(name: String) =
        MainActivity::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun libraryField(name: String) =
        Library::class.java.getDeclaredField(name).apply { isAccessible = true }

    private fun fixture(): ContentRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val body = when (chain.request().url.encodedPath) {
                "/reading/bookapi/plan/v:version/" ->
                    """{"code":0,"data":[{"show_type":547,"video_data":[{"series_id":"222","title":"长剧第二季","content_type":1004,"episode_cnt":10,"season_index":2}]}]}"""
                "/detail" ->
                    """<script>window._ROUTER_DATA = {"loaderData":{"detail_page":{"seriesDetail":{"series_id":"222","series_name":"长剧第二季","vid_list":["11","12"]}}}};</script>"""
                else -> """{"code":1,"message":"offline fixture"}"""
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200)
                .message("fixture").body(body.toResponseBody()).build()
        }.build()
        return ContentRepository(http)
    }

    private fun offer(activity: MainActivity, current: Detail) =
        MainActivity::class.java.getDeclaredMethod("offerNextSeason", Detail::class.java)
            .apply { isAccessible = true }.invoke(activity, current)

    @Test fun hintCanBeCancelledAndDisappearsWhenLeavingTheForeground() {
        val worker = Executors.newSingleThreadExecutor()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val originalIo = field("io").get(activity) as ExecutorService
        try {
            field("io").set(activity, worker)
            originalIo.shutdownNow()
            field("repository").set(activity, fixture())
            controller.create()
            val library = field("library").get(activity) as Library
            (libraryField("loader").get(library) as ExecutorService).submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            library.autoNext = true
            library.seasonAutoplay = true

            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            field("detail").set(activity, current)
            field("episodeIndex").set(activity, 0)
            field("foreground").set(activity, true)
            field("screen").set(activity, "player")
            field("pausedForLifecycle").set(activity, false)

            offer(activity, current)
            worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val banner = field("seasonBanner").get(activity) as TextView
            assertTrue(banner.text.toString().contains("本季结束后播放《长剧第二季》"))
            assertFalse(banner.isFocusable)
            assertEquals(View.VISIBLE, banner.visibility)

            MainActivity::class.java.getDeclaredMethod("cancelNextSeason")
                .apply { isAccessible = true }.invoke(activity)
            assertNull(field("seasonBanner").get(activity))
            assertNull(banner.parent)
            assertFalse(field("seasonPending").get(activity) as Boolean)

            offer(activity, current)
            worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            val second = field("seasonBanner").get(activity) as TextView
            controller.pause()
            assertNull(second.parent)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
        } finally {
            worker.shutdownNow()
            controller.destroy()
        }
    }
}
