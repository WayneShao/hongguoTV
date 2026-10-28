package com.hongguotv.nativeapp
import android.app.Activity
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class PreloadPreferencesTest {
 @Test fun eachModeRetainsItsThresholdAndIntervalAfterRestart() {
  val a=Robolectric.buildActivity(Activity::class.java).setup().get()
  val prefs=PreloadPreferences(a)
  val time=PreloadSettings(minutes=10,checkMinutes=2)
  prefs.save(time)
  prefs.save(prefs.load().copy(mode=PreloadMode.EPISODES,episodes=20,checkEpisodes=3))
  val restored=PreloadPreferences(a).load()
  assertEquals(20,restored.episodes);assertEquals(3,restored.checkEpisodes)
  assertEquals(10,restored.minutes);assertEquals(2,restored.checkMinutes)
  assertEquals(PreloadMode.EPISODES,restored.mode)
 }
 @Test fun selectionDialogStartsOnSavedOptionAndRestoresAnchor() {
  val controller=Robolectric.buildActivity(Activity::class.java).setup();val a=controller.get()
  val anchor=android.widget.Button(a).apply { isFocusableInTouchMode=true };a.setContentView(anchor)
  val tools=TvTools(a);var selected=-1
  tools.chooseSelected("预加载阈值",listOf("1 分钟","5 分钟","10 分钟"),1,anchor){selected=it}
  val dialog=org.robolectric.shadows.ShadowAlertDialog.getLatestAlertDialog()
  assertEquals(1,dialog.listView.checkedItemPosition)
  dialog.listView.performItemClick(null,2,2L)
  org.robolectric.shadows.ShadowLooper.idleMainLooper()
  assertEquals(2,selected);assertFalse(dialog.isShowing);assertTrue("dismiss restores the settings button",anchor.hasFocus())
  tools.destroy();controller.pause().stop().destroy()
 }

 @Test fun upgradingFromOldSettingsDisablesPreloadWithoutLosingParameters() {
  val a=Robolectric.buildActivity(Activity::class.java).setup().get()
  a.getSharedPreferences("preload-settings-v1",0).edit().clear().putString("mode","EPISODES")
   .putInt("episodes",20).putInt("checkEpisodes",3).commit()
  val prefs=PreloadPreferences(a);assertFalse(prefs.load().enabled);assertEquals(20,prefs.load().episodes)
  prefs.save(prefs.load().copy(enabled=true));assertTrue(PreloadPreferences(a).load().enabled)
  prefs.save(prefs.load().copy(enabled=false));assertFalse(PreloadPreferences(a).load().enabled)
  assertEquals(20,prefs.load().episodes);assertEquals(3,prefs.load().checkEpisodes)
 }

}
