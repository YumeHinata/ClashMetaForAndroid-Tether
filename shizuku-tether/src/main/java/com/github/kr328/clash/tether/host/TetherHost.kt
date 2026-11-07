package com.github.kr328.clash.tether.host

import android.content.Context

/**
 * Abstraction over the host app (CMFA) internals.
 * The tether module only depends on this interface; the app module supplies
 * the concrete implementation, so upstream CMFA changes touch one adapter file.
 */
interface TetherHost {
    val applicationContext: Context
    val isProxyRunning: Boolean

    fun observeProxyState(callback: ProxyStateCallback)
    fun removeProxyStateObserver(callback: ProxyStateCallback)
}

interface ProxyStateCallback {
    fun onProxyStarted()
    fun onProxyStopped(cause: String?)
}

object TetherHostProvider {
    @Volatile
    private var instance: TetherHost? = null

    fun init(host: TetherHost) {
        instance = host
    }

    val host: TetherHost
        get() = instance ?: error("TetherHost not initialized; call TetherBootstrap.onApplicationCreate first")
}