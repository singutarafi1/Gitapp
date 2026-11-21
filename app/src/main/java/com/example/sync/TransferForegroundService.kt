package com.example.sync

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
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class TransferForegroundService : Service() {

  private var wakeLock: PowerManager.WakeLock? = null

  override fun onCreate() {
    super.onCreate()
    createNotificationChannel()
    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
    wakeLock =
      pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "GitFiles::TransferWakeLock").apply {
        setReferenceCounted(false)
        acquire(60 * 60 * 1000L) // Safety 60-minute max timeout
      }
  }

  override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
    when (intent?.action) {
      ACTION_CANCEL -> {
        _cancelRequests.tryEmit(Unit)
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_STOP -> {
        stopSelf()
        return START_NOT_STICKY
      }
      ACTION_UPDATE -> {
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "GitFiles"
        val detail = intent.getStringExtra(EXTRA_DETAIL).orEmpty()
        val completed = intent.getIntExtra(EXTRA_COMPLETED, 0)
        val total = intent.getIntExtra(EXTRA_TOTAL, 0)
        val notification = buildNotification(title, detail, completed, total)
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
      }
      else -> {
        val title = intent?.getStringExtra(EXTRA_TITLE) ?: "GitFiles transfer"
        val detail = intent?.getStringExtra(EXTRA_DETAIL).orEmpty()
        val completed = intent?.getIntExtra(EXTRA_COMPLETED, 0) ?: 0
        val total = intent?.getIntExtra(EXTRA_TOTAL, 0) ?: 0
        val notification = buildNotification(title, detail, completed, total)
        val fgType =
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
          } else {
            0
          }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, fgType)
      }
    }
    return START_NOT_STICKY
  }

  override fun onDestroy() {
    try {
      if (wakeLock?.isHeld == true) {
        wakeLock?.release()
      }
    } catch (_: Exception) {}
    wakeLock = null
    super.onDestroy()
  }

  override fun onBind(intent: Intent?): IBinder? = null

  private fun createNotificationChannel() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      val channel =
        NotificationChannel(
            CHANNEL_ID,
            "File Transfers",
            NotificationManager.IMPORTANCE_LOW,
          )
          .apply {
            description = "Keeps GitHub uploads and downloads active when screen is off"
            setShowBadge(false)
          }
      val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
      nm.createNotificationChannel(channel)
    }
  }

  private fun buildNotification(
    title: String,
    detail: String,
    completed: Int,
    total: Int,
  ): Notification {
    val openIntent =
      Intent(this, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
      }
    val openPending =
      PendingIntent.getActivity(
        this,
        0,
        openIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val cancelIntent =
      Intent(this, TransferForegroundService::class.java).apply {
        action = ACTION_CANCEL
      }
    val cancelPending =
      PendingIntent.getService(
        this,
        1,
        cancelIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
      )

    val contentText =
      when {
        total > 0 && detail.isNotEmpty() -> "$completed/$total • $detail"
        total > 0 -> "$completed/$total"
        detail.isNotEmpty() -> detail
        else -> "In progress"
      }

    return NotificationCompat.Builder(this, CHANNEL_ID)
      .setSmallIcon(R.drawable.ic_sync_notification)
      .setContentTitle(title)
      .setContentText(contentText)
      .setOngoing(true)
      .setOnlyAlertOnce(true)
      .setContentIntent(openPending)
      .setProgress(total.coerceAtLeast(0), completed.coerceAtLeast(0), total <= 0)
      .addAction(0, getString(R.string.action_cancel), cancelPending)
      .build()
  }

  companion object {
    private const val CHANNEL_ID = "gitfiles_transfers"
    private const val NOTIFICATION_ID = 1001
    private const val ACTION_START = "com.example.sync.ACTION_START"
    private const val ACTION_UPDATE = "com.example.sync.ACTION_UPDATE"
    private const val ACTION_STOP = "com.example.sync.ACTION_STOP"
    private const val ACTION_CANCEL = "com.example.sync.ACTION_CANCEL"
    private const val EXTRA_TITLE = "extra_title"
    private const val EXTRA_DETAIL = "extra_detail"
    private const val EXTRA_COMPLETED = "extra_completed"
    private const val EXTRA_TOTAL = "extra_total"

    private val _cancelRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val cancelRequests: SharedFlow<Unit> = _cancelRequests.asSharedFlow()

    @Volatile
    private var lastUpdateAtMs: Long = 0L

    fun start(context: Context, title: String, total: Int = 0) {
      lastUpdateAtMs = 0L
      val intent =
        Intent(context, TransferForegroundService::class.java).apply {
          action = ACTION_START
          putExtra(EXTRA_TITLE, title)
          putExtra(EXTRA_COMPLETED, 0)
          putExtra(EXTRA_TOTAL, total)
        }
      runCatching { ContextCompat.startForegroundService(context, intent) }
    }

    fun updateProgress(
      context: Context,
      title: String,
      detail: String,
      completed: Int,
      total: Int,
    ) {
      val now = System.currentTimeMillis()
      if (completed < total && now - lastUpdateAtMs < 400L) return
      lastUpdateAtMs = now

      val intent =
        Intent(context, TransferForegroundService::class.java).apply {
          action = ACTION_UPDATE
          putExtra(EXTRA_TITLE, title)
          putExtra(EXTRA_DETAIL, detail)
          putExtra(EXTRA_COMPLETED, completed)
          putExtra(EXTRA_TOTAL, total)
        }
      runCatching { context.startService(intent) }
    }

    fun stop(context: Context) {
      runCatching {
        context.stopService(Intent(context, TransferForegroundService::class.java))
      }
    }
  }
}
