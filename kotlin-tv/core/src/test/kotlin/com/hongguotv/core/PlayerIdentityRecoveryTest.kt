package com.hongguotv.core

import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class PlayerIdentityRecoveryTest {
    @Test fun explicitRejectionCanRecoverAfterFailedRegistrationCooldown() {
        val store=Store(old.toJson());var registrations=0;var now=0L
        val source=PlayerDeviceSource(OkHttpClient(),store={store},recoverEmpty=true,monotonicMillis={now},
            register={if(++registrations==1) throw IOException("temporary registration failure");fresh})
        fun recover()=source.recover {if(it==old) throw DeviceRejectedException("rejected");"ok"}
        assertThrows(IOException::class.java){recover()}
        now=300_000
        assertEquals("ok",recover());assertEquals(2,registrations);assertEquals(1,store.writes)
    }
    @Test fun twoEmptyRoundsShareOneCandidateAcrossRepositoriesUntilSuccess() {
        val store=Store(old.toJson()); var registrations=0; var now=0L
        fun source()=PlayerDeviceSource(OkHttpClient(),store={store},recoverEmpty=true,
            register={registrations++;fresh},monotonicMillis={now})
        fun fail() { assertThrows(EmptyPlaybackException::class.java) { source().recover<String> {throw EmptyPlaybackException()} } }
        fail(); assertEquals(0,registrations)
        fail(); assertEquals(1,registrations)
        now=600_000; repeat(3){fail()}; assertEquals(1,registrations)
        source().recover { "valid" }
        fail(); assertEquals(1,registrations)
        fail(); assertEquals(2,registrations)
        assertEquals(0,store.writes)
    }
    @Test fun nonEmptyFailureBreaksEmptySequenceAndCancellationNeverRegisters() {
        val store=Store(old.toJson());var registrations=0
        val source=PlayerDeviceSource(OkHttpClient(),store={store},recoverEmpty=true,register={registrations++;fresh})
        fun empty() {assertThrows(EmptyPlaybackException::class.java){source.recover<String>{throw EmptyPlaybackException()}}}
        empty()
        assertThrows(IOException::class.java){source.recover<String>{throw IOException("network")}}
        empty();assertEquals(0,registrations)
        val scope=RequestScope();scope.cancel()
        assertThrows(IOException::class.java){scope.run {source.recover<String>{throw EmptyPlaybackException()}}}
        assertEquals(0,registrations)
    }
    @Test fun concurrentEmptyFailuresHaveOnlyOneCandidateEvenAfterCooldown() {
        val store=Store(old.toJson());val registrations=java.util.concurrent.atomic.AtomicInteger()
        val arrived=java.util.concurrent.CyclicBarrier(2); val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val jobs=(1..2).map { pool.submit<Boolean> {
                val source=PlayerDeviceSource(OkHttpClient(),store={store},recoverEmpty=true,register={registrations.incrementAndGet();fresh})
                try {source.recover<String> {if(it==old) arrived.await(2,java.util.concurrent.TimeUnit.SECONDS);throw EmptyPlaybackException()};false}
                catch(_: EmptyPlaybackException){true}
            } }
            jobs.forEach {assertTrue(it.get(3,java.util.concurrent.TimeUnit.SECONDS))}
            assertEquals(1,registrations.get());assertEquals(0,store.writes)
        } finally {pool.shutdownNow()}
    }
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
    @Test fun repeatedFailedCandidateIsSuppressedDuringCooldown() {
        val store = Store(old.toJson())
        var registrations = 0
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { registrations++; fresh })
        repeat(2) { assertThrows(IOException::class.java) { source.recover<String> {
            throw DeviceRejectedException("rejected")
        } } }
        assertEquals(1, registrations)
        assertEquals(old.toJson(), store.value)
    }
    @Test fun cooldownUsesInjectedMonotonicClockAndExpiresAfterFiveMinutes() {
        val store=Store(old.toJson()); var now=0L; var registrations=0
        val source=PlayerDeviceSource(OkHttpClient(),store={store},register={registrations++;fresh},monotonicMillis={now})
        fun fail() { assertThrows(IOException::class.java) { source.recover<String> { throw DeviceRejectedException("rejected") } } }
        fail(); now=299_999; fail(); assertEquals(1,registrations)
        now=300_000; fail(); assertEquals(2,registrations)
    }

    @Test fun concurrentFailuresAcrossSourcesShareOneFailedCandidate() {
        val store=Store(old.toJson()); val registrations=java.util.concurrent.atomic.AtomicInteger()
        val arrived=java.util.concurrent.CountDownLatch(2)
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val results=(1..2).map {
                val source=PlayerDeviceSource(OkHttpClient(),store={store},register={registrations.incrementAndGet();fresh})
                pool.submit<Boolean> {
                    try { source.recover<String> { device ->
                        if(device==old) { arrived.countDown(); assertTrue(arrived.await(2,java.util.concurrent.TimeUnit.SECONDS)) }
                        throw DeviceRejectedException("rejected")
                    }; false } catch(_: DeviceRejectedException) { true }
                }
            }
            results.forEach { assertTrue(it.get(3,java.util.concurrent.TimeUnit.SECONDS)) }
            assertEquals(1,registrations.get()); assertEquals(0,store.writes)
        } finally { pool.shutdownNow() }
    }

    @Test fun halfRoundEmptiesAccumulateTowardCandidateRotation() {
        val store=Store(old.toJson()); var registrations=0
        val source=PlayerDeviceSource(OkHttpClient(),store={store},recoverEmpty=true,register={registrations++;fresh})
        fun fail(completeRound: Boolean) { assertThrows(EmptyPlaybackException::class.java) { source.recover<String> { throw EmptyPlaybackException(completeRound) } } }
        fail(true)
        fail(false); assertEquals(0,registrations)
        fail(false); assertEquals(1,registrations)
        assertEquals(0,store.writes)
    }
    @Test fun emptyReasonsSurfaceThroughMessageForFailureLogs() {
        assertTrue(EmptyPlaybackException().message.orEmpty().contains("[raw_blank]"))
        assertTrue(EmptyPlaybackException(true, EmptyPlaybackReason.TAB_DATA_EMPTY).message.orEmpty().contains("[tab_data_empty]"))
        assertTrue(EmptyPlaybackException(false, EmptyPlaybackReason.RAW_BLANK, "routes=1/1 raw_blank=1").message.orEmpty().contains("[routes=1/1 raw_blank=1]"))
    }
    @Test fun searchStyleRecoveryDoesNotTreatEmptyPlaybackAsRejection() {
        val source=PlayerDeviceSource(OkHttpClient(),store={Store(old.toJson())},register={error("unexpected registration")},cooldownMillis=0)
        assertThrows(EmptyPlaybackException::class.java) { source.recover<String> { throw EmptyPlaybackException() } }
    }
    @Test fun cancelledRecoveryWaiterExitsBeforeCandidateTransactionFinishes() {
        val store=Store(old.toJson())
        val candidateEntered=java.util.concurrent.CountDownLatch(1)
        val releaseCandidate=java.util.concurrent.CountDownLatch(1)
        val waiterRejected=java.util.concurrent.CountDownLatch(1)
        val scope=RequestScope()
        val source=PlayerDeviceSource(OkHttpClient(),store={store},register={fresh})
        val pool=java.util.concurrent.Executors.newFixedThreadPool(2)
        try {
            val owner=pool.submit<String> { source.recover { device ->
                if(device==old) throw DeviceRejectedException("old")
                candidateEntered.countDown(); releaseCandidate.await(); "candidate"
            } }
            assertTrue(candidateEntered.await(2,java.util.concurrent.TimeUnit.SECONDS))
            val waiter=pool.submit<Boolean> {
                try { scope.run { source.recover<String> { waiterRejected.countDown(); throw DeviceRejectedException("old") } }; false }
                catch(_: java.io.InterruptedIOException) { true }
            }
            assertTrue(waiterRejected.await(2,java.util.concurrent.TimeUnit.SECONDS)); scope.cancel()
            assertTrue(waiter.get(1,java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(owner.isDone)
            releaseCandidate.countDown(); assertEquals("candidate",owner.get(2,java.util.concurrent.TimeUnit.SECONDS))
        } finally { releaseCandidate.countDown();pool.shutdownNow() }
    }

    @Test fun explicitRotationPersistsOnlyAfterCandidatePlaybackSucceeds() {
        val store = Store(old.toJson())
        var registrations = 0
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { registrations++; fresh })
        val result = source.recover { device ->
            if(device==old) throw DeviceRejectedException("rejected")
            assertEquals(fresh, device)
            "playable"
        }
        assertEquals("playable", result)
        assertEquals(1, registrations)
        assertEquals(1, store.writes)
        assertEquals(fresh, PlayerDevice.fromJson(store.value!!))
    }

    @Test fun failedExplicitRotationKeepsPreviousIdentity() {
        val store = Store(old.toJson())
        val source = PlayerDeviceSource(OkHttpClient(), store = { store }, register = { fresh })
        assertThrows(IOException::class.java) {
            source.recover<String> { if(it==old) throw DeviceRejectedException("rejected"); throw IOException("candidate empty") }
        }
        assertEquals(0, store.writes)
        assertEquals(old.toJson(), store.value)
    }
}
