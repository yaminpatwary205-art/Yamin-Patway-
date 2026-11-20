package com.example.alarm

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
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

        AlarmScheduler.ensureNotificationChannel(context)
        val notification = AlarmRingingService.buildAlarmNotification(context, type, targetId, label, alarmId)

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

        // Start foreground ringing service so alarm ringtone + vibration play even in other apps
        AlarmRingingService.start(context, type, targetId, label, alarmId)

        // Repeat every minute until opened/acknowledged
        AlarmScheduler.scheduleOneMinuteRepeat(context, type, targetId, label, alarmId)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return
        if (!AppDatabase.hasActiveSession(context)) return
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
