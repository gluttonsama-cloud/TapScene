package com.tapscene.recording

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.os.Build
import android.os.IBinder
import com.tapscene.clickplan.ClickPlayback
import com.tapscene.clickplan.ClickConsentActivity

/** A fresh user grant is required. There is no sticky start, boot receiver or saved token. */
class RecordingService : Service() {
    private var receiverRegistered = false
    internal var sessionId: String? = null
        private set
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                RecordingCoordinator.stopFromService(this@RecordingService, RecordingStopReason.ScreenLocked)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screenReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        else registerReceiver(screenReceiver, filter)
        receiverRegistered = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val sessionId = intent.getStringExtra(EXTRA_SESSION_ID)
                if (sessionId == null || !RecordingCoordinator.isPending(sessionId)) {
                    if (!RecordingCoordinator.isOwnedBy(this)) stopSelf(startId)
                    return START_NOT_STICKY
                }
                this.sessionId = sessionId
                try {
                    val manager = getSystemService(NotificationManager::class.java)
                    manager.createNotificationChannel(NotificationChannel(
                        CHANNEL_ID, "本机无声录制", NotificationManager.IMPORTANCE_LOW,
                    ).apply {
                        description = "录制进行时提供持续提示与停止按钮"
                        setShowBadge(false)
                        lockscreenVisibility = Notification.VISIBILITY_SECRET
                    })
                    val notification = notification()
                    if (Build.VERSION.SDK_INT >= 29) {
                        startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
                    } else startForeground(NOTIFICATION_ID, notification)
                    // The foreground notification is established BEFORE getMediaProjection().
                    RecordingCoordinator.startFromService(this, sessionId)
                } catch (_: Exception) {
                    RecordingCoordinator.failServiceStart(sessionId)
                    finishSession()
                }
            }
            ACTION_STOP -> {
                ClickPlayback.stop(this)
                RecordingCoordinator.stopFromService(this, RecordingStopReason.User)
            }
            ACTION_PAUSE -> ClickPlayback.pause()
            else -> if (!RecordingCoordinator.isOwnedBy(this)) finishSession()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        RecordingCoordinator.configurationChanged(this)
    }

    override fun onDestroy() {
        if (receiverRegistered) {
            unregisterReceiver(screenReceiver)
            receiverRegistered = false
        }
        // Best effort only. Recovery relies on the durable journal, not on this callback.
        RecordingCoordinator.stopFromService(this, RecordingStopReason.ServiceDestroyed)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    internal fun finishSession() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    @Suppress("DEPRECATION")
    private fun notification(): Notification {
        val stopIntent = PendingIntent.getService(
            this, 1, Intent(this, RecordingService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("TapScene 正在无声录制")
            .setContentText("仅保存在本机；点击停止结束本段录制")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_SECRET)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(stopIntent)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "停止录制", stopIntent).build())
        if (RecordingCoordinator.isClickSession(sessionId)) {
            val pause = PendingIntent.getService(this, 2, Intent(this, RecordingService::class.java).setAction(ACTION_PAUSE),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val resume = PendingIntent.getActivity(this, 3, Intent(this, ClickConsentActivity::class.java)
                .putExtra("resume", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            builder.setContentText("点击链录屏 · 暂停只阻止后续点；可随时停止")
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_pause, "暂停点击", pause).build())
                .addAction(Notification.Action.Builder(android.R.drawable.ic_media_play, "核对并继续", resume).build())
        }
        return builder.build()
    }

    internal companion object {
        const val ACTION_START = "com.tapscene.recording.START"
        const val ACTION_STOP = "com.tapscene.recording.STOP"
        const val ACTION_PAUSE = "com.tapscene.recording.PAUSE_CLICKS"
        const val EXTRA_SESSION_ID = "sessionId"
        private const val CHANNEL_ID = "tapscene_recording"
        private const val NOTIFICATION_ID = 4102
    }
}
