package com.github.kr328.clash.tether

import android.content.Context
import com.github.kr328.clash.tether.host.TetherHostProvider
import com.github.kr328.clash.tether.shizuku.ShizukuGate
import com.github.kr328.clash.tether.shizuku.ShizukuState
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Unified tether controller.
 * 
 * Both manual switch and auto-start call setEnabled().
 * Persists desired state and handles lifecycle including Shizuku crashes.
 */
object TetherController {
    private const val PREF_NAME = "tether"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_AUTO_START = "auto_start"
    private const val SHIZUKU_CHECK_INTERVAL_MS = 10_000L
    private const val AUTO_CHECK_INTERVAL_MS = 4_000L

    private val _state = MutableStateFlow(TetherState())
    val state: StateFlow<TetherState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var shizukuMonitorJob: Job? = null

    @Volatile
    private var desiredEnabled = false

    @Volatile
    private var isRunning = false

    // Set by a manual stop; cleared by a manual enable or a new proxy-start event.
    @Volatile
    private var userStopped = false

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Enable or disable tether.
     * Called by both manual switch and auto-start.
     * Persists the desired state and handles lifecycle.
     */
    fun setEnabled(context: Context, enabled: Boolean, byUser: Boolean = false) {
        val appCtx = context.applicationContext
        desiredEnabled = enabled
        if (enabled) {
            userStopped = false
        } else if (byUser) {
            // A manual stop sticks until the next proxy start or manual enable.
            userStopped = true
        }

        // Persist the desired state
        val prefs = appCtx.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()

        if (enabled) {
            startTether(appCtx)
        } else {
            stopTether(appCtx)
        }
    }

    /**
     * Check if tether is enabled (desired state).
     */
    fun isEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_ENABLED, false)
    }

    /**
     * Auto-start: enable tether when proxy starts (if auto-start is enabled).
     */
    fun onProxyStarted(context: Context) {
        if (!isAutoStartEnabled(context)) return
        userStopped = false  // a new proxy session re-arms auto-start
        if (desiredEnabled) return  // Already enabled

        setEnabled(context, true)
    }

    /**
     * Auto-stop: disable tether when proxy stops.
     */
    fun onProxyStopped(context: Context) {
        if (!desiredEnabled) return

        setEnabled(context, false)
    }

    // ------------------------------------------------------------------
    // Preferences
    // ------------------------------------------------------------------

    fun isAutoStartEnabled(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START, false)
    }

    fun setAutoStartEnabled(context: Context, enabled: Boolean) {
        TetherManager.setAutoStartEnabled(context, enabled)
    }

    // ------------------------------------------------------------------
    // Internal - tether lifecycle
    // ------------------------------------------------------------------

    private fun startTether(context: Context) {
        if (isRunning) return
        isRunning = true

        TetherLogger.log("TetherController", "Starting tether")
        TetherForegroundService.start(context)
        
        // Start monitoring Shizuku status for crash recovery
        startShizukuMonitor(context)
    }

    private fun stopTether(context: Context) {
        if (!isRunning) return
        isRunning = false

        TetherLogger.log("TetherController", "Stopping tether")
        stopShizukuMonitor()
        TetherForegroundService.stop(context)
    }

    // ------------------------------------------------------------------
    // Shizuku crash recovery
    // ------------------------------------------------------------------

    /**
     * Monitor Shizuku status. If Shizuku recovers from a crash and tether should be running,
     * restart the tether.
     */
    private fun startShizukuMonitor(context: Context) {
        stopShizukuMonitor()
        
        shizukuMonitorJob = scope.launch {
            while (isActive && desiredEnabled) {
                delay(SHIZUKU_CHECK_INTERVAL_MS)
                
                if (!desiredEnabled) break
                
                val shizukuState = ShizukuGate.resolveState(context)
                val tetherStatus = _state.value.status

                // Only recover when proxy is actually running too.
                if (shizukuState is ShizukuState.Ready &&
                    TetherHostProvider.host.isProxyRunning &&
                    (tetherStatus == TetherStatus.ERROR || tetherStatus == TetherStatus.IDLE)) {
                    TetherLogger.log("TetherController", "Shizuku recovered, restarting tether")
                    TetherForegroundService.start(context)
                }
            }
        }
    }

    private fun stopShizukuMonitor() {
        shizukuMonitorJob?.cancel()
        shizukuMonitorJob = null
    }

    // ------------------------------------------------------------------
    // Auto-start monitor: start tether only when proxy + Shizuku + hotspot all ready
    // ------------------------------------------------------------------

    private var autoMonitorJob: Job? = null
    private var lastHotspotOn = false
    private var lastProxyRunning = false

    /**
     * Runs while auto-start is enabled. Reacts to proxy-off->on and hotspot-off->on edges
     * and starts the tether only when proxy + Shizuku + hotspot are all ready.
     * Edge-triggered so a manual stop sticks; the observer remains the fast path.
     */
    fun ensureAutoStartMonitor(context: Context) {
        if (autoMonitorJob?.isActive == true) return
        val appCtx = context.applicationContext
        lastHotspotOn = runCatching { HotspotMonitor.isHotspotEnabled(appCtx) }.getOrDefault(false)
        lastProxyRunning = runCatching { TetherHostProvider.host.isProxyRunning }.getOrDefault(false)
        autoMonitorJob = scope.launch {
            while (isActive) {
                delay(AUTO_CHECK_INTERVAL_MS)
                if (!isAutoStartEnabled(appCtx)) break

                val hotspotOn = runCatching { HotspotMonitor.isHotspotEnabled(appCtx) }.getOrDefault(false)
                val proxyOn = runCatching { TetherHostProvider.host.isProxyRunning }.getOrDefault(false)
                val risingHotspot = hotspotOn && !lastHotspotOn
                val risingProxy = proxyOn && !lastProxyRunning
                lastHotspotOn = hotspotOn
                lastProxyRunning = proxyOn

                if (desiredEnabled || userStopped) continue

                val ready = proxyOn && hotspotOn && runCatching {
                    ShizukuGate.resolveState(appCtx) is ShizukuState.Ready
                }.getOrDefault(false)

                val st = _state.value.status
                if (ready && (risingProxy || risingHotspot) &&
                    (st == TetherStatus.IDLE || st == TetherStatus.ERROR)) {
                    TetherLogger.log("TetherController", "Auto-start: proxy/hotspot edge with all prerequisites ready")
                    setEnabled(appCtx, true)
                }
            }
            autoMonitorJob = null
        }
    }

    fun stopAutoStartMonitor() {
        autoMonitorJob?.cancel()
        autoMonitorJob = null
    }

    // ------------------------------------------------------------------
    // State forwarding from TetherManager
    // ------------------------------------------------------------------

    /**
     * Forward state changes from TetherManager to our state flow.
     * Called by TetherManager when state changes.
     */
    internal fun updateState(newState: TetherState) {
        _state.value = newState
    }
}
