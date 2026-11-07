package com.github.kr328.clash.tether.adapter

import android.content.Context
import com.github.kr328.clash.remote.Broadcasts
import com.github.kr328.clash.remote.Remote
import com.github.kr328.clash.tether.host.ProxyStateCallback
import com.github.kr328.clash.tether.host.TetherHost
import java.util.UUID

/**
 * The single place where tether touches CMFA internals.
 * If upstream CMFA changes Global/Remote/Broadcasts, only this file needs updating.
 */
class CmfaTetherHost(private val appContext: Context) : TetherHost {

    override val applicationContext: Context
        get() = appContext

    override val isProxyRunning: Boolean
        get() = Remote.broadcasts.clashRunning

    private val adapters = mutableMapOf<ProxyStateCallback, Broadcasts.Observer>()

    override fun observeProxyState(callback: ProxyStateCallback) {
        val observer = object : Broadcasts.Observer {
            override fun onServiceRecreated() {}
            override fun onProfileChanged() {}
            override fun onProfileUpdateCompleted(uuid: UUID?) {}
            override fun onProfileUpdateFailed(uuid: UUID?, reason: String?) {}
            override fun onProfileLoaded() {}
            override fun onStarted() = callback.onProxyStarted()
            override fun onStopped(cause: String?) = callback.onProxyStopped(cause)
        }
        synchronized(adapters) { adapters[callback] = observer }
        Remote.broadcasts.addObserver(observer)
    }

    override fun removeProxyStateObserver(callback: ProxyStateCallback) {
        val observer = synchronized(adapters) { adapters.remove(callback) } ?: return
        Remote.broadcasts.removeObserver(observer)
    }
}