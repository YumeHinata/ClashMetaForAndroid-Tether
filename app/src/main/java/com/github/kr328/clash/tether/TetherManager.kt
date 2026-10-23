package com.github.kr328.clash.tether

import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.tether.shizuku.ShizukuGate
import com.github.kr328.clash.tether.shizuku.ShizukuState
import com.github.kr328.clash.tether.shizuku.TetherClient
import com.github.kr328.clash.tether.testnetwork.CompatibilityCheck
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.UUID

enum class TetherStatus {
    IDLE,
    WAITING_HOTSPOT,
    CONNECTED,
    ERROR,
}

data class TetherState(
    val status: TetherStatus = TetherStatus.IDLE,
    val upstreamInterface: String = "",
    val connectedClients: Int = 0,
    val error: String = "",
    val compatibilityReport: String = "",
    val hotspotOn: Boolean = false,
)

object TetherManager {
    private val _state = MutableStateFlow(TetherState())
    val state: StateFlow<TetherState> = _state.asStateFlow()

    private var client: TetherClient? = null
    private val bindLock = Any()
    
    // Generation counter to invalidate stale async operations
    @Volatile
    private var generation: Long = 0

    private var monitorJob: Job? = null
    private val monitorScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private const val PREF_NAME = "tether"
    private const val KEY_AUTO_START = "auto_start"
    private const val HOTSPOT_POLL_INTERVAL_MS = 3_000L

    // Forward state changes to TetherController
    private fun updateState(newState: TetherState) {
        _state.value = newState
        TetherController.updateState(newState)
    }

    // ------------------------------------------------------------------
    // Proxy observer (Broadcasts.Observer)
    // ------------------------------------------------------------------

    @Volatile
    private var proxyObserverRegistered = false

    private val proxyObserver = object : com.github.kr328.clash.remote.Broadcasts.Observer {
        override fun onServiceRecreated() {}
        override fun onProfileChanged() {}
        override fun onProfileUpdateCompleted(uuid: UUID?) {}
        override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {}
        override fun onProfileLoaded() {}

        override fun onStarted() {
            TetherLogger.log("TetherManager", "Proxy started (Broadcasts.onStarted)")
            TetherController.onProxyStarted(Global.application)
        }

        override fun onStopped(cause: String?) {
            TetherLogger.log("TetherManager", "Proxy stopped (Broadcasts.onStopped, cause=$cause)")
            TetherController.onProxyStopped(Global.application)
        }
    }

    fun registerProxyObserver() {
        if (proxyObserverRegistered) return
        Remote.broadcasts.addObserver(proxyObserver)
        proxyObserverRegistered = true
    }

    fun unregisterProxyObserver() {
        if (!proxyObserverRegistered) return
        Remote.broadcasts.removeObserver(proxyObserver)
        proxyObserverRegistered = false
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
    // Public API
    // ------------------------------------------------------------------

    /**
     * Start tether lifecycle.
     *
     * 1. Compatibility check -> ERROR if unsupported.
     * 2. Register proxy observer (if not already).
     * 3. Increment generation (invalidates stale ops).
     * 4. Bind shell service.
     * 5. On connected: query hotspot -> startTethering or WAITING_HOTSPOT.
     * 6. Start polling (in onConnected, not here).
     */
    fun start(context: Context, isAutoStart: Boolean = false) {
        val appCtx = context.applicationContext

        synchronized(bindLock) {
            val currentStatus = _state.value.status
            if (currentStatus != TetherStatus.IDLE && currentStatus != TetherStatus.ERROR) {
                return
            }

            // Check Shizuku readiness before attempting to bind
            val shizukuState = ShizukuGate.resolveState(appCtx)
            if (shizukuState !is ShizukuState.Ready) {
                updateState(TetherState(
                    status = TetherStatus.ERROR,
                    error = "Shizuku not ready: $shizukuState",
                ))
                return
            }

            val compat = CompatibilityCheck.probe(appCtx)
            if (!compat.isSupported) {
                updateState(TetherState(
                    status = TetherStatus.ERROR,
                    compatibilityReport = compat.details,
                    error = "Device not supported",
                ))
                return
            }

            registerProxyObserver()

            generation++
            val myGen = generation

            if (client != null) {
                try { client?.unbind(appCtx) } catch (_: Exception) {}
                client = null
            }

            val c = TetherClient()
            client = c
            var onConnectedFired = false

            try {
                c.bind(
                    context = appCtx,
                    onConnected = { service ->
                        if (onConnectedFired) return@bind
                        onConnectedFired = true

                        if (myGen != generation) return@bind

                        monitorScope.launch {
                            try {
                                val hotspotOn = service.isHotspotEnabled
                                updateState(_state.value.copy(hotspotOn = hotspotOn, error = ""))

                                if (hotspotOn) {
                                    startTetheringOnService(service, myGen, appCtx)
                                } else {
                                    updateState(_state.value.copy(status = TetherStatus.WAITING_HOTSPOT))
                                    startHotspotMonitoring(appCtx, myGen)
                                }
                            } catch (e: Exception) {
                                TetherLogger.logError("TetherManager", "Failed to query hotspot", e)
                                updateState(TetherState(
                                    status = TetherStatus.ERROR,
                                    error = "Failed to query hotspot: ${e.message}",
                                ))
                            }
                        }
                    },
                    onDisconnected = {
                        handleShellDisconnect(myGen)
                    },
                )
            } catch (e: Exception) {
                TetherLogger.logError("TetherManager", "bind() threw exception", e)
                client = null
                updateState(TetherState(
                    status = TetherStatus.ERROR,
                    error = "Failed to bind service: ${e.message}",
                ))
            }
        }
    }

    /**
     * Stop tether lifecycle.
     *
     * 1. Increment generation (invalidates pending ops).
     * 2. Capture current client and null it out.
     * 3. Unregister observer, stop polling.
     * 4. doStop with CAPTURED client (async on IO thread).
     * 5. state = IDLE.
     */
    fun stop(context: Context) {
        val appCtx = context.applicationContext

        val capturedClient: TetherClient?
        val myGen: Long

        synchronized(bindLock) {
            generation++
            myGen = generation

            unregisterProxyObserver()
            stopHotspotMonitoring()

            capturedClient = client
            client = null

            updateState(TetherState())
        }

        monitorScope.launch(Dispatchers.IO) {
            doStopWithClient(appCtx, capturedClient)
        }
    }

    fun checkCompatibility(context: Context): String {
        return CompatibilityCheck.probe(context).details
    }

    // ------------------------------------------------------------------
    // Internal - tether lifecycle
    // ------------------------------------------------------------------

    /**
     * Call startTethering() on the service.
     * Checks generation to avoid stale operations.
     */
    private fun startTetheringOnService(service: com.github.kr328.clash.tether.ITetherService, gen: Long, context: Context) {
        if (gen != generation) return

        updateState(_state.value.copy(status = TetherStatus.CONNECTED, error = ""))

        val shizukuState = ShizukuGate.resolveState(context)
        if (shizukuState !is ShizukuState.Ready) {
            val errorMsg = "Shizuku not ready: $shizukuState"
            TetherLogger.logError("TetherManager", errorMsg)
            updateState(TetherState(
                status = TetherStatus.ERROR,
                error = errorMsg,
            ))
            return
        }

        try {
            service.startTethering(false, 0)
            updateState(_state.value.copy(upstreamInterface = service.upstreamInterface))
            startHotspotMonitoring(context, gen)
        } catch (e: Exception) {
            TetherLogger.logError("TetherManager", "startTethering() failed", e)
            updateState(TetherState(
                status = TetherStatus.ERROR,
                error = e.message ?: "startTethering failed",
            ))
        }
    }

    /**
     * Stop with a specific client reference (not the global one).
     * This prevents race conditions where the global client has been replaced.
     */
    private fun doStopWithClient(context: Context, target: TetherClient?) {
        if (target == null) return
        try {
            target.service?.stopTethering()
        } catch (e: Exception) {
            TetherLogger.log("TetherManager", "stopTethering() best-effort failed: ${e.message}")
        }
        try {
            target.unbind(context)
        } catch (e: Exception) {
            TetherLogger.logError("TetherManager", "unbind failed", e)
        }
    }

    /**
     * Handle shell service disconnect.
     * Only process if generation matches (avoid stale callbacks).
     */
    private fun handleShellDisconnect(gen: Long) {
        if (gen != generation) return

        val currentStatus = _state.value.status

        if (currentStatus == TetherStatus.CONNECTED || currentStatus == TetherStatus.WAITING_HOTSPOT) {
            TetherLogger.log("TetherManager", "Shell process died in active state -> ERROR")
            client = null
            updateState(_state.value.copy(
                status = TetherStatus.ERROR,
                error = "Shell process died unexpectedly",
            ))
        } else {
            client = null
        }
    }

    // ------------------------------------------------------------------
    // Hotspot monitoring (polling)
    // ------------------------------------------------------------------

    /**
     * Start hotspot polling.
     * Called from onConnected (not from start()) to avoid immediate exit.
     */
    private fun startHotspotMonitoring(context: Context, gen: Long) {
        if (monitorJob?.isActive == true) return

        monitorJob = monitorScope.launch {
            while (isActive) {
                if (gen != generation) break

                val currentStatus = _state.value.status
                if (currentStatus == TetherStatus.IDLE || currentStatus == TetherStatus.ERROR) break

                try {
                    val isOn = queryHotspotState(context)
                    val prev = _state.value.hotspotOn
                    if (isOn != prev) {
                        updateState(_state.value.copy(hotspotOn = isOn))
                        handleHotspotStateChange(context, isOn, gen)
                    }
                } catch (e: Exception) {
                    TetherLogger.logError("TetherManager", "Polling error", e)
                }
                delay(HOTSPOT_POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Query hotspot state: prefer shell service (reliable), fallback to local check.
     */
    private fun queryHotspotState(context: Context): Boolean {
        val serviceClient = client
        if (serviceClient != null && serviceClient.isConnected) {
            try {
                val result = serviceClient.service?.isHotspotEnabled ?: false
                return result
            } catch (e: Exception) {
                TetherLogger.log("TetherManager", "Shell service hotspot check failed: ${e.message}")
            }
        }

        // Fallback to local check (may fail on Android 16)
        return HotspotMonitor.isHotspotEnabled(context)
    }

    private fun stopHotspotMonitoring() {
        monitorJob?.cancel()
        monitorJob = null
    }

    /**
     * React to a hotspot state change detected by the polling loop.
     *
     * - Hotspot ON  + WAITING_HOTSPOT -> startTethering (keep client bound)
     * - Hotspot OFF + CONNECTED       -> stopTethering (keep client bound for monitoring)
     */
    private fun handleHotspotStateChange(context: Context, isOn: Boolean, gen: Long) {
        val currentStatus = _state.value.status

        when {
            isOn && currentStatus == TetherStatus.WAITING_HOTSPOT -> {
                monitorScope.launch {
                    try {
                        val c = client
                        val svc = c?.service
                        if (c != null && svc != null && c.isConnected && gen == generation) {
                            startTetheringOnService(svc, gen, context)
                        }
                    } catch (e: Exception) {
                        TetherLogger.logError("TetherManager", "Auto-start failed", e)
                        updateState(TetherState(
                            status = TetherStatus.ERROR,
                            error = "Auto-start failed: ${e.message}",
                        ))
                    }
                }
            }

            !isOn && currentStatus == TetherStatus.CONNECTED -> {
                monitorScope.launch(Dispatchers.IO) {
                    try {
                        val c = client
                        if (c != null && c.isConnected && gen == generation) {
                            c.service?.stopTethering()
                        }
                        updateState(_state.value.copy(
                            status = TetherStatus.WAITING_HOTSPOT,
                            upstreamInterface = "",
                            hotspotOn = false,
                        ))
                    } catch (e: Exception) {
                        TetherLogger.logError("TetherManager", "stopTethering() failed", e)
                    }
                }
            }
        }
    }
}
