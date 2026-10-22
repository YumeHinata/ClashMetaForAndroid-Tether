package com.github.kr328.clash.tether.control

import android.content.Context
import java.lang.reflect.Method

object TetheringDiagnostics {
    
    fun listTetheringManagerMethods(context: Context): List<String> {
        val tetheringManager = getTetheringManager(context) ?: return emptyList()
        
        return tetheringManager::class.java.declaredMethods.map { method ->
            "${method.name}(${method.parameterTypes.joinToString { it.simpleName }})"
        }.sorted()
    }
    
    fun hasSetPreferTestNetworks(context: Context): Boolean {
        val tetheringManager = getTetheringManager(context) ?: return false
        
        return try {
            tetheringManager::class.java.getDeclaredMethod(
                "setPreferTestNetworks", 
                Boolean::class.javaPrimitiveType
            )
            true
        } catch (e: NoSuchMethodException) {
            false
        }
    }
    
    private fun getTetheringManager(context: Context): Any? {
        return try {
            val clazz = Class.forName("android.net.TetheringManager")
            context.getSystemService("tethering")
        } catch (_: Exception) {
            null
        }
    }
}
