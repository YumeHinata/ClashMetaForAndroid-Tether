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

        // setupTestNetwork() has different overloads across AOSP releases and OEM
        // builds, e.g. (LinkProperties, boolean, IBinder), (LinkProperties, IBinder),
        // (String, IBinder). Enumerate the declared methods and invoke the first one
        // whose parameter list we can fully satisfy. This is robust on Android 13-16
        // and on MIUI/HyperOS, where the return value may be null even on success
        // (the caller polls ConnectivityManager.allNetworks to discover the network).
        val candidates = manager::class.java.declaredMethods
            .filter { it.name == "setupTestNetwork" }
            .sortedByDescending { it.parameterCount }

        if (candidates.isEmpty()) {
            throw NoSuchMethodException("setupTestNetwork not found on ${manager::class.java}")
        }

        var lastError: Exception? = null
        for (method in candidates) {
            val args = buildArgsFor(method.parameterTypes, lp, interfaceName, lifetimeToken)
                ?: continue
            val signature = method.parameterTypes.joinToString(prefix = "(", postfix = ")") { it.simpleName }
            try {
                method.isAccessible = true
                val returnValue = method.invoke(manager, *args)
                android.util.Log.i("TestNetworkApi", "setupTestNetwork$signature returned: $returnValue")
                return
            } catch (e: Exception) {
                android.util.Log.w("TestNetworkApi", "setupTestNetwork$signature failed: ${e.message}")
                lastError = e
            }
        }
        throw lastError ?: IllegalStateException("No invocable setupTestNetwork overload")
    }

    /**
     * Build an argument array for a setupTestNetwork overload when every parameter type
     * can be satisfied from the values we hold; otherwise return null so the caller skips
     * that overload.
     */
    private fun buildArgsFor(
        paramTypes: Array<Class<*>>,
        lp: LinkProperties,
        interfaceName: String,
        lifetimeToken: IBinder,
    ): Array<Any?>? {
        val args = arrayOfNulls<Any>(paramTypes.size)
        for (i in paramTypes.indices) {
            val t = paramTypes[i]
            args[i] = when {
                t == LinkProperties::class.java -> lp
                t == IBinder::class.java -> lifetimeToken
                t == String::class.java -> interfaceName
                t == Boolean::class.javaPrimitiveType -> true
                t == android.net.NetworkCapabilities::class.java -> buildTestNetworkCapabilities()
                    ?: return null
                else -> return null
            }
        }
        return args
    }

    /**
     * Without INTERNET+TRUSTED the tethering upstream filter never considers the test
     * network a candidate, so preferTestNetworks alone cannot select it as upstream.
     */
    private fun buildTestNetworkCapabilities(): android.net.NetworkCapabilities? {
        return try {
            val nc = android.net.NetworkCapabilities()
            val clazz = android.net.NetworkCapabilities::class.java
            val addCapability = clazz.getMethod("addCapability", Int::class.javaPrimitiveType)
            val capNames = listOf(
                "NET_CAPABILITY_INTERNET",
                "NET_CAPABILITY_NOT_RESTRICTED",
                "NET_CAPABILITY_TRUSTED",
                "NET_CAPABILITY_NOT_METERED",
                "NET_CAPABILITY_NOT_ROAMING",
                "NET_CAPABILITY_NOT_CONGESTED",
                "NET_CAPABILITY_NOT_SUSPENDED",
            )
            for (name in capNames) {
                val value = clazz.getField(name).getInt(null)
                addCapability.invoke(nc, value)
            }
            try {
                clazz.getMethod("setLinkUpstreamBandwidthKbps", Int::class.javaPrimitiveType)
                    .invoke(nc, 1_000_000)
                clazz.getMethod("setLinkDownstreamBandwidthKbps", Int::class.javaPrimitiveType)
                    .invoke(nc, 1_000_000)
            } catch (_: Exception) {
                // bandwidth setters are cosmetic for upstream selection
            }
            nc
        } catch (e: Exception) {
            android.util.Log.w("TestNetworkApi", "buildTestNetworkCapabilities failed: ${e.message}")
            null
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
