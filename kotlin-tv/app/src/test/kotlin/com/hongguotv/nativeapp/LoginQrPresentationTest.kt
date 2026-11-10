package com.hongguotv.nativeapp

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import android.os.Looper
import android.util.Base64
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[26])
class LoginQrPresentationTest {
    private class Fixture(val expiresIn: Long=60_000,val confirm: Boolean=false,val blockProfile: Boolean=false,val blockCreate: Boolean=false,val badImage: Boolean=false) : AutoCloseable {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get()
        val host=LinearLayout(activity)
        val creates=AtomicInteger(); val polls=AtomicInteger()
        val profileStarted=CountDownLatch(1); val releaseProfile=CountDownLatch(1)
        val profileFinished=CountDownLatch(1); val releaseCreate=CountDownLatch(1); val createFinished=CountDownLatch(1)
        fun hold(latch: CountDownLatch) { while(true) { try { if(latch.await(10,TimeUnit.MILLISECONDS)) return } catch(_: InterruptedException) { /* deliberately ignore cancelled transport */ } } }
        val png=ByteArrayOutputStream().also { Bitmap.createBitmap(8,8,Bitmap.Config.ARGB_8888).compress(Bitmap.CompressFormat.PNG,100,it) }.toByteArray()
        val repo=AccountRepository(OkHttpClient.Builder().addInterceptor { chain ->
            val path=chain.request().url.encodedPath
            val data=when {
                path.contains("get_qrcode") -> {
                    creates.incrementAndGet()
                    if(blockCreate) hold(releaseCreate)
                    createFinished.countDown()
                    JSONObject().put("data",JSONObject().put("token","fixture-${creates.get()}").put("qrcode",if(badImage) "A" else Base64.encodeToString(png,Base64.NO_WRAP)).put("expire_time",(System.currentTimeMillis()+expiresIn)/1000)).toString()
                }
                path.contains("check_qrconnect") -> {
                    polls.incrementAndGet()
                    if(!confirm) throw IOException("offline fixture")
                    """{"data":{"status":"confirmed","redirect_url":"https://hongguoduanju.com/fixture-login"}}"""
                }
                path.contains("user/info") -> {
                    profileStarted.countDown()
                    if(blockProfile) hold(releaseProfile)
                    profileFinished.countDown()
                    """{"code":0,"data":{"user_name":"fixture","user_id":"123"}}"""
                }
                else -> "{}"
            }
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .apply { if(path=="/fixture-login") addHeader("Set-Cookie","sessionid=fixture; Path=/; Secure") }
                .body(data.toResponseBody("application/json".toMediaType())).build()
        }.build())
        var changed=0
        val screen=AccountScreen(activity,repo,{ title,click -> Button(activity).apply { text=title; setOnClickListener { click() } } },{}, { changed++ },{ _,_,_ -> })
        init { AuthStore.clear(activity); activity.setContentView(host); screen.show(host) }
        fun views(root: View=host): List<View> = listOf(root)+if(root is ViewGroup) (0 until root.childCount).flatMap { views(root.getChildAt(it)) } else emptyList()
        fun click(text: String)=views().filterIsInstance<TextView>().first { it.text==text }.performClick()
        fun await(condition: ()->Boolean) {
            val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(8)
            while(!condition() && System.nanoTime()<end) { shadowOf(Looper.getMainLooper()).idle(); Thread.sleep(5) }
            shadowOf(Looper.getMainLooper()).idle(); assertTrue("fixture condition timed out",condition())
        }
        fun start() { click("显示登录二维码"); await { views().filterIsInstance<ImageView>().any { it.drawable!=null } } }
        fun advance(ms: Long) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)) }
        fun settleWorker() {
            val field=AccountScreen::class.java.getDeclaredField("worker").apply { isAccessible=true }
            (field.get(screen) as java.util.concurrent.ExecutorService).submit {}.get(8,TimeUnit.SECONDS)
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() { releaseCreate.countDown(); releaseProfile.countDown(); screen.destroy(); controller.pause().stop().destroy() }
    }
    @Test fun enlargementSharesBitmapWithoutRestartingQrAndBackRestoresFocus() = Fixture().use { f ->
        f.start(); val anchor=f.views().filterIsInstance<TextView>().first { it.text=="放大二维码" }; anchor.requestFocus(); anchor.performClick()
        val images=f.views().filterIsInstance<ImageView>().filter { it.drawable!=null }
        assertEquals(2,images.size); assertEquals(1,f.creates.get()); assertEquals(0,f.polls.get())
        assertSame((images[0].drawable as BitmapDrawable).bitmap,(images[1].drawable as BitmapDrawable).bitmap)
        assertNotSame(images[0].drawable,images[1].drawable)
        images.forEach { assertFalse((it.drawable as BitmapDrawable).paint.isFilterBitmap) }
        f.screen.goBack(); assertTrue(anchor.hasFocus()); assertEquals(1,f.creates.get())
        assertEquals(1,f.views().filterIsInstance<ImageView>().size)
        f.screen.goBack(); assertTrue(f.views().filterIsInstance<TextView>().any { it.text=="显示登录二维码" })
    }
    @Test fun clickingQrAlsoEnlargesAndRefreshUpdatesBothAcceptedImages() = Fixture().use { f ->
        f.start(); f.views().filterIsInstance<ImageView>().first().performClick()
        assertEquals(2,f.views().filterIsInstance<ImageView>().size)
        val refresh=f.views().filterIsInstance<TextView>().last { it.text=="刷新二维码" }
        refresh.performClick()
        assertTrue(f.views().filterIsInstance<ImageView>().all { it.drawable==null })
        f.await { f.creates.get()==2 && f.views().filterIsInstance<ImageView>().count { it.drawable!=null }==2 }
        assertTrue(f.views().filterIsInstance<TextView>().any { it.text=="关闭放大" })
    }
    @Test fun independentExpiryDisablesBothImagesAfterPollingFailure() = Fixture(expiresIn=5000).use { f ->
        f.start(); f.click("放大二维码"); f.advance(2100); f.await { f.polls.get()==1 }
        f.advance(3500)
        assertTrue(f.views().filterIsInstance<ImageView>().all { it.drawable==null && !it.isEnabled })
        assertTrue(f.views().filterIsInstance<TextView>().any { it.text.toString().contains("二维码已过期") })
        f.advance(5000); assertEquals(1,f.polls.get())
    }
    @Test fun enlargementHonorsActualNarrowAndShortViewportAndKeepsControlsScrollable() = Fixture().use { f ->
        f.start(); f.click("放大二维码")
        listOf(600 to 800,320 to 180,900 to 640).forEach { (w,h) ->
            f.host.measure(View.MeasureSpec.makeMeasureSpec(w,View.MeasureSpec.AT_MOST),View.MeasureSpec.makeMeasureSpec(h,View.MeasureSpec.EXACTLY)); f.host.layout(0,0,f.host.measuredWidth,h)
            val image=f.views().filterIsInstance<ImageView>().last()
            assertTrue(image.measuredWidth<=w); assertEquals(image.measuredWidth,image.measuredHeight)
            val close=f.views().filterIsInstance<TextView>().last { it.text=="关闭放大" }
            val scroll=f.views().filterIsInstance<ScrollView>().last()
            val body=image.parent as LinearLayout
            if(h==800) assertTrue("QR and controls fit available height",body.measuredHeight<=h)
            scroll.fullScroll(View.FOCUS_DOWN); close.requestFocus(); shadowOf(Looper.getMainLooper()).idle()
            assertTrue("Close remains reachable by scrolling",close.bottom<=scroll.scrollY+scroll.height)
        }
    }
    @Test fun expiredOrReplacedGenerationCannotKeepTentativeLoginEvenWithCorruptStorage() {
        listOf("refresh","back","expiry").forEach { action -> Fixture(expiresIn=5000,confirm=true,blockProfile=true).use { f ->
            f.start(); f.advance(2100); f.await { f.profileStarted.count==0L }
            assertNotNull(f.repo.currentSession())
            f.activity.getSharedPreferences("native-auth-v1",0).edit().putString("session","{broken").commit()
            when(action) { "refresh" -> f.click("刷新二维码"); "back" -> f.screen.goBack(); else -> f.advance(3500) }
            assertNull(f.repo.currentSession()); assertEquals(1L,f.profileFinished.count)
            f.releaseProfile.countDown(); f.await { f.profileFinished.count==0L }
            f.settleWorker()
            if(action=="refresh") f.await { f.creates.get()==2 }
            assertNull(f.repo.currentSession()); assertEquals(0,f.changed)
        } }
    }
    @Test fun acceptedLoginClosesEnlargementAndPersistsSession() = Fixture(confirm=true).use { f ->
        f.start(); f.click("放大二维码"); f.advance(2100); f.await { f.changed==1 }
        assertFalse(f.views().filterIsInstance<TextView>().any { it.text=="关闭放大" })
        assertTrue(f.views().filterIsInstance<TextView>().any { it.text=="云端收藏" })
        AuthStore.restore(f.activity,f.repo); assertNotNull(f.repo.currentSession())
    }
    @Test fun closeInvalidatesPendingCreateAndDoesNotRevivePresentation() = Fixture(blockCreate=true).use { f ->
        f.click("显示登录二维码"); f.await { f.creates.get()==1 }; f.screen.close()
        f.releaseCreate.countDown(); f.await { f.createFinished.count==0L }; f.settleWorker(); f.advance(65_000)
        assertNull(f.repo.currentSession()); assertEquals(0,f.changed)
        assertFalse(f.views().filterIsInstance<TextView>().any { it.text=="关闭放大" })
        assertTrue(f.views().filterIsInstance<ImageView>().all { it.drawable==null })
    }
    @Test fun enlargedDecodeFailureIsVisibleInsteadOfHiddenBehindLoading() = Fixture(blockCreate=true,badImage=true).use { f ->
        f.click("显示登录二维码"); f.click("放大二维码"); f.releaseCreate.countDown()
        f.await { f.views().filterIsInstance<TextView>().any { it.text.toString().contains("二维码图片无效") } }
        assertTrue(f.views().filterIsInstance<TextView>().count { it.text.toString().contains("二维码图片无效") }==2)
    }
    @Test fun blockedCreateReplacementOnlyAcceptsTheNewGeneration() = Fixture(blockCreate=true).use { f ->
        f.click("显示登录二维码"); f.await { f.creates.get()==1 }; f.click("放大二维码")
        f.click("刷新二维码"); f.releaseCreate.countDown(); f.settleWorker()
        f.await { f.creates.get()==2 && f.views().filterIsInstance<ImageView>().count { it.drawable!=null }==2 }
        assertEquals(0,f.polls.get()); assertEquals(0,f.changed)
    }
}
