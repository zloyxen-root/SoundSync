package com.soundsync.app.service

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
import com.soundsync.app.R
import com.soundsync.app.data.sync.SyncEngine
import com.soundsync.app.data.sync.SyncState
import com.soundsync.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class SyncForegroundService : Service() {

    private val serviceScope = CoroutineScope(Dispatchers.Main + Job())
    private lateinit var syncEngine: SyncEngine
    private lateinit var notificationManager: NotificationManager

    companion object {
        const val CHANNEL_ID = "soundsync_service_channel"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START_SYNC = "com.soundsync.app.START_SYNC"
        const val ACTION_STOP_SYNC = "com.soundsync.app.STOP_SYNC"

        fun startSync(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_START_SYNC
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stopSync(context: Context) {
            val intent = Intent(context, SyncForegroundService::class.java).apply {
                action = ACTION_STOP_SYNC
            }
            context.startService(intent)
        }
    }

    override fun onCreate() {
        super.onCreate()
        syncEngine = SyncEngine(applicationContext)
        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_SYNC -> {
                syncEngine.cancelSync()
                stopForeground(true)
                stopSelf()
            }
            ACTION_START_SYNC -> {
                startForeground(NOTIFICATION_ID, buildNotification("Подготовка к синхронизации...", 0, 0))
                observeAndExecute()
            }
        }
        return START_NOT_STICKY
    }

    private fun observeAndExecute() {
        // Collect state updates and update notification
        serviceScope.launch {
            syncEngine.syncState.collectLatest { state ->
                when (state) {
                    is SyncState.FetchingLikes -> {
                        updateNotification("Получение лайков: ${state.loadedCount} найдено", 0, 0, indeterminate = true)
                    }
                    is SyncState.Diffing -> {
                        updateNotification("Сверка треков: +${state.toDownloadCount}, -${state.toDeleteCount}", 0, 0, indeterminate = true)
                    }
                    is SyncState.Downloading -> {
                        val progress = if (state.totalTracksToDownload > 0) {
                            (state.currentTrackIndex * 100) / state.totalTracksToDownload
                        } else 0
                        updateNotification(
                            "Загрузка (${state.currentTrackIndex}/${state.totalTracksToDownload}): ${state.currentTrackTitle}",
                            progress,
                            100,
                            indeterminate = false
                        )
                    }
                    is SyncState.Completed -> {
                        updateNotification(
                            "Синхронизация завершена: +${state.addedCount}, -${state.deletedCount}",
                            100,
                            100,
                            indeterminate = false
                        )
                        stopForeground(false)
                        stopSelf()
                    }
                    is SyncState.Error -> {
                        updateNotification("Ошибка: ${state.message}", 0, 0, indeterminate = false)
                        stopForeground(false)
                        stopSelf()
                    }
                    SyncState.Idle -> {}
                }
            }
        }

        // Run sync
        serviceScope.launch(Dispatchers.IO) {
            syncEngine.performSync()
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.sync_channel_name),
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = getString(R.string.sync_channel_description)
                setShowBadge(false)
            }
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(
        content: String,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean = false
    ): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("SoundSync")
            .setContentText(content)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setProgress(maxProgress, progress, indeterminate)
            .build()
    }

    private fun updateNotification(
        content: String,
        progress: Int,
        maxProgress: Int,
        indeterminate: Boolean = false
    ) {
        val notif = buildNotification(content, progress, maxProgress, indeterminate)
        notificationManager.notify(NOTIFICATION_ID, notif)
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }
}
