package com.example.alarm

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.example.MainActivity
import com.example.R
import com.example.data.local.AppDatabase
import com.example.data.local.UserStatsEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val type = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE) ?: "note"
        val targetId = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID) ?: ""
        val label = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL) ?: "রিভিশন অ্যালার্ম"
        val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID) ?: ""

        ensureNotificationChannel(context)

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(AlarmScheduler.EXTRA_ALARM_TRIGGERED, true)
            putExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE, type)
            putExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID, targetId)
            putExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL, label)
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, alarmId)
        }

        val fullScreenPendingIntent = PendingIntent.getActivity(
            context,
            3001,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, AlarmScheduler.NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("রিভিশন অ্যালার্ম সময় হয়েছে!")
            .setContentText(label)
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .setContentIntent(fullScreenPendingIntent)
            .setFullScreenIntent(fullScreenPendingIntent, true)
            .build()

        val hasNotifPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        if (hasNotifPermission) {
            try {
                NotificationManagerCompat.from(context).notify(AlarmScheduler.NOTIFICATION_ID, notification)
            } catch (_: SecurityException) {
            }
        }

        // Repeat every minute until opened/acknowledged
        AlarmScheduler.scheduleOneMinuteRepeat(context, type, targetId, label, alarmId)
    }

    private fun ensureNotificationChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return
            val existing = nm.getNotificationChannel(AlarmScheduler.NOTIFICATION_CHANNEL_ID)
            if (existing == null) {
                val channel = NotificationChannel(
                    AlarmScheduler.NOTIFICATION_CHANNEL_ID,
                    "Revision Alarms",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Full-screen study revision reminders"
                    enableVibration(true)
                }
                nm.createNotificationChannel(channel)
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val dao = AppDatabase.getInstance(context).appDao()
                val next = AlarmScheduler.computeNextTrigger(
                    nowMs = System.currentTimeMillis(),
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
