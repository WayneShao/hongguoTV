package com.hongguotv.nativeapp

import android.app.Activity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[28])
class AccountScreenTest {
    private fun texts(view: View): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
    @Test fun signedOutScreenStaysInHostAndBackInvokesHostNavigation() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup()
        val activity=controller.get(); val host=LinearLayout(activity); activity.setContentView(host)
        val repo=AccountRepository(); AuthStore.clear(activity)
        var returned=false
        val screen=AccountScreen(activity,repo,{ title,click -> Button(activity).apply { text=title; setOnClickListener { click() } } },{ returned=true },{}, { _,_,_ -> })
        screen.show(host)
        assertTrue(texts(host).any { it.text=="显示登录二维码" })
        texts(host).first { it.text=="返回设置" }.performClick()
        assertTrue(returned)
        screen.destroy(); controller.pause().stop().destroy()
    }
    @Test fun logoutClearsSessionButPreservesLocalLibraryPreferences() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val a=controller.get()
        val host=LinearLayout(a); a.setContentView(host)
        val repo=AccountRepository(); val session=AuthSession(mapOf("sessionid" to "fixture"),"",AuthUser("user","测试账号"))
        AuthStore.save(a,session); repo.restoreSession(session)
        a.getSharedPreferences("library-fixture",0).edit().putString("history","keep").commit()
        var changes=0
        val screen=AccountScreen(a,repo,{ title,click -> Button(a).apply { text=title; setOnClickListener { click() } } },{}, { changes++ },{ _,_,_ -> })
        screen.show(host)
        assertTrue(texts(host).any { it.text=="云端收藏" })
        texts(host).first { it.text=="退出红果账号" }.performClick()
        assertNull(repo.currentSession()); assertEquals(1,changes)
        assertEquals("keep",a.getSharedPreferences("library-fixture",0).getString("history",null))
        AuthStore.restore(a,repo); assertNull(repo.currentSession())
        screen.destroy(); controller.pause().stop().destroy()
    }
    @Test fun closingWindowDiscardsUnacceptedLoginSession() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val a=controller.get()
        val repo=AccountRepository(); AuthStore.clear(a)
        val screen=AccountScreen(a,repo,{ title,click -> Button(a).apply { text=title; setOnClickListener { click() } } },{}, {},{ _,_,_ -> })
        screen.show(LinearLayout(a))
        repo.restoreSession(AuthSession(mapOf("sessionid" to "late"),"",AuthUser("late","")))
        screen.close(); assertNull(repo.currentSession())
        screen.destroy(); controller.pause().stop().destroy()
    }
    @Test fun qrBackReturnsToAccountBeforeLeavingSettings() {
        val controller=Robolectric.buildActivity(Activity::class.java).setup(); val a=controller.get()
        val http=okhttp3.OkHttpClient.Builder().addInterceptor { throw java.io.IOException("offline fixture") }.build()
        val repo=AccountRepository(http); AuthStore.clear(a)
        var returned=0
        val host=LinearLayout(a); a.setContentView(host)
        val screen=AccountScreen(a,repo,{ title,click -> Button(a).apply { text=title; setOnClickListener { click() } } },{ returned++ },{}, { _,_,_ -> })
        screen.show(host); texts(host).first { it.text=="显示登录二维码" }.performClick()
        screen.goBack()
        assertEquals(0,returned)
        assertTrue(texts(host).any { it.text=="显示登录二维码" })
        screen.goBack(); assertEquals(1,returned)
        screen.destroy(); controller.pause().stop().destroy()
    }

}
