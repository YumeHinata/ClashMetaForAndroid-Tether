package com.github.kr328.clash.tether

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*

class TetherForegroundService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        // Observe state changes to keep notification text up-to-date
        scope.launch {
            TetherManager.state.collect { state ->
                val text = notificationTextFor(state.status, state.upstreamInterface, state.error)
                updateNotification(text)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val isStopping = intent?.action == ACTION_STOP
        val isAutoStart = intent?.getBooleanExtra("isAutoStart", false) ?: false

        startForeground(NOTIFICATION_ID, buildNotification("Starting tether proxy..."))

        if (isStopping) {
            // Synchronous stop: prevents stopSelf() from triggering onDestroy
            // before TetherManager.stop() completes (race condition fix)
            TetherManager.stop(this)
            stopSelf()
        } else {
            // Register proxy observer BEFORE start so we detect proxy stop while tether is active
            TetherManager.registerProxyObserver()
            scope.launch {
                TetherManager.start(this@TetherForegroundService, isAutoStart = isAutoStart)
            }
        }

        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        TetherManager.unregisterProxyObserver()
        TetherManager.stop(this)
        scope.cancel()
        super.onDestroy()
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        android.util.Log.i("TetherForegroundService", "App removed from recent tasks, cleaning up")
        TetherManager.stop(this)
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    // ------------------------------------------------------------------
    // Notification helpers
    // ------------------------------------------------------------------

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Tether Proxy",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shizuku tether proxy service"
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(text: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Clash Tether Proxy")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_menu_share)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun notificationTextFor(status: TetherStatus, upstream: String, error: String): String = when (status) {
        TetherStatus.CONNECTED -> "Tether proxy active via $upstream"
        TetherStatus.WAITING_HOTSPOT -> "Waiting for WiFi hotspot to turn on..."
        TetherStatus.ERROR -> "Error: ${error.ifEmpty { "Unknown" }}"
        TetherStatus.IDLE -> "Tether proxy idle"
    }

    companion object {
        const val ACTION_STOP = "com.github.kr328.clash.tether.STOP"
        private const val CHANNEL_ID = "tether_proxy"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context, isAutoStart: Boolean = false) {
            val intent = Intent(context, TetherForegroundService::class.java).apply {
                putExtra("isAutoStart", isAutoStart)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, TetherForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}
