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

        // 3. Stop WiFi downstream tethering (with retry)
        try {
            val rebasedCtx = rebaseContextIfNeeded(context)
            stopWifiTetheringWithRetry(rebasedCtx, tag)
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

        // 5. Verify upstream released
        try {
            val interfaceName = session?.interfaceName
            if (interfaceName != null) {
                verifyUpstreamReleased(interfaceName, tag)
            }
        } catch (e: Exception) {
            android.util.Log.w(tag, "Upstream release verification failed: ${e.message}")
        }

        // 6. Shutdown callback thread (prevent HandlerThread leak)
        try {
            DownstreamControl.shutdownCallbackThread()
            android.util.Log.i(tag, "Callback thread shutdown")
        } catch (e: Exception) {
            android.util.Log.w(tag, "Failed to shutdown callback thread: ${e.message}")
        }

        android.util.Log.i(tag, "Full cleanup completed")
    }

    /**
     * Stop WiFi tethering with retry mechanism.
     * Reference: shizzi's releaseDownstreamWith() - retries up to 3 times.
     */
    private fun stopWifiTetheringWithRetry(context: Context, tag: String, maxAttempts: Int = 3) {
        for (attempt in 1..maxAttempts) {
            DownstreamControl.stopWifiTethering(context)
            Thread.sleep(1_000)

            // Verify if really stopped
            val stillTethered = try {
                val tm = context.getSystemService("tethering")
                val method = tm::class.java.getMethod("getTetheredIfaces")
                val ifaces = method.invoke(tm) as? Array<*>
                ifaces?.any { WIFI_IFACE_REGEX.matches(it.toString()) } == true
            } catch (_: Exception) { false }

            if (!stillTethered) return
            android.util.Log.w(tag, "WiFi still tethered after attempt $attempt/$maxAttempts")
        }
        android.util.Log.w(tag, "WiFi still tethered after $maxAttempts attempts")
    }

    /**
     * Verify that upstream has moved off our test network.
     * Polls for up to 4 seconds.
     */
    private fun verifyUpstreamReleased(interfaceName: String, tag: String) {
        val deadline = System.currentTimeMillis() + 4_000L
        while (System.currentTimeMillis() < deadline) {
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                process = null

                val current = UPSTREAM_IFACES_REGEX.find(output)?.groupValues?.get(1).orEmpty()
                if (!current.split(',').any { it.trim() == interfaceName }) {
                    android.util.Log.i(tag, "Upstream verified: no longer using $interfaceName")
                    return
                }
            } catch (e: Exception) {
                android.util.Log.w(tag, "dumpsys tethering failed: ${e.message}")
            } finally {
                process?.destroy()
            }
            Thread.sleep(500)
        }
        android.util.Log.w(tag, "Upstream still on $interfaceName after timeout")
    }

    private val WIFI_IFACE_REGEX = Regex("^(wlan|softap|swlan|ap|wifi)\\d*$", RegexOption.IGNORE_CASE)
    private val UPSTREAM_IFACES_REGEX = Regex("Current upstream interface\\(s\\): \\[([^\\]]*)\\]")

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
     * Uses createPackageContext to set OpPackageName, then fixes AttributionSource.
     */
    private fun rebaseContextIfNeeded(context: Context): Context {
        return try {
            // Step 1: Create shell package context for correct OpPackageName
            val shellContext = context.createPackageContext("com.android.shell", 0)

            // Step 2: On Android S+, also fix AttributionSource
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                val implClass = Class.forName("android.app.ContextImpl")
                val field = implClass.getDeclaredField("mAttributionSource")
                field.isAccessible = true
                val source = field.get(shellContext)
                val withPkg = source::class.java.getMethod("withPackageName", String::class.java)
                val rebased = withPkg.invoke(source, "com.android.shell")
                field.set(shellContext, rebased)
            }

            shellContext
        } catch (e: Exception) {
            android.util.Log.w(TAG, "rebaseContextIfNeeded failed: ${e.message}")
            context
        }
    }
}
