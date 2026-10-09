package com.hongguotv.core

import org.junit.Assert.assertEquals
import org.junit.Test

class RayNeoDefaultsTest {
    @Test fun freshInstallPrefers480() { assertEquals(480,LibraryDisk.decode(emptyMap<String,Any>()).settings.quality) }
    @Test fun storedQualitySurvivesUpgrade() {
        for (q in listOf(480,720,1080)) assertEquals(q,LibraryDisk.decode(mapOf("quality" to q)).settings.quality)
    }
    @Test fun invalidQualityFallsBackTo480() { assertEquals(480,PlaybackQuality.normalize(123)) }
    @Test fun missingBackupSettingsUse480() { assertEquals(480,BackupSettings().quality) }
}
