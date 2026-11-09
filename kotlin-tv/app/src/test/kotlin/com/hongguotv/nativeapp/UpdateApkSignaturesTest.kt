package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private fun sig(vararg value: Int) = Signature(value.map { it.toByte() }.toByteArray())

    @Test fun explicitEmptyModernArrayFallsBackToLegacy() {
        val legacy = arrayOf(sig(1))
        assertEquals(legacy.single().toCharsString(), UpdateApkSignatures.select(emptyArray(), legacy).single())
    }

    @Test fun modernMismatchRejectsEvenWhenLegacyMatches() {
        // 新版字段非空时绝不能混入旧字段历史，否则会掩盖真正的签名不一致。
        val installed = UpdateApkSignatures.select(arrayOf(sig(2)), arrayOf(sig(1)))
        val candidate = UpdateApkSignatures.select(arrayOf(sig(1)), arrayOf(sig(1)))
        assertFalse(UpdateApkSignatures.matches(installed, candidate))
    }

    @Test fun archiveAndCurrentFallbackAreIndependent() {
        // 当前安装包只写旧字段、归档包只写新版字段，各自独立回退后仍应匹配。
        val currentLegacy = UpdateApkSignatures.select(null, arrayOf(sig(7)))
        val archiveModern = UpdateApkSignatures.select(arrayOf(sig(7)), null)
        assertTrue(UpdateApkSignatures.matches(currentLegacy, archiveModern))
        // 反向组合：当前新版字段优先，归档空新版数组回退到旧字段。
        val currentModern = UpdateApkSignatures.select(arrayOf(sig(7)), arrayOf(sig(8)))
        val archiveLegacy = UpdateApkSignatures.select(emptyArray(), arrayOf(sig(7)))
        assertTrue(UpdateApkSignatures.matches(currentModern, archiveLegacy))
    }

    @Test fun bothEmptyFieldsRejectInstall() {
        val installed = UpdateApkSignatures.select(null, null)
        val candidate = UpdateApkSignatures.select(emptyArray(), null)
        assertTrue(installed.isEmpty())
        assertTrue(candidate.isEmpty())
        assertFalse(UpdateApkSignatures.matches(installed, candidate))
        assertFalse(UpdateApkSignatures.matches(emptySet(), emptySet()))
    }
}
