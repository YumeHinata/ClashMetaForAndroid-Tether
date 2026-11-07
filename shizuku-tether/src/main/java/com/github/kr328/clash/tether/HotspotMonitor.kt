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
        // Method 1: Try hidden API via reflection
        try {
            val wm = context.applicationContext
                .getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wm != null) {
                val method = wm.javaClass.getMethod("isWifiApEnabled")
                val result = method.invoke(wm) as Boolean
                android.util.Log.d("HotspotMonitor", "Hidden API check: $result")
                return result
            }
        } catch (e: Exception) {
            android.util.Log.w("HotspotMonitor", "Hidden API method failed (${e.javaClass.simpleName}): ${e.message}")
        }

        // Method 2: Shell command fallback (works on Android 16+ without root)
        try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            // Check for recent startTethering without corresponding stopTethering
            val lastStartIdx = output.lastIndexOf("startTethering calling:")
            val lastStopIdx = output.lastIndexOf("stopTethering calling:")
            val wasRecentlyStarted = lastStartIdx > lastStopIdx && lastStartIdx != -1
            
            // Also check for active TETHERED state (not historical OBSERVED)
            val hasActiveTether = output.lines().any { line ->
                line.contains("state=TETHERED") && !line.trim().startsWith("OBSERVED")
            }
            
            val isHotspot = hasActiveTether || wasRecentlyStarted
            android.util.Log.d("HotspotMonitor", "Shell tethering check: active=$hasActiveTether, recent=$wasRecentlyStarted -> $isHotspot")
            return isHotspot
        } catch (e: Exception) {
            android.util.Log.w("HotspotMonitor", "Shell command check failed: ${e.message}")
        }

        return false
    }
}
