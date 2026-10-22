package com.github.kr328.clash.tether

import android.content.Context
import android.net.wifi.WifiManager

/**
 * Utility for checking WiFi hotspot (soft AP) state.
 *
 * Uses multiple detection methods for compatibility across Android versions:
 * 1. Hidden API via reflection (works on Android 11-15)
 * 2. Shell command fallback for Android 16+ where hidden APIs are restricted
 */
object HotspotMonitor {

    /**
     * Return `true` when the WiFi hotspot (soft AP) is currently enabled.
     */
    fun isHotspotEnabled(context: Context): Boolean {
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val method = wm.javaClass.getMethod("isWifiApEnabled")
                return method.invoke(wm) as Boolean
            }
        } catch (_: Exception) {
        }

        // Shell command fallback (works on Android 16+ without root)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            val lastStartIdx = output.lastIndexOf("startTethering calling:")
            val lastStopIdx = output.lastIndexOf("stopTethering calling:")
            val wasRecentlyStarted = lastStartIdx > lastStopIdx && lastStartIdx != -1
            
            val hasActiveTether = output.lines().any { line ->
                line.contains("state=TETHERED") && !line.trim().startsWith("OBSERVED")
            }
            
            return hasActiveTether || wasRecentlyStarted
        } catch (_: Exception) {
        }

        return false
    }
}
