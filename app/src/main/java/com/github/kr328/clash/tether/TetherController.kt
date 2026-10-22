package com.github.kr328.clash.tether

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.tether.shizuku.ShizukuGate
import com.github.kr328.clash.tether.shizuku.ShizukuState
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

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

    private val _state = MutableStateFlow(TetherState())
    val state: StateFlow<TetherState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var shizukuMonitorJob: Job? = null

    @Volatile
    private var desiredEnabled = false

    @Volatile
    private var isRunning = false

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Enable or disable tether.
     * Called by both manual switch and auto-start.
     * Persists the desired state and handles lifecycle.
     */
    fun setEnabled(context: Context, enabled: Boolean) {
        val appCtx = context.applicationContext
        desiredEnabled = enabled
        
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
        val prefs = context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START, enabled).apply()
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
                
                // If Shizuku is ready but tether is in ERROR or IDLE, restart
                if (shizukuState is ShizukuState.Ready && 
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
