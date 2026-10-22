package com.github.kr328.clash.tether.testnetwork

import android.net.LinkAddress
import android.net.LinkProperties
import android.net.Network
import android.os.IBinder
import android.os.ParcelFileDescriptor
import java.lang.reflect.Method
import java.net.InetAddress

data class TunHandle(
    val fileDescriptor: ParcelFileDescriptor,
    val interfaceName: String,
)

class TestNetworkApi(private val manager: Any) {

    fun createTunInterface(addresses: List<LinkAddress>): TunHandle {
        val method = findCreateTunMethod()
        val result = method.invoke(manager, addresses.toTypedArray())
            ?: throw IllegalStateException("createTunInterface returned null")

        val fd = result::class.java.getMethod("getFileDescriptor").invoke(result) as ParcelFileDescriptor
        val name = result::class.java.getMethod("getInterfaceName").invoke(result) as String

        return TunHandle(fd, name)
    }

    fun setupTestNetwork(
        interfaceName: String,
        dnsServers: List<InetAddress>,
        lifetimeToken: IBinder,
    ) {
        val lp = LinkProperties().apply {
            this.interfaceName = interfaceName
            try {
                val method = LinkProperties::class.java.getMethod("addDnsServer", InetAddress::class.java)
                dnsServers.forEach { server -> method.invoke(this, server) }
            } catch (e: NoSuchMethodException) {
                try {
                    val method = LinkProperties::class.java.getMethod("addDnsServer", String::class.java)
                    dnsServers.forEach { server -> server.hostAddress?.let { method.invoke(this, it) } }
                } catch (e2: NoSuchMethodException) {
                    android.util.Log.w("TestNetworkApi", "No addDnsServer method available, skipping DNS configuration")
                }
            }
        }

        // Try standard method: setupTestNetwork(LinkProperties, boolean, IBinder)
        try {
            val method = manager::class.java.getDeclaredMethod(
                "setupTestNetwork",
                LinkProperties::class.java,
                Boolean::class.javaPrimitiveType,
                IBinder::class.java,
            )
            method.isAccessible = true
            method.invoke(manager, lp, true, lifetimeToken)
            // Return value may be null on MIUI/HyperOS but network is still registered.
            // Caller must poll ConnectivityManager.allNetworks to discover the network.
            return
        } catch (e: NoSuchMethodException) {
            // Try fallback below
        } catch (e: Exception) {
            android.util.Log.e("TestNetworkApi", "setupTestNetwork(LinkProperties) failed: ${e.message}", e)
        }

        // Fallback: setupTestNetwork(String, IBinder)
        try {
            val fallback = manager::class.java.getDeclaredMethod(
                "setupTestNetwork",
                String::class.java,
                IBinder::class.java,
            )
            fallback.isAccessible = true
            fallback.invoke(manager, interfaceName, lifetimeToken)
        } catch (e: NoSuchMethodException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.e("TestNetworkApi", "setupTestNetwork(String) failed: ${e.message}", e)
            throw e
        }
    }

    fun teardownTestNetwork(network: Network) {
        val method = manager::class.java.getDeclaredMethod(
            "teardownTestNetwork",
            Network::class.java,
        )
        method.isAccessible = true
        method.invoke(manager, network)
    }

    private fun findCreateTunMethod(): Method {
        val clazz = manager::class.java

        return try {
            clazz.getDeclaredMethod("createTunInterface", Array<LinkAddress>::class.java)
        } catch (_: NoSuchMethodException) {
            clazz.getDeclaredMethod("createTunInterface", Collection::class.java)
        }.apply { isAccessible = true }
    }

    companion object {
        fun fromContext(context: android.content.Context): TestNetworkApi? {
            val manager = context.getSystemService("test_network") ?: return null
            return TestNetworkApi(manager)
        }
    }
}
