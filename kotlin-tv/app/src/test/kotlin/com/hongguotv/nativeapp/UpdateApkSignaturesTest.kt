package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class UpdateApkSignaturesTest {
    @Test fun requestsBothModernAndLegacyCertificateFields() {
        val flags = UpdateApkSignatures.flags()
        assertNotEquals(0, flags and PackageManager.GET_SIGNATURES)
        assertNotEquals(0, flags and PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Test @Config(sdk = [26])
    fun olderFrameworkOnlyRequestsLegacyCertificates() {
        val flags = UpdateApkSignatures.flags()
        assertNotEquals(0, flags and PackageManager.GET_SIGNATURES)
        assertEquals(0, flags and PackageManager.GET_SIGNING_CERTIFICATES)
    }

    @Test fun emptyModernFieldFallsBackToLegacySignature() {
        val info = PackageInfo().apply { signatures = arrayOf(Signature(byteArrayOf(1, 2, 3))) }
        val signers = UpdateApkSignatures.signers(info)
        assertEquals(1, signers.size)
        assertEquals(info.signatures!!.single().toCharsString(), signers.single())
    }
}
