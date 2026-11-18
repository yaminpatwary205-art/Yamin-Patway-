package com.example.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.example.alarm.AlarmScheduler
import com.example.data.local.*
import com.example.data.remote.*
import com.example.translation.TranslationOutcome
import com.example.translation.TranslationService
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.UUID

data class WordDiffToken(
    val expected: String,
    val typed: String,
    val correct: Boolean
)

data class ParagraphCheckResult(
    val tokens: List<WordDiffToken>,
    val accuracyPercent: Int,
    val wrongWords: List<String>
)

class RevisionRepository(
    private val context: Context,
    val dao: AppDao,
    private val firestoreSync: FirestoreSyncRepository
) {
    private var currentUserId: String? = null

    val translationService = TranslationService(
        dao = dao,
        onTranslationCached = { entity ->
            if (currentUserId != null && isOnline()) {
                firestoreSync.pushDoc("translations", entity.id, entity.toFsMap())
            }
        }
    )

    val booksFlow: Flow<List<BookEntity>> = dao.observeBooks()
    val chaptersFlow: Flow<List<ChapterEntity>> = dao.observeAllChapters()
    val notesFlow: Flow<List<NoteEntity>> = dao.observeAllNotes()
    val paragraphsFlow: Flow<List<ParagraphFileEntity>> = dao.observeParagraphs()
    val vocabListsFlow: Flow<List<VocabListEntity>> = dao.observeVocabLists()
    val vocabWordsFlow: Flow<List<VocabWordEntity>> = dao.observeAllVocabWords()
    val weakWordsFlow: Flow<List<WeakWordEntity>> = dao.observeWeakWords()
    val translationsFlow: Flow<List<TranslationCacheEntity>> = dao.observeTranslations()
    val statsFlow: Flow<UserStatsEntity> = dao.observeUserStats().map { it ?: UserStatsEntity() }
    val alarmsFlow: Flow<List<RevisionAlarmEntity>> = dao.observeAlarms()

    fun setActiveUser(uid: String?) {
        currentUserId = uid?.takeIf { it.isNotBlank() }
    }

    fun isOnline(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    suspend fun ensureSeeded() {
        val existingBooks = dao.getAllBooksOnce()
        if (existingBooks.isEmpty()) {
            dao.upsertBooks(DefaultBooksSeed.books)
        } else {
            val existingIds = existingBooks.map { it.id }.toSet()
            val missing = DefaultBooksSeed.books.filter { it.id !in existingIds }
            if (missing.isNotEmpty()) dao.upsertBooks(missing)
        }
        val stats = dao.getUserStatsOnce()
        if (stats == null) {
            dao.upsertUserStats(UserStatsEntity(todayDate = todayDayString()))
        }
    }

    suspend fun syncWithCloudIfSignedIn(): Result<SyncStatus> {
        ensureSeeded()
        val uid = currentUserId ?: return Result.success(SyncStatus.LOCAL_ONLY)
        if (!isOnline()) return Result.success(SyncStatus.OFFLINE)
        val res = firestoreSync.syncBidirectional(uid)
        return if (res.isSuccess) {
            Result.success(SyncStatus.SYNCED)
        } else {
            Result.success(SyncStatus.ERROR)
        }
    }

    // --- Section A: Books, Chapters, Notes ---

    suspend fun updateBook(book: BookEntity) {
        val updated = book.copy(
            title = book.title.trim().ifBlank { "Untitled" }.take(120),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertBook(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("books", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun addChapter(bookId: String, title: String) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val existing = dao.getAllChaptersOnce().filter { it.bookId == bookId }
        val maxOrder = (existing.maxOfOrNull { it.sortOrder } ?: -1) + 1
        val chapter = ChapterEntity(
            id = newId("ch"),
            bookId = bookId,
            title = clean.take(120),
            sortOrder = maxOrder,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertChapter(chapter)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("chapters", chapter.id, chapter.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun updateChapter(chapter: ChapterEntity) {
        val updated = chapter.copy(
            title = chapter.title.trim().ifBlank { "অধ্যায়" }.take(120),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertChapter(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("chapters", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun swapChapterOrder(first: ChapterEntity, second: ChapterEntity) {
        val now = System.currentTimeMillis()
        val u1 = first.copy(sortOrder = second.sortOrder, updatedAtEpochMs = now)
        val u2 = second.copy(sortOrder = first.sortOrder, updatedAtEpochMs = now)
        dao.upsertChapters(listOf(u1, u2))
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("chapters", u1.id, u1.toFsMap())
            firestoreSync.pushDoc("chapters", u2.id, u2.toFsMap())
        }
    }

    suspend fun deleteChapter(chapterId: String) {
        val notes = dao.getAllNotesOnce().filter { it.chapterId == chapterId }
        dao.deleteNotesForChapter(chapterId)
        dao.deleteChapterById(chapterId)
        for (note in notes) {
            if (currentUserId != null && isOnline()) {
                firestoreSync.deleteDocOrQueue("notes", note.id)
            } else if (currentUserId != null) {
                dao.upsertPendingDelete(PendingDeleteEntity("notes:${note.id}", "notes", note.id))
            }
        }
        if (currentUserId != null && isOnline()) {
            firestoreSync.deleteDocOrQueue("chapters", chapterId)
        } else if (currentUserId != null) {
            dao.upsertPendingDelete(PendingDeleteEntity("chapters:$chapterId", "chapters", chapterId))
        }
        rescheduleAlarms()
    }

    suspend fun addNote(bookId: String, chapterId: String, content: String, starred: Boolean = false) {
        val clean = content.trim()
        if (clean.isEmpty()) return
        val note = NoteEntity(
            id = newId("nt"),
            bookId = bookId,
            chapterId = chapterId,
            content = clean.take(5000),
            starred = starred,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertNote(note)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("notes", note.id, note.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun addMultipleNotes(bookId: String, chapterId: String, rawMultiline: String, starred: Boolean = false) {
        val lines = rawMultiline
            .replace("\r\n", "\n")
            .split("\n")
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (lines.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = lines.mapIndexed { idx, line ->
            NoteEntity(
                id = newId("nt"),
                bookId = bookId,
                chapterId = chapterId,
                content = line.take(5000),
                starred = starred,
                updatedAtEpochMs = now + idx
            )
        }
        dao.upsertNotes(entities)
        if (currentUserId != null && isOnline()) {
            entities.forEach { firestoreSync.pushDoc("notes", it.id, it.toFsMap()) }
        }
        rescheduleAlarms()
    }

    suspend fun updateNote(note: NoteEntity) {
        val updated = note.copy(
            content = note.content.trim().ifBlank { "-" }.take(5000),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertNote(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("notes", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun duplicateNote(note: NoteEntity) {
        val copy = note.copy(
            id = newId("nt"),
            revisionCount = 0,
            lastRevisedEpochMs = 0L,
            lastRevisedDay = "",
            lastTriggeredEpochMs = 0L,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertNote(copy)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("notes", copy.id, copy.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun moveNoteToChapter(note: NoteEntity, targetChapter: ChapterEntity) {
        val updated = note.copy(
            bookId = targetChapter.bookId,
            chapterId = targetChapter.id,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertNote(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("notes", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun deleteNote(noteId: String) {
        dao.deleteNoteById(noteId)
        if (currentUserId != null && isOnline()) {
            firestoreSync.deleteDocOrQueue("notes", noteId)
        } else if (currentUserId != null) {
            dao.upsertPendingDelete(PendingDeleteEntity("notes:$noteId", "notes", noteId))
        }
        rescheduleAlarms()
    }

    suspend fun bulkPauseNotes(noteIds: Set<String>, pause: Boolean) {
        val now = System.currentTimeMillis()
        val notes = dao.getAllNotesOnce().filter { it.id in noteIds }
        val updated = notes.map {
            it.copy(overrideEnabled = if (pause) 0 else 1, updatedAtEpochMs = now)
        }
        dao.upsertNotes(updated)
        if (currentUserId != null && isOnline()) {
            updated.forEach { firestoreSync.pushDoc("notes", it.id, it.toFsMap()) }
        }
        rescheduleAlarms()
    }

    suspend fun bulkDeleteNotes(noteIds: Set<String>) {
        noteIds.forEach { deleteNote(it) }
    }

    suspend fun markNoteRevised(noteId: String) {
        val now = System.currentTimeMillis()
        val today = todayDayString()
        val note = dao.getNoteById(noteId) ?: return
        val updated = note.copy(
            revisionCount = note.revisionCount + 1,
            lastRevisedEpochMs = now,
            lastRevisedDay = today,
            lastTriggeredEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.upsertNote(updated)
        val book = dao.getBookById(note.bookId)
        val chapter = dao.getChapterById(note.chapterId)
        val noteHasOverride = note.overrideEnabled != -1 || note.overrideIntervalMinutes > 0
        val chapHasOverride = chapter != null && (chapter.overrideEnabled != -1 || chapter.overrideIntervalMinutes > 0)
        if (!noteHasOverride && !chapHasOverride && book != null) {
            val uBook = book.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now)
            dao.upsertBook(uBook)
            if (currentUserId != null && isOnline()) firestoreSync.pushDoc("books", uBook.id, uBook.toFsMap())
        }
        if (!noteHasOverride && chapter != null) {
            val uChap = chapter.copy(lastTriggeredEpochMs = now, updatedAtEpochMs = now)
            dao.upsertChapter(uChap)
            if (currentUserId != null && isOnline()) firestoreSync.pushDoc("chapters", uChap.id, uChap.toFsMap())
        }
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("notes", updated.id, updated.toFsMap())
        }
        incrementStatsAndStreak()
        rescheduleAlarms()
    }

    // --- Section B: English Paragraphs ---

    suspend fun addOrUpdateParagraph(
        existingId: String?,
        title: String,
        rawText: String,
        intervalMinutes: Int,
        memoryMode: Boolean,
        enabled: Boolean
    ) {
        val lines = splitParagraphIntoLines(rawText)
        if (lines.isEmpty()) return
        val old = if (existingId != null) dao.getParagraphById(existingId) else null
        val entity = ParagraphFileEntity(
            id = existingId ?: newId("pr"),
            title = title.trim().ifBlank { "Untitled Paragraph" }.take(160),
            rawText = rawText.trim().take(15000),
            lines = lines.take(300),
            currentLineIndex = (old?.currentLineIndex ?: 0).coerceIn(0, lines.lastIndex),
            enabled = enabled,
            intervalMinutes = intervalMinutes.coerceIn(1, 1440),
            memoryMode = memoryMode,
            lastTriggeredEpochMs = old?.lastTriggeredEpochMs ?: 0L,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertParagraph(entity)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("paragraphs", entity.id, entity.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun updateParagraph(paragraph: ParagraphFileEntity) {
        val updated = paragraph.copy(updatedAtEpochMs = System.currentTimeMillis())
        dao.upsertParagraph(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("paragraphs", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun advanceParagraphLine(paragraphId: String) {
        val para = dao.getParagraphById(paragraphId) ?: return
        val nextIdx = if (para.lines.isEmpty()) 0 else (para.currentLineIndex + 1) % para.lines.size
        val now = System.currentTimeMillis()
        val updated = para.copy(
            currentLineIndex = nextIdx,
            lastTriggeredEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.upsertParagraph(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("paragraphs", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun deleteParagraph(paragraphId: String) {
        dao.deleteParagraphById(paragraphId)
        if (currentUserId != null && isOnline()) {
            firestoreSync.deleteDocOrQueue("paragraphs", paragraphId)
        } else if (currentUserId != null) {
            dao.upsertPendingDelete(PendingDeleteEntity("paragraphs:$paragraphId", "paragraphs", paragraphId))
        }
        rescheduleAlarms()
    }

    // --- Section C: English Vocabulary ---

    suspend fun addVocabList(title: String, intervalMinutes: Int, reverseMode: Boolean, rawBulkWords: String) {
        val list = VocabListEntity(
            id = newId("vl"),
            title = title.trim().ifBlank { "Vocabulary List" }.take(160),
            enabled = true,
            intervalMinutes = intervalMinutes.coerceIn(1, 1440),
            reverseMode = reverseMode,
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertVocabList(list)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("vocab_lists", list.id, list.toFsMap())
        }
        if (rawBulkWords.isNotBlank()) {
            addBulkVocabWords(list.id, rawBulkWords)
        }
        rescheduleAlarms()
    }

    suspend fun updateVocabList(list: VocabListEntity) {
        val updated = list.copy(updatedAtEpochMs = System.currentTimeMillis())
        dao.upsertVocabList(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("vocab_lists", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun deleteVocabList(listId: String) {
        val words = dao.getAllVocabWordsOnce().filter { it.listId == listId }
        dao.deleteVocabWordsForList(listId)
        dao.deleteVocabListById(listId)
        for (word in words) {
            if (currentUserId != null && isOnline()) {
                firestoreSync.deleteDocOrQueue("vocab_words", word.id)
            } else if (currentUserId != null) {
                dao.upsertPendingDelete(PendingDeleteEntity("vocab_words:${word.id}", "vocab_words", word.id))
            }
        }
        if (currentUserId != null && isOnline()) {
            firestoreSync.deleteDocOrQueue("vocab_lists", listId)
        } else if (currentUserId != null) {
            dao.upsertPendingDelete(PendingDeleteEntity("vocab_lists:$listId", "vocab_lists", listId))
        }
        rescheduleAlarms()
    }

    suspend fun addBulkVocabWords(listId: String, rawInput: String) {
        val parsed = parseBulkVocab(rawInput)
        if (parsed.isEmpty()) return
        val now = System.currentTimeMillis()
        val entities = parsed.map { (w, m) ->
            VocabWordEntity(
                id = newId("vw"),
                listId = listId,
                word = w.take(120),
                meaningBn = m.take(300),
                enabled = true,
                updatedAtEpochMs = now
            )
        }
        dao.upsertVocabWords(entities)
        if (currentUserId != null && isOnline()) {
            entities.forEach { firestoreSync.pushDoc("vocab_words", it.id, it.toFsMap()) }
        }
        rescheduleAlarms()
    }

    suspend fun updateVocabWord(word: VocabWordEntity) {
        val updated = word.copy(
            word = word.word.trim().take(120),
            meaningBn = word.meaningBn.trim().take(300),
            updatedAtEpochMs = System.currentTimeMillis()
        )
        dao.upsertVocabWord(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("vocab_words", updated.id, updated.toFsMap())
        }
        rescheduleAlarms()
    }

    suspend fun deleteVocabWords(wordIds: Set<String>) {
        for (id in wordIds) {
            dao.deleteVocabWordById(id)
            if (currentUserId != null && isOnline()) {
                firestoreSync.deleteDocOrQueue("vocab_words", id)
            } else if (currentUserId != null) {
                dao.upsertPendingDelete(PendingDeleteEntity("vocab_words:$id", "vocab_words", id))
            }
        }
        rescheduleAlarms()
    }

    suspend fun recordVocabPractice(listId: String, wordId: String, correct: Boolean) {
        val now = System.currentTimeMillis()
        val word = dao.getVocabWordById(wordId)
        if (word != null) {
            val updatedWord = word.copy(
                practiceCount = word.practiceCount + 1,
                lastPracticedEpochMs = now,
                mastered = correct,
                weakCount = if (correct) word.weakCount else word.weakCount + 1,
                updatedAtEpochMs = now
            )
            dao.upsertVocabWord(updatedWord)
            if (currentUserId != null && isOnline()) {
                firestoreSync.pushDoc("vocab_words", updatedWord.id, updatedWord.toFsMap())
            }
            if (!correct) {
                addOrIncrementWeakWord(word.word, word.meaningBn, "vocab")
            }
        }
        val list = dao.getVocabListById(listId)
        if (list != null) {
            val updatedList = list.copy(
                lastTriggeredEpochMs = now,
                currentIndex = list.currentIndex + 1,
                updatedAtEpochMs = now
            )
            dao.upsertVocabList(updatedList)
            if (currentUserId != null && isOnline()) {
                firestoreSync.pushDoc("vocab_lists", updatedList.id, updatedList.toFsMap())
            }
        }
        if (correct) incrementStatsAndStreak()
        rescheduleAlarms()
    }

    suspend fun addOrIncrementWeakWord(word: String, meaningBn: String, sourceType: String) {
        val clean = word.trim().lowercase().replace(Regex("[^a-zA-Z'\\-]"), "")
        if (clean.isEmpty()) return
        val id = "wk_" + clean.take(60)
        val existing = dao.getWeakWordById(id)
        val now = System.currentTimeMillis()
        val entity = WeakWordEntity(
            id = id,
            word = clean.take(120),
            meaningBn = meaningBn.ifBlank { existing?.meaningBn.orEmpty() }.take(300),
            sourceType = if (sourceType == "paragraph") "paragraph" else "vocab",
            mistakeCount = (existing?.mistakeCount ?: 0) + 1,
            lastMistakeEpochMs = now,
            updatedAtEpochMs = now
        )
        dao.upsertWeakWord(entity)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushDoc("weak_words", entity.id, entity.toFsMap())
        }
    }

    suspend fun deleteWeakWord(weakId: String) {
        dao.deleteWeakWordById(weakId)
        if (currentUserId != null && isOnline()) {
            firestoreSync.deleteDocOrQueue("weak_words", weakId)
        } else if (currentUserId != null) {
            dao.upsertPendingDelete(PendingDeleteEntity("weak_words:$weakId", "weak_words", weakId))
        }
    }

    // --- Lazy Single-Item Translation ---

    suspend fun translateSingleItem(text: String): TranslationOutcome {
        return translationService.translate(text, "en", "bn")
    }

    suspend fun saveManualSingleTranslation(text: String, meaningBn: String) {
        translationService.saveManualTranslation(text, meaningBn, "en", "bn")
    }

    // --- Stats, Snooze, Settings ---

    suspend fun updateStats(transform: (UserStatsEntity) -> UserStatsEntity) {
        val current = dao.getUserStatsOnce() ?: UserStatsEntity(todayDate = todayDayString())
        val updated = transform(current).copy(updatedAtEpochMs = System.currentTimeMillis())
        dao.upsertUserStats(updated)
        if (currentUserId != null && isOnline()) {
            firestoreSync.pushUserProfile(updated)
        }
    }

    suspend fun incrementStatsAndStreak() {
        val today = todayDayString()
        val yesterday = yesterdayDayString()
        updateStats { stats ->
            val todayCount = if (stats.todayDate == today) stats.todayRevisionCount + 1 else 1
            val newStreak = when {
                stats.lastActiveDay == today -> maxOf(1, stats.streakCount)
                stats.lastActiveDay == yesterday -> stats.streakCount + 1
                else -> 1
            }
            stats.copy(
                todayDate = today,
                todayRevisionCount = todayCount,
                lastActiveDay = today,
                streakCount = newStreak,
                snoozedUntilEpochMs = 0L
            )
        }
    }

    // --- Multiple Custom Alarms ---

    suspend fun saveAlarm(alarm: RevisionAlarmEntity): RevisionAlarmEntity {
        val now = System.currentTimeMillis()
        val existing = dao.getAlarmById(alarm.id)
        val scheduleChanged = existing != null && (
            existing.scheduleType != alarm.scheduleType ||
                existing.hourOfDay != alarm.hourOfDay ||
                existing.minute != alarm.minute ||
                existing.repeatDaysCsv != alarm.repeatDaysCsv ||
                existing.intervalMinutes != alarm.intervalMinutes
            )
        val normalizedLastTrigger = when {
            existing == null && alarm.scheduleType == "clock" -> now
            scheduleChanged -> now
            else -> existing?.lastTriggeredEpochMs ?: alarm.lastTriggeredEpochMs
        }
        val normalized = alarm.copy(
            id = alarm.id.ifBlank { newId("al") },
            title = alarm.title.trim().ifBlank { "রিভিশন অ্যালার্ম" }.take(120),
            intervalMinutes = alarm.intervalMinutes.coerceIn(1, 1440),
            hourOfDay = alarm.hourOfDay.coerceIn(0, 23),
            minute = alarm.minute.coerceIn(0, 59),
            lastTriggeredEpochMs = normalizedLastTrigger,
            snoozedUntilEpochMs = if (scheduleChanged) 0L else alarm.snoozedUntilEpochMs,
            updatedAtEpochMs = now
        )
        dao.upsertAlarm(normalized)
        rescheduleAlarms()
        return normalized
    }

    suspend fun toggleAlarmEnabled(alarmId: String, enabled: Boolean) {
        val alarm = dao.getAlarmById(alarmId) ?: return
        val now = System.currentTimeMillis()
        val updated = alarm.copy(
            enabled = enabled,
            lastTriggeredEpochMs = if (enabled) now else alarm.lastTriggeredEpochMs,
            snoozedUntilEpochMs = if (enabled) 0L else alarm.snoozedUntilEpochMs,
            updatedAtEpochMs = now
        )
        dao.upsertAlarm(updated)
        rescheduleAlarms()
    }

    suspend fun deleteAlarm(alarmId: String) {
        dao.deleteAlarmById(alarmId)
        rescheduleAlarms()
    }

    suspend fun markAlarmTriggered(alarmId: String, advanceCursor: Boolean) {
        val alarm = dao.getAlarmById(alarmId) ?: return
        val now = System.currentTimeMillis()
        val updated = alarm.copy(
            lastTriggeredEpochMs = now,
            snoozedUntilEpochMs = 0L,
            rotationCursor = if (advanceCursor) alarm.rotationCursor + 1 else alarm.rotationCursor,
            updatedAtEpochMs = now
        )
        dao.upsertAlarm(updated)
        rescheduleAlarms()
    }

    suspend fun snoozeSingleAlarm(alarmId: String, minutes: Int) {
        val alarm = dao.getAlarmById(alarmId) ?: return
        val now = System.currentTimeMillis()
        val until = now + minutes.coerceIn(1, 120) * 60_000L
        dao.upsertAlarm(
            alarm.copy(
                snoozedUntilEpochMs = until,
                updatedAtEpochMs = now
            )
        )
        rescheduleAlarms()
    }

    suspend fun snoozeAll(minutes: Int) {
        val until = System.currentTimeMillis() + minutes.coerceIn(1, 120) * 60_000L
        updateStats { it.copy(snoozedUntilEpochMs = until) }
        rescheduleAlarms()
    }

    suspend fun rescheduleAlarms() {
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
    }

    // --- Backup JSON Export / Import ---

    suspend fun exportBackupJson(): String {
        val root = JSONObject()
        root.put("version", 1)
        root.put("exportedAt", System.currentTimeMillis())

        val stats = dao.getUserStatsOnce() ?: UserStatsEntity()
        root.put("stats", JSONObject().apply {
            put("themeMode", stats.themeMode)
            put("soundEnabled", stats.soundEnabled)
            put("alarmVolume", stats.alarmVolume.toDouble())
            put("vibrationEnabled", stats.vibrationEnabled)
            put("defaultSnoozeMinutes", stats.defaultSnoozeMinutes)
            put("vocabOrderMode", stats.vocabOrderMode)
            put("streakCount", stats.streakCount)
            put("lastActiveDay", stats.lastActiveDay)
            put("todayRevisionCount", stats.todayRevisionCount)
            put("todayDate", stats.todayDate)
        })

        root.put("books", JSONArray().apply {
            dao.getAllBooksOnce().forEach { b ->
                put(JSONObject().apply {
                    put("id", b.id); put("title", b.title); put("colorHex", b.colorHex)
                    put("iconKey", b.iconKey); put("sortOrder", b.sortOrder); put("enabled", b.enabled)
                    put("intervalMinutes", b.intervalMinutes); put("useActiveHours", b.useActiveHours)
                    put("activeStartMinutes", b.activeStartMinutes); put("activeEndMinutes", b.activeEndMinutes)
                    put("examDateEpochMs", b.examDateEpochMs)
                })
            }
        })

        root.put("chapters", JSONArray().apply {
            dao.getAllChaptersOnce().forEach { c ->
                put(JSONObject().apply {
                    put("id", c.id); put("bookId", c.bookId); put("title", c.title)
                    put("sortOrder", c.sortOrder); put("overrideEnabled", c.overrideEnabled)
                    put("overrideIntervalMinutes", c.overrideIntervalMinutes)
                })
            }
        })

        root.put("notes", JSONArray().apply {
            dao.getAllNotesOnce().forEach { n ->
                put(JSONObject().apply {
                    put("id", n.id); put("bookId", n.bookId); put("chapterId", n.chapterId)
                    put("content", n.content); put("starred", n.starred)
                    put("overrideEnabled", n.overrideEnabled); put("overrideIntervalMinutes", n.overrideIntervalMinutes)
                    put("revisionCount", n.revisionCount); put("lastRevisedEpochMs", n.lastRevisedEpochMs)
                })
            }
        })

        root.put("paragraphs", JSONArray().apply {
            dao.getAllParagraphsOnce().forEach { p ->
                put(JSONObject().apply {
                    put("id", p.id); put("title", p.title); put("rawText", p.rawText)
                    put("currentLineIndex", p.currentLineIndex); put("enabled", p.enabled)
                    put("intervalMinutes", p.intervalMinutes); put("memoryMode", p.memoryMode)
                })
            }
        })

        root.put("vocabLists", JSONArray().apply {
            dao.getAllVocabListsOnce().forEach { l ->
                put(JSONObject().apply {
                    put("id", l.id); put("title", l.title); put("enabled", l.enabled)
                    put("intervalMinutes", l.intervalMinutes); put("reverseMode", l.reverseMode)
                })
            }
        })

        root.put("vocabWords", JSONArray().apply {
            dao.getAllVocabWordsOnce().forEach { w ->
                put(JSONObject().apply {
                    put("id", w.id); put("listId", w.listId); put("word", w.word)
                    put("meaningBn", w.meaningBn); put("enabled", w.enabled); put("mastered", w.mastered)
                    put("weakCount", w.weakCount); put("practiceCount", w.practiceCount)
                })
            }
        })

        root.put("weakWords", JSONArray().apply {
            dao.getAllWeakWordsOnce().forEach { wk ->
                put(JSONObject().apply {
                    put("id", wk.id); put("word", wk.word); put("meaningBn", wk.meaningBn)
                    put("sourceType", wk.sourceType); put("mistakeCount", wk.mistakeCount)
                    put("lastMistakeEpochMs", wk.lastMistakeEpochMs)
                })
            }
        })

        root.put("alarms", JSONArray().apply {
            dao.getAllAlarmsOnce().forEach { al ->
                put(JSONObject().apply {
                    put("id", al.id)
                    put("title", al.title)
                    put("enabled", al.enabled)
                    put("scheduleType", al.scheduleType)
                    put("intervalMinutes", al.intervalMinutes)
                    put("hourOfDay", al.hourOfDay)
                    put("minute", al.minute)
                    put("repeatDaysCsv", al.repeatDaysCsv)
                    put("useActiveHours", al.useActiveHours)
                    put("activeStartMinutes", al.activeStartMinutes)
                    put("activeEndMinutes", al.activeEndMinutes)
                    put("selectedBookIds", JSONArray(al.selectedBookIds))
                    put("selectedChapterIds", JSONArray(al.selectedChapterIds))
                    put("selectedNoteIds", JSONArray(al.selectedNoteIds))
                    put("selectedParagraphIds", JSONArray(al.selectedParagraphIds))
                    put("selectedVocabListIds", JSONArray(al.selectedVocabListIds))
                    put("rotationCursor", al.rotationCursor)
                })
            }
        })

        return root.toString(2)
    }

    suspend fun importBackupJson(jsonStr: String): Result<Unit> {
        return try {
            val root = JSONObject(jsonStr)
            val now = System.currentTimeMillis()
            root.optJSONArray("books")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    BookEntity(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        colorHex = o.optString("colorHex", "#4F46E5"),
                        iconKey = o.optString("iconKey", "menu_book"),
                        sortOrder = o.optInt("sortOrder", i),
                        enabled = o.optBoolean("enabled", true),
                        intervalMinutes = o.optInt("intervalMinutes", 30),
                        useActiveHours = o.optBoolean("useActiveHours", true),
                        activeStartMinutes = o.optInt("activeStartMinutes", 480),
                        activeEndMinutes = o.optInt("activeEndMinutes", 1320),
                        examDateEpochMs = o.optLong("examDateEpochMs", 0L),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertBooks(list)
            }
            root.optJSONArray("chapters")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    ChapterEntity(
                        id = o.getString("id"),
                        bookId = o.getString("bookId"),
                        title = o.getString("title"),
                        sortOrder = o.optInt("sortOrder", i),
                        overrideEnabled = o.optInt("overrideEnabled", -1),
                        overrideIntervalMinutes = o.optInt("overrideIntervalMinutes", 0),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertChapters(list)
            }
            root.optJSONArray("notes")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    NoteEntity(
                        id = o.getString("id"),
                        bookId = o.getString("bookId"),
                        chapterId = o.getString("chapterId"),
                        content = o.getString("content"),
                        starred = o.optBoolean("starred", false),
                        overrideEnabled = o.optInt("overrideEnabled", -1),
                        overrideIntervalMinutes = o.optInt("overrideIntervalMinutes", 0),
                        revisionCount = o.optInt("revisionCount", 0),
                        lastRevisedEpochMs = o.optLong("lastRevisedEpochMs", 0L),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertNotes(list)
            }
            root.optJSONArray("paragraphs")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    val raw = o.getString("rawText")
                    ParagraphFileEntity(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        rawText = raw,
                        lines = splitParagraphIntoLines(raw).ifEmpty { listOf(raw) },
                        currentLineIndex = o.optInt("currentLineIndex", 0),
                        enabled = o.optBoolean("enabled", true),
                        intervalMinutes = o.optInt("intervalMinutes", 15),
                        memoryMode = o.optBoolean("memoryMode", false),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertParagraphs(list)
            }
            root.optJSONArray("vocabLists")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    VocabListEntity(
                        id = o.getString("id"),
                        title = o.getString("title"),
                        enabled = o.optBoolean("enabled", true),
                        intervalMinutes = o.optInt("intervalMinutes", 15),
                        reverseMode = o.optBoolean("reverseMode", false),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertVocabLists(list)
            }
            root.optJSONArray("vocabWords")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    VocabWordEntity(
                        id = o.getString("id"),
                        listId = o.getString("listId"),
                        word = o.getString("word"),
                        meaningBn = o.optString("meaningBn", ""),
                        enabled = o.optBoolean("enabled", true),
                        mastered = o.optBoolean("mastered", false),
                        weakCount = o.optInt("weakCount", 0),
                        practiceCount = o.optInt("practiceCount", 0),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertVocabWords(list)
            }
            root.optJSONObject("stats")?.let { s ->
                val cur = dao.getUserStatsOnce() ?: UserStatsEntity(todayDate = todayDayString())
                dao.upsertUserStats(
                    cur.copy(
                        themeMode = s.optString("themeMode", cur.themeMode),
                        soundEnabled = s.optBoolean("soundEnabled", cur.soundEnabled),
                        alarmVolume = s.optDouble("alarmVolume", cur.alarmVolume.toDouble()).toFloat().coerceIn(0f, 1f),
                        vibrationEnabled = s.optBoolean("vibrationEnabled", cur.vibrationEnabled),
                        defaultSnoozeMinutes = s.optInt("defaultSnoozeMinutes", cur.defaultSnoozeMinutes).coerceIn(1, 120),
                        vocabOrderMode = s.optString("vocabOrderMode", cur.vocabOrderMode),
                        streakCount = s.optInt("streakCount", cur.streakCount).coerceAtLeast(0),
                        lastActiveDay = s.optString("lastActiveDay", cur.lastActiveDay),
                        todayRevisionCount = s.optInt("todayRevisionCount", cur.todayRevisionCount).coerceAtLeast(0),
                        todayDate = s.optString("todayDate", cur.todayDate),
                        updatedAtEpochMs = now
                    )
                )
            }
            root.optJSONArray("weakWords")?.let { arr ->
                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    WeakWordEntity(
                        id = o.getString("id"),
                        word = o.getString("word"),
                        meaningBn = o.optString("meaningBn", ""),
                        sourceType = o.optString("sourceType", "vocab"),
                        mistakeCount = o.optInt("mistakeCount", 1).coerceAtLeast(1),
                        lastMistakeEpochMs = o.optLong("lastMistakeEpochMs", now),
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertWeakWords(list)
            }
            root.optJSONArray("alarms")?.let { arr ->
                fun JSONArray?.toStringList(): List<String> =
                    if (this == null) emptyList() else (0 until length()).map { optString(it) }.filter { it.isNotBlank() }

                val list = (0 until arr.length()).map { i ->
                    val o = arr.getJSONObject(i)
                    RevisionAlarmEntity(
                        id = o.getString("id"),
                        title = o.optString("title", "রিভিশন অ্যালার্ম"),
                        enabled = o.optBoolean("enabled", true),
                        scheduleType = o.optString("scheduleType", "interval"),
                        intervalMinutes = o.optInt("intervalMinutes", 15).coerceIn(1, 1440),
                        hourOfDay = o.optInt("hourOfDay", 8).coerceIn(0, 23),
                        minute = o.optInt("minute", 0).coerceIn(0, 59),
                        repeatDaysCsv = o.optString("repeatDaysCsv", ""),
                        useActiveHours = o.optBoolean("useActiveHours", false),
                        activeStartMinutes = o.optInt("activeStartMinutes", 480),
                        activeEndMinutes = o.optInt("activeEndMinutes", 1320),
                        selectedBookIds = o.optJSONArray("selectedBookIds").toStringList(),
                        selectedChapterIds = o.optJSONArray("selectedChapterIds").toStringList(),
                        selectedNoteIds = o.optJSONArray("selectedNoteIds").toStringList(),
                        selectedParagraphIds = o.optJSONArray("selectedParagraphIds").toStringList(),
                        selectedVocabListIds = o.optJSONArray("selectedVocabListIds").toStringList(),
                        rotationCursor = o.optInt("rotationCursor", 0),
                        lastTriggeredEpochMs = now,
                        updatedAtEpochMs = now
                    )
                }
                dao.upsertAlarms(list)
            }
            syncWithCloudIfSignedIn()
            rescheduleAlarms()
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        fun newId(prefix: String): String =
            "${prefix}_${UUID.randomUUID().toString().replace("-", "").take(12)}"

        fun todayDayString(): String =
            SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

        fun yesterdayDayString(): String {
            val cal = Calendar.getInstance()
            cal.add(Calendar.DAY_OF_YEAR, -1)
            return SimpleDateFormat("yyyy-MM-dd", Locale.US).format(cal.time)
        }

        fun splitParagraphIntoLines(rawText: String): List<String> {
            return rawText
                .replace("\r\n", "\n")
                .split(Regex("(?<=[.!?])\\s+|\\n+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }

        fun parseBulkVocab(rawInput: String): List<Pair<String, String>> {
            val tokens = rawInput
                .replace("\r\n", "\n")
                .split(Regex("[\\n,]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() }

            return tokens.mapNotNull { entry ->
                val sepIdx = entry.indexOf(" - ").takeIf { it >= 0 } ?: entry.indexOf("=")
                if (sepIdx > 0) {
                    val word = entry.substring(0, sepIdx).trim()
                    val meaning = entry.substring(sepIdx + if (entry.startsWith(" - ", sepIdx)) 3 else 1).trim()
                    if (word.isNotBlank()) word to meaning else null
                } else {
                    entry to ""
                }
            }
        }

        fun compareParagraphWords(expectedLine: String, typedLine: String): ParagraphCheckResult {
            val expectedWords = expectedLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val typedWords = typedLine.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            val tokens = mutableListOf<WordDiffToken>()
            val wrongWords = mutableListOf<String>()
            var correctCount = 0

            val clean = { s: String -> s.lowercase().replace(Regex("[^a-z0-9']"), "") }

            for (i in expectedWords.indices) {
                val exp = expectedWords[i]
                val typ = typedWords.getOrNull(i).orEmpty()
                val isOk = clean(exp).isNotEmpty() && clean(exp) == clean(typ)
                if (isOk) {
                    correctCount++
                } else {
                    wrongWords += exp.replace(Regex("[^a-zA-Z'\\-]"), "")
                }
                tokens += WordDiffToken(expected = exp, typed = typ, correct = isOk)
            }

            val accuracy = if (expectedWords.isEmpty()) 0 else ((correctCount * 100f) / expectedWords.size).toInt()
            return ParagraphCheckResult(
                tokens = tokens,
                accuracyPercent = accuracy.coerceIn(0, 100),
                wrongWords = wrongWords.filter { it.isNotBlank() }.distinct()
            )
        }
    }
}
