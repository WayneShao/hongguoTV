// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.app.Activity
import android.content.Context
import android.os.Looper
import com.hongguotv.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(manifest=Config.NONE,sdk=[21])
@LooperMode(LooperMode.Mode.PAUSED)
class AutoProgressSyncTest {
    private class Fixture: AutoCloseable {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        val repo=AccountRepository()
        var now=1_000_000L
        var accept=true
        var starts=0
        var callback: ((AccountProgressMerge.Result)->Unit)?=null
        val contexts=mutableListOf<AccountContext>()
        val updates=mutableListOf<Pair<AccountContext,AccountProgressMerge.Result>>()
        val sync: AutoProgressSync
        init {
            prefs().edit().clear().commit()
            repo.restoreSession(session("700"))
            sync=create()
        }
        fun prefs()=activity.getSharedPreferences("auto-progress-sync-v1",Context.MODE_PRIVATE)
        fun create()=AutoProgressSync(activity,repo,
            start={ ctx,done ->
                contexts+=ctx
                if(!accept) false else { starts++; callback=done; true }
            },
            updated={ ctx,result -> updates+=ctx to result },
            clock={ now })
        fun account(): AccountContext=repo.captureAccount()!!
        fun login(id: String) { repo.restoreSession(session(id)) }
        fun deliver(result: AccountProgressMerge.Result=AccountProgressMerge.Result()) {
            val done=callback ?: error("no captured callback")
            callback=null
            done(result)
            shadowOf(Looper.getMainLooper()).idle()
        }
        override fun close() { sync.close(); activity.finish() }
    }
    companion object {
        private const val INTERVAL=5*60_000L
        private fun session(id: String)=AuthSession(mapOf("sessionid" to "session-$id"),"token-$id",AuthUser("user-$id","fixture",accountId=id))
    }
    @Test fun disabledByDefaultAndOnlyStartsAfterConfiguredInterval() {
        Fixture().use { f ->
            assertFalse(f.sync.enabled)
            assertEquals(5,f.sync.intervalMinutes)
            assertEquals(listOf(1,5,10,15,30,60),AutoProgressSync.intervalOptions)
            assertFalse(f.sync.recordsLoaded())
            assertEquals(0,f.starts)
            f.sync.enabled=true
            assertTrue(f.sync.recordsLoaded())
            assertEquals(1,f.starts)
            f.deliver()
            assertEquals(1,f.updates.size)
            assertFalse(f.sync.recordsLoaded())
            f.now += INTERVAL-1
            assertFalse(f.sync.recordsLoaded())
            f.now += 1
            assertTrue(f.sync.recordsLoaded())
            assertEquals(2,f.starts)
        }
    }
    @Test fun oneFlightPerAccountUntilCompletionEvenWhenLoadsRepeat() {
        Fixture().use { f ->
            f.sync.enabled=true
            assertTrue(f.sync.recordsLoaded())
            assertFalse(f.sync.recordsLoaded())
            f.now += INTERVAL*2
            assertFalse(f.sync.recordsLoaded())
            f.deliver()
            assertTrue(f.sync.recordsLoaded())
            assertEquals(2,f.starts)
        }
    }
    @Test fun rejectedStartReleasesReservationWithoutConsumingTheInterval() {
        Fixture().use { f ->
            f.sync.enabled=true
            f.accept=false
            assertFalse(f.sync.recordsLoaded())
            assertEquals(0,f.starts)
            f.accept=true
            assertTrue(f.sync.recordsLoaded())
            assertEquals(1,f.starts)
        }
    }
    @Test fun acceptedFailureStillConsumesTheInterval() {
        Fixture().use { f ->
            f.sync.enabled=true
            assertTrue(f.sync.recordsLoaded())
            f.deliver(AccountProgressMerge.Result(error="offline"))
            assertEquals(1,f.updates.size)
            assertNotNull(f.updates.single().second.error)
            assertFalse(f.sync.recordsLoaded())
            f.now += INTERVAL-1
            assertFalse(f.sync.recordsLoaded())
            f.now += 1
            assertTrue(f.sync.recordsLoaded())
        }
    }
    @Test fun manualSuccessBypassesGateAndResetsClockWhileFailureDoesNot() {
        Fixture().use { f ->
            f.sync.enabled=true
            f.sync.intervalMinutes=10
            f.now=1_000_000
            f.sync.manualCompleted(f.account(),AccountProgressMerge.Result())
            assertFalse(f.sync.recordsLoaded())
            f.now += 10*60_000-1
            assertFalse(f.sync.recordsLoaded())
            f.now += 1
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            f.now += 60_000
            f.sync.manualCompleted(f.account(),AccountProgressMerge.Result(error="offline"))
            f.now=2_199_999
            assertFalse(f.sync.recordsLoaded())
            f.now=2_200_000
            assertTrue(f.sync.recordsLoaded())
        }
    }
    @Test fun intervalValidationAndSettingsPersistAcrossControllerRestart() {
        Fixture().use { f ->
            f.sync.intervalMinutes=7
            assertEquals(5,f.sync.intervalMinutes)
            f.sync.intervalMinutes=15
            assertEquals(15,f.sync.intervalMinutes)
            f.sync.enabled=true
            f.sync.close()
            val restarted=f.create()
            assertTrue(restarted.enabled)
            assertEquals(15,restarted.intervalMinutes)
            f.prefs().edit().putInt("intervalMinutes",9).commit()
            restarted.close()
            val normalized=f.create()
            assertEquals(5,normalized.intervalMinutes)
            normalized.close()
        }
    }
    @Test fun restartUsesPersistedOwnerTimestamp() {
        Fixture().use { f ->
            f.sync.enabled=true
            f.sync.intervalMinutes=15
            f.now=1_000_000
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            f.sync.close()
            val restarted=f.create()
            assertTrue(restarted.enabled)
            assertEquals(15,restarted.intervalMinutes)
            f.now=1_000_000+15*60_000-1
            assertFalse(restarted.recordsLoaded())
            f.now += 1
            assertTrue(restarted.recordsLoaded())
            restarted.close()
        }
    }
    @Test fun accountOwnerKeepsItsOwnClockAcrossSwitches() {
        Fixture().use { f ->
            f.sync.enabled=true
            f.now=1_000_000
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            f.login("800")
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            f.login("700")
            assertFalse(f.sync.recordsLoaded())
            f.now=1_000_000+INTERVAL
            assertTrue(f.sync.recordsLoaded())
        }
    }
    @Test fun clockRollbackAllowsOneAttemptThenRebases() {
        Fixture().use { f ->
            f.sync.enabled=true
            f.now=1_000_000
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            f.now=900_000
            assertTrue(f.sync.recordsLoaded())
            f.deliver()
            assertFalse(f.sync.recordsLoaded())
            f.now += INTERVAL-1
            assertFalse(f.sync.recordsLoaded())
            f.now += 1
            assertTrue(f.sync.recordsLoaded())
        }
    }
    @Test fun staleCallbackAfterAccountSwitchIsDroppedAndDoesNotBlockNewOwner() {
        Fixture().use { f ->
            f.sync.enabled=true
            assertTrue(f.sync.recordsLoaded())
            val stale=f.callback ?: error("no captured callback")
            f.login("800")
            assertTrue(f.sync.recordsLoaded())
            val current=f.repo.captureAccount()!!
            stale(AccountProgressMerge.Result(pending=1))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(f.updates.isEmpty())
            assertFalse(f.sync.recordsLoaded())
            f.deliver()
            assertEquals(1,f.updates.size)
            assertEquals(current.owner,f.updates.single().first.owner)
        }
    }
    @Test fun closeSuppressesCompletionAndFurtherLoads() {
        Fixture().use { f ->
            f.sync.enabled=true
            assertTrue(f.sync.recordsLoaded())
            f.sync.close()
            f.deliver()
            assertTrue(f.updates.isEmpty())
            assertFalse(f.sync.recordsLoaded())
        }
    }
    @Test fun synchronousCallbackCannotStartTwiceAndStillThrottles() {
        val activity=Robolectric.buildActivity(Activity::class.java).setup().get()
        activity.getSharedPreferences("auto-progress-sync-v1",Context.MODE_PRIVATE).edit().clear().commit()
        val repo=AccountRepository()
        repo.restoreSession(session("700"))
        var now=1_000_000L
        var starts=0
        var reentrant=false
        var updated=0
        var holder: AutoProgressSync?=null
        val sync=AutoProgressSync(activity,repo,
            start={ _,done -> starts++; reentrant=holder!!.recordsLoaded(); done(AccountProgressMerge.Result()); true },
            updated={ _, _ -> updated++ },
            clock={ now })
        holder=sync
        sync.enabled=true
        assertTrue(sync.recordsLoaded())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1,starts)
        assertFalse(reentrant)
        assertEquals(1,updated)
        assertFalse(sync.recordsLoaded())
        now += INTERVAL
        assertTrue(sync.recordsLoaded())
        sync.close(); activity.finish()
    }
}
