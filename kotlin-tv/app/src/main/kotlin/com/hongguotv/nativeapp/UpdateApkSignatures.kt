// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build

/** 同时请求新旧签名证书字段，使新系统解析归档 APK 时也能完成比对。 */
@Suppress("DEPRECATION")
internal object UpdateApkSignatures {
    fun flags(): Int = PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        // 有些定制系统只填写旧版字段；仅当新版字段为空时才回退到旧字段。
        // 新版字段非空时绝不能混入旧版签名历史，否则会掩盖签名不一致。
        val certificates = modern?.takeIf { it.isNotEmpty() } ?: info.signatures
        return certificates?.map { it.toCharsString() }?.toSet().orEmpty()
    }
}
