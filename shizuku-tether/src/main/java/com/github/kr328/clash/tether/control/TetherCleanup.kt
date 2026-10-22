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

        try {
            val rebasedCtx = rebaseContextIfNeeded(context)
            DownstreamControl.stopWifiTethering(rebasedCtx)
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to stop WiFi tethering: ${e.message}")
        }

        try {
            val rebasedCtx = rebaseContextIfNeeded(context)
            TetheringPreferenceApi.setPreferTestNetworks(rebasedCtx, false)
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to clear preferTestNetworks: ${e.message}")
        }
    }

    /**
     * Quick cleanup - only stop session and forwarder.
     * Used when we need fast cleanup without touching downstream.
     */
    fun cleanupSessionOnly(
        session: TetherSession?,
        tag: String = TAG,
    ) {
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
