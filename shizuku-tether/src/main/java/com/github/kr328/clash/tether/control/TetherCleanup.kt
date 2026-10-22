package com.github.kr328.clash.tether.control

import android.content.Context
import android.net.ConnectivityManager
import com.github.kr328.clash.tether.forwarder.TetherPathBridge

/**
 * Shared cleanup logic for tether resources.
 * Used by both CMFA side (TetherManager) and Shizuku side (TetherShellService).
 */
object TetherCleanup {
    private const val TAG = "TetherCleanup"

    /**
     * Full cleanup of all tether resources.
     * Safe to call multiple times - idempotent.
     */
    fun cleanupAll(
        context: Context,
        session: TetherSession?,
        tag: String = TAG,
    ) {
        android.util.Log.i(tag, "Starting full cleanup...")

        // 1. Stop gVisor forwarder
        try {
            TetherPathBridge.stop()
            android.util.Log.i(tag, "gVisor forwarder stopped")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop forwarder: ${e.message}")
        }

        // 2. Stop TetherSession (closes TUN fd, teardown test network)
        try {
            session?.stop()
            android.util.Log.i(tag, "TetherSession stopped")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop session: ${e.message}")
        }

        // 3. Stop WiFi downstream tethering
        try {
            val rebasedCtx = rebaseContextIfNeeded(context)
            DownstreamControl.stopWifiTethering(rebasedCtx)
            android.util.Log.i(tag, "WiFi downstream stopped")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop WiFi tethering: ${e.message}")
        }

        // 4. Clear preferTestNetworks
        try {
            val rebasedCtx = rebaseContextIfNeeded(context)
            TetheringPreferenceApi.setPreferTestNetworks(rebasedCtx, false)
            android.util.Log.i(tag, "preferTestNetworks cleared")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to clear preferTestNetworks: ${e.message}")
        }

        android.util.Log.i(tag, "Full cleanup completed")
    }

    /**
     * Quick cleanup - only stop session and forwarder.
     * Used when we need fast cleanup without touching downstream.
     */
    fun cleanupSessionOnly(
        session: TetherSession?,
        tag: String = TAG,
    ) {
        android.util.Log.i(tag, "Quick cleanup (session only)...")

        try {
            TetherPathBridge.stop()
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop forwarder: ${e.message}")
        }

        try {
            session?.stop()
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop session: ${e.message}")
        }

        android.util.Log.i(tag, "Quick cleanup completed")
    }

    /**
     * Rebase context for shell process (Android 12+ attribution source).
     * No-op if not in shell process or not needed.
     */
    private fun rebaseContextIfNeeded(context: Context): Context {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val field = Context::class.java.getDeclaredField("mAttributionSource")
                field.isAccessible = true
                val source = field.get(context)
                val withPkg = source::class.java.getMethod("withPackageName", String::class.java)
                val rebased = withPkg.invoke(source, "com.android.shell")
                field.set(context, rebased)
            }
            context
        } catch (_: Exception) {
            context
        }
    }
}
