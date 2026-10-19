package com.hongguotv.nativeapp

import android.content.Context
import com.hongguotv.core.VendorConstants
import java.security.SecureRandom

internal object DeviceIdentity {
    private const val PREFS = "hongguotv_device_identity"
    private const val IID = "iid"
    private const val DEVICE_ID = "device_id"
    private val random = SecureRandom()
    private var appContext: Context? = null
    private var committed: Pair<String, String>? = null
    private var pending: Pair<String, String>? = null

    fun configure(context: Context) {
        appContext = context.applicationContext
        val preferences = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val iid = preferences.getString(IID, null).takeIf(::valid) ?: next()
        val deviceId = preferences.getString(DEVICE_ID, null).takeIf(::valid) ?: next()
        preferences.edit().putString(IID, iid).putString(DEVICE_ID, deviceId).apply()
        committed = iid to deviceId
        VendorConstants.configureIdentity(iid, deviceId)
    }

    fun prepareRotation() {
        val iid = next()
        var deviceId = next()
        while (deviceId == iid) deviceId = next()
        pending = iid to deviceId
        VendorConstants.configureIdentity(iid, deviceId)
    }

    fun commitRotation() {
        val candidate = pending ?: return
        appContext?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()
            ?.putString(IID, candidate.first)?.putString(DEVICE_ID, candidate.second)?.apply()
        committed = candidate
        pending = null
    }

    fun rollbackRotation() {
        pending = null
        committed?.let { VendorConstants.configureIdentity(it.first, it.second) }
    }

    private fun valid(value: String?): Boolean = value?.matches(Regex("[1-9][0-9]{15}")) == true
    private fun next(): String {
        val value = 1_000_000_000_000_000L + random.nextLong(9_000_000_000_000_000L)
        return value.toString()
    }
}
