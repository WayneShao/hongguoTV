// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build

/** 同时请求新旧签名证书字段，使新系统解析归档 APK 时也能完成比对。 */
@Suppress("DEPRECATION")
internal object UpdateApkSignatures {
    fun flags(): Int = PackageManager.GET_SIGNATURES or
        (if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else 0)

    /**
     * 新版字段非空时只信新版；仅当其为 null 或空数组时才回退旧字段。
     * 新版字段存在但为空数组同样视为“未填写”，以便兼容只写旧字段的定制系统。
     */
    fun select(modern: Array<Signature>?, legacy: Array<Signature>?): Set<String> =
        (modern?.takeIf { it.isNotEmpty() } ?: legacy)?.map { it.toCharsString() }?.toSet().orEmpty()

    fun signers(info: PackageInfo): Set<String> {
        val modern = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else null
        return select(modern, info.signatures)
    }

    /** 双方都必须有证书且完全一致；任一方为空都视为不可验证，绝不能因“都为空”而通过。 */
    fun matches(installed: Set<String>, candidate: Set<String>): Boolean =
        installed.isNotEmpty() && installed == candidate
}
