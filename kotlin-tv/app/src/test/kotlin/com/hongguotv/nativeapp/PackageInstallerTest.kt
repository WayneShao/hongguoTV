package com.hongguotv.nativeapp

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.os.Build
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[21,23,25,26],manifest=Config.NONE)
class PackageInstallerTest {
    private val uri=Uri.parse("content://test.updates/update.apk")
    @Test fun launchUsesVersionAppropriateActionAndReadGrants() {
        val launches=mutableListOf<Intent>()
        PackageInstaller.launch(uri) { launches+=it }
        assertEquals(1,launches.size)
        val intent=launches.single()
        assertEquals(if(Build.VERSION.SDK_INT<26) Intent.ACTION_INSTALL_PACKAGE else Intent.ACTION_VIEW,intent.action)
        assertEquals(uri,intent.data);assertEquals(uri,intent.clipData!!.getItemAt(0).uri)
        assertEquals("application/vnd.android.package-archive",intent.type)
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
    }
    @Test fun failedLegacyLaunchFallsBackButSuccessfulLaunchNeverRepeats() {
        val launches=mutableListOf<String?>()
        if(Build.VERSION.SDK_INT>=26) {
            assertThrows(ActivityNotFoundException::class.java) { PackageInstaller.launch(uri) { launches+=it.action;throw ActivityNotFoundException() } }
            assertEquals(1,launches.size)
        } else {
            PackageInstaller.launch(uri) {
                assertEquals(uri,it.data);assertEquals(uri,it.clipData!!.getItemAt(0).uri)
                assertTrue(it.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
                launches+=it.action;if(launches.size==1) throw ActivityNotFoundException()
            }
            assertEquals(listOf(Intent.ACTION_INSTALL_PACKAGE,Intent.ACTION_VIEW),launches)
        }
    }
    @Test fun bothLaunchFailuresPropagateForActionableUi() {
        assertThrows(SecurityException::class.java) { PackageInstaller.launch(uri) { throw SecurityException() } }
    }
}
