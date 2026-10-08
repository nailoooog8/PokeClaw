// Copyright 2026 PokeClaw (agents.io). All rights reserved.
// Licensed under the Apache License, Version 2.0.

package io.agents.pokeclaw.service

import android.Manifest
import android.app.*
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import io.agents.pokeclaw.AppCapabilityCoordinator
import io.agents.pokeclaw.R
import io.agents.pokeclaw.ServiceBindingState
import io.agents.pokeclaw.utils.XLog

/**
 * Foreground service for active task / monitor notifications only.
 */
class ForegroundService : Service() {

    companion object {
        private const val TAG = "ForegroundService"
        private const val MONITOR_HEALTH_POLL_MS = 5_000L
        const val CHANNEL_ID = "PokeClaw_foreground_channel"
        const val NOTIFICATION_ID = 1001
        private const val EXTRA_TITLE = "extra_title"
        private const val EXTRA_TEXT = "extra_text"
        private const val DEFAULT_TASK_TITLE = "PokeClaw · Task in progress"
        private const val DEFAULT_TASK_TEXT = "Running task..."
        private const val DEFAULT_MONITOR_TITLE = "PokeClaw · Monitoring"
        private const val DEGRADED_MONITOR_TITLE = "PokeClaw · Monitoring paused"
        private const val KEY_KEEPALIVE_PERSISTENT = "KEY_KEEPALIVE_PERSISTENT"

        private enum class ForegroundMode {
            IDLE,
            TASK,
            MONITOR,
        }

        @Volatile
        private var _isRunning = false

        @Volatile
        private var _mode = ForegroundMode.IDLE

        /**
         * Check whether the foreground service is running
         */
        fun isRunning(): Boolean = _isRunning

        /**
         * Update the foreground notification with task progress text.
         * Safe to call from any thread — posts to NotificationManager directly.
         */
        fun updateTaskStatus(context: Context, statusText: String) {
            _mode = ForegroundMode.TASK
            showNotification(context, DEFAULT_TASK_TITLE, statusText)
        }

        /**
         * Show monitor state if auto-reply is active, otherwise stop the foreground service.
         */
        fun resetToIdle(context: Context) {
            syncToBackgroundState(context)
        }

        fun showMonitorStatus(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (!manager.isEnabled || manager.monitoredContacts.isEmpty()) {
                _mode = ForegroundMode.IDLE
                stop(context)
                return false
            }
            _mode = ForegroundMode.MONITOR
            val capabilities = AppCapabilityCoordinator.snapshot(context)
            if (capabilities.notificationAccessState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.notificationAccessState == ServiceBindingState.CONNECTING) {
                        "Notification Access reconnecting…"
                    } else {
                        "Notification Access disconnected"
                    }
                )
            }
            if (capabilities.accessibilityState != ServiceBindingState.READY) {
                return showNotification(
                    context,
                    DEGRADED_MONITOR_TITLE,
                    if (capabilities.accessibilityState == ServiceBindingState.CONNECTING) {
                        "Accessibility reconnecting…"
                    } else {
                        "Accessibility disconnected"
                    }
                )
            }
            val contacts = manager.monitoredContacts.toList()
            val text = when (contacts.size) {
                0 -> "Monitoring in background"
                1 -> "Monitoring ${contacts.first()}"
                else -> "Monitoring ${contacts.size} chats"
            }
            return showNotification(context, DEFAULT_MONITOR_TITLE, text)
        }

        fun syncToBackgroundState(context: Context): Boolean {
            val manager = AutoReplyManager.getInstance()
            if (manager.isEnabled && manager.monitoredContacts.isNotEmpty()) {
                return showMonitorStatus(context)
            }
            val keepText = when {
                // User opted for a persistent foreground service ("常驻后台")
                isPersistentKeepAliveEnabled() -> "Keep-alive active"
                // Keep alive while any channel (Telegram/Discord/WeChat) holds a live
                // connection, otherwise vivo-style ROMs freeze the process and drop
                // the polling/socket.
                hasConnectedChannels() -> "Channels connected"
                // Accessibility is the auto-reply infrastructure: keep the process
                // alive whenever it's enabled so the system doesn't kill the binding.
                isAccessibilityEnabled(context) -> "Keep-alive active"
                else -> null
            }
            _mode = ForegroundMode.IDLE
            return if (keepText != null) {
                showNotification(context, DEFAULT_MONITOR_TITLE, keepText)
            } else {
                stop(context)
                false
            }
        }

        fun isPersistentKeepAliveEnabled(): Boolean {
            // Default off: this key is never written on existing installs, so a
            // default of true made every install look like it had opted in and
            // kept isKeepAliveNeeded() permanently true, so stop() never ran.
            return runCatching { io.agents.pokeclaw.utils.KVUtils.getBoolean(KEY_KEEPALIVE_PERSISTENT, false) }
                .getOrDefault(false)
        }

        fun setPersistentKeepAliveEnabled(enabled: Boolean) {
            io.agents.pokeclaw.utils.KVUtils.putBoolean(KEY_KEEPALIVE_PERSISTENT, enabled)
        }

        fun hasConnectedChannels(): Boolean {
            return runCatching { io.agents.pokeclaw.channel.ChannelManager.hasConnectedChannel() }
                .getOrDefault(false)
        }

        private fun isAccessibilityEnabled(context: Context): Boolean {
            return runCatching { ClawAccessibilityService.isEnabledInSettings(context) }
                .getOrDefault(false)
        }

        private fun isKeepAliveNeeded(context: Context): Boolean {
            return isPersistentKeepAliveEnabled() || hasConnectedChannels() ||
                isAccessibilityEnabled(context)
        }

        private fun showNotification(context: Context, title: String, text: String): Boolean {
            if (!hasNotificationPermission(context)) {
                return false
            }

            try {
                if (_isRunning) {
                    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.notify(NOTIFICATION_ID, buildNotification(context, title, text))
                    return true
                }
                start(context, title, text)
                return true
            } catch (e: Exception) {
                XLog.w(TAG, "Foreground notification update failed", e)
                return false
            }
        }

        /**
         * Start the foreground service
         * @param context Context
         * @return true if started successfully, false if notification permission is missing
         */
        fun start(
            context: Context,
            title: String = context.getString(R.string.notification_content_title),
            text: String = context.getString(R.string.notification_content_text)
        ): Boolean {
            // Android 13+ requires notification permission check
            if (!hasNotificationPermission(context)) {
                return false
            }

            return try {
                val intent = Intent(context, ForegroundService::class.java).apply {
                    putExtra(EXTRA_TITLE, title)
                    putExtra(EXTRA_TEXT, text)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
                true
            } catch (e: Exception) {
                // API 31+ throws ForegroundServiceStartNotAllowedException when the
                // app is in the background. Surface it instead of only logging,
                // but only when we still hold an Activity to show it on.
                XLog.w(TAG, "Foreground service start blocked or failed", e)
                if (context !is android.app.Application) {
                    runCatching {
                        Toast.makeText(
                            context,
                            "无法启动前台服务，任务保活未生效",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }
                false
            }
        }

        private fun hasNotificationPermission(context: Context): Boolean {
            return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        }

        fun stop(context: Context) {
            _mode = ForegroundMode.IDLE
            val intent = Intent(context, ForegroundService::class.java)
            context.stopService(intent)
            runCatching {
                val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.cancel(NOTIFICATION_ID)
            }
        }

        private fun buildNotification(context: Context, title: String, text: String): Notification {
            val intent = Intent(context, io.agents.pokeclaw.ui.chat.ComposeChatActivity::class.java)
            val pendingIntent = PendingIntent.getActivity(
                context,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )

            return NotificationCompat.Builder(context, CHANNEL_ID)
                .setContentTitle(title)
                .setContentText(text)
                .setSmallIcon(R.drawable.ic_launcher)
                .setContentIntent(pendingIntent)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setAutoCancel(false)
                .build()
        }
    }

    private val healthHandler = Handler(Looper.getMainLooper())
    private val healthRunnable = object : Runnable {
        override fun run() {
            if (!_isRunning) return
            if (_mode == ForegroundMode.MONITOR) {
                ForegroundService.syncToBackgroundState(applicationContext)
            } else if (_mode == ForegroundMode.IDLE && !isKeepAliveNeeded(applicationContext)) {
                // Keepalive no longer needed: persistent flag off, last channel
                // dropped and accessibility disabled
                _mode = ForegroundMode.IDLE
                stop(applicationContext)
            }
            healthHandler.postDelayed(this, MONITOR_HEALTH_POLL_MS)
        }
    }

    override fun onCreate() {
        super.onCreate()
        _isRunning = true
        createNotificationChannel()
        if (hasNotificationPermission(this)) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(this, DEFAULT_TASK_TITLE, DEFAULT_TASK_TEXT)
            )
        } else {
            stopSelf()
            return
        }
        healthHandler.post(healthRunnable)
    }

    override fun onDestroy() {
        super.onDestroy()
        _isRunning = false
        healthHandler.removeCallbacksAndMessages(null)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val notification = createNotification(intent)
        startForeground(NOTIFICATION_ID, notification)
        if (intent == null) {
            // System restarted the service after a process kill (START_STICKY).
            // Re-sync to actual state: keep running only if monitoring or a
            // channel still needs it, otherwise shut down cleanly.
            _mode = ForegroundMode.IDLE
            if (!syncToBackgroundState(applicationContext)) {
                stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.notification_channel_description)
                setShowBadge(false)
            }
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(intent: Intent?): Notification {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: DEFAULT_TASK_TITLE
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: DEFAULT_TASK_TEXT
        return buildNotification(this, title, text)
    }
}
