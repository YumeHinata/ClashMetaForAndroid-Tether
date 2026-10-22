package com.github.kr328.clash.tether

import android.content.Context
import android.os.Build
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.BufferedWriter
import java.text.SimpleDateFormat
import java.util.*

object TetherLogger {
    private var writer: BufferedWriter? = null
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault())
    
    fun init(context: Context) {
        try {
            val logDir = context.getExternalFilesDir(null) ?: context.filesDir
            if (!logDir.exists()) {
                logDir.mkdirs()
            }
            
            val logFile = File(logDir, "tether_debug.log")
            writer = BufferedWriter(FileWriter(logFile, true))
            log("=== TetherLogger initialized ===")
            log("Log file: ${logFile.absolutePath}")
            log("Android SDK: ${Build.VERSION.SDK_INT}")
        } catch (e: Exception) {
            android.util.Log.e("TetherLogger", "Failed to init file logger", e)
            try {
                val logDir = context.filesDir
                val logFile = File(logDir, "tether_debug.log")
                writer = BufferedWriter(FileWriter(logFile, true))
                log("=== TetherLogger initialized (fallback) ===")
            } catch (e2: Exception) {
                android.util.Log.e("TetherLogger", "Fallback also failed", e2)
            }
        }
    }
    
    fun log(message: String) {
        val timestamp = dateFormat.format(Date())
        val logLine = "[$timestamp] $message"
        
        // Write to file
        try {
            writer?.apply {
                write(logLine)
                newLine()
                flush()
            }
        } catch (e: Exception) {
            android.util.Log.e("TetherLogger", "Failed to write log", e)
        }
        
        // Also write to logcat (info level to avoid noise)
        android.util.Log.i("TetherDebug", message)
    }
    
    fun log(tag: String, message: String) {
        log("[$tag] $message")
    }
    
    fun logError(tag: String, message: String, throwable: Throwable? = null) {
        val errorMsg = if (throwable != null) {
            "$message: ${throwable.javaClass.simpleName}: ${throwable.message}"
        } else {
            message
        }
        log("[$tag] ERROR: $errorMsg")
        
        // Print stack trace to log
        if (throwable != null) {
            val sw = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(sw))
            log("Stack trace: ${sw.toString()}")
        }
    }
    
    fun close() {
        try {
            writer?.close()
            writer = null
        } catch (e: Exception) {
            // Ignore
        }
    }
}
