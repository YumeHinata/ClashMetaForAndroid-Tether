package com.github.kr328.clash.tether.forwarder

import tetherpath.Tetherpath
import java.lang.Exception

object TetherPathBridge {
    fun start(tunFD: Int, mtu: Int = 1500, socksAddr: String = "127.0.0.1:7891", vpnHandle: Long = 0L) {
        try {
            // Set VPN network handle for socket binding (android_setsocknetwork)
            if (vpnHandle != 0L) {
                Tetherpath.setVpnNetworkHandle(vpnHandle)
                android.util.Log.i("TetherPathBridge", "VPN network handle set: $vpnHandle")
            }
            Tetherpath.start(tunFD.toLong(), mtu.toLong(), socksAddr)
        } catch (e: Exception) {
            android.util.Log.e("TetherPathBridge", "start failed", e)
            throw e
        }
    }

    fun stop() {
        try {
            Tetherpath.stop()
        } catch (e: Exception) {
            android.util.Log.e("TetherPathBridge", "stop failed", e)
        }
    }
}
