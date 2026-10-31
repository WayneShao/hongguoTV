// SPDX-License-Identifier: GPL-3.0-only
package com.hongguotv.nativeapp

/** Local-only fixture DNS; retains the production HTTP domain policy. adb reverse binds the server. */
internal fun fixtureClient() = okhttp3.OkHttpClient.Builder().dns(object : okhttp3.Dns {
    override fun lookup(name: String): List<java.net.InetAddress> =
        if (name == "fixture.byteicdn.com") listOf(java.net.InetAddress.getByAddress(byteArrayOf(127,0,0,1)))
        else okhttp3.Dns.SYSTEM.lookup(name)
}).build()
