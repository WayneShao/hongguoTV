package com.hongguotv.nativeapp

import android.content.Context
import android.net.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.*

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[21])
class NetworkCompatTest {
    @Test fun lanUsesConnectedWifiOrEthernetAndNeverCellularOrDisconnectedLinks() {
        val manager=RuntimeEnvironment.getApplication().getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow=shadowOf(manager)
        for((transport,type) in listOf(NetworkCapabilities.TRANSPORT_WIFI to ConnectivityManager.TYPE_WIFI,
            NetworkCapabilities.TRANSPORT_ETHERNET to ConnectivityManager.TYPE_ETHERNET,
            NetworkCapabilities.TRANSPORT_CELLULAR to ConnectivityManager.TYPE_MOBILE)) {
            shadow.clearAllNetworks()
            val network=ShadowNetwork.newInstance(101)
            val info=ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,type,0,true,true)
            shadow.addNetwork(network,info);shadow.setActiveNetworkInfo(info)
            val caps=ShadowNetworkCapabilities.newInstance()
            shadowOf(caps).addTransportType(transport)
            shadow.setNetworkCapabilities(network,caps)
            val link=LinkAddress::class.java.getDeclaredConstructor(java.net.InetAddress::class.java,Int::class.javaPrimitiveType).apply{isAccessible=true}.newInstance(java.net.InetAddress.getByAddress(byteArrayOf(192.toByte(),168.toByte(),1,9)),24)
            val properties=LinkProperties()
            LinkProperties::class.java.getDeclaredMethod("addLinkAddress",LinkAddress::class.java).apply{isAccessible=true}.invoke(properties,link)
            shadow.setLinkProperties(network,properties)
            assertTrue(NetworkCompat.available(manager))
            assertEquals(if(transport==NetworkCapabilities.TRANSPORT_CELLULAR)null else "192.168.1.9",NetworkCompat.lanAddress(manager))
            shadowOf(info).setConnectionStatus(false)
            assertFalse(NetworkCompat.available(manager));assertNull(NetworkCompat.lanAddress(manager))
        }
    }
}
