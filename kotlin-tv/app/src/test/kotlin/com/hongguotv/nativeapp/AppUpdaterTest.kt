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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
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
}
