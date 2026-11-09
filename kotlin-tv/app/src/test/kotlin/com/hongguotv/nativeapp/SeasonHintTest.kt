package com.hongguotv.nativeapp

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
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
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
import java.lang.reflect.Proxy
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

    private fun fixture(relatedCalls: IntArray, failRelated: Boolean = false): ContentRepository {
        val http = OkHttpClient.Builder().addInterceptor { chain ->
            val path = chain.request().url.encodedPath
            if ("plan" in path) relatedCalls[0]++
            if ("plan" in path && failRelated) throw java.io.IOException("related service unavailable")
            val body = when {
                "plan" in path ->
                    """{"code":0,"data":[{"show_type":547,"video_data":[{"series_id":"222","title":"长剧第二季","content_type":1004,"episode_cnt":10,"season_index":2}]}]}"""
                path == "/detail" ->
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

    private fun maybePrepare(activity: MainActivity) =
        MainActivity::class.java.getDeclaredMethod("maybePrepareSeason")
            .apply { isAccessible = true }.invoke(activity)

    private fun seekTo(activity: MainActivity, position: Long) =
        MainActivity::class.java.getDeclaredMethod("seekTo", java.lang.Long.TYPE)
            .apply { isAccessible = true }.invoke(activity, position)

    private fun seek(activity: MainActivity, direction: Int) =
        MainActivity::class.java.getDeclaredMethod("seek", java.lang.Integer.TYPE, java.lang.Integer.TYPE)
            .apply { isAccessible = true }.invoke(activity, direction, 0)

    private fun goBack(activity: MainActivity) =
        MainActivity::class.java.getDeclaredMethod("goBack")
            .apply { isAccessible = true }.invoke(activity)

    private fun end(activity: MainActivity, current: Detail, ended: ExoPlayer) =
        MainActivity::class.java.getDeclaredMethod(
            "handlePlaybackEnded",
            Detail::class.java,
            ExoPlayer::class.java,
        ).apply { isAccessible = true }.invoke(activity, current, ended)

    private fun proxyPlayer(
        duration: Long = 5000L,
        position: Long = 1000L,
        playing: Boolean = true,
        state: Int = Player.STATE_ENDED,
    ): Pair<ExoPlayer, MutableList<String>> {
        val calls = mutableListOf<String>()
        val player = Proxy.newProxyInstance(ExoPlayer::class.java.classLoader, arrayOf(ExoPlayer::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "getPlaybackLooper", "getApplicationLooper" -> Looper.getMainLooper()
                "getDuration" -> duration
                "getCurrentPosition" -> position
                "getPlaybackState" -> state
                "getPlayWhenReady", "isPlaying" -> playing
                "getPlaybackParameters" -> PlaybackParameters(1f)
                else -> primitiveDefault(method.returnType)
            }
        } as ExoPlayer
        return player to calls
    }

    private fun primitiveDefault(type: Class<*>): Any? = when (type) {
        java.lang.Boolean.TYPE -> false
        java.lang.Integer.TYPE -> 0
        java.lang.Long.TYPE -> 0L
        java.lang.Float.TYPE -> 0f
        java.lang.Double.TYPE -> 0.0
        java.lang.Short.TYPE -> 0.toShort()
        java.lang.Byte.TYPE -> 0.toByte()
        java.lang.Character.TYPE -> ' '
        else -> null
    }

    private fun installViews(activity: MainActivity) {
        val root = field("root").get(activity) as FrameLayout
        val hud = LinearLayout(activity).apply { visibility = View.VISIBLE }
        val controls = LinearLayout(activity)
        hud.addView(TextView(activity))
        hud.addView(controls)
        root.addView(hud)
        field("hud").set(activity, hud)
        field("playbackTitle").set(activity, TextView(activity))
        field("playbackText").set(activity, TextView(activity))
        field("controls").set(activity, controls)
        field("progressBar").set(activity, ProgressBar(activity))
    }

    private fun ready(activity: MainActivity, player: ExoPlayer, current: Detail) {
        field("player").set(activity, player)
        field("detail").set(activity, current)
        field("episodeIndex").set(activity, 0)
        field("foreground").set(activity, true)
        field("screen").set(activity, "player")
        field("pausedForLifecycle").set(activity, false)
        field("requestedAutoplay").set(activity, true)
        field("sleepStopped").set(activity, false)
        field("playbackReady").set(activity, true)
        field("seasonSuppressed").set(activity, false)
        field("seasonPreparationAttempted").set(activity, false)
        field("seasonSeekToEnd").set(activity, false)
        field("playError").set(activity, false)
        field("panel").set(activity, false)
    }

    private fun nextSeason() = Detail(Series("222", "长剧第二季"), listOf("11", "12"))

    private fun View.textViews(): List<TextView> = buildList {
        if (this@textViews is TextView) add(this@textViews)
        if (this@textViews is ViewGroup) for (index in 0 until childCount) addAll(getChildAt(index).textViews())
    }

    private fun withActivity(failRelated: Boolean = false, block: (MainActivity, ExecutorService, IntArray, ActivityController<MainActivity>) -> Unit) {
        val worker = Executors.newSingleThreadExecutor()
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val originalIo = field("io").get(activity) as ExecutorService
        val relatedCalls = intArrayOf(0)
        try {
            field("io").set(activity, worker)
            originalIo.shutdownNow()
            field("repository").set(activity, fixture(relatedCalls, failRelated))
            controller.create()
            val library = field("library").get(activity) as Library
            (libraryField("loader").get(library) as ExecutorService).submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            library.autoNext = true
            library.seasonAutoplay = true
            installViews(activity)
            block(activity, worker, relatedCalls, controller)
        } finally {
            worker.shutdownNow()
            controller.destroy()
        }
    }

    private fun drain(worker: ExecutorService) {
        worker.submit { }.get(5, TimeUnit.SECONDS)
        ShadowLooper.idleMainLooper()
    }

    @Test fun hintCanBeCancelledAndDisappearsWhenLeavingTheForeground() {
        withActivity { activity, worker, _, controller ->
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, proxyPlayer().first, current)

            offer(activity, current)
            drain(worker)
            val banner = field("seasonBanner").get(activity) as TextView
            assertTrue(banner.text.toString().contains("本季结束后播放《长剧第二季》"))
            assertFalse(banner.isFocusable)
            assertEquals(View.VISIBLE, banner.visibility)

            MainActivity::class.java.getDeclaredMethod("cancelNextSeason")
                .apply { isAccessible = true }.invoke(activity)
            assertNull(field("seasonBanner").get(activity))
            assertNull(banner.parent)
            assertFalse(field("seasonPending").get(activity) as Boolean)

            ready(activity, proxyPlayer().first, current)
            offer(activity, current)
            drain(worker)
            val second = field("seasonBanner").get(activity) as TextView
            controller.pause()
            assertNull(second.parent)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
        }
    }

    @Test fun cancellingThePreparedHintInTheUiKeepsStateEndedOnTheCurrentSeason() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer().first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            field("nextSeasonDetail").set(activity, nextSeason())

            MainActivity::class.java.getDeclaredMethod("showPanel")
                .apply { isAccessible = true }.invoke(activity)
            val controls = field("controls").get(activity) as LinearLayout
            val cancel = controls.textViews().firstOrNull { it.text == "取消本次跨季连播" }
                ?: error("missing cancel button")
            cancel.performClick()
            assertTrue(field("seasonSuppressed").get(activity) as Boolean)
            assertNull(field("nextSeasonDetail").get(activity))

            end(activity, current, player)
            drain(worker)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
            assertEquals(0, relatedCalls[0])
        }
    }

    @Test fun seasonAutoplayOffDoesNotFallBackToTheLookupAfterACancelledPreparedSeason() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer().first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            field("nextSeasonDetail").set(activity, nextSeason())
            val library = field("library").get(activity) as Library
            library.seasonAutoplay = false

            end(activity, current, player)
            drain(worker)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
            assertEquals(0, relatedCalls[0])
        }
    }

    @Test fun seekingToTheEndCancelsThePreparedHintAndLateLookup() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer().first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            field("nextSeasonDetail").set(activity, nextSeason())

            seekTo(activity, 5000L)
            assertTrue(field("seasonSuppressed").get(activity) as Boolean)
            assertNull(field("nextSeasonDetail").get(activity))
            assertNull(field("seasonBanner").get(activity))

            end(activity, current, player)
            drain(worker)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
            assertEquals(0, relatedCalls[0])
        }
    }

    @Test fun stepSeekingCancelsThePreparedHint() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer(position = 3000L).first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            field("nextSeasonDetail").set(activity, nextSeason())

            seek(activity, 1)
            assertTrue(field("seasonSuppressed").get(activity) as Boolean)
            assertNull(field("nextSeasonDetail").get(activity))

            end(activity, current, player)
            drain(worker)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
            assertEquals(0, relatedCalls[0])
        }
    }

    @Test fun backHidingTheHudCancelsThePreparedHint() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer().first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            field("nextSeasonDetail").set(activity, nextSeason())

            goBack(activity)
            assertEquals(View.GONE, (field("hud").get(activity) as View).visibility)
            assertTrue(field("seasonSuppressed").get(activity) as Boolean)
            assertNull(field("nextSeasonDetail").get(activity))

            end(activity, current, player)
            drain(worker)
            assertEquals("111", (field("detail").get(activity) as Detail).series.id)
            assertEquals(0, relatedCalls[0])
        }
    }

    @Test fun lateHintCallbackAfterNavigationOrCancelNeverRestoresTheBanner() {
        withActivity { activity, worker, _, _ ->
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, proxyPlayer().first, current)

            offer(activity, current)
            worker.submit { }.get(5, TimeUnit.SECONDS)
            field("screen").set(activity, "catalog")
            field("foreground").set(activity, false)
            ShadowLooper.idleMainLooper()
            assertNull(field("seasonBanner").get(activity))
            assertNull(field("nextSeasonDetail").get(activity))
            assertFalse(field("seasonPending").get(activity) as Boolean)

            ready(activity, proxyPlayer().first, current)
            offer(activity, current)
            MainActivity::class.java.getDeclaredMethod("cancelNextSeason")
                .apply { isAccessible = true }.invoke(activity)
            worker.submit { }.get(5, TimeUnit.SECONDS)
            ShadowLooper.idleMainLooper()
            assertNull(field("seasonBanner").get(activity))
            assertNull(field("nextSeasonDetail").get(activity))
            assertFalse(field("seasonPending").get(activity) as Boolean)
        }
    }

    @Test fun failedPreparationIsNotRetriedOnTicksButAllowsEndedFallback() {
        withActivity(failRelated = true) { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer(position = 3000L).first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)
            repeat(3) {
                maybePrepare(activity)
                drain(worker)
            }
            assertEquals("one attempt across the three related hosts", 3, relatedCalls[0])
            assertNull(field("seasonBanner").get(activity))
            end(activity, current, player)
            drain(worker)
            assertEquals("ended fallback remains a separately gated attempt", 6, relatedCalls[0])
        }
    }

    @Test fun unknownDurationNeverOffersTheHintWithoutInvokingTheLookup() {
        withActivity { activity, worker, relatedCalls, _ ->
            val player = proxyPlayer(duration = 0L, position = 0L).first
            val current = Detail(Series("111", "长剧第一季"), listOf("123"))
            ready(activity, player, current)

            maybePrepare(activity)
            drain(worker)
            assertNull(field("seasonBanner").get(activity))
            assertNull(field("nextSeasonDetail").get(activity))
            assertFalse(field("seasonPending").get(activity) as Boolean)
            assertEquals(0, relatedCalls[0])
        }
    }
}
