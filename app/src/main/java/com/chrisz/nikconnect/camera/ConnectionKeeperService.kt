package com.chrisz.nikconnect.camera

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
import com.chrisz.nikconnect.MainActivity
import com.chrisz.nikconnect.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * 连接保活前台服务：只要持有 PTP 连接就保持前台通知，避免退后台时进程被冻结、
 * Wi-Fi socket 被系统回收导致相机端断开。连接断开或用户退出时 stopSelf。
 */
class ConnectionKeeperService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: getString(R.string.keeper_default_title)
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: getString(R.string.keeper_default_text)
        startForegroundCompat(title, text)
        _running.value = true
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        _running.value = false
        super.onDestroy()
    }

    private fun startForegroundCompat(title: String, text: String) {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                getString(R.string.keeper_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_camera)
            .setContentIntent(contentIntent)
            .setOngoing(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val CHANNEL_ID = "connection_keeper"
        private const val NOTIFICATION_ID = 1
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        private val _running = MutableStateFlow(false)

        /** UI 可观察的服务运行状态（true = 前台服务持有中）。 */
        val running: StateFlow<Boolean> = _running

        /** 连接成功后调用：启动保活服务（已运行则仅更新通知文案）。 */
        fun start(context: Context, title: String, text: String) {
            val intent = Intent(context, ConnectionKeeperService::class.java)
                .putExtra(EXTRA_TITLE, title)
                .putExtra(EXTRA_TEXT, text)
            context.startForegroundService(intent)
        }

        /** 断开/退出时调用：停止保活服务。 */
        fun stop(context: Context) {
            context.stopService(Intent(context, ConnectionKeeperService::class.java))
        }
    }
}
