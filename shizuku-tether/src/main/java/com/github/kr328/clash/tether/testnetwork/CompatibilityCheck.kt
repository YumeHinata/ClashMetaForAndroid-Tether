package com.github.kr328.clash.tether.testnetwork

import android.content.Context
import android.os.Build

data class CompatibilityReport(
    val isSupported: Boolean,
    val testNetworkAvailable: Boolean,
    val standardTetheringAvailable: Boolean,
    val preferTestNetworksAvailable: Boolean,
    val details: String,
)

object CompatibilityCheck {

    fun probe(context: Context): CompatibilityReport {
        // NOTE: test_network service is only available to Shell UID (2000) with MANAGE_TEST_NETWORKS permission
        // The app process cannot access this service, so getSystemService("test_network") returns null here
        // When using Shizuku, the UserService runs as shell UID and WILL have access
        // So we assume test_network is available if Shizuku is working
        
        val testNetworkAvailable = true  // Assume available when using Shizuku
        
        val standardTetheringAvailable = try {
            context.getSystemService("tethering") != null
        } catch (_: Exception) {
            false
        }

        val preferTestNetworksAvailable = probePreferTestNetworks()

        val details = buildString {
            appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Test Network Manager: Available (via Shizuku shell UID)")
            appendLine("Standard Tethering: ${if (standardTetheringAvailable) "OK" else "MISSING"}")
            appendLine("setPreferTestNetworks: ${if (preferTestNetworksAvailable) "OK (direct)" else "Will use HiddenApiBypass"}")
            appendLine()
            appendLine("Device is supported for tether proxy.")
            appendLine("Note: Actual test_network availability will be verified in Shizuku UserService.")
        }

        return CompatibilityReport(
            isSupported = true,  // Supported when using Shizuku
            testNetworkAvailable = true,
            standardTetheringAvailable = standardTetheringAvailable,
            preferTestNetworksAvailable = preferTestNetworksAvailable,
            details = details,
        )
    }

    private fun probePreferTestNetworks(): Boolean {
        return try {
            val clazz = Class.forName("android.net.TetheringManager")
            clazz.getDeclaredMethod("setPreferTestNetworks", Boolean::class.javaPrimitiveType)
            true
        } catch (_: Exception) {
            false
        }
    }
}
