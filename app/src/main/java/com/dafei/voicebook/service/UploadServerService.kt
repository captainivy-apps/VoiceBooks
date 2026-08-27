package com.dafei.voicebook.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.app.ServiceCompat
import com.dafei.voicebook.MainActivity
import com.dafei.voicebook.R
import com.dafei.voicebook.domain.upload.UploadHttpServer
import com.dafei.voicebook.util.NetworkAddressHelper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Collections

class UploadServerService : Service() {
    private var server: UploadHttpServer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopServer()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startServer()
        }
        return START_NOT_STICKY
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        stopServer()
        stopSelf()
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (server?.isAlive == true) return
        createNotificationChannel()
        val notification = buildNotification(NetworkAddressHelper.buildServerUrl())
        try {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                } else {
                    0
                },
            )
        } catch (e: Exception) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
            ) {
                Log.w(TAG, "Upload server foreground start blocked", e)
            } else {
                Log.e(TAG, "Failed to start upload server foreground", e)
            }
            _serverAddress.value = null
            stopSelf()
            return
        }
        try {
            val uploadServer = UploadHttpServer(applicationContext, sessionDedupMd5s)
            uploadServer.start(NanoTimeout, false)
            server = uploadServer
            _serverAddress.value = NetworkAddressHelper.buildServerUrl()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start upload HTTP server", e)
            _serverAddress.value = null
            stopSelf()
        }
    }

    private fun stopServer() {
        server?.let {
            try {
                it.stop()
            } catch (_: Exception) {}
        }
        server = null
        _serverAddress.value = null
        sessionDedupMd5s.clear()
    }

    private fun buildNotification(address: String?): Notification {
        val openIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val text = address ?: getString(R.string.upload_server_starting)
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.upload_server_notification_title))
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentIntent(openIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.upload_server_notification_channel),
                NotificationManager.IMPORTANCE_LOW,
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    companion object {
        private const val CHANNEL_ID = "upload_server"
        private const val NOTIFICATION_ID = 2001
        private const val NanoTimeout = 5000

        const val ACTION_STOP = "com.dafei.voicebook.UPLOAD_SERVER_STOP"

        private val _serverAddress = MutableStateFlow<String?>(null)
        val serverAddress: StateFlow<String?> = _serverAddress.asStateFlow()

        private val sessionDedupMd5s: MutableSet<String> = Collections.synchronizedSet(mutableSetOf())

        fun removeDedupMd5s(md5s: Collection<String>) {
            if (md5s.isEmpty()) return
            sessionDedupMd5s.removeAll(md5s.toSet())
        }

        fun start(context: Context) {
            try {
                val intent = Intent(context, UploadServerService::class.java)
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    e.javaClass.name == "android.app.ForegroundServiceStartNotAllowedException"
                ) {
                    Log.w(TAG, "Upload server start blocked while app is in background", e)
                } else {
                    Log.e(TAG, "Failed to start upload server", e)
                }
            }
        }

        fun stop(context: Context) {
            val intent = Intent(context, UploadServerService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }

        fun isRunning(): Boolean = _serverAddress.value != null

        private const val TAG = "UploadServerService"
    }
}
