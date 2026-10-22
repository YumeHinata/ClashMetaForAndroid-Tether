package com.github.kr328.clash.tether.shizuku

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import com.github.kr328.clash.tether.ITetherService
import com.github.kr328.clash.tether.control.DownstreamControl
import com.github.kr328.clash.tether.control.TetherCleanup
import com.github.kr328.clash.tether.control.TetherSession
import com.github.kr328.clash.tether.control.TetheringPreferenceApi
import com.github.kr328.clash.tether.testnetwork.CompatibilityCheck
import com.github.kr328.clash.tether.testnetwork.TestNetworkApi
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

class TetherShellService : ITetherService.Stub() {

    @Volatile
    private var session: TetherSession? = null

    @Volatile
    private var cleaned_up = false

    @Volatile
    private var watchdogRunning = false

    /**
     * Parent CMFA process PID, passed via init().
     * Used by watchdog to detect CMFA crash.
     */
    @Volatile
    private var parentPid: String = ""

    init {
        try {
            HiddenApiBypass.addHiddenApiExemptions("Landroid/net/", "Landroid/os/")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to enable hidden API bypass: ${e.message}")
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            forceCleanup()
        })
    }

    override fun init(parentPid: String) {
        // If already initialized with a different PID, cleanup first (re-injection handling)
        if (this.parentPid.isNotEmpty() && this.parentPid != parentPid) {
            forceCleanup()
        }
        
        this.parentPid = parentPid
        
        if (parentPid.isNotEmpty()) {
            startWatchdog()
        }
    }

    /**
     * Watchdog monitors parent CMFA process.
     * If CMFA crashes, cleanup tether resources and exit.
     */
    private fun startWatchdog() {
        if (watchdogRunning) return
        
        if (parentPid.isEmpty()) {
            return
        }
        
        watchdogRunning = true
        
        Thread({
            while (watchdogRunning) {
                try {
                    // Check if the specific parent PID is still alive
                    val process = Runtime.getRuntime().exec(arrayOf("kill", "-0", parentPid))
                    val exitCode = process.waitFor()
                    if (exitCode != 0) {
                        android.util.Log.i(TAG, "Parent CMFA process (PID $parentPid) died, cleaning up")
                        forceCleanup()
                        watchdogRunning = false
                        // Exit the shell process since CMFA is gone
                        System.exit(0)
                    }
                } catch (_: Exception) {
                    // kill -0 returns non-zero if process doesn't exist
                }
                Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }, "tether-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    /**
     * Full cleanup using shared TetherCleanup utility.
     * Idempotent - safe to call multiple times.
     */
    private fun forceCleanup() {
        watchdogRunning = false
        if (cleaned_up) return
        cleaned_up = true
        
        try {
            val ctx = getAppContext()
            val rebasedCtx = rebaseContext(ctx)
            TetherCleanup.cleanupAll(rebasedCtx, session, TAG)
            session = null
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Force cleanup failed", e)
        }
    }

    private fun cleanup() {
        forceCleanup()
    }

    private fun getAppContext(): Context {
        return try {
            val activityThreadClass = Class.forName("android.app.ActivityThread")
            val currentActivityThreadMethod: Method = activityThreadClass.getMethod("currentActivityThread")
            val activityThread = currentActivityThreadMethod.invoke(null)
            val getApplicationMethod: Method = activityThreadClass.getMethod("getApplication")
            getApplicationMethod.invoke(activityThread) as Context
        } catch (e: Exception) {
            throw IllegalStateException("Failed to get application context: ${e.message}", e)
        }
    }

    override fun startTethering(enableLogging: Boolean, vpnMode: Int) {
        // Guard: prevent duplicate sessions
        if (session != null) {
            forceCleanup()
        }
        cleaned_up = false  // Reset for new session lifecycle

        // Ensure watchdog is running (in case init() wasn't called)
        if (parentPid.isNotEmpty()) {
            startWatchdog()
        }

        var createdSession: TetherSession? = null
        try {
            val ctx = getAppContext()
            val rebasedCtx = rebaseContext(ctx)
            val cm = rebasedCtx.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

            val testNetworkApi = TestNetworkApi.fromContext(rebasedCtx)
                ?: throw IllegalStateException("TestNetworkManager not available in shell context")

            // Clear stale preference
            try {
                TetheringPreferenceApi.setPreferTestNetworks(rebasedCtx, false)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "clear preferTestNetworks failed: ${e.message}")
            }

            // Create TUN + setupTestNetwork + poll allNetworks for discovery
            val s = TetherSession(testNetworkApi, cm, rebasedCtx)
            createdSession = s  // Save reference for rollback on failure
            val fd = s.start()
            session = s  // Only assign after start() succeeds
            val interfaceName = s.interfaceName

            // Set preference for test networks
            try {
                TetheringPreferenceApi.setPreferTestNetworks(rebasedCtx, true)
            } catch (e: Exception) {
                android.util.Log.e(TAG, "setPreferTestNetworks(true) failed: ${e.message}", e)
            }

            // Verify upstream is our test network
            var verified = verifyUpstream(interfaceName)
            if (!verified) {
                // Force upstream re-evaluation by restarting downstream WiFi tethering.
                // The hotspot was already active before we created testtun, so the tethering
                // system selected cellular as upstream. Restarting forces it to re-evaluate
                // with preferTestNetworks=true, which should select testtun0.
                try {
                    DownstreamControl.stopWifiTethering(rebasedCtx)
                    Thread.sleep(2000)
                    DownstreamControl.startWifiTethering(rebasedCtx)
                    Thread.sleep(3000)
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "Failed to restart downstream tethering: ${e.message}")
                }
                verified = verifyUpstream(interfaceName)
                if (!verified) {
                    android.util.Log.w(TAG, "Upstream verification failed after restart for $interfaceName")
                }
            }
        } catch (e: Exception) {
            // Rollback: stop the created session if it was assigned but later steps failed
            try { createdSession?.stop() } catch (_: Exception) {}
            session = null
            android.util.Log.e(TAG, "startTethering() crashed", e)
            throw e
        }
    }

    override fun stopTethering() {
        forceCleanup()
    }

    override fun getStatus(): String {
        return if (session != null) "CONNECTED" else "DISCONNECTED"
    }

    override fun getUpstreamInterface(): String {
        return session?.interfaceName ?: ""
    }

    override fun getConnectedClients(): Int {
        return 0
    }

    override fun isCompatible(): Boolean {
        return try {
            val ctx = getAppContext()
            CompatibilityCheck.probe(rebaseContext(ctx)).isSupported
        } catch (e: Exception) {
            android.util.Log.e(TAG, "isCompatible failed: ${e.message}")
            false
        }
    }

    override fun getCompatibilityReport(): String {
        return try {
            val ctx = getAppContext()
            CompatibilityCheck.probe(rebaseContext(ctx)).details
        } catch (e: Exception) {
            "Failed to get context: ${e.message}"
        }
    }

    override fun isHotspotEnabled(): Boolean {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            
            // Method 1: Check for active WiFi tether interfaces in "Tether state:" section
            // Format: "wlanX - TetheredState" or "softapX - TetheredState"
            val hasActiveTether = output.contains(Regex("""wlan\d+\s*-\s*TetheredState""")) ||
                                  output.contains(Regex("""softap\d+\s*-\s*TetheredState"""))
            
            // Method 2: Fallback - check for recent startTethering without corresponding stopTethering
            val lastStartIdx = output.lastIndexOf("startTethering calling:")
            val lastStopIdx = output.lastIndexOf("stopTethering calling:")
            val wasRecentlyStarted = lastStartIdx > lastStopIdx && lastStartIdx != -1
            
            val isEnabled = hasActiveTether || wasRecentlyStarted
            isEnabled
        } catch (e: Exception) {
            android.util.Log.w(TAG, "isHotspotEnabled failed: ${e.message}")
            false
        }
    }

    /**
     * Verify that the tethering upstream is our test network interface.
     * Polls dumpsys tethering for UPSTREAM_TIMEOUT_MS.
     */
    private fun verifyUpstream(interfaceName: String?): Boolean {
        if (interfaceName == null) return false
        val deadline = System.currentTimeMillis() + UPSTREAM_VERIFY_TIMEOUT_MS
        while (System.currentTimeMillis() < deadline) {
            try {
                val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
                val output = process.inputStream.bufferedReader().readText()
                process.waitFor()
                if (output.contains("upstream") && output.contains(interfaceName)) {
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "dumpsys tethering failed: ${e.message}")
            }
            Thread.sleep(1000)
        }
        return false
    }

    private fun rebaseContext(context: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            return try {
                val field = Context::class.java.getDeclaredField("mAttributionSource")
                field.isAccessible = true
                val source = field.get(context)
                val withPkg = source::class.java.getMethod("withPackageName", String::class.java)
                val rebased = withPkg.invoke(source, "com.android.shell")
                field.set(context, rebased)
                context
            } catch (_: Exception) {
                context
            }
        }
        return context
    }

    companion object {
        private const val TAG = "TetherShellService"
        private const val SETTLE_MS = 3_000L
        private const val UPSTREAM_VERIFY_TIMEOUT_MS = 8_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 5_000L
    }
}
