package com.hongguotv.nativeapp

import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address

internal object NetworkCompat {
    @Suppress("DEPRECATION")
    fun available(manager: ConnectivityManager): Boolean = if(Build.VERSION.SDK_INT>=23) {
        manager.getNetworkCapabilities(manager.activeNetwork)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)==true
    } else manager.activeNetworkInfo?.isConnected==true

    @Suppress("DEPRECATION")
    fun lanAddress(manager: ConnectivityManager): String? {
        val networks=if(Build.VERSION.SDK_INT>=23) listOfNotNull(manager.activeNetwork) else manager.allNetworks.toList()
        for(network in networks) {
            if(Build.VERSION.SDK_INT<23 && manager.getNetworkInfo(network)?.isConnected!=true) continue
            val capabilities=manager.getNetworkCapabilities(network) ?: continue
            if(!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue
            val address=manager.getLinkProperties(network)?.linkAddresses?.map {it.address}
                ?.firstOrNull {it is Inet4Address && it.isSiteLocalAddress}?.hostAddress
            if(address!=null) return address
        }
        return null
    }
}
