package com.github.kr328.clash.tether

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.github.kr328.clash.TetherSettingsActivity
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.tether.adapter.CmfaTetherHost
import com.github.kr328.clash.tether.host.TetherHostProvider
import com.github.kr328.clash.tether.testnetwork.CompatibilityCheck

/**
 * The ONLY entry points the CMFA host app needs to call.
 * Everything else lives inside the shizuku-tether module.
 *
 * Host integration surface (kept minimal to survive upstream rebases):
 *   MainApplication.onCreate  -> TetherBootstrap.onApplicationCreate(this)
 *   SettingsActivity          -> TetherBootstrap.openSettings(this)
 *   External triggers         -> TetherBootstrap.start/stop/toggle
 */
object TetherBootstrap {

    fun onApplicationCreate(app: Application) {
        TetherHostProvider.init(CmfaTetherHost(app))
        autoStartIfNeeded(app)
    }

    fun openSettings(context: Context) {
        context.startActivity(Intent(context, TetherSettingsActivity::class.java))
    }

    fun start(context: Context) = TetherController.setEnabled(context, true)

    fun stop(context: Context) = TetherController.setEnabled(context, false)

    fun toggle(context: Context) =
        TetherController.setEnabled(context, !TetherController.isEnabled(context))

    val state get() = TetherController.state

    private fun autoStartIfNeeded(app: Application) {
        try {
            if (!TetherController.isAutoStartEnabled(app)) return

            // Catch future proxy start events and watch for all-ready conditions.
            TetherManager.registerProxyObserver()
            TetherController.ensureAutoStartMonitor(app)

            if (TetherHostProvider.host.isProxyRunning) {
                if (!CompatibilityCheck.probe(app).isSupported) {
                    Log.d("Tether auto-start skipped: device not supported")
                    return
                }
                Log.d("Tether auto-starting (proxy already running)...")
                TetherController.setEnabled(app, true)
            } else {
                // clashRunning may be false because broadcasts.register() hasn't
                // completed yet; re-check shortly to catch an already-running proxy.
                Handler(Looper.getMainLooper()).postDelayed({
                    try {
                        if (TetherHostProvider.host.isProxyRunning &&
                            TetherManager.state.value.status == TetherStatus.IDLE
                        ) {
                            Log.d("Tether auto-start: delayed re-check succeeded")
                            TetherController.setEnabled(app, true)
                        }
                    } catch (e: Exception) {
                        Log.w("Tether auto-start delayed check failed: ${e.message}")
                    }
                }, 2000)
            }
        } catch (e: Exception) {
            Log.w("Tether auto-start failed: ${e.message}")
        }
    }
}