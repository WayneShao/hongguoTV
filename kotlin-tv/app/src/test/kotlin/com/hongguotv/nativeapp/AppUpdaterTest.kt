package com.hongguotv.nativeapp

import android.app.Activity
import android.app.AlertDialog
import android.os.Looper
import android.widget.Button
import com.hongguotv.core.AppUpdateRepository
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowAlertDialog
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[21,28])
class AppUpdaterTest {
    @Test fun manualCheckUsesRealDialogAndPauseCancelsLateCallback() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); activity.setTheme(android.R.style.Theme_Material_Dialog_Alert)
        val anchor=Button(activity);activity.setContentView(anchor)
        val entered=CountDownLatch(1);val released=CountDownLatch(1)
        val repository=AppUpdateRepository {builder -> builder.addInterceptor {chain ->
            entered.countDown();released.await(3,TimeUnit.SECONDS)
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("absent").body("".toResponseBody()).build()
        }}
        val updater=AppUpdater(activity,repository){true}
        try {
            updater.resume();updater.show(anchor)
            val dialog=ShadowAlertDialog.getLatestAlertDialog()
            assertTrue(dialog.isShowing);assertEquals("检查更新",dialog.getButton(AlertDialog.BUTTON_POSITIVE).text.toString())
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            updater.pause();assertFalse(updater.showing)
            released.countDown();shadowOf(Looper.getMainLooper()).idle()
            assertFalse(updater.showing)
        } finally {released.countDown();updater.destroy();controller.pause().stop().destroy()}
    }
    @Test fun automaticCheckIsEnabledButWaitsWhilePlaybackIsActive() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        activity.getSharedPreferences("app_updates",0).edit().clear().commit()
        val entered=CountDownLatch(1)
        val repository=AppUpdateRepository {builder -> builder.addInterceptor {chain ->
            entered.countDown()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(404).message("absent").body("".toResponseBody()).build()
        }}
        var browsing=false
        val updater=AppUpdater(activity,repository){browsing}
        try {
            updater.resume();shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(8))
            assertEquals(1L,entered.count)
            browsing=true;shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            updater.pause();assertFalse(updater.showing)
        } finally {updater.destroy();controller.pause().stop().destroy()}
    }
    @Test fun routeSelectionPersistsAndRejectsUnknownValues() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        activity.getSharedPreferences("app_updates",0).edit().clear().putString("github_route","https://evil.invalid/").commit()
        val updater=AppUpdater(activity){false}
        try {
            assertEquals(0,updater.routeIndex)
            assertEquals("自动 · 直连失败后切换镜像",updater.routeLabel)
            val mirror=AppUpdateRepository.DEFAULT_ACCELERATORS.single()
            updater.selectRoute(2)
            assertEquals(mirror,activity.getSharedPreferences("app_updates",0).getString("github_route",""))
            assertEquals("镜像 · gh-proxy.com",updater.routeLabel)
            updater.selectRoute(99)
            assertEquals(mirror,activity.getSharedPreferences("app_updates",0).getString("github_route",""))
        } finally {updater.destroy();controller.pause().stop().destroy()}
    }

    private fun idleUntil(timeoutMillis: Long = 3000, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            if (condition()) return
            Thread.sleep(10)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun routeSwitchAfterFailedCheckRechecksImmediately() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        activity.setTheme(android.R.style.Theme_Material_Dialog_Alert)
        // 固定直连线路，先验证一次失败后再切换镜像，调用次数才可精确断言。
        activity.getSharedPreferences("app_updates",0).edit().clear().putString("github_route","direct").commit()
        val anchor=Button(activity);activity.setContentView(anchor)
        var calls=0
        val seen=mutableListOf<String>()
        // 注入的仓库也要读取同一线路偏好，才能真实复现“切线后下一次检查走新线路”。
        val repository=AppUpdateRepository(route = {
            activity.getSharedPreferences("app_updates",0).getString("github_route","auto").orEmpty()
        }) {builder -> builder.addInterceptor {chain ->
            calls++;seen+=chain.request().url.toString()
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(500).message("failed").body("".toResponseBody()).build()
        }}
        val updater=AppUpdater(activity,repository){true}
        try {
            assertEquals("direct",activity.getSharedPreferences("app_updates",0).getString("github_route",""))
            assertEquals(1,updater.routeIndex)
            updater.resume();updater.show(anchor)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            idleUntil { calls>=1 && updater.statusText!="正在检查新版…" }
            assertEquals(seen.toString(),1,calls)
            val prefs=activity.getSharedPreferences("app_updates",0)
            assertTrue("检查开始时应记录 last_attempt", prefs.getLong("last_attempt",0)>0)
            updater.selectRoute(2)
            assertEquals("切换线路后必须立即恢复检查资格",0L,prefs.getLong("last_attempt",0))
            assertTrue(updater.statusText.contains("重新检查"))
            ShadowAlertDialog.getLatestAlertDialog().dismiss()
            // 下一轮轮询应立刻重新检查，而不是继续等满 6 小时。
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(15))
            idleUntil { calls>=2 }
            assertEquals(2,calls)
        } finally {updater.destroy();controller.pause().stop().destroy()}
    }

    @Test fun routeSwitchDuringBlockedCheckCancelsWithoutMirrorFallbackOrStaleStatus() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup();val activity=controller.get()
        activity.setTheme(android.R.style.Theme_Material_Dialog_Alert)
        activity.getSharedPreferences("app_updates",0).edit().clear().commit()
        val anchor=Button(activity);activity.setContentView(anchor)
        val blocked=CountDownLatch(1);val release=CountDownLatch(1)
        val interrupted=AtomicBoolean(false);val metadata=AtomicInteger(0);val mirror=AtomicInteger(0)
        val repository=AppUpdateRepository(route = {
            activity.getSharedPreferences("app_updates",0).getString("github_route","auto").orEmpty()
        }) {builder -> builder.addInterceptor {chain ->
            val host=chain.request().url.host
            if(host=="api.github.com") {
                metadata.incrementAndGet();blocked.countDown()
                try { release.await() } catch (e: InterruptedException) {
                    interrupted.set(true);Thread.currentThread().interrupt();throw IOException("cancelled",e)
                }
            } else mirror.incrementAndGet()
            throw IOException("blocked request released without response")
        }}
        val updater=AppUpdater(activity,repository){true}
        try {
            updater.resume();updater.show(anchor)
            ShadowAlertDialog.getLatestAlertDialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick()
            assertTrue(blocked.await(2,TimeUnit.SECONDS))
            assertEquals(1,metadata.get())
            updater.selectRoute(2)
            val switched="更新线路已切换为镜像 · gh-proxy.com，将重新检查更新"
            assertEquals(switched,updater.statusText)
            release.countDown()
            idleUntil { interrupted.get() }
            Thread.sleep(100);shadowOf(Looper.getMainLooper()).idle()
            assertEquals("取消后不得继续尝试镜像",0,mirror.get())
            assertEquals("迟到的失败回调不得覆盖线路切换提示",switched,updater.statusText)
        } finally {release.countDown();updater.destroy();controller.pause().stop().destroy()}
    }
}
