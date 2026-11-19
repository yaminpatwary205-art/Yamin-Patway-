package com.example.alarm

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.UserStatsEntity
import kotlinx.coroutines.*

class AlarmRingingService : Service() {
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var audioManager: AlarmAudioManager? = null
    private var autoStopJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audioManager = AlarmAudioManager(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP_RINGING) {
            stopAlarmAndSelf()
            return START_NOT_STICKY
        }

        val type = intent?.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE) ?: "note"
        val targetId = intent?.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID) ?: ""
        val label = intent?.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL) ?: "রিভিশন অ্যালার্ম"
        val alarmId = intent?.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: ""

        AlarmScheduler.ensureNotificationChannel(this)
        val notification = buildAlarmNotification(this, type, targetId, label, alarmId)

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    AlarmScheduler.NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
                )
            } else {
                startForeground(AlarmScheduler.NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
            try {
                NotificationManagerCompat.from(this).notify(AlarmScheduler.NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }

        serviceScope.launch {
            val stats = withContext(Dispatchers.IO) {
                runCatching { AppDatabase.getInstance(applicationContext).appDao().getUserStatsOnce() }.getOrNull()
                    ?: UserStatsEntity()
            }
            audioManager?.startAlarmFeedback(
                soundEnabled = stats.soundEnabled,
                volume = stats.alarmVolume,
                vibrationEnabled = stats.vibrationEnabled
            )
        }

        autoStopJob?.cancel()
        autoStopJob = serviceScope.launch {
            delay(45_000L)
            audioManager?.stopAlarmFeedback()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_DETACH)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(false)
            }
            stopSelf()
        }

        return START_NOT_STICKY
    }

    private fun stopAlarmAndSelf() {
        autoStopJob?.cancel()
        audioManager?.stopAlarmFeedback()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
        NotificationManagerCompat.from(this).cancel(AlarmScheduler.NOTIFICATION_ID)
        stopSelf()
    }

    override fun onDestroy() {
        autoStopJob?.cancel()
        audioManager?.stopAlarmFeedback()
        serviceScope.cancel()
        super.onDestroy()
    }

    companion object {
        const val ACTION_START_RINGING = "com.example.alarm.ACTION_START_RINGING"
        const val ACTION_STOP_RINGING = "com.example.alarm.ACTION_STOP_RINGING"
        const val ACTION_DISMISS_ALARM = "com.example.alarm.ACTION_DISMISS_ALARM"
        const val ACTION_SNOOZE_ALARM = "com.example.alarm.ACTION_SNOOZE_ALARM"

        fun start(
            context: Context,
            type: String,
            targetId: String,
            label: String,
            alarmId: String
        ) {
            val intent = Intent(context, AlarmRingingService::class.java).apply {
                action = ACTION_START_RINGING
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE, type)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID, targetId)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL, label)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // Fallback when OEM blocks background FGS: show notification directly
                AlarmScheduler.ensureNotificationChannel(context)
                val notif = buildAlarmNotification(context, type, targetId, label, alarmId)
                try {
                    NotificationManagerCompat.from(context).notify(AlarmScheduler.NOTIFICATION_ID, notif)
                } catch (_: SecurityException) {
                }
            }
        }

        fun stop(context: Context) {
            try {
                val stopIntent = Intent(context, AlarmRingingService::class.java).apply {
                    action = ACTION_STOP_RINGING
                }
                context.stopService(stopIntent)
            } catch (_: Exception) {
            }
            try {
                NotificationManagerCompat.from(context).cancel(AlarmScheduler.NOTIFICATION_ID)
            } catch (_: Exception) {
            }
        }

        fun buildAlarmNotification(
            context: Context,
            type: String,
            targetId: String,
            label: String,
            alarmId: String
        ): Notification {
            val openIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(AlarmScheduler.EXTRA_ALARM_TRIGGERED, true)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE, type)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID, targetId)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL, label)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            val openPendingIntent = PendingIntent.getActivity(
                context,
                3001,
                openIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val snoozeIntent = Intent(context, AlarmActionReceiver::class.java).apply {
                action = ACTION_SNOOZE_ALARM
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            val snoozePendingIntent = PendingIntent.getBroadcast(
                context,
                3002,
                snoozeIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val dismissIntent = Intent(context, AlarmActionReceiver::class.java).apply {
                action = ACTION_DISMISS_ALARM
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE, type)
                putExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID, targetId)
                putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
            }
            val dismissPendingIntent = PendingIntent.getBroadcast(
                context,
                3003,
                dismissIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            return NotificationCompat.Builder(context, AlarmScheduler.NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("রিভিশন অ্যালার্ম সময় হয়েছে!")
                .setContentText(label)
                .setStyle(NotificationCompat.BigTextStyle().bigText("$label\nট্যাপ করে সরাসরি রিভিশন শুরু করুন।"))
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setAutoCancel(true)
                .setOngoing(false)
                .setContentIntent(openPendingIntent)
                .setFullScreenIntent(openPendingIntent, true)
                .addAction(0, "পড়ুন", openPendingIntent)
                .addAction(0, "৫ মি. স্নুজ", snoozePendingIntent)
                .addAction(0, "বন্ধ", dismissPendingIntent)
                .build()
        }
    }
}

class AlarmActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID).orEmpty()
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE).orEmpty()
        val targetId = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID).orEmpty()

        AlarmRingingService.stop(context)
        AlarmScheduler.cancelRepeatReminder(context)

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).appDao()
                val now = System.currentTimeMillis()
                when (action) {
                    AlarmRingingService.ACTION_SNOOZE_ALARM -> {
                        val snoozeUntil = now + 5 * 60_000L
                        if (alarmId.isNotBlank()) {
                            dao.getAlarmById(alarmId)?.let { al ->
                                dao.upsertAlarm(al.copy(snoozedUntilEpochMs = snoozeUntil, updatedAtEpochMs = now))
                            }
                        } else {
                            val stats = dao.getUserStatsOnce() ?: UserStatsEntity()
                            dao.upsertUserStats(stats.copy(snoozedUntilEpochMs = snoozeUntil, updatedAtEpochMs = now))
                        }
                    }
                    AlarmRingingService.ACTION_DISMISS_ALARM -> {
                        if (alarmId.isNotBlank()) {
                            dao.getAlarmById(alarmId)?.let { al ->
                                dao.upsertAlarm(
                                    al.copy(
                                        lastTriggeredEpochMs = now,
                                        snoozedUntilEpochMs = 0L,
                                        rotationCursor = al.rotationCursor + 1,
                                        updatedAtEpochMs = now
                                    )
                                )
                            }
                        } else {
                            when (type) {
                                "note" -> dao.getNoteById(targetId)?.let { note ->
                                    dao.upsertNote(note.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now))
                                    val noteHasOverride = note.overrideEnabled != -1 || note.overrideIntervalMinutes > 0
                                    val chap = dao.getChapterById(note.chapterId)
                                    val chapHasOverride = chap != null && (chap.overrideEnabled != -1 || chap.overrideIntervalMinutes > 0)
                                    if (!noteHasOverride && chap != null) {
                                        dao.upsertChapter(chap.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now))
                                    }
                                    if (!noteHasOverride && !chapHasOverride) {
                                        dao.getBookById(note.bookId)?.let { book ->
                                            dao.upsertBook(book.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now))
                                        }
                                    }
                                }
                                "paragraph" -> dao.getParagraphById(targetId)?.let {
                                    dao.upsertParagraph(it.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now))
                                }
                                "vocab" -> dao.getVocabListById(targetId)?.let {
                                    dao.upsertVocabList(it.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now))
                                }
                            }
                        }
                    }
                }
                val next = AlarmScheduler.computeNextTrigger(
                    nowMs = now,
                    stats = dao.getUserStatsOnce() ?: UserStatsEntity(),
                    books = dao.getAllBooksOnce(),
                    chapters = dao.getAllChaptersOnce(),
                    notes = dao.getAllNotesOnce(),
                    paragraphs = dao.getAllParagraphsOnce(),
                    vocabLists = dao.getAllVocabListsOnce(),
                    vocabWords = dao.getAllVocabWordsOnce(),
                    alarms = dao.getAllAlarmsOnce()
                )
                AlarmScheduler.scheduleNextAlarm(context, next)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
