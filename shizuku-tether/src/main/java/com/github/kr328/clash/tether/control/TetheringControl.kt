package com.github.kr328.clash.tether.control

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit

object TetheringPreferenceApi {

    fun setPreferTestNetworks(context: Context, enable: Boolean) {
        val tetheringManager = getTetheringManager(context)
            ?: throw IllegalStateException("TetheringManager not available")

        val method = tetheringManager::class.java
            .getDeclaredMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType)
        method.isAccessible = true
        method.invoke(tetheringManager, enable)
    }

    private fun getTetheringManager(context: Context): Any? {
        return try {
            val clazz = Class.forName("android.net.TetheringManager")
            val method = Context::class.java.getMethod("getSystemService", String::class.java)
            method.invoke(context, "tethering")
        } catch (_: Exception) {
            null
        }
    }
}

object DownstreamControl {
    private const val TETHERING_WIFI = 0
    private const val STOP_SETTLE_MS = 3_000L
    private const val START_TIMEOUT_MS = 15_000L
    private const val SUCCESS = "onTetheringStarted"

    fun startWifiTethering(context: Context): Pair<Boolean, String> {
        val viaTethering = startViaTetheringManager(context)
        if (viaTethering.first) return viaTethering

        val viaWifiManager = startViaWifiManager(context)
        return when {
            viaWifiManager.first -> viaWifiManager
            else -> false to "tethering[${viaTethering.second}]; wifiManager[${viaWifiManager.second}]"
        }
    }

    fun stopWifiTethering(context: Context) {
        val tetheringManager = getTetheringManager(context) ?: return

        try {
            val method = tetheringManager::class.java
                .getMethod("stopTethering", Int::class.javaPrimitiveType)
            method.invoke(tetheringManager, TETHERING_WIFI)
            Thread.sleep(STOP_SETTLE_MS)
        } catch (_: Exception) {
        }
    }

    private fun startViaWifiManager(context: Context): Pair<Boolean, String> = runCatching {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE)
            ?: return false to "wifi service unavailable"

        val configClass = Class.forName("android.net.wifi.SoftApConfiguration")
        val method = wifiManager.javaClass.getMethod("startTetheredHotspot", configClass)
        val accepted = method.invoke(wifiManager, null) as? Boolean ?: false

        when {
            accepted -> true to "startTetheredHotspot accepted"
            else -> false to "startTetheredHotspot returned false"
        }
    }.getOrElse { failure ->
        false to "startTetheredHotspot: ${failure.cause?.message ?: failure.message}"
    }

    private fun startViaTetheringManager(context: Context): Pair<Boolean, String> {
        val exempt = startTetheringRequest(context, isExemptFromEntitlement = true)
        if (exempt.first) return exempt

        val plain = startTetheringRequest(context, isExemptFromEntitlement = false)
        return when {
            plain.first -> plain
            else -> false to "exempt[${exempt.second}]; plain[${plain.second}]"
        }
    }

    private fun startTetheringRequest(context: Context, isExemptFromEntitlement: Boolean): Pair<Boolean, String> {
        val request = runCatching { buildTetheringRequest(isExemptFromEntitlement) }
            .getOrElse { return false to "buildTetheringRequest: ${it.message}" }

        val latch = CountDownLatch(1)
        val result = arrayOf("no callback")

        val callback = createStartCallback(latch, result)
            ?: return false to "could not build StartTetheringCallback proxy"

        return runCatching {
            invokeStartTethering(context, request, callback)
            val didComplete = latch.await(START_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            val didSucceed = didComplete && result[0] == SUCCESS
            didSucceed to result[0]
        }.getOrElse { false to "startTethering: ${it.message}" }
    }

    private fun buildTetheringRequest(isExemptFromEntitlement: Boolean): Any {
        val builderClass = Class.forName("android.net.TetheringManager\$TetheringRequest\$Builder")
        val builder = builderClass
            .getConstructor(Int::class.javaPrimitiveType)
            .newInstance(TETHERING_WIFI)

        if (isExemptFromEntitlement) {
            runCatching {
                builderClass
                    .getDeclaredMethod("setExemptFromEntitlementCheck", Boolean::class.java)
                    .invoke(builder, true)
            }
        }
        return builderClass.getMethod("build").invoke(builder)!!
    }

    private fun invokeStartTethering(context: Context, request: Any, callback: Any) {
        val tetheringManager = getTetheringManager(context)
            ?: error("tethering service unavailable")
        
        val requestClass = Class.forName("android.net.TetheringManager\$TetheringRequest")
        val callbackClass = Class.forName("android.net.TetheringManager\$StartTetheringCallback")
        val method = tetheringManager::class.java.getMethod(
            "startTethering",
            requestClass,
            Executor::class.java,
            callbackClass,
        )
        method.invoke(tetheringManager, request, mainExecutor(), callback)
    }

    private fun createStartCallback(latch: CountDownLatch, result: Array<String>): Any? =
        runCatching {
            val callbackClass =
                Class.forName("android.net.TetheringManager\$StartTetheringCallback")

            java.lang.reflect.Proxy.newProxyInstance(
                callbackClass.classLoader,
                arrayOf(callbackClass),
            ) { _, method, args ->
                recordCallback(method.name, args, result, latch)
                null
            }
        }.getOrNull()

    private fun recordCallback(
        name: String,
        args: Array<Any?>?,
        result: Array<String>,
        latch: CountDownLatch,
    ) {
        when (name) {
            "onTetheringStarted" -> {
                result[0] = SUCCESS
                latch.countDown()
            }

            "onTetheringFailed" -> {
                val code = args?.firstOrNull() as? Int
                result[0] = "onTetheringFailed($code)"
                latch.countDown()
            }
        }
    }

    private fun mainExecutor(): Executor {
        val handler = Handler(Looper.getMainLooper())
        return Executor { command -> handler.post(command) }
    }

    private fun getTetheringManager(context: Context): Any? {
        return try {
            Context::class.java.getMethod("getSystemService", String::class.java)
                .invoke(context, "tethering")
        } catch (_: Exception) {
            null
        }
    }
}
