package com.github.kr328.clash.tether.control

import android.net.ConnectivityManager
import android.net.LinkAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Binder
import com.github.kr328.clash.tether.forwarder.TetherPathBridge
import com.github.kr328.clash.tether.testnetwork.TestNetworkApi
import com.github.kr328.clash.tether.testnetwork.TunHandle
import com.github.kr328.clash.tether.testnetwork.buildLinkAddress
import java.net.InetAddress

class TetherSession(
    private val testNetworkApi: TestNetworkApi,
    private val connectivityManager: ConnectivityManager,
    private val context: android.content.Context,
) {
    private var tunHandle: TunHandle? = null
    private var network: Network? = null
    private var lifetimeToken: Binder? = null
    private var forwarderStarted = false
    private var keepAliveCallback: ConnectivityManager.NetworkCallback? = null

    val tunFd: Int? get() = tunHandle?.fileDescriptor?.fd
    val interfaceName: String? get() = tunHandle?.interfaceName
    val acquiredNetwork: Network? get() = network

    fun start(): Int {
        val token = Binder()
        lifetimeToken = token

        val addresses = listOf(
            buildLinkAddress(InetAddress.getByName("192.0.2.2"), 24),
            buildLinkAddress(InetAddress.getByName("2001:db8::2"), 64),
        )

        val dnsServers = listOf(
            InetAddress.getByName("2001:4860:4860::8888"),
            InetAddress.getByName("8.8.8.8"),
        )

        val handle = try {
            testNetworkApi.createTunInterface(addresses)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "createTunInterface failed", e)
            throw e
        }
        tunHandle = handle

        try {
            testNetworkApi.setupTestNetwork(handle.interfaceName, dnsServers, token)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "setupTestNetwork failed", e)
            throw e
        }

        val net = awaitAvailability(handle.interfaceName, AVAILABILITY_TIMEOUT_MS)
        network = net

        requestKeepAlive(net)

        val vpnHandle = findVpnNetworkHandle()

        try {
            TetherPathBridge.start(handle.fileDescriptor.fd, vpnHandle = vpnHandle)
            forwarderStarted = true
        } catch (e: Exception) {
            android.util.Log.e(TAG, "TetherPathBridge.start failed", e)
            throw e
        }

        return handle.fileDescriptor.fd
    }

    fun stop() {
        if (forwarderStarted) {
            TetherPathBridge.stop()
            forwarderStarted = false
        }

        keepAliveCallback?.let { cb ->
            runCatching { connectivityManager.unregisterNetworkCallback(cb) }
        }
        keepAliveCallback = null

        network?.let { net ->
            runCatching { testNetworkApi.teardownTestNetwork(net) }
        }
        network = null

        tunHandle?.let { handle ->
            runCatching { handle.fileDescriptor.close() }
        }
        tunHandle = null
        lifetimeToken = null
    }

    /**
     * Poll ConnectivityManager.allNetworks to find the network registered on our TUN interface.
     * setupTestNetwork may return null on MIUI/HyperOS, but the network is still registered
     * internally and discoverable via allNetworks.
     */
    private fun awaitAvailability(interfaceName: String, timeoutMs: Long): Network {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            findNetworkOn(interfaceName)?.let { return it }
            Thread.sleep(POLL_INTERVAL_MS)
        }
        throw IllegalStateException("Test network '$interfaceName' did not become available within ${timeoutMs}ms")
    }

    private fun findNetworkOn(interfaceName: String): Network? {
        return connectivityManager.allNetworks.firstOrNull { candidate ->
            connectivityManager.getLinkProperties(candidate)?.interfaceName == interfaceName
        }
    }

    /**
     * Register an empty NetworkCallback with TRANSPORT_TEST to prevent the framework
     * from linger-removing the test network.
     */
    private fun requestKeepAlive(network: Network) {
        try {
            val transportTest = resolveTransportTest()

            val request = NetworkRequest.Builder()
                .clearCapabilities()
                .addTransportType(transportTest)
                .build()

            val cb = object : ConnectivityManager.NetworkCallback() {}
            keepAliveCallback = cb
            connectivityManager.requestNetwork(request, cb)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to register keep-alive callback: ${e.message}")
        }
    }

    /**
     * Resolve TRANSPORT_TEST constant via reflection (hidden API).
     * Falls back to 7 (AOSP value) if not available.
     */
    private fun resolveTransportTest(): Int = runCatching {
        NetworkCapabilities::class.java.getField("TRANSPORT_TEST").getInt(null)
    }.getOrDefault(7)

    /**
     * Find the VPN network and return its handle.
     * The handle is used by gVisor to pin sockets to the VPN network.
     */
    private fun findVpnNetworkHandle(): Long {
        return try {
            val transportVpn = resolveTransportVpn()
            val vpnNetwork = connectivityManager.allNetworks.firstOrNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps?.hasTransport(transportVpn) == true
            }
            if (vpnNetwork != null) {
                getNetworkHandle(vpnNetwork)
            } else {
                android.util.Log.w(TAG, "No VPN network found")
                0L
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to find VPN network handle: ${e.message}")
            0L
        }
    }

    /**
     * Resolve TRANSPORT_VPN constant via reflection (hidden API on some versions).
     */
    private fun resolveTransportVpn(): Int = runCatching {
        NetworkCapabilities::class.java.getField("TRANSPORT_VPN").getInt(null)
    }.getOrDefault(NetworkCapabilities.TRANSPORT_VPN)

    /**
     * Get the network handle using reflection (hidden API).
     */
    private fun getNetworkHandle(network: Network): Long {
        return try {
            val method = connectivityManager.javaClass.getMethod("getNetworkHandle", Network::class.java)
            method.invoke(connectivityManager, network) as Long
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getNetworkHandle failed: ${e.message}")
            0L
        }
    }

    companion object {
        private const val TAG = "TetherSession"
        private const val AVAILABILITY_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 200L
    }
}
