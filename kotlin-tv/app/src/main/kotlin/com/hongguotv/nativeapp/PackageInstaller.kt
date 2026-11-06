package com.hongguotv.nativeapp

import android.content.ActivityNotFoundException
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Build

internal object PackageInstaller {
    @Suppress("DEPRECATION")
    fun launch(uri: Uri,start: (Intent)->Unit) {
        fun intent(action: String)=Intent(action).setDataAndType(uri,"application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).apply { clipData=ClipData.newRawUri("更新安装包",uri) }
        if(Build.VERSION.SDK_INT<26) {
            try { start(intent(Intent.ACTION_INSTALL_PACKAGE));return }
            catch(_: ActivityNotFoundException) { }
            catch(_: SecurityException) { }
        }
        start(intent(Intent.ACTION_VIEW))
    }
}
