package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlayerIdentityRecoveryTest {
    private val old = PlayerDevice("123", "456", "old", "", "")
    private val fresh = PlayerDevice("789", "987", "new", "", "")
    private class Store(var value: String?) : PlayerDeviceStore {
        var writes = 0
        override fun load() = value
        override fun save(value: String?) { this.value = value; writes++ }
    }

    @Test fun healthyStoredIdentityNeverRegistersOrRewrites() {
        val store = Store(old.toJson())
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { error("unexpected registration") })
        repeat(2) { assertEquals(old, source.recover { it }) }
        assertEquals(0, store.writes)
    }

    @Test fun rejectionProbesOnceAndPersistsOnlyAfterSuccessAcrossInstances() {
        val store = Store(old.toJson())
        var registrations = 0
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { registrations++; fresh })
        val result = source.recover {
            assertEquals(old.toJson(), store.value)
            if (it == old) throw DeviceRejectedException("rejected")
            it
        }
        assertEquals(fresh, result)
        assertEquals(1, registrations)
        assertEquals(1, store.writes)
        val restarted = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { error("unexpected registration") })
        assertEquals(fresh, restarted.recover { it })
    }

    @Test fun failedCandidateKeepsOldIdentityOnDiskAndNextRequest() {
        val store = Store(old.toJson())
        var registrations = 0
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { registrations++; fresh })
        assertThrows(IOException::class.java) { source.recover {
            if (it == old) throw DeviceRejectedException("rejected")
            throw IOException("candidate failed")
        } }
        assertEquals(1, registrations)
        assertEquals(0, store.writes)
        assertEquals(old, source.recover { it })
    }

    @Test fun networkFailureDoesNotRegister() {
        val store = Store(old.toJson())
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { error("unexpected registration") })
        assertThrows(IOException::class.java) { source.recover<String> { throw IOException("timeout") } }
        assertEquals(0, store.writes)
    }

    @Test fun migrationTriesExistingLegacyIdentityBeforeRegistration() {
        val store = Store(null)
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { error("unexpected registration") })
        assertEquals(VendorConstants.device.getValue("device_id"), source.recover { it.deviceId })
        assertEquals(0, store.writes)
    }
}
