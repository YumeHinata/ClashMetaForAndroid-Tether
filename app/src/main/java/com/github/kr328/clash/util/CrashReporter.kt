package com.github.kr328.clash.util

import android.content.Context
import com.github.kr328.clash.common.compat.currentProcessName
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object CrashReporter {
    fun init(context: Context) {
        installCrashHandler(context)
        dumpPreviousCrash(context)
    }

    private fun installCrashHandler(context: Context) {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                saveCrashLog(context, thread, throwable)
            } catch (_: Exception) {
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    private fun dumpPreviousCrash(context: Context) {
        try {
            val process = Runtime.getRuntime().exec(
                arrayOf("logcat", "-d", "-s", "AndroidRuntime:E", "DEBUG:V", "Go:E", "tombstoned:E", "crash_dump:E")
            )
            val logcat = process.inputStream.bufferedReader().readText()
            process.waitFor()

            if (logcat.contains("FATAL EXCEPTION") || logcat.contains("signal ") || logcat.contains("Abort message")) {
                val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
                val report = buildString {
                    appendLine("=== Previous Crash (captured on startup) ===")
                    appendLine("Time: $timestamp")
                    appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
                    appendLine("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
                    appendLine()
                    appendLine(logcat)
                }
                saveCrashReport(context, "prev_crash_${timestamp}.txt", report)
            }
        } catch (_: Exception) {
        }
    }

    private fun saveCrashLog(context: Context, thread: Thread, throwable: Throwable) {
        val timestamp = SimpleDateFormat("yyyy-MM-dd_HH-mm-ss", Locale.US).format(Date())
        val procName = (context.applicationContext as? android.app.Application)?.currentProcessName
            ?: context.packageName

        val report = buildString {
            appendLine("=== Crash Report ===")
            appendLine("Time: $timestamp")
            appendLine("Thread: ${thread.name}")
            appendLine("Process: $procName")
            appendLine("Android SDK: ${android.os.Build.VERSION.SDK_INT}")
            appendLine("Device: ${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}")
            appendLine()
            appendLine("=== Exception ===")
            val sw = StringWriter()
            throwable.printStackTrace(PrintWriter(sw))
            appendLine(sw.toString())

            var cause = throwable.cause
            while (cause != null) {
                appendLine("=== Cause ===")
                val csw = StringWriter()
                cause.printStackTrace(PrintWriter(csw))
                appendLine(csw.toString())
                cause = cause.cause
            }

            appendLine("=== Logcat (last 200 lines) ===")
            try {
                val p = Runtime.getRuntime().exec(arrayOf("logcat", "-d", "-t", "200"))
                appendLine(p.inputStream.bufferedReader().readText())
                p.waitFor()
            } catch (e: Exception) {
                appendLine("Failed to dump logcat: ${e.message}")
            }
        }

        saveCrashReport(context, "crash_${timestamp}.txt", report)
    }

    private fun saveCrashReport(context: Context, filename: String, report: String) {
        try {
            val dir = context.getExternalFilesDir(null)
            if (dir != null) {
                File(dir, filename).writeText(report)
            }
        } catch (_: Exception) {
        }

        try {
            context.filesDir.resolve(filename).writeText(report)
        } catch (_: Exception) {
        }

        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/")
                }
                val uri = context.contentResolver.insert(
                    android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values
                )
                uri?.let {
                    context.contentResolver.openOutputStream(it)?.use { out ->
                        out.write(report.toByteArray())
                    }
                }
            } catch (_: Exception) {
            }
        }
    }
}
