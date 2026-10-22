package com.github.kr328.clash.tether.shizuku

import android.content.ComponentName
import android.content.ServiceConnection
import android.os.Build
import android.os.IBinder
import com.github.kr328.clash.shizukutether.BuildConfig
import com.github.kr328.clash.tether.ITetherService
import rikka.shizuku.Shizuku

class TetherClient {
    private val serviceTag = "clash-tether"
    private var connection: ServiceConnection? = null
    private var binder: ITetherService? = null

    val isConnected: Boolean get() = binder != null
    val service: ITetherService? get() = binder

    fun bind(
        context: android.content.Context,
        onConnected: (ITetherService) -> Unit,
        onDisconnected: () -> Unit,
    ) {
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder) {
                val tether = ITetherService.Stub.asInterface(service)
                binder = tether
                
                // Pass our PID to Shizuku side for watchdog monitoring (interlock)
                val myPid = android.os.Process.myPid().toString()
                try {
                    tether.init(myPid)
                    android.util.Log.i("TetherClient", "init() called with PID=$myPid")
                } catch (e: Exception) {
                    android.util.Log.w("TetherClient", "init() failed: ${e.message}")
                }
                
                onConnected(tether)
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binder = null
                onDisconnected()
            }
        }
        connection = conn

        val args = Shizuku.UserServiceArgs(
            ComponentName(
                context.packageName,
                "com.github.kr328.clash.tether.shizuku.TetherShellService",
            ),
        )
            .daemon(false)  // Changed to false so service stops when unbound
            .tag(serviceTag)
            .processNameSuffix("tether")
            .debuggable(BuildConfig.DEBUG)
            .version(CONTRACT_VERSION)

        Shizuku.bindUserService(args, conn)
    }

    fun unbind(context: android.content.Context) {
        android.util.Log.i("TetherClient", "unbind() called")

        // Step 1: Try to stop tethering in shell process (best-effort)
        val serviceRef = binder
        if (serviceRef != null) {
            val stopFuture = java.util.concurrent.CompletableFuture.runAsync {
                try {
                    serviceRef.stopTethering()
                    android.util.Log.i("TetherClient", "stopTethering() succeeded")
                } catch (e: Exception) {
                    android.util.Log.w("TetherClient", "stopTethering() failed: ${e.message}")
                }
            }
            try {
                stopFuture.get(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: java.util.concurrent.TimeoutException) {
                android.util.Log.w("TetherClient", "stopTethering() timed out after 5s")
                stopFuture.cancel(true)
            } catch (e: Exception) {
                android.util.Log.w("TetherClient", "stopTethering() error: ${e.message}")
            }
        }

        // Step 2: Always unbind the service (this kills the shell process with daemon(false))
        val conn = connection
        connection = null
        binder = null

        if (conn != null) {
            try {
                val args = Shizuku.UserServiceArgs(
                    ComponentName(
                        context.packageName,
                        "com.github.kr328.clash.tether.shizuku.TetherShellService",
                    ),
                )
                    .daemon(false)
                    .tag(serviceTag)
                    .processNameSuffix("tether")
                    .debuggable(BuildConfig.DEBUG)
                    .version(CONTRACT_VERSION)

                Shizuku.unbindUserService(args, conn, true)
                android.util.Log.i("TetherClient", "unbindUserService() completed")
            } catch (e: Exception) {
                android.util.Log.e("TetherClient", "unbindUserService() failed: ${e.message}")
            }
        }
    }

    companion object {
        const val CONTRACT_VERSION = 5  // v5: added init(parentPid) for interlock watchdog
    }
}
