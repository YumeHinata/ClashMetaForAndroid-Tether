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
    private var boundToVpn = false

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

        // Step 1: Create TUN interface
        val handle = try {
            testNetworkApi.createTunInterface(addresses)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "createTunInterface failed", e)
            throw e
        }
        tunHandle = handle
        android.util.Log.i(TAG, "TUN interface created: ${handle.interfaceName}, fd: ${handle.fileDescriptor.fd}")

        // Step 2: Register as test network
        try {
            testNetworkApi.setupTestNetwork(handle.interfaceName, dnsServers, token)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "setupTestNetwork failed", e)
            throw e
        }

        // Step 3: Poll allNetworks to discover the registered network
        val net = awaitAvailability(handle.interfaceName, AVAILABILITY_TIMEOUT_MS)
        network = net
        android.util.Log.i(TAG, "Network discovered: $net")

        // Step 4: Register keep-alive callback
        requestKeepAlive(net)

        // Step 5: Route forwarder traffic through the Clash VPN network so that
        // tethered clients actually egress via the proxy (not the shell process's
        // default network). Two complementary mechanisms:
        //   (a) bindProcessToNetwork(vpn) - public API 23+, binds every socket this
        //       shell process dials to the VPN network. Robust on Android 13-16.
        //   (b) pass the VPN network handle to the native forwarder for per-socket
        //       pinning via android_setsocknetwork() (libandroid.so) as a fallback.
        val vpnNetwork = findVpnNetwork()
        if (vpnNetwork != null) {
            bindProcessToVpn(vpnNetwork)
        } else {
            android.util.Log.w(TAG, "No VPN network found; tethered traffic may bypass the proxy")
        }
        val vpnHandle = vpnNetwork?.let { getNetworkHandle(it) } ?: 0L
        android.util.Log.i(TAG, "VPN network=$vpnNetwork handle=$vpnHandle processBound=$boundToVpn")

        // Step 6: Start gVisor data forwarder
        try {
            TetherPathBridge.start(handle.fileDescriptor.fd, vpnHandle = vpnHandle)
            forwarderStarted = true
            android.util.Log.i(TAG, "gVisor datapath started")
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

        if (boundToVpn) {
            runCatching { connectivityManager.bindProcessToNetwork(null) }
            boundToVpn = false
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

        android.util.Log.i(TAG, "Session stopped and cleaned up")
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
            android.util.Log.i(TAG, "Keep-alive callback registered for $network")
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
     * Find the Clash VPN network (TRANSPORT_VPN) among all system networks.
     */
    private fun findVpnNetwork(): Network? {
        return try {
            val transportVpn = resolveTransportVpn()
            connectivityManager.allNetworks.firstOrNull { network ->
                val caps = connectivityManager.getNetworkCapabilities(network)
                caps?.hasTransport(transportVpn) == true
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "findVpnNetwork failed: ${e.message}")
            null
        }
    }

    /**
     * Bind this shell process to the VPN network so that all sockets created
     * afterwards (i.e. the gVisor forwarder's upstream dials) egress through
     * Clash. Public API since Android 6.0 (API 23); works on Android 13-16.
     */
    private fun bindProcessToVpn(network: Network) {
        boundToVpn = try {
            val ok = connectivityManager.bindProcessToNetwork(network)
            if (!ok) android.util.Log.w(TAG, "bindProcessToNetwork returned false")
            ok
        } catch (e: Exception) {
            android.util.Log.w(TAG, "bindProcessToNetwork failed: ${e.message}")
            false
        }
    }

    /**
     * Resolve TRANSPORT_VPN constant via reflection (hidden API on some versions).
     */
    private fun resolveTransportVpn(): Int = runCatching {
        NetworkCapabilities::class.java.getField("TRANSPORT_VPN").getInt(null)
    }.getOrDefault(NetworkCapabilities.TRANSPORT_VPN)

    /**
     * Get the network handle via the hidden Network.getNetworkHandle() method.
     * NOTE: this lives on android.net.Network, NOT on ConnectivityManager.
     * Requires HiddenApiBypass (enabled in the shell service). Returns 0 on failure,
     * in which case process-level binding (bindProcessToVpn) still routes traffic.
     */
    private fun getNetworkHandle(network: Network): Long {
        return try {
            val method = Network::class.java.getMethod("getNetworkHandle")
            method.invoke(network) as Long
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Network.getNetworkHandle failed: ${e.message}")
            0L
        }
    }

    companion object {
        private const val TAG = "TetherSession"
        private const val AVAILABILITY_TIMEOUT_MS = 10_000L
        private const val POLL_INTERVAL_MS = 200L
    }
}
