package com.github.kr328.clash

import android.widget.Toast
import com.github.kr328.clash.design.TetherSettingsDesign
import com.google.android.material.switchmaterial.SwitchMaterial
import com.github.kr328.clash.tether.TetherForegroundService
import com.github.kr328.clash.tether.TetherManager
import com.github.kr328.clash.tether.TetherStatus
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.selects.select

class TetherSettingsActivity : BaseActivity<TetherSettingsDesign>() {
    override suspend fun main() {
        com.github.kr328.clash.tether.TetherLogger.init(this)

        val design = TetherSettingsDesign(this)
        setContentDesign(design)

        val switchView = design.root.findViewById<SwitchMaterial>(com.github.kr328.clash.design.R.id.auto_start_switch)
        val isEnabled = TetherManager.isAutoStartEnabled(this)
        design.setupAutoStartSwitch(isEnabled, switchView) { isChecked ->
            TetherManager.setAutoStartEnabled(this, isChecked)
        }

        // Observe tether status changes and update UI
        launch {
            TetherManager.state.collect { state ->
                val status = state.status

                // Update toggle button text based on status
                val buttonText = when (status) {
                    TetherStatus.IDLE -> "Start Tether"
                    TetherStatus.CONNECTED -> "Stop Tether"
                    TetherStatus.ERROR -> "Retry (Error)"
                    TetherStatus.WAITING_HOTSPOT -> "Cancel (Waiting...)"
                }
                design.setToggleText(buttonText)

                // Show status-dependent subtext
                val subtext = when (status) {
                    TetherStatus.ERROR -> {
                        val errorMsg = state.error.ifEmpty { "Unknown error" }
                        "Error: $errorMsg\nCheck /Android/data/com.github.kr328.clash/files/tether_debug.log"
                    }
                    TetherStatus.CONNECTED -> "Connected via ${state.upstreamInterface}"
                    TetherStatus.WAITING_HOTSPOT -> "Acceleration enabled. Turn on WiFi hotspot to start proxy."
                    else -> "Share proxy via Shizuku tethering"
                }
                design.setToggleSubtext(subtext)

                // Disable toggle while in transient states (none now, but keep for safety)
                design.setToggleEnabled(true)
            }
        }

        while (isActive) {
            select<Unit> {
                events.onReceive {

                }
                design.requests.onReceive {
                    when (it) {
                        TetherSettingsDesign.Request.ToggleTether -> {
                            val currentState = TetherManager.state.value.status

                            when (currentState) {
                                TetherStatus.CONNECTED, TetherStatus.WAITING_HOTSPOT -> {
                                    // Stop / Cancel
                                    TetherForegroundService.stop(this@TetherSettingsActivity)
                                    val msg = if (currentState == TetherStatus.WAITING_HOTSPOT) "Cancelled" else "Tether stopped"
                                    Toast.makeText(this@TetherSettingsActivity, msg, Toast.LENGTH_SHORT).show()
                                }
                                else -> {
                                    // IDLE or ERROR -> start acceleration
                                    // Register proxy observer before starting
                                    TetherManager.registerProxyObserver()
                                    TetherForegroundService.start(this@TetherSettingsActivity)
                                    Toast.makeText(this@TetherSettingsActivity, "Tether starting...", Toast.LENGTH_SHORT).show()
                                }
                            }
                        }
                        TetherSettingsDesign.Request.ToggleAutoStart -> {
                            val newState = TetherManager.isAutoStartEnabled(this@TetherSettingsActivity)
                            switchView.isChecked = newState
                            // If just enabled and proxy is running, start tether
                            if (newState && com.github.kr328.clash.remote.Remote.broadcasts.clashRunning
                                && TetherManager.state.value.status == TetherStatus.IDLE) {
                                TetherForegroundService.start(this@TetherSettingsActivity)
                            }
                        }
                    }
                }
            }
        }
    }
}
