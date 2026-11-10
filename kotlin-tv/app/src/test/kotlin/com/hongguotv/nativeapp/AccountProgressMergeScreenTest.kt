// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[21])
class AccountProgressMergeScreenTest {
    private fun session(id: String="700")=AuthSession(mapOf("sessionid" to "fixture-$id"),"token-$id",AuthUser("user-$id","fixture",accountId=id))
    private class Fixture(loggedIn: Boolean=true,withIntents: Boolean=false): AutoCloseable {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val host=LinearLayout(activity)
        val repo=AccountRepository()
        val intents=if(withIntents) AccountIntents(activity,repo,object: AccountIntentStorage {
            override val key="merge-screen-${UUID.randomUUID()}"
            private var value="{}"
            override fun read()=value
            override fun commit(value: String): Boolean { this.value=value; return true }
        }) else null
        var starts=0
        var complete: ((String)->Unit)?=null
        val screen: AccountScreen
        init {
            AuthStore.clear(activity)
            val initial=if(loggedIn) AuthSession(mapOf("sessionid" to "fixture-700"),"token-700",AuthUser("user-700","fixture",accountId="700")) else null
            if(initial!=null) AuthStore.save(activity,initial)
            repo.restoreSession(initial)
            intents?.let { coordinator ->
                val loaded=CountDownLatch(1)
                coordinator.loadAsync { loaded.countDown() }
                assertTrue(loaded.await(5,TimeUnit.SECONDS)); assertTrue(coordinator.isReady())
            }
            screen=AccountScreen(activity,repo,{ text,click -> Button(activity).apply { this.text=text; setOnClickListener { click() } } },{}, {},{ _,_,_ -> },
                intents=intents,
                mergeProgress={ context,done -> assertTrue(repo.isCurrent(context)); starts++; complete=done; true })
            activity.setContentView(host)
            screen.show(host)
        }
        fun views(view: View=host): List<TextView> = if(view is ViewGroup) (0 until view.childCount).flatMap { views(view.getChildAt(it)) } else listOfNotNull(view as? TextView)
        fun merge()=views().first { it.text=="合并观看进度" }
        fun has(text: String)=views().any { it.text.toString().contains(text) }
        fun deliver(text: String) { complete!!(text); shadowOf(Looper.getMainLooper()).idle() }
        override fun close() { screen.destroy(); intents?.close(); AuthStore.clear(activity); activity.finish() }
    }
    @Test fun `explicit signed in entry explains farther policy and does not start on render`() {
        Fixture().use { f ->
            assertTrue(f.has("按观看进度合并，本地领先时上报云端"))
            assertTrue(f.merge().isEnabled)
            assertEquals(0,f.starts)
        }
        Fixture(false).use { f -> assertFalse(f.has("合并观看进度")); assertEquals(0,f.starts) }
    }
    @Test fun `repeat click is disabled while running and result restores the action`() {
        Fixture().use { f ->
            f.merge().performClick()
            assertEquals(1,f.starts)
            assertFalse(f.merge().isEnabled)
            assertTrue(f.has("正在合并"))
            f.merge().performClick()
            assertEquals(1,f.starts)
            f.deliver("本地更新 2，已提交 0，待上报 1，跳过 3")
            assertTrue(f.has("本地更新 2"))
            assertTrue(f.merge().isEnabled)
        }
    }
    @Test fun `late page and identical relogin callbacks cannot display stale results`() {
        Fixture().use { f ->
            f.merge().performClick(); f.screen.close(); f.screen.show(f.host)
            f.deliver("STALE-PAGE-RESULT")
            assertFalse(f.has("STALE-PAGE-RESULT"))
            f.merge().performClick(); f.repo.logout(); f.repo.restoreSession(session())
            f.deliver("STALE-ACCOUNT-RESULT")
            assertFalse(f.has("STALE-ACCOUNT-RESULT"))
        }
    }
    @Test fun `changed history generation releases the same page action without displaying stale result`() {
        Fixture(withIntents=true).use { f ->
            f.merge().performClick()
            f.intents!!.rewatch(f.repo.captureAccount()!!,"123")
            f.deliver("STALE-GENERATION-RESULT")
            assertFalse(f.has("STALE-GENERATION-RESULT"))
            assertTrue(f.merge().isEnabled)
            assertTrue(f.has("同步状态已变化"))
            f.merge().performClick()
            assertEquals(2,f.starts)
        }
    }
}
