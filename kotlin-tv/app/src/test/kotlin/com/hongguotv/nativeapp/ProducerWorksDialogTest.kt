package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowAlertDialog

@RunWith(org.robolectric.RobolectricTestRunner::class)
@Config(manifest = Config.NONE, sdk = [28], qualifiers = "w960dp-h540dp-land-mdpi")
@LooperMode(LooperMode.Mode.PAUSED)
class ProducerWorksDialogTest {
    private fun views(view: View): List<View> = listOf(view) + if (view is ViewGroup)
        (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else emptyList()

    private fun repository(answer: (okhttp3.Request) -> String) = ContentRepository(OkHttpClient.Builder().addInterceptor { chain ->
        Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
            .body(answer(chain.request()).toResponseBody()).build()
    }.build())

    @Test fun verifiedSearchRowsBindPostersAndOpenSelection() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val anchor = TextView(activity.get())
        activity.get().setContentView(anchor)
        val repo = repository { request ->
            val path = request.url.encodedPath
            when {
                "plan" in path -> """{"code":0,"data":[{"show_type":547,"video_data":[]}]}"""
                "multi-detail" in path -> """{"code":0,"data":[{"book_id":"777","media_id":"456","author":"制作方甲","book_name":"制作方的另一部剧","thumb_url":"https://example.com/other.jpg"}]}"""
                else -> """{"pageNum":1,"hasMore":false,"searchList":[{"video_data":{"series_id":"777","series_name":"制作方的另一部剧","series_cover":"https://example.com/cover.jpg","genre":"205"}}]}"""
            }
        }
        var selected: Series? = null
        val current = Series("123", "当前作品", "https://example.com/current.jpg")
        val controller = ProducerWorksDialog(activity.get(), repo, Producer("456", "制作方甲"), current,
            anchor, { _, _ -> }, { selected = it })
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            fun settled(): Boolean = views(dialog.window!!.decorView).any { it.tag == "777" }
            for (attempt in 0..300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (settled()) break
                Thread.sleep(10)
            }
            assertTrue(settled())
            val card = views(dialog.window!!.decorView).single { it.tag == "777" }
            assertTrue(views(card).any { it is ImageView })
            card.performClick()
            assertEquals("777", selected?.id)
            assertFalse(dialog.isShowing)
        } finally { controller.close(); activity.pause().stop().destroy() }
    }

    @Test fun failedSearchPreservesVerifiedWorksAndOffersRetry() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup()
        val anchor = TextView(activity.get())
        activity.get().setContentView(anchor)
        val repo = repository { request ->
            val path = request.url.encodedPath
            when {
                "plan" in path -> """{"code":0,"data":[{"show_type":547,"video_data":[]}]}"""
                "multi-detail" in path -> """{"code":0,"data":[{"book_id":"123","media_id":"456","author":"制作方甲"}]}"""
                else -> """{"code":"search_rejected"}"""
            }
        }
        var selected: Series? = null
        val current = Series("123", "当前作品", "https://example.com/cover.jpg")
        val controller = ProducerWorksDialog(activity.get(), repo, Producer("456", "制作方甲"), current,
            anchor, { _, _ -> }, { selected = it })
        try {
            val dialog = ShadowAlertDialog.getLatestAlertDialog()
            fun retry() = views(dialog.window!!.decorView).filterIsInstance<TextView>()
                .firstOrNull { it.text.toString() == "重试查找更多作品" }
            for (attempt in 0..300) {
                shadowOf(Looper.getMainLooper()).idle()
                if (retry() != null) break
                Thread.sleep(10)
            }
            assertNotNull(retry())
            assertTrue(retry()!!.isEnabled)
            views(dialog.window!!.decorView).single { it.tag == "123" }.performClick()
            assertEquals(current, selected)
            assertFalse(dialog.isShowing)
        } finally { controller.close(); activity.pause().stop().destroy() }
    }
}
