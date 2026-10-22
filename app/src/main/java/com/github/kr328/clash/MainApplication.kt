package com.github.kr328.clash

import android.app.Application
import android.content.Context
import com.github.kr328.clash.common.Global
import com.github.kr328.clash.common.compat.currentProcessName
import com.github.kr328.clash.common.log.Log
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.service.util.sendServiceRecreated
import com.github.kr328.clash.util.CrashReporter
import com.github.kr328.clash.util.clashDir
import java.io.File
import java.io.FileOutputStream

@Suppress("unused")
class MainApplication : Application() {

    override fun attachBaseContext(base: Context?) {
        super.attachBaseContext(base)

        Global.init(this)
    }

    override fun onCreate() {
        super.onCreate()

        val processName = currentProcessName
        extractGeoFiles()

        Log.d("Process $processName started")

        if (BuildConfig.ENABLE_CRASH_REPORTER) {
            CrashReporter.init(this)
        }

        if (processName == packageName) {
            Remote.launch()
            autoStartTetherIfNeeded()
        } else {
            sendServiceRecreated()
        }
    }

    private fun autoStartTetherIfNeeded() {
        try {
            val controller = com.github.kr328.clash.tether.TetherController
            
            // Check if auto-start is enabled
            if (!controller.isAutoStartEnabled(this)) return

            // Register proxy observer to catch future proxy start events
            com.github.kr328.clash.tether.TetherManager.registerProxyObserver()

            // If proxy is already running, enable tether via controller
            if (Remote.broadcasts.clashRunning) {
                val compat = com.github.kr328.clash.tether.testnetwork.CompatibilityCheck.probe(this)
                if (!compat.isSupported) {
                    Log.d("Tether auto-start skipped: device not supported")
                    return
                }
                Log.d("Tether auto-starting (proxy already running)...")
                controller.setEnabled(this, true)
            } else {
                // clashRunning may be false because broadcasts.register() hasn't completed yet.
                // Schedule a delayed re-check to catch the case where proxy is already running.
                Log.d("Tether auto-start: clashRunning=false, scheduling delayed re-check")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    try {
                        if (Remote.broadcasts.clashRunning && 
                            com.github.kr328.clash.tether.TetherManager.state.value.status == 
                                com.github.kr328.clash.tether.TetherStatus.IDLE) {
                            Log.d("Tether auto-start: delayed re-check succeeded, starting tether")
                            controller.setEnabled(this@MainApplication, true)
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

    private fun extractGeoFiles() {
        clashDir.mkdirs()

        val updateDate = packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        val geoipFile = File(clashDir, "geoip.metadb")
        if (geoipFile.exists() && geoipFile.lastModified() < updateDate) {
            geoipFile.delete()
        }
        if (!geoipFile.exists()) {
            FileOutputStream(geoipFile).use {
                assets.open("geoip.metadb").copyTo(it)
            }
        }

        val geositeFile = File(clashDir, "geosite.dat")
        if (geositeFile.exists() && geositeFile.lastModified() < updateDate) {
            geositeFile.delete()
        }
        if (!geositeFile.exists()) {
            FileOutputStream(geositeFile).use {
                assets.open("geosite.dat").copyTo(it)
            }
        }

        val asnFile = File(clashDir, "ASN.mmdb")
        if (asnFile.exists() && asnFile.lastModified() < updateDate) {
            asnFile.delete()
        }
        if (!asnFile.exists()) {
            FileOutputStream(asnFile).use {
                assets.open("ASN.mmdb").copyTo(it)
            }
        }

        val bundleMRSFile = File(clashDir, "BundleMRS.7z")
        if (bundleMRSFile.exists() && bundleMRSFile.lastModified() < updateDate) {
            bundleMRSFile.delete()
        }
        if (!bundleMRSFile.exists()) {
            FileOutputStream(bundleMRSFile).use {
                assets.open("BundleMRS.7z").copyTo(it)
            }
        }
    }

    fun finalize() {
        Global.destroy()
    }
}
