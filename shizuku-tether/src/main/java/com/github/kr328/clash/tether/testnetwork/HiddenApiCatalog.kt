package com.github.kr328.clash.tether.testnetwork

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.lang.reflect.Method
import java.net.InetAddress

data class HiddenApiPath(
    val id: String,
    val className: String,
    val memberName: String,
    val since: Int,
    val notes: String,
)

object HiddenApiCatalog {
    val paths: List<HiddenApiPath> = listOf(
        HiddenApiPath(
            id = "TestNetworkManager.class",
            className = "android.net.TestNetworkManager",
            memberName = "<class>",
            since = 29,
            notes = "Obtained via getSystemService(\"test_network\"). @TestApi.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.createTunInterface",
            className = "android.net.TestNetworkManager",
            memberName = "createTunInterface",
            since = 29,
            notes = "Two overloads: LinkAddress[] and Collection<LinkAddress>.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.setupTestNetwork",
            className = "android.net.TestNetworkManager",
            memberName = "setupTestNetwork",
            since = 29,
            notes = "(LinkProperties, boolean, IBinder) overload preferred. Requires MANAGE_TEST_NETWORKS.",
        ),
        HiddenApiPath(
            id = "TestNetworkManager.teardownTestNetwork",
            className = "android.net.TestNetworkManager",
            memberName = "teardownTestNetwork",
            since = 29,
            notes = "Takes the Network handle.",
        ),
        HiddenApiPath(
            id = "TestNetworkInterface.getFileDescriptor",
            className = "android.net.TestNetworkInterface",
            memberName = "getFileDescriptor",
            since = 29,
            notes = "Returns ParcelFileDescriptor owning the TUN.",
        ),
        HiddenApiPath(
            id = "TestNetworkInterface.getInterfaceName",
            className = "android.net.TestNetworkInterface",
            memberName = "getInterfaceName",
            since = 29,
            notes = "Yields the testtunN name.",
        ),
        HiddenApiPath(
            id = "TetheringManager.setPreferTestNetworks",
            className = "android.net.TetheringManager",
            memberName = "setPreferTestNetworks",
            since = 32,
            notes = "THE load-bearing call. Gated on TETHER_PRIVILEGED.",
        ),
        HiddenApiPath(
            id = "TetheringManager.startTethering",
            className = "android.net.TetheringManager",
            memberName = "startTethering",
            since = 30,
            notes = "(TetheringRequest, Executor, StartTetheringCallback). Needs TETHER_PRIVILEGED.",
        ),
        HiddenApiPath(
            id = "TetheringManager.stopTethering",
            className = "android.net.TetheringManager",
            memberName = "stopTethering",
            since = 30,
            notes = "(int type). 0 = TETHERING_WIFI.",
        ),
        HiddenApiPath(
            id = "LinkAddress.<init>",
            className = "android.net.LinkAddress",
            memberName = "<init>",
            since = 29,
            notes = "(InetAddress, int) constructor is package-private.",
        ),
        HiddenApiPath(
            id = "NetworkCapabilities.TRANSPORT_TEST",
            className = "android.net.NetworkCapabilities",
            memberName = "TRANSPORT_TEST",
            since = 29,
            notes = "@hide constant, value 7 on AOSP.",
        ),
    )
}

fun buildLinkAddress(address: InetAddress, prefixLength: Int): LinkAddress {
    val constructor = LinkAddress::class.java
        .getDeclaredConstructor(InetAddress::class.java, Int::class.javaPrimitiveType)
    constructor.isAccessible = true
    return constructor.newInstance(address, prefixLength)
}

fun resolveTransportTest(): Int = runCatching {
    NetworkCapabilities::class.java.getField("TRANSPORT_TEST").getInt(null)
}.getOrDefault(TRANSPORT_TEST_AOSP_FALLBACK)

const val TRANSPORT_TEST_AOSP_FALLBACK = 7
