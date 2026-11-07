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
import com.github.kr328.clash.tether.testnetwork.resolveTransportTest
import org.lsposed.hiddenapibypass.HiddenApiBypass
import java.lang.reflect.Method

class TetherShellService : ITetherService.Stub() {

    @Volatile
    private var session: TetherSession? = null

    @Volatile
    private var cleaned_up = false

    @Volatile
    private var watchdogRunning = false

    @Volatile
    private var startInProgress = false

    /**
     * Parent CMFA process PID, passed via init().
     * Used by watchdog to detect CMFA crash.
     */
    @Volatile
    private var parentPid: String = ""

    /** /proc starttime of parentPid at init(), to detect pid reuse. */
    @Volatile
    private var parentStartAt: Long? = null

    /** Last time init/startTethering touched us; drives idle self-exit. */
    @Volatile
    private var lastActiveAt: Long = System.currentTimeMillis()

    init {
        try {
            // Exempt every class descriptor (all start with "L") so that reflection into
            // hidden/blocklisted framework APIs works on Android 13-16. This process runs
            // as the privileged shell UID (2000) via Shizuku, so a broad exemption is safe
            // and covers android.net.*, android.os.*, android.content.* (mAttributionSource),
            // Network.getNetworkHandle(), TetheringManager.setPreferTestNetworks(), etc.
            HiddenApiBypass.addHiddenApiExemptions("L")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to enable hidden API bypass: ${e.message}")
        }

        Runtime.getRuntime().addShutdownHook(Thread {
            System.err.println("TetherShellService: Shutdown hook triggered")
            forceCleanup()
        })
    }

    override fun init(parentPid: String) {
        android.util.Log.i(TAG, "init() called with parentPid=$parentPid")
        
        // If already initialized with a different PID, cleanup first (re-injection handling)
        if (this.parentPid.isNotEmpty() && this.parentPid != parentPid) {
            android.util.Log.i(TAG, "Re-initialization detected, cleaning up old session")
            forceCleanup()
        }
        
        this.parentPid = parentPid
        this.parentStartAt = parentStartTime(parentPid)
        this.lastActiveAt = System.currentTimeMillis()

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
            android.util.Log.w(TAG, "No parent PID set, watchdog not starting")
            return
        }
        
        android.util.Log.i(TAG, "Watchdog monitoring parent PID: $parentPid")
        watchdogRunning = true
        
        Thread({
            while (watchdogRunning) {
                try {
                    // kill -0 cannot probe another uid's process (shell 2000 vs app uid),
                    // so read /proc instead; cmdline guards against pid reuse.
                    if (!isParentAlive(parentPid)) {
                        android.util.Log.i(TAG, "Parent CMFA process (PID $parentPid) died, cleaning up")
                        forceCleanup()
                        watchdogRunning = false
                        // Exit the shell process since CMFA is gone
                        System.exit(0)
                    } else if (session == null &&
                        System.currentTimeMillis() - lastActiveAt > IDLE_EXIT_MS) {
                        // No active session and idle too long (e.g. Shizuku left us alive
                        // after unbind). Reclaim the shell process.
                        android.util.Log.i(TAG, "Idle with no session, exiting shell process")
                        forceCleanup()
                        watchdogRunning = false
                        System.exit(0)
                    }
                } catch (_: Exception) {
                    // treat transient read errors as alive
                }
                Thread.sleep(WATCHDOG_CHECK_INTERVAL_MS)
            }
        }, "tether-watchdog").apply {
            isDaemon = true
            start()
        }
    }

    private fun isParentAlive(pid: String): Boolean {
        return try {
            val cmdline = java.io.File("/proc/$pid/cmdline").readText()
            if (!cmdline.contains("clash", ignoreCase = true)) return false
            // Guard against pid reuse: the start-time must match what we saw at init().
            val recorded = parentStartAt ?: return true
            parentStartTime(pid) == recorded
        } catch (_: Exception) {
            false
        }
    }

    /** Field 22 (starttime) of /proc/<pid>/stat, in clock ticks since boot. */
    private fun parentStartTime(pid: String): Long? = try {
        val stat = java.io.File("/proc/$pid/stat").readText()
        val afterComm = stat.substringAfterLast(')')
        afterComm.trim().split(' ').getOrNull(19)?.toLongOrNull()
    } catch (_: Exception) {
        null
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
            android.util.Log.i(TAG, "Force cleanup completed")
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
        android.util.Log.i(TAG, "startTethering() called")

        // Reentrancy guard: the app may call startTethering again while a start is still
        // in flight (e.g. reacting to the downstream restart). Overlapping starts create
        // two TUNs and leave the system upstream pinned to the first (dead) one.
        if (startInProgress) {
            android.util.Log.w(TAG, "startTethering already in progress, ignoring")
            return
        }
        startInProgress = true
        try {
            startTetheringInternal(enableLogging, vpnMode)
        } finally {
            startInProgress = false
        }
    }

    private fun startTetheringInternal(enableLogging: Boolean, vpnMode: Int) {
        lastActiveAt = System.currentTimeMillis()
        // Guard: prevent duplicate sessions
        if (session != null) {
            android.util.Log.w(TAG, "Existing session found, stopping it first")
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

            // Clean slate: teardown leftover test networks from prior (possibly killed)
            // sessions and stop the downstream, so the system upstream cannot stay pinned
            // to a dead interface and re-election happens with only our TUN present.
            cleanSlate(rebasedCtx, cm, testNetworkApi)

            // Create TUN + setupTestNetwork + poll allNetworks for discovery
            val s = TetherSession(testNetworkApi, cm, rebasedCtx)
            createdSession = s  // Save reference for rollback on failure
            val fd = s.start()
            session = s  // Only assign after start() succeeds
            val interfaceName = s.interfaceName
            android.util.Log.i(TAG, "Session started, TUN: $interfaceName, fd: $fd")

            // Set preference for test networks
            try {
                TetheringPreferenceApi.setPreferTestNetworks(rebasedCtx, true)
                android.util.Log.i(TAG, "setPreferTestNetworks(true) called successfully")
            } catch (e: Exception) {
                android.util.Log.e(TAG, "setPreferTestNetworks(true) failed: ${e.message}", e)
            }

            // Start the downstream now that our TUN is the preferred candidate, so the
            // first upstream election already sees it (avoids mid-session restart churn).
            try {
                DownstreamControl.startWifiTethering(rebasedCtx)
                android.util.Log.i(TAG, "Downstream started, waiting for election...")
                Thread.sleep(3000)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to start downstream: ${e.message}")
            }

            // Verify upstream is our test network
            var verified = verifyUpstream(interfaceName)
            if (!verified) {
                android.util.Log.w(TAG, "Upstream verification failed for $interfaceName, forcing re-evaluation")
                restartDownstream(rebasedCtx)
                verified = verifyUpstream(interfaceName)
            }
            if (verified) {
                android.util.Log.i(TAG, "Upstream verified: $interfaceName")
            } else {
                android.util.Log.w(TAG, "Upstream STILL not our test network after restart")
            }

            android.util.Log.i(TAG, "startTethering() completed successfully")
        } catch (e: Exception) {
            // Rollback: stop the created session if it was assigned but later steps failed
            try { createdSession?.stop() } catch (_: Exception) {}
            session = null
            android.util.Log.e(TAG, "startTethering() crashed", e)
            throw e
        }
    }

    override fun stopTethering() {
        android.util.Log.i(TAG, "stopTethering() called")
        forceCleanup()
        android.util.Log.i(TAG, "stopTethering completed")
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
        // Primary: TetheringManager.getTetheredIfaces() - a hidden but shell-accessible API
        // (with HiddenApiBypass) whose result is stable across AOSP 13-16 and HyperOS, unlike
        // the dumpsys text format which OEMs frequently change.
        getTetheredIfaces()?.let { ifaces ->
            val wifiUp = ifaces.any { WIFI_IFACE_REGEX.matches(it) }
            android.util.Log.d(TAG, "isHotspotEnabled via getTetheredIfaces=${ifaces.toList()} -> $wifiUp")
            if (wifiUp) return true
        }

        // Fallback: parse `dumpsys tethering` (works even if getTetheredIfaces is unavailable).
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()

            // Method 1: an active WiFi downstream interface in a "TetheredState"/"state=TETHERED" line.
            val hasActiveTether = TETHERED_IFACE_REGEX.containsMatchIn(output) ||
                output.contains("state=TETHERED")

            // Method 2: recent startTethering without a matching stopTethering.
            val lastStartIdx = output.lastIndexOf("startTethering calling:")
            val lastStopIdx = output.lastIndexOf("stopTethering calling:")
            val wasRecentlyStarted = lastStartIdx > lastStopIdx && lastStartIdx != -1

            val isEnabled = hasActiveTether || wasRecentlyStarted
            android.util.Log.d(TAG, "isHotspotEnabled(dumpsys): active=$hasActiveTether, recent=$wasRecentlyStarted -> $isEnabled")
            isEnabled
        } catch (e: Exception) {
            android.util.Log.w(TAG, "isHotspotEnabled failed: ${e.message}")
            false
        }
    }

    /**
     * Query the currently tethered downstream interfaces via the hidden
     * TetheringManager.getTetheredIfaces(). Returns null if unavailable on this build.
     */
    private fun getTetheredIfaces(): Array<String>? {
        return try {
            val ctx = rebaseContext(getAppContext())
            val tm = ctx.getSystemService("tethering") ?: return null
            val method = tm::class.java.getMethod("getTetheredIfaces")
            method.invoke(tm) as? Array<String>
        } catch (e: Exception) {
            android.util.Log.w(TAG, "getTetheredIfaces unavailable: ${e.message}")
            null
        }
    }

    /**
     * Reset tethering to a clean slate before creating a new TUN: teardown leftover test
     * networks from prior (possibly killed) sessions, clear the preference, and stop the
     * downstream so the next upstream election sees only our freshly-created TUN.
     */
    private fun cleanSlate(ctx: Context, cm: ConnectivityManager, api: TestNetworkApi) {
        try {
            val testTransport = resolveTransportTest()
            cm.allNetworks.forEach { net ->
                val caps = cm.getNetworkCapabilities(net) ?: return@forEach
                if (caps.hasTransport(testTransport)) {
                    android.util.Log.i(TAG, "Tearing down leftover test network: $net")
                    runCatching { api.teardownTestNetwork(net) }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "leftover test-network cleanup failed: ${e.message}")
        }
        try {
            TetheringPreferenceApi.setPreferTestNetworks(ctx, false)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clear preferTestNetworks failed: ${e.message}")
        }
        try {
            DownstreamControl.stopWifiTethering(ctx)
            Thread.sleep(1500)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "clean-slate downstream stop failed: ${e.message}")
        }
    }

    private fun restartDownstream(ctx: Context) {
        try {
            DownstreamControl.stopWifiTethering(ctx)
            android.util.Log.i(TAG, "Stopped WiFi downstream, waiting for settle...")
            Thread.sleep(2000)
            DownstreamControl.startWifiTethering(ctx)
            android.util.Log.i(TAG, "Restarted WiFi downstream, waiting for re-evaluation...")
            Thread.sleep(3000)
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to restart downstream tethering: ${e.message}")
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
            var process: Process? = null
            try {
                process = Runtime.getRuntime().exec(arrayOf("dumpsys", "tethering"))
                val output = process.inputStream.bufferedReader().use { it.readText() }
                process.waitFor()
                // Strict: only accept when the active upstream list contains our testtun.
                val current = UPSTREAM_IFACES_REGEX.find(output)?.groupValues?.get(1).orEmpty()
                android.util.Log.d(TAG, "verifyUpstream: current=[$current] want=$interfaceName")
                if (current.split(',').any { it.trim() == interfaceName }) {
                    return true
                }
            } catch (e: Exception) {
                android.util.Log.w(TAG, "dumpsys tethering failed: ${e.message}")
            } finally {
                process?.destroy()
            }
            Thread.sleep(1000)
        }
        return false
    }

    private fun rebaseContext(context: Context): Context {
        return try {
            // Step 1: Create a context for the shell package to set OpPackageName
            val shellContext = context.createPackageContext("com.android.shell", 0)

            // Step 2: On Android S+, also fix the AttributionSource
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
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
            android.util.Log.w(TAG, "rebaseContext failed: ${e.message}")
            context
        }
    }

    companion object {
        private const val TAG = "TetherShellService"
        private const val SETTLE_MS = 3_000L
        private const val UPSTREAM_VERIFY_TIMEOUT_MS = 8_000L
        private const val WATCHDOG_CHECK_INTERVAL_MS = 5_000L
        private const val IDLE_EXIT_MS = 60_000L

        // Matches a whole WiFi-hotspot downstream interface name across AOSP/OEM naming:
        // wlan1, softap0, ap0, wifi0, swlan0 (HyperOS/MIUI variants).
        private val WIFI_IFACE_REGEX =
            Regex("^(wlan|softap|swlan|ap|wifi)\\d*$", RegexOption.IGNORE_CASE)

        // Matches a "<wifi-iface> - TetheredState" entry inside `dumpsys tethering`.
        private val TETHERED_IFACE_REGEX =
            Regex("(?i)\\b(?:wlan|softap|swlan|ap|wifi)\\d*\\s*-\\s*TetheredState")

        // Captures the comma-separated active upstream list, e.g.
        // "Current upstream interface(s): [rmnet_data2]" -> "rmnet_data2"
        private val UPSTREAM_IFACES_REGEX =
            Regex("Current upstream interface\\(s\\): \\[([^\\]]*)\\]")
    }
}
