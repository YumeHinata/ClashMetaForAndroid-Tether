package com.github.kr328.clash.tether.shizuku

import android.content.Context
import android.content.pm.PackageManager
import rikka.shizuku.Shizuku

sealed interface ShizukuState {
    data object NotInstalled : ShizukuState
    data object NotRunning : ShizukuState
    data object PermissionRequired : ShizukuState
    data class Ready(val uid: Int, val isRoot: Boolean) : ShizukuState
}

object ShizukuGate {
    private const val PERMISSION_REQUEST_CODE = 4001

    fun resolveState(context: Context): ShizukuState {
        if (!isShizukuInstalled(context)) return ShizukuState.NotInstalled

        return try {
            when {
                !Shizuku.pingBinder() -> ShizukuState.NotRunning
                !hasPermission() -> ShizukuState.PermissionRequired
                else -> ShizukuState.Ready(Shizuku.getUid(), Shizuku.getUid() == 0)
            }
        } catch (e: Exception) {
            ShizukuState.NotRunning
        }
    }

    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (e: Exception) {
            false
        }
    }

    fun requestPermission(requestCode: Int = PERMISSION_REQUEST_CODE) {
        try {
            Shizuku.requestPermission(requestCode)
        } catch (_: Exception) {
        }
    }

    private fun isShizukuInstalled(context: Context): Boolean {
        return try {
            context.packageManager.getPackageInfo("moe.shizuku.privileged.api", 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }
}
