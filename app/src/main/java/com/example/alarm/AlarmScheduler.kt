package com.example.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import com.example.data.local.*
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

sealed interface ScheduledTrigger {
    val nextTriggerEpochMs: Long
    val labelBn: String
    val type: String // "note", "paragraph", "vocab"
    val targetId: String

    data class NoteScope(
        override val nextTriggerEpochMs: Long,
        override val labelBn: String,
        val noteId: String,
        val bookId: String,
        val chapterId: String
    ) : ScheduledTrigger {
        override val type = "note"
        override val targetId = noteId
    }

    data class ParagraphScope(
        override val nextTriggerEpochMs: Long,
        override val labelBn: String,
        val paragraphId: String
    ) : ScheduledTrigger {
        override val type = "paragraph"
        override val targetId = paragraphId
    }

    data class VocabScope(
        override val nextTriggerEpochMs: Long,
        override val labelBn: String,
        val listId: String
    ) : ScheduledTrigger {
        override val type = "vocab"
        override val targetId = listId
    }

    data class CustomAlarmScope(
        override val nextTriggerEpochMs: Long,
        override val labelBn: String,
        val alarmId: String,
        val resolvedType: String, // "note", "paragraph", "vocab"
        val resolvedTargetId: String
    ) : ScheduledTrigger {
        override val type = resolvedType
        override val targetId = resolvedTargetId
    }
}

sealed interface AlarmContentItem {
    val itemType: String
    val itemId: String
    val shortLabel: String

    data class NoteItem(
        val note: NoteEntity,
        val book: BookEntity,
        val chapter: ChapterEntity
    ) : AlarmContentItem {
        override val itemType = "note"
        override val itemId = note.id
        override val shortLabel = "${book.title} • ${chapter.title}"
    }

    data class ParagraphItem(val paragraph: ParagraphFileEntity) : AlarmContentItem {
        override val itemType = "paragraph"
        override val itemId = paragraph.id
        override val shortLabel = "প্যারাগ্রাফ: ${paragraph.title}"
    }

    data class VocabItem(val list: VocabListEntity) : AlarmContentItem {
        override val itemType = "vocab"
        override val itemId = list.id
        override val shortLabel = "ভোকাবুলারি: ${list.title}"
    }
}

data class EffectiveNoteSchedule(
    val enabled: Boolean,
    val intervalMinutes: Int,
    val useActiveHours: Boolean,
    val activeStartMinutes: Int,
    val activeEndMinutes: Int
)

object AlarmScheduler {
    const val EXTRA_ALARM_TRIGGERED = "extra_alarm_triggered"
    const val EXTRA_TRIGGER_TYPE = "extra_trigger_type"
    const val EXTRA_TRIGGER_TARGET_ID = "extra_trigger_target_id"
    const val EXTRA_TRIGGER_LABEL = "extra_trigger_label"
    const val EXTRA_ALARM_ID = "extra_alarm_id"
    const val EXTRA_IS_REPEAT = "extra_is_repeat"
    const val NOTIFICATION_CHANNEL_ID = "revision_alarm_channel"
    const val NOTIFICATION_ID = 1001
    private const val ALARM_REQUEST_CODE = 2001
    private const val REPEAT_REQUEST_CODE = 2002

    fun resolveNoteSchedule(
        book: BookEntity,
        chapter: ChapterEntity,
        note: NoteEntity
    ): EffectiveNoteSchedule {
        val enabled = when {
            note.overrideEnabled != -1 -> note.overrideEnabled == 1
            chapter.overrideEnabled != -1 -> chapter.overrideEnabled == 1
            else -> book.enabled
        }
        val interval = when {
            note.overrideIntervalMinutes > 0 -> note.overrideIntervalMinutes
            chapter.overrideIntervalMinutes > 0 -> chapter.overrideIntervalMinutes
            else -> book.intervalMinutes
        }.coerceIn(1, 1440)

        val useActive = when {
            chapter.useActiveHours != -1 -> chapter.useActiveHours == 1
            else -> book.useActiveHours
        }
        val startMin = if (chapter.useActiveHours == 1) chapter.activeStartMinutes else book.activeStartMinutes
        val endMin = if (chapter.useActiveHours == 1) chapter.activeEndMinutes else book.activeEndMinutes
        return EffectiveNoteSchedule(enabled, interval, useActive, startMin, endMin)
    }

    fun isWithinActiveHours(epochMs: Long, startMinutes: Int, endMinutes: Int): Boolean {
        val cal = Calendar.getInstance().apply { timeInMillis = epochMs }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        return if (startMinutes <= endMinutes) {
            currentMin in startMinutes..endMinutes
        } else {
            currentMin >= startMinutes || currentMin <= endMinutes
        }
    }

    fun adjustForActiveHours(
        candidateEpochMs: Long,
        useActiveHours: Boolean,
        startMinutes: Int,
        endMinutes: Int
    ): Long {
        if (!useActiveHours || isWithinActiveHours(candidateEpochMs, startMinutes, endMinutes)) {
            return candidateEpochMs
        }
        val cal = Calendar.getInstance().apply { timeInMillis = candidateEpochMs }
        val currentMin = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.set(Calendar.HOUR_OF_DAY, startMinutes / 60)
        cal.set(Calendar.MINUTE, startMinutes % 60)
        if (currentMin > startMinutes) {
            cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal.timeInMillis
    }

    fun parseRepeatDays(repeatDaysCsv: String): Set<Int> {
        if (repeatDaysCsv.isBlank()) return emptySet()
        return repeatDaysCsv.split(",")
            .mapNotNull { it.trim().toIntOrNull() }
            .filter { it in Calendar.SUNDAY..Calendar.SATURDAY }
            .toSet()
    }

    fun computeNextClockTriggerMs(
        nowMs: Long,
        lastTriggeredEpochMs: Long,
        hourOfDay: Int,
        minute: Int,
        repeatDaysCsv: String,
        snoozedUntilEpochMs: Long = 0L
    ): Long {
        val allowedDays = parseRepeatDays(repeatDaysCsv)
        val todayCal = Calendar.getInstance().apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hourOfDay.coerceIn(0, 23))
            set(Calendar.MINUTE, minute.coerceIn(0, 59))
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (dayOffset in 0..14) {
            val candidate = (todayCal.clone() as Calendar).apply {
                if (dayOffset > 0) add(Calendar.DAY_OF_YEAR, dayOffset)
            }
            val dow = candidate.get(Calendar.DAY_OF_WEEK)
            if (allowedDays.isNotEmpty() && dow !in allowedDays) continue
            val candidateMs = candidate.timeInMillis
            if (lastTriggeredEpochMs >= candidateMs) continue
            if (lastTriggeredEpochMs == 0L && candidateMs + 60_000L < nowMs) continue
            return maxOf(candidateMs, snoozedUntilEpochMs)
        }
        return maxOf(nowMs + 86_400_000L, snoozedUntilEpochMs)
    }

    fun computeNextIntervalTriggerMs(
        alarm: RevisionAlarmEntity,
        globalSnoozedUntilMs: Long
    ): Long {
        val baseTime = if (alarm.lastTriggeredEpochMs > 0L) alarm.lastTriggeredEpochMs else alarm.updatedAtEpochMs
        var nextMs = baseTime + alarm.intervalMinutes.coerceIn(1, 1440) * 60_000L
        val effectiveSnooze = maxOf(alarm.snoozedUntilEpochMs, globalSnoozedUntilMs)
        if (effectiveSnooze > nextMs) {
            nextMs = effectiveSnooze
        }
        return adjustForActiveHours(nextMs, alarm.useActiveHours, alarm.activeStartMinutes, alarm.activeEndMinutes)
    }

    fun resolveAlarmItems(
        alarm: RevisionAlarmEntity,
        books: List<BookEntity>,
        chapters: List<ChapterEntity>,
        notes: List<NoteEntity>,
        paragraphs: List<ParagraphFileEntity>,
        vocabLists: List<VocabListEntity>,
        vocabWords: List<VocabWordEntity>
    ): List<AlarmContentItem> {
        val bookMap = books.associateBy { it.id }
        val chapterMap = chapters.associateBy { it.id }
        val selectedBooks = alarm.selectedBookIds.toSet()
        val selectedChapters = alarm.selectedChapterIds.toSet()
        val selectedNotes = alarm.selectedNoteIds.toSet()
        val selectedParas = alarm.selectedParagraphIds.toSet()
        val selectedVocabs = alarm.selectedVocabListIds.toSet()

        val items = mutableListOf<AlarmContentItem>()

        // Stable order so rotationCursor deterministically visits every chosen note without shifting
        val noteItems = notes
            .filter { note ->
                val directNote = note.id in selectedNotes
                val viaParent = note.chapterId in selectedChapters || note.bookId in selectedBooks
                (directNote || (viaParent && note.overrideEnabled != 0)) &&
                    bookMap.containsKey(note.bookId) &&
                    chapterMap.containsKey(note.chapterId)
            }
            .sortedWith(
                compareBy<NoteEntity>({ bookMap[it.bookId]?.sortOrder ?: 0 }, { chapterMap[it.chapterId]?.sortOrder ?: 0 }, { it.updatedAtEpochMs }, { it.id })
            )
            .mapNotNull { note ->
                val b = bookMap[note.bookId] ?: return@mapNotNull null
                val c = chapterMap[note.chapterId] ?: return@mapNotNull null
                AlarmContentItem.NoteItem(note, b, c)
            }
        items += noteItems

        val paraItems = paragraphs
            .filter { it.id in selectedParas && it.lines.isNotEmpty() }
            .sortedBy { it.id }
            .map { AlarmContentItem.ParagraphItem(it) }
        items += paraItems

        val activeVocabListIds = vocabWords.filter { it.enabled }.map { it.listId }.toSet()
        val vocabItems = vocabLists
            .filter { it.id in selectedVocabs && it.id in activeVocabListIds }
            .sortedBy { it.id }
            .map { AlarmContentItem.VocabItem(it) }
        items += vocabItems

        return items
    }

    fun computeNextTriggerForAlarm(
        alarm: RevisionAlarmEntity,
        nowMs: Long,
        stats: UserStatsEntity,
        books: List<BookEntity>,
        chapters: List<ChapterEntity>,
        notes: List<NoteEntity>,
        paragraphs: List<ParagraphFileEntity>,
        vocabLists: List<VocabListEntity>,
        vocabWords: List<VocabWordEntity>
    ): ScheduledTrigger.CustomAlarmScope? {
        if (!alarm.enabled) return null
        val items = resolveAlarmItems(alarm, books, chapters, notes, paragraphs, vocabLists, vocabWords)
        if (items.isEmpty()) return null
        val picked = items[alarm.rotationCursor.coerceAtLeast(0) % items.size]
        val nextMs = if (alarm.scheduleType == "clock") {
            computeNextClockTriggerMs(
                nowMs = nowMs,
                lastTriggeredEpochMs = alarm.lastTriggeredEpochMs,
                hourOfDay = alarm.hourOfDay,
                minute = alarm.minute,
                repeatDaysCsv = alarm.repeatDaysCsv,
                snoozedUntilEpochMs = maxOf(alarm.snoozedUntilEpochMs, stats.snoozedUntilEpochMs)
            )
        } else {
            computeNextIntervalTriggerMs(alarm, stats.snoozedUntilEpochMs)
        }
        return ScheduledTrigger.CustomAlarmScope(
            nextTriggerEpochMs = nextMs,
            labelBn = "${alarm.title}: ${picked.shortLabel}",
            alarmId = alarm.id,
            resolvedType = picked.itemType,
            resolvedTargetId = picked.itemId
        )
    }

    /**
     * Computes all active candidate triggers (custom multi-alarms + per-book/chapter/note/paragraph/vocab schedules)
     * and picks the earliest upcoming (or overdue) trigger.
     */
    fun computeNextTrigger(
        nowMs: Long,
        stats: UserStatsEntity,
        books: List<BookEntity>,
        chapters: List<ChapterEntity>,
        notes: List<NoteEntity>,
        paragraphs: List<ParagraphFileEntity>,
        vocabLists: List<VocabListEntity>,
        vocabWords: List<VocabWordEntity>,
        alarms: List<RevisionAlarmEntity> = emptyList()
    ): ScheduledTrigger? {
        val bookMap = books.associateBy { it.id }
        val chapterMap = chapters.associateBy { it.id }
        val candidates = mutableListOf<ScheduledTrigger>()

        // Custom user-defined multi-alarms
        for (alarm in alarms) {
            computeNextTriggerForAlarm(
                alarm = alarm,
                nowMs = nowMs,
                stats = stats,
                books = books,
                chapters = chapters,
                notes = notes,
                paragraphs = paragraphs,
                vocabLists = vocabLists,
                vocabWords = vocabWords
            )?.let { candidates += it }
        }

        // Section A: Per-book / per-chapter / per-note schedule scopes
        val activeNotesWithSchedule = notes.mapNotNull { note ->
            val book = bookMap[note.bookId] ?: return@mapNotNull null
            val chapter = chapterMap[note.chapterId] ?: return@mapNotNull null
            val sched = resolveNoteSchedule(book, chapter, note)
            if (!sched.enabled) return@mapNotNull null
            Triple(note, Pair(book, chapter), sched)
        }

        if (activeNotesWithSchedule.isNotEmpty()) {
            val grouped = activeNotesWithSchedule.groupBy { (note, _, _) ->
                when {
                    note.overrideEnabled != -1 || note.overrideIntervalMinutes > 0 -> "note:${note.id}"
                    else -> {
                        val chap = chapterMap[note.chapterId]
                        if (chap != null && (chap.overrideEnabled != -1 || chap.overrideIntervalMinutes > 0)) {
                            "chap:${chap.id}"
                        } else {
                            "book:${note.bookId}"
                        }
                    }
                }
            }

            for ((scopeKey, groupItems) in grouped) {
                val picked = groupItems.minWithOrNull(
                    compareByDescending<Triple<NoteEntity, Pair<BookEntity, ChapterEntity>, EffectiveNoteSchedule>> { it.first.starred }
                        .thenBy { it.first.lastRevisedEpochMs }
                        .thenBy { it.first.revisionCount }
                ) ?: continue
                val (note, pair, sched) = picked
                val (book, chapter) = pair
                val scopeLastTrigger = when {
                    scopeKey.startsWith("note:") -> note.lastTriggeredEpochMs
                    scopeKey.startsWith("chap:") -> maxOf(note.lastTriggeredEpochMs, chapter.lastTriggeredEpochMs)
                    else -> maxOf(note.lastTriggeredEpochMs, book.lastTriggeredEpochMs)
                }
                val baseTime = if (scopeLastTrigger > 0L) scopeLastTrigger else groupItems.maxOf { it.first.updatedAtEpochMs }
                var nextMs = baseTime + sched.intervalMinutes * 60_000L
                if (stats.snoozedUntilEpochMs > nextMs) {
                    nextMs = stats.snoozedUntilEpochMs
                }
                nextMs = adjustForActiveHours(nextMs, sched.useActiveHours, sched.activeStartMinutes, sched.activeEndMinutes)
                candidates += ScheduledTrigger.NoteScope(
                    nextTriggerEpochMs = nextMs,
                    labelBn = "${book.title} • ${chapter.title}",
                    noteId = note.id,
                    bookId = book.id,
                    chapterId = chapter.id
                )
            }
        }

        // Section B: English Paragraphs
        for (para in paragraphs) {
            if (!para.enabled || para.lines.isEmpty()) continue
            val baseTime = if (para.lastTriggeredEpochMs > 0L) para.lastTriggeredEpochMs else para.updatedAtEpochMs
            var nextMs = baseTime + para.intervalMinutes.coerceIn(1, 1440) * 60_000L
            if (stats.snoozedUntilEpochMs > nextMs) nextMs = stats.snoozedUntilEpochMs
            candidates += ScheduledTrigger.ParagraphScope(
                nextTriggerEpochMs = nextMs,
                labelBn = "প্যারাগ্রাফ: ${para.title}",
                paragraphId = para.id
            )
        }

        // Section C: English Vocab Lists
        val wordsByList = vocabWords.filter { it.enabled }.groupBy { it.listId }
        for (list in vocabLists) {
            val activeWords = wordsByList[list.id].orEmpty()
            if (!list.enabled || activeWords.isEmpty()) continue
            val baseTime = if (list.lastTriggeredEpochMs > 0L) list.lastTriggeredEpochMs else list.updatedAtEpochMs
            var nextMs = baseTime + list.intervalMinutes.coerceIn(1, 1440) * 60_000L
            if (stats.snoozedUntilEpochMs > nextMs) nextMs = stats.snoozedUntilEpochMs
            candidates += ScheduledTrigger.VocabScope(
                nextTriggerEpochMs = nextMs,
                labelBn = "ভোকাবুলারি: ${list.title}",
                listId = list.id
            )
        }

        return candidates.minByOrNull { it.nextTriggerEpochMs }
    }

    fun scheduleNextAlarm(context: Context, trigger: ScheduledTrigger?) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            if (trigger != null) {
                putExtra(EXTRA_TRIGGER_TYPE, trigger.type)
                putExtra(EXTRA_TRIGGER_TARGET_ID, trigger.targetId)
                putExtra(EXTRA_TRIGGER_LABEL, trigger.labelBn)
                putExtra(EXTRA_ALARM_ID, (trigger as? ScheduledTrigger.CustomAlarmScope)?.alarmId ?: "")
                putExtra(EXTRA_IS_REPEAT, false)
            }
        }
        val pi = PendingIntent.getBroadcast(
            context,
            ALARM_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        if (trigger == null) {
            alarmManager.cancel(pi)
            return
        }

        val triggerAt = maxOf(System.currentTimeMillis() + 3_000L, trigger.nextTriggerEpochMs)
        val canExact = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
        try {
            if (canExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        } catch (_: SecurityException) {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun scheduleOneMinuteRepeat(
        context: Context,
        type: String,
        targetId: String,
        labelBn: String,
        alarmId: String = ""
    ) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_TRIGGER_TYPE, type)
            putExtra(EXTRA_TRIGGER_TARGET_ID, targetId)
            putExtra(EXTRA_TRIGGER_LABEL, labelBn)
            putExtra(EXTRA_ALARM_ID, alarmId)
            putExtra(EXTRA_IS_REPEAT, true)
        }
        val pi = PendingIntent.getBroadcast(
            context,
            REPEAT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nextMinute = System.currentTimeMillis() + 60_000L
        try {
            alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, nextMinute, pi)
        } catch (_: SecurityException) {
        }
    }

    fun cancelRepeatReminder(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val intent = Intent(context, AlarmReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context,
            REPEAT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pi != null) {
            alarmManager.cancel(pi)
            pi.cancel()
        }
    }

    fun generateIcsContent(
        books: List<BookEntity>,
        paragraphs: List<ParagraphFileEntity>,
        vocabLists: List<VocabListEntity>,
        alarms: List<RevisionAlarmEntity> = emptyList()
    ): String {
        val fmt = SimpleDateFormat("yyyyMMdd'T'HHmmss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }
        val now = System.currentTimeMillis()
        val sb = StringBuilder()
        sb.appendLine("BEGIN:VCALENDAR")
        sb.appendLine("VERSION:2.0")
        sb.appendLine("PRODID:-//Revision Alarm//Study Scheduler//BN")
        sb.appendLine("CALSCALE:GREGORIAN")

        fun appendEvent(uid: String, summary: String, description: String, intervalMin: Int, startMs: Long) {
            val endMs = startMs + 5 * 60_000L
            val rrule = when {
                intervalMin <= 60 -> "FREQ=HOURLY;INTERVAL=1"
                else -> "FREQ=DAILY;INTERVAL=1"
            }
            sb.appendLine("BEGIN:VEVENT")
            sb.appendLine("UID:$uid@revisionalarm.app")
            sb.appendLine("DTSTAMP:${fmt.format(Date(now))}")
            sb.appendLine("DTSTART:${fmt.format(Date(startMs))}")
            sb.appendLine("DTEND:${fmt.format(Date(endMs))}")
            sb.appendLine("SUMMARY:$summary")
            sb.appendLine("DESCRIPTION:$description")
            sb.appendLine("RRULE:$rrule")
            sb.appendLine("BEGIN:VALARM")
            sb.appendLine("TRIGGER:-PT0M")
            sb.appendLine("ACTION:DISPLAY")
            sb.appendLine("DESCRIPTION:$summary")
            sb.appendLine("END:VALARM")
            sb.appendLine("END:VEVENT")
        }

        alarms.filter { it.enabled }.forEachIndexed { idx, alarm ->
            val start = if (alarm.scheduleType == "clock") {
                computeNextClockTriggerMs(now, alarm.lastTriggeredEpochMs, alarm.hourOfDay, alarm.minute, alarm.repeatDaysCsv)
            } else {
                now + alarm.intervalMinutes * 60_000L + idx * 60_000L
            }
            appendEvent(
                uid = "alarm-${alarm.id}",
                summary = "অ্যালার্ম: ${alarm.title}",
                description = if (alarm.scheduleType == "clock") "নির্ধারিত সময়: ${alarm.hourOfDay}:${alarm.minute.toString().padStart(2, '0')}" else "প্রতি ${alarm.intervalMinutes} মিনিট পরপর",
                intervalMin = if (alarm.scheduleType == "clock") 1440 else alarm.intervalMinutes,
                startMs = start
            )
        }

        books.filter { it.enabled }.forEachIndexed { idx, book ->
            appendEvent(
                uid = "book-${book.id}",
                summary = "রিভিশন অ্যালার্ম: ${book.title}",
                description = "প্রতি ${book.intervalMinutes} মিনিট পরপর ${book.title} রিভিশন দিন।",
                intervalMin = book.intervalMinutes,
                startMs = now + (idx + 1) * 10 * 60_000L
            )
        }

        paragraphs.filter { it.enabled }.forEachIndexed { idx, para ->
            appendEvent(
                uid = "para-${para.id}",
                summary = "প্যারাগ্রাফ প্র্যাকটিস: ${para.title}",
                description = "ইংরেজি লাইন অনুশীলন (${para.currentLineIndex + 1}/${para.lines.size})",
                intervalMin = para.intervalMinutes,
                startMs = now + (idx + 1) * 15 * 60_000L
            )
        }

        vocabLists.filter { it.enabled }.forEachIndexed { idx, list ->
            appendEvent(
                uid = "vocab-${list.id}",
                summary = "ভোকাবুলারি অনুশীলন: ${list.title}",
                description = "ইংরেজি শব্দ ও বানান অনুশীলন",
                intervalMin = list.intervalMinutes,
                startMs = now + (idx + 1) * 20 * 60_000L
            )
        }

        sb.appendLine("END:VCALENDAR")
        return sb.toString()
    }
}
