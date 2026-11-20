package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.alarm.AlarmScheduler
import com.example.data.RevisionRepository
import com.example.data.local.*
import com.example.data.remote.FirestoreSyncRepository
import com.example.translation.TranslationOutcome
import com.example.translation.TranslationService
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Calendar

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RevisionAlarmLogicTest {

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var repo: RevisionRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()
        val fsSync = FirestoreSyncRepository(
            db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
            dao = dao,
            auth = FirebaseAuth.getInstance()
        )
        repo = RevisionRepository(context, dao, fsSync)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `default 11 Bengali books are seeded with exact names`() = runBlocking {
        val expected = listOf(
            "বাংলা প্রথম পত্র",
            "বাংলা দ্বিতীয় পত্র",
            "ইংলিশ প্রথম পত্র",
            "ইংলিশ দ্বিতীয় পত্র",
            "সাধারণ গণিত",
            "সাধারণ বিজ্ঞান",
            "হিসাববিজ্ঞান",
            "ব্যবসায় উদ্যোগ",
            "ফিন্যান্স ও ব্যাংকিং",
            "ইসলাম শিক্ষা",
            "আইসিটি"
        )
        repo.ensureSeeded()
        val books = dao.getAllBooksOnce()
        assertEquals(11, books.size)
        assertEquals(expected, books.map { it.title })
    }

    @Test
    fun `schedule inheritance prioritizes note then chapter then book`() {
        val book = DefaultBooksSeed.books[0].copy(enabled = true, intervalMinutes = 30)
        val chapter = ChapterEntity("ch_1", book.id, "অধ্যায় ১", 0, overrideEnabled = 0, overrideIntervalMinutes = 15)
        val noteInherited = NoteEntity("n_1", book.id, chapter.id, "Note 1")
        val noteOverride = NoteEntity("n_2", book.id, chapter.id, "Note 2", overrideEnabled = 1, overrideIntervalMinutes = 5)

        val sched1 = AlarmScheduler.resolveNoteSchedule(book, chapter, noteInherited)
        assertFalse(sched1.enabled)
        assertEquals(15, sched1.intervalMinutes)

        val sched2 = AlarmScheduler.resolveNoteSchedule(book, chapter, noteOverride)
        assertTrue(sched2.enabled)
        assertEquals(5, sched2.intervalMinutes)
    }

    @Test
    fun `computeNextTrigger prefers starred and least recently revised notes with stable countdown`() {
        val book = DefaultBooksSeed.books[0].copy(
            enabled = true,
            intervalMinutes = 15,
            useActiveHours = false,
            lastTriggeredEpochMs = 1_000_000L
        )
        val chapter = ChapterEntity("ch_1", book.id, "অধ্যায় ১", 0)
        val noteRegular = NoteEntity("n_reg", book.id, chapter.id, "Regular", starred = false, lastRevisedEpochMs = 500L)
        val noteStarred = NoteEntity("n_star", book.id, chapter.id, "Starred", starred = true, lastRevisedEpochMs = 800L)

        val t1 = AlarmScheduler.computeNextTrigger(
            nowMs = 1_100_000L,
            stats = UserStatsEntity(),
            books = listOf(book),
            chapters = listOf(chapter),
            notes = listOf(noteRegular, noteStarred),
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )
        val t2 = AlarmScheduler.computeNextTrigger(
            nowMs = 1_200_000L,
            stats = UserStatsEntity(),
            books = listOf(book),
            chapters = listOf(chapter),
            notes = listOf(noteRegular, noteStarred),
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )

        assertNotNull(t1)
        assertEquals("n_star", t1!!.targetId)
        assertEquals(t1.nextTriggerEpochMs, t2!!.nextTriggerEpochMs)
        assertEquals(1_000_000L + 15 * 60_000L, t1.nextTriggerEpochMs)
    }

    @Test
    fun `note override trigger does not delay book-scoped sibling notes`() {
        val book = DefaultBooksSeed.books[0].copy(
            enabled = true,
            intervalMinutes = 30,
            useActiveHours = false,
            lastTriggeredEpochMs = 1_000_000L
        )
        val chapter = ChapterEntity("ch_1", book.id, "অধ্যায় ১", 0)
        val bookScopedNote = NoteEntity("n_book", book.id, chapter.id, "Book scoped", updatedAtEpochMs = 1_000_000L)
        val overrideNote = NoteEntity(
            id = "n_over",
            bookId = book.id,
            chapterId = chapter.id,
            content = "Override 5m",
            overrideEnabled = 1,
            overrideIntervalMinutes = 5,
            lastTriggeredEpochMs = 2_000_000L
        )

        val next = AlarmScheduler.computeNextTrigger(
            nowMs = 2_100_000L,
            stats = UserStatsEntity(),
            books = listOf(book),
            chapters = listOf(chapter),
            notes = listOf(bookScopedNote, overrideNote),
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )

        assertNotNull(next)
        assertEquals("n_over", next!!.targetId)
        assertEquals(2_000_000L + 5 * 60_000L, next.nextTriggerEpochMs)

        // When overrideNote hasn't been triggered yet, or bookScopedNote is overdue, bookScopedNote still keeps book's 1_000_000L base
        val laterOverride = overrideNote.copy(lastTriggeredEpochMs = 5_000_000L)
        val pickedBook = AlarmScheduler.computeNextTrigger(
            nowMs = 2_500_000L,
            stats = UserStatsEntity(),
            books = listOf(book),
            chapters = listOf(chapter),
            notes = listOf(bookScopedNote, laterOverride),
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )
        assertEquals("n_book", pickedBook!!.targetId)
        assertEquals(1_000_000L + 30 * 60_000L, pickedBook.nextTriggerEpochMs)
    }

    @Test
    fun `active hours defers night triggers to 8am`() {
        val cal = Calendar.getInstance().apply {
            set(2026, Calendar.MAY, 10, 23, 30, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nightMs = cal.timeInMillis
        assertFalse(AlarmScheduler.isWithinActiveHours(nightMs, 8 * 60, 22 * 60))

        val adjustedMs = AlarmScheduler.adjustForActiveHours(nightMs, useActiveHours = true, startMinutes = 8 * 60, endMinutes = 22 * 60)
        val adjCal = Calendar.getInstance().apply { timeInMillis = adjustedMs }
        assertEquals(11, adjCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, adjCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(0, adjCal.get(Calendar.MINUTE))
    }

    @Test
    fun `chapters and notes CRUD, duplicate, move, bulk pause, and revision tracking work`() = runBlocking {
        repo.ensureSeeded()
        val bookId = DefaultBooksSeed.books[0].id
        repo.addChapter(bookId, "অধ্যায় ১")
        repo.addChapter(bookId, "অধ্যায় ২")
        val chs = dao.getAllChaptersOnce().filter { it.bookId == bookId }.sortedBy { it.sortOrder }
        assertEquals(2, chs.size)

        repo.swapChapterOrder(chs[0], chs[1])
        val swapped = dao.getAllChaptersOnce().filter { it.bookId == bookId }.sortedBy { it.sortOrder }
        assertEquals("অধ্যায় ২", swapped[0].title)

        repo.addNote(bookId, swapped[0].id, "প্রথম নোট", starred = true)
        val note1 = dao.getAllNotesOnce().single()
        repo.duplicateNote(note1)
        assertEquals(2, dao.getAllNotesOnce().size)

        repo.addMultipleNotes(bookId, swapped[0].id, "তৃতীয় নোট\nচতুর্থ নোট", starred = false)
        assertEquals(4, dao.getAllNotesOnce().size)

        repo.moveNoteToChapter(note1, swapped[1])
        val moved = dao.getNoteById(note1.id)!!
        assertEquals(swapped[1].id, moved.chapterId)

        val allIds = dao.getAllNotesOnce().map { it.id }.toSet()
        repo.bulkPauseNotes(allIds, pause = true)
        assertTrue(dao.getAllNotesOnce().all { it.overrideEnabled == 0 })

        repo.markNoteRevised(note1.id)
        val revised = dao.getNoteById(note1.id)!!
        assertEquals(1, revised.revisionCount)
        assertEquals(RevisionRepository.todayDayString(), revised.lastRevisedDay)
        val stats = dao.getUserStatsOnce()!!
        assertEquals(1, stats.todayRevisionCount)
        assertEquals(1, stats.streakCount)

        repo.bulkDeleteNotes(allIds)
        assertTrue(dao.getAllNotesOnce().isEmpty())
    }

    @Test
    fun `paragraph line splitting, word-by-word comparison, and looping work`() = runBlocking {
        repo.ensureSeeded()
        val lines = RevisionRepository.splitParagraphIntoLines(
            "A winter morning is misty and cold. Dew drops glisten on the grass!\nBirds chirp happily."
        )
        assertEquals(3, lines.size)
        assertEquals("A winter morning is misty and cold.", lines[0])

        // Even missing space after period or long unpunctuated run-on text must split into single short lines
        val noSpaceLines = RevisionRepository.splitParagraphIntoLines("First sentence.Second sentence!Third one.")
        assertEquals(listOf("First sentence.", "Second sentence!", "Third one."), noSpaceLines)

        val longUnpunctuated = "This is a very long continuous English paragraph pasted without any periods or newlines so that the app still splits it nicely into short manageable single lines for revision"
        val chunkedLines = RevisionRepository.splitParagraphIntoLines(longUnpunctuated)
        assertTrue("Long unpunctuated paragraph must split into multiple lines", chunkedLines.size >= 2)
        assertTrue("Every line must stay concise", chunkedLines.all { it.length <= 95 })

        AlarmScheduler.ensureNotificationChannel(context)
        assertEquals("revision_alarm_channel_v2", AlarmScheduler.NOTIFICATION_CHANNEL_ID)

        val diff = RevisionRepository.compareParagraphWords(
            expectedLine = "Dew drops glisten on the grass.",
            typedLine = "Dew drop glisten on a grass."
        )
        assertEquals(6, diff.tokens.size)
        assertEquals(4, diff.tokens.count { it.correct })
        assertEquals(66, diff.accuracyPercent)
        assertEquals(listOf("drops", "the"), diff.wrongWords)

        repo.addOrUpdateParagraph(
            existingId = null,
            title = "Winter Morning",
            rawText = "Line one. Line two.",
            intervalMinutes = 10,
            memoryMode = true,
            enabled = true
        )
        val para = dao.getAllParagraphsOnce().single()
        assertEquals(2, para.lines.size)
        assertEquals(0, para.currentLineIndex)

        repo.advanceParagraphLine(para.id)
        assertEquals(1, dao.getParagraphById(para.id)!!.currentLineIndex)
        repo.advanceParagraphLine(para.id)
        assertEquals(0, dao.getParagraphById(para.id)!!.currentLineIndex) // looped!
    }

    @Test
    fun `bulk vocab parser and practice track mastery and weak words`() = runBlocking {
        repo.ensureSeeded()
        val parsed = RevisionRepository.parseBulkVocab(
            "resilient - দৃঢ়চেতা\nserene, eloquent - বাগ্মী"
        )
        assertEquals(3, parsed.size)
        assertEquals("resilient" to "দৃঢ়চেতা", parsed[0])
        assertEquals("serene" to "", parsed[1])
        assertEquals("eloquent" to "বাগ্মী", parsed[2])

        repo.addVocabList("Unit 1", 15, reverseMode = false, rawBulkWords = "resilient - দৃঢ়চেতা")
        val list = dao.getAllVocabListsOnce().single()
        val word = dao.getAllVocabWordsOnce().single()

        repo.recordVocabPractice(list.id, word.id, correct = false)
        val afterWrong = dao.getVocabWordById(word.id)!!
        assertFalse(afterWrong.mastered)
        assertEquals(1, afterWrong.weakCount)
        assertEquals(1, dao.getAllWeakWordsOnce().size)

        repo.recordVocabPractice(list.id, word.id, correct = true)
        val afterRight = dao.getVocabWordById(word.id)!!
        assertTrue(afterRight.mastered)
    }

    @Test
    fun `translation cache key is deterministic and returns cached meaning without network`() = runBlocking {
        val key1 = TranslationService.buildCacheKey("Resilient", "en", "bn")
        val key2 = TranslationService.buildCacheKey("  resilient  ", "en", "bn")
        assertEquals(key1, key2)

        repo.saveManualSingleTranslation("Resilient", "দৃঢ়চেতা")
        val outcome = repo.translateSingleItem("resilient")
        assertTrue(outcome is TranslationOutcome.Success)
        val success = outcome as TranslationOutcome.Success
        assertEquals("দৃঢ়চেতা", success.translatedText)
        assertTrue(success.fromCache)
    }

    @Test
    fun `JSON backup export and import round-trip preserves all data including alarms, weakWords and stats`() = runBlocking {
        repo.ensureSeeded()
        val bookId = DefaultBooksSeed.books[0].id
        repo.addChapter(bookId, "ব্যাকআপ অধ্যায়")
        val chId = dao.getAllChaptersOnce().single().id
        repo.addNote(bookId, chId, "গুরুত্বপূর্ণ নোট", starred = true)
        val noteId = dao.getAllNotesOnce().single().id
        repo.addOrUpdateParagraph(null, "Para 1", "First. Second.", 20, memoryMode = true, enabled = true)
        val paraId = dao.getAllParagraphsOnce().single().id
        repo.addVocabList("Vocab 1", 10, reverseMode = true, rawBulkWords = "lucid - স্পষ্ট")
        repo.addOrIncrementWeakWord("ephemeral", "ক্ষণস্থায়ী", "vocab")
        repo.saveAlarm(
            RevisionAlarmEntity(
                id = "al_backup_1",
                title = "সকালের রুটিন",
                scheduleType = "clock",
                hourOfDay = 7,
                minute = 30,
                selectedNoteIds = listOf(noteId),
                selectedParagraphIds = listOf(paraId)
            )
        )
        repo.updateStats { it.copy(themeMode = "dark", defaultSnoozeMinutes = 10, streakCount = 4) }

        val json = repo.exportBackupJson()

        // Import into a fresh in-memory database
        val db2 = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val dao2 = db2.appDao()
        val repo2 = RevisionRepository(
            context,
            dao2,
            FirestoreSyncRepository(
                db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
                dao = dao2,
                auth = FirebaseAuth.getInstance()
            )
        )
        val res = repo2.importBackupJson(json)
        assertTrue(res.isSuccess)
        assertEquals(11, dao2.getAllBooksOnce().size)
        assertEquals(1, dao2.getAllChaptersOnce().size)
        assertEquals(1, dao2.getAllNotesOnce().size)
        assertEquals(1, dao2.getAllParagraphsOnce().size)
        assertEquals(1, dao2.getAllVocabListsOnce().size)
        assertEquals(1, dao2.getAllVocabWordsOnce().size)
        assertEquals(1, dao2.getAllWeakWordsOnce().size)
        assertEquals(1, dao2.getAllAlarmsOnce().size)
        val importedAlarm = dao2.getAllAlarmsOnce().single()
        assertEquals("সকালের রুটিন", importedAlarm.title)
        assertEquals(listOf(noteId), importedAlarm.selectedNoteIds)
        assertEquals(listOf(paraId), importedAlarm.selectedParagraphIds)
        val importedStats = dao2.getUserStatsOnce()!!
        assertEquals("dark", importedStats.themeMode)
        assertEquals(10, importedStats.defaultSnoozeMinutes)
        assertEquals(4, importedStats.streakCount)
        db2.close()
    }

    @Test
    fun `ics calendar export generates valid VCALENDAR with VEVENT and VALARM`() {
        val ics = AlarmScheduler.generateIcsContent(
            books = listOf(DefaultBooksSeed.books[0]),
            paragraphs = listOf(ParagraphFileEntity("p1", "Winter", "Cold.", listOf("Cold."))),
            vocabLists = listOf(VocabListEntity("v1", "Unit 1")),
            alarms = listOf(RevisionAlarmEntity("al1", "Evening Drill", scheduleType = "clock", hourOfDay = 19, minute = 0))
        )
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("Evening Drill"))
        assertTrue(ics.contains("END:VCALENDAR"))
    }

    @Test
    fun `multiple custom alarms filter granular notes, paragraphs, vocab and rotate cleanly`() = runBlocking {
        repo.ensureSeeded()
        val book = DefaultBooksSeed.books[0]
        repo.addChapter(book.id, "অধ্যায় ১")
        val ch1 = dao.getAllChaptersOnce().single()
        repo.addNote(book.id, ch1.id, "নির্বাচিত নোট ১")
        repo.addNote(book.id, ch1.id, "বাদ দেওয়া নোট ২")
        val allNotes = dao.getAllNotesOnce()
        val note1 = allNotes.first { it.content == "নির্বাচিত নোট ১" }

        repo.addOrUpdateParagraph(null, "Environment", "Trees give us oxygen. Protect forests. Plant more trees.", 15, false, true)
        val para = dao.getAllParagraphsOnce().single()
        assertEquals(3, para.lines.size)

        repo.addVocabList("Daily Words", 20, false, "candid - স্পষ্টভাষী")
        val vList = dao.getAllVocabListsOnce().single()

        val alarm1 = repo.saveAlarm(
            RevisionAlarmEntity(
                id = "al_multi_1",
                title = "অ্যালার্ম ১",
                scheduleType = "interval",
                intervalMinutes = 10,
                selectedNoteIds = listOf(note1.id),
                selectedParagraphIds = listOf(para.id),
                selectedVocabListIds = listOf(vList.id),
                lastTriggeredEpochMs = 1_000_000L
            )
        )
        val alarm2 = repo.saveAlarm(
            RevisionAlarmEntity(
                id = "al_multi_2",
                title = "অ্যালার্ম ২",
                scheduleType = "interval",
                intervalMinutes = 25,
                selectedChapterIds = listOf(ch1.id),
                lastTriggeredEpochMs = 1_000_000L
            )
        )

        val resolved1 = AlarmScheduler.resolveAlarmItems(
            alarm = alarm1,
            books = dao.getAllBooksOnce(),
            chapters = dao.getAllChaptersOnce(),
            notes = dao.getAllNotesOnce(),
            paragraphs = dao.getAllParagraphsOnce(),
            vocabLists = dao.getAllVocabListsOnce(),
            vocabWords = dao.getAllVocabWordsOnce()
        )
        // Must contain ONLY note1, para, vList (never note2!)
        assertEquals(3, resolved1.size)
        assertEquals(listOf(note1.id, para.id, vList.id), resolved1.map { it.itemId })

        val resolved2 = AlarmScheduler.resolveAlarmItems(
            alarm = alarm2,
            books = dao.getAllBooksOnce(),
            chapters = dao.getAllChaptersOnce(),
            notes = dao.getAllNotesOnce(),
            paragraphs = dao.getAllParagraphsOnce(),
            vocabLists = dao.getAllVocabListsOnce(),
            vocabWords = dao.getAllVocabWordsOnce()
        )
        assertEquals(2, resolved2.size)

        // Advance cursor on alarm1 rotates to paragraph, then vocab
        repo.markAlarmTriggered(alarm1.id, advanceCursor = true)
        val trigAfterOne = AlarmScheduler.computeNextTriggerForAlarm(
            alarm = dao.getAlarmById(alarm1.id)!!,
            nowMs = System.currentTimeMillis(),
            stats = UserStatsEntity(),
            books = dao.getAllBooksOnce(),
            chapters = dao.getAllChaptersOnce(),
            notes = dao.getAllNotesOnce(),
            paragraphs = dao.getAllParagraphsOnce(),
            vocabLists = dao.getAllVocabListsOnce(),
            vocabWords = dao.getAllVocabWordsOnce()
        )!!
        assertEquals("paragraph", trigAfterOne.resolvedType)
        assertEquals(para.id, trigAfterOne.resolvedTargetId)

        // Snooze ONLY alarm1; alarm2 remains unsnoozed
        repo.snoozeSingleAlarm(alarm1.id, 15)
        assertTrue(dao.getAlarmById(alarm1.id)!!.snoozedUntilEpochMs > System.currentTimeMillis())
        assertEquals(0L, dao.getAlarmById(alarm2.id)!!.snoozedUntilEpochMs)
    }

    @Test
    fun `clock alarm schedules exact daily time and rolls to next allowed day after firing`() {
        val nowCal = Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, 15, 7, 45, 0) // Monday 07:45
            set(Calendar.MILLISECOND, 0)
        }
        val nextBefore = AlarmScheduler.computeNextClockTriggerMs(
            nowMs = nowCal.timeInMillis,
            lastTriggeredEpochMs = 0L,
            hourOfDay = 8,
            minute = 30,
            repeatDaysCsv = ""
        )
        val resCal = Calendar.getInstance().apply { timeInMillis = nextBefore }
        assertEquals(15, resCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, resCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, resCal.get(Calendar.MINUTE))

        // After firing at 08:31 Monday, next occurrence must roll to Tuesday 08:30
        val firedAtCal = Calendar.getInstance().apply {
            set(2026, Calendar.JUNE, 15, 8, 31, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val nextAfter = AlarmScheduler.computeNextClockTriggerMs(
            nowMs = firedAtCal.timeInMillis,
            lastTriggeredEpochMs = firedAtCal.timeInMillis,
            hourOfDay = 8,
            minute = 30,
            repeatDaysCsv = ""
        )
        val afterCal = Calendar.getInstance().apply { timeInMillis = nextAfter }
        assertEquals(16, afterCal.get(Calendar.DAY_OF_MONTH))
        assertEquals(8, afterCal.get(Calendar.HOUR_OF_DAY))
        assertEquals(30, afterCal.get(Calendar.MINUTE))
    }

    @Test
    fun `migration 1 to 2 creates revision_alarms table cleanly`() {
        val helperDb = android.database.sqlite.SQLiteDatabase.create(null)
        val frameworkDb = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory()
        assertNotNull(frameworkDb)
        helperDb.close()
        // Verify MIGRATION_1_2 SQL executes without syntax error on an actual SupportSQLiteDatabase
        val openDb = context.openOrCreateDatabase("migration_test_check.db", Context.MODE_PRIVATE, null)
        openDb.execSQL("DROP TABLE IF EXISTS revision_alarms")
        val supportDb = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_verify.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(2) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    AppDatabase.MIGRATION_1_2.migrate(db)
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val conn = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(supportDb).writableDatabase
        conn.execSQL("INSERT INTO revision_alarms VALUES ('id1','Test',1,'interval',15,8,0,'',0,480,1320,'[]','[]','[]','[]','[]',0,0,0,100)")
        val cursor = conn.query("SELECT title FROM revision_alarms WHERE id='id1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("Test", cursor.getString(0))
        cursor.close()
        conn.close()
        openDb.close()
        context.deleteDatabase("migration_test_check.db")
        context.deleteDatabase("migration_verify.db")
    }

    @Test
    fun `one-minute timer schedules exactly 60 seconds ahead and 50 notes follow user sortOrder`() = runBlocking {
        repo.ensureSeeded()
        val book = DefaultBooksSeed.books[0]
        assertFalse("Default books should run 24h unless night pause enabled", book.useActiveHours)

        repo.addChapter(book.id, "বড় অধ্যায়")
        val chapter = dao.getAllChaptersOnce().single()

        // Simulate adding 50 notes in bulk
        val fiftyLines = (1..50).joinToString("\n") { "নোট নম্বর $it" }
        repo.addMultipleNotes(book.id, chapter.id, fiftyLines, starred = false)

        val notes = dao.getAllNotesOnce()
        assertEquals(50, notes.size)
        assertEquals((0..49).toList(), notes.map { it.sortOrder })
        assertEquals("নোট নম্বর 1", notes.first().content)
        assertEquals("নোট নম্বর 50", notes.last().content)

        // User changes chapter timer to 1 minute after notes were added
        val beforeUpdateMs = System.currentTimeMillis()
        repo.updateChapter(chapter.copy(overrideEnabled = 1, overrideIntervalMinutes = 1))
        val afterUpdateMs = System.currentTimeMillis()

        val firstTrigger = AlarmScheduler.computeNextTrigger(
            nowMs = afterUpdateMs,
            stats = UserStatsEntity(),
            books = dao.getAllBooksOnce(),
            chapters = dao.getAllChaptersOnce(),
            notes = dao.getAllNotesOnce(),
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )!!

        // Must pick Note #1 and schedule 60 seconds after the timer was set (not overdue immediately!)
        assertEquals(notes.first().id, firstTrigger.targetId)
        assertTrue(
            "1-min timer should schedule ~60s ahead",
            firstTrigger.nextTriggerEpochMs in (beforeUpdateMs + 59_000L)..(afterUpdateMs + 61_000L)
        )

        // Move Note #50 to position #1 (index 0) and verify it now comes FIRST
        val note50 = notes.last()
        repo.moveNoteToPosition(chapter.id, note50.id, 0)
        val reordered = dao.getAllNotesOnce()
        assertEquals(note50.id, reordered.first().id)
        assertEquals(0, reordered.first().sortOrder)

        val afterReorderTrigger = AlarmScheduler.computeNextTrigger(
            nowMs = System.currentTimeMillis(),
            stats = UserStatsEntity(),
            books = dao.getAllBooksOnce(),
            chapters = dao.getAllChaptersOnce(),
            notes = reordered,
            paragraphs = emptyList(),
            vocabLists = emptyList(),
            vocabWords = emptyList()
        )!!
        assertEquals(note50.id, afterReorderTrigger.targetId)

        // Set Note #25 to its own independent 2-minute interval while chapter stays 1 minute
        val note25 = reordered.first { it.content == "নোট নম্বর 25" }
        repo.updateNote(note25.copy(overrideEnabled = 1, overrideIntervalMinutes = 2))
        val updated25 = dao.getNoteById(note25.id)!!
        assertTrue(updated25.lastTriggeredEpochMs > 0L)
    }

    @Test
    fun `migration 2 to 3 adds sortOrder column to notes table`() {
        val supportDb = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_2_3_verify.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(3) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, chapterId TEXT NOT NULL, content TEXT NOT NULL, starred INTEGER NOT NULL, overrideEnabled INTEGER NOT NULL, overrideIntervalMinutes INTEGER NOT NULL, revisionCount INTEGER NOT NULL, lastRevisedEpochMs INTEGER NOT NULL, lastRevisedDay TEXT NOT NULL, lastTriggeredEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)"
                    )
                    AppDatabase.MIGRATION_2_3.migrate(db)
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val conn = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(supportDb).writableDatabase
        conn.execSQL("INSERT INTO notes (id,bookId,chapterId,content,starred,overrideEnabled,overrideIntervalMinutes,revisionCount,lastRevisedEpochMs,lastRevisedDay,lastTriggeredEpochMs,updatedAtEpochMs) VALUES ('n1','b1','c1','Hello',0,-1,0,0,0,'',0,100)")
        val cursor = conn.query("SELECT sortOrder FROM notes WHERE id='n1'")
        assertTrue(cursor.moveToFirst())
        assertEquals(0, cursor.getInt(0))
        cursor.close()
        conn.close()
        context.deleteDatabase("migration_2_3_verify.db")
    }

    @Test
    fun `migration 3 to 4 adds imagePath column to notes table`() {
        val supportDb = androidx.sqlite.db.SupportSQLiteOpenHelper.Configuration.builder(context)
            .name("migration_3_4_verify.db")
            .callback(object : androidx.sqlite.db.SupportSQLiteOpenHelper.Callback(4) {
                override fun onCreate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                    db.execSQL(
                        "CREATE TABLE notes (id TEXT NOT NULL PRIMARY KEY, bookId TEXT NOT NULL, chapterId TEXT NOT NULL, content TEXT NOT NULL, starred INTEGER NOT NULL, sortOrder INTEGER NOT NULL DEFAULT 0, overrideEnabled INTEGER NOT NULL, overrideIntervalMinutes INTEGER NOT NULL, revisionCount INTEGER NOT NULL, lastRevisedEpochMs INTEGER NOT NULL, lastRevisedDay TEXT NOT NULL, lastTriggeredEpochMs INTEGER NOT NULL, updatedAtEpochMs INTEGER NOT NULL)"
                    )
                    AppDatabase.MIGRATION_3_4.migrate(db)
                }
                override fun onUpgrade(db: androidx.sqlite.db.SupportSQLiteDatabase, oldVersion: Int, newVersion: Int) {}
            })
            .build()
        val conn = androidx.sqlite.db.framework.FrameworkSQLiteOpenHelperFactory().create(supportDb).writableDatabase
        conn.execSQL("INSERT INTO notes (id,bookId,chapterId,content,starred,sortOrder,overrideEnabled,overrideIntervalMinutes,revisionCount,lastRevisedEpochMs,lastRevisedDay,lastTriggeredEpochMs,updatedAtEpochMs) VALUES ('n1','b1','c1','Diagram',0,0,-1,0,0,0,'',0,100)")
        val cursor = conn.query("SELECT imagePath FROM notes WHERE id='n1'")
        assertTrue(cursor.moveToFirst())
        assertEquals("", cursor.getString(0))
        cursor.close()
        conn.close()
        context.deleteDatabase("migration_3_4_verify.db")
    }

    @Test
    fun `note image files copy locally, reorder to any position, and clean up on delete`() = runBlocking {
        repo.ensureSeeded()
        val book = DefaultBooksSeed.books[0]
        repo.addChapter(book.id, "চিত্র অধ্যায়")
        val chapter = dao.getAllChaptersOnce().single()

        // Create a temporary PNG bitmap and save via URI
        val tempInput = java.io.File(context.cacheDir, "sample_diagram.png")
        val bmp = android.graphics.Bitmap.createBitmap(120, 80, android.graphics.Bitmap.Config.ARGB_8888)
        java.io.FileOutputStream(tempInput).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
        bmp.recycle()

        val savedPath = repo.savePickedNoteImage(android.net.Uri.fromFile(tempInput))
        assertNotNull(savedPath)
        assertTrue(java.io.File(savedPath!!).exists())

        repo.addNote(book.id, chapter.id, "প্রথম লেখা")
        repo.addNote(book.id, chapter.id, "", starred = true, imagePath = savedPath)

        val notes = dao.getAllNotesOnce()
        assertEquals(2, notes.size)
        val imgNote = notes.last()
        assertEquals("চিত্র নোট", imgNote.content)
        assertEquals(savedPath, imgNote.imagePath)

        // Move image note to #1 (dragged to top)
        repo.moveNoteToPosition(chapter.id, imgNote.id, 0)
        assertEquals(imgNote.id, dao.getAllNotesOnce().first().id)

        // Delete image note cleans up local image file
        repo.deleteNote(imgNote.id)
        assertFalse(java.io.File(savedPath).exists())
        assertTrue(tempInput.delete())
    }

    @Test
    fun `different gmail accounts and guest mode have strictly isolated databases and image directories`(): Unit = runBlocking {
        val uid1 = "gmail_uid_1"
        val uid2 = "gmail_uid_2"

        val db1 = AppDatabase.getInstanceForUser(context, uid1)
        val db2 = AppDatabase.getInstanceForUser(context, uid2)
        val dbLocal = AppDatabase.getInstanceForUser(context, null)
        try {
            assertEquals("revision_alarm_u_gmail_uid_1.db", AppDatabase.dbNameForUser(uid1))
            assertEquals("revision_alarm_u_gmail_uid_2.db", AppDatabase.dbNameForUser(uid2))
            assertEquals("revision_alarm_local.db", AppDatabase.dbNameForUser(null))

            val fs1 = FirestoreSyncRepository(
                db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
                dao = db1.appDao(),
                auth = FirebaseAuth.getInstance()
            )
            val fs2 = FirestoreSyncRepository(
                db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
                dao = db2.appDao(),
                auth = FirebaseAuth.getInstance()
            )
            val fsLocal = FirestoreSyncRepository(
                db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
                dao = dbLocal.appDao(),
                auth = FirebaseAuth.getInstance()
            )

            val repo1 = RevisionRepository(context, db1.appDao(), fs1, initialUserId = uid1)
            val repo2 = RevisionRepository(context, db2.appDao(), fs2, initialUserId = uid2)
            val repoLocal = RevisionRepository(context, dbLocal.appDao(), fsLocal, initialUserId = null)

            repo1.ensureSeeded()
            repo2.ensureSeeded()
            repoLocal.ensureSeeded()

            // Create temporary image to verify scoped image directories
            val tempInput = java.io.File(context.cacheDir, "acct_isolation_img.png")
            val bmp = android.graphics.Bitmap.createBitmap(64, 64, android.graphics.Bitmap.Config.ARGB_8888)
            java.io.FileOutputStream(tempInput).use { bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) }
            bmp.recycle()

            val img1 = repo1.savePickedNoteImage(android.net.Uri.fromFile(tempInput))!!
            val img2 = repo2.savePickedNoteImage(android.net.Uri.fromFile(tempInput))!!
            assertTrue(img1.contains("/note_images/u_gmail_uid_1/"))
            assertTrue(img2.contains("/note_images/u_gmail_uid_2/"))
            assertNotEquals(img1, img2)

            // Account 1 adds data
            val bookId = DefaultBooksSeed.books[0].id
            repo1.addChapter(bookId, "Gmail 1 Chapter")
            val ch1 = db1.appDao().getAllChaptersOnce().single()
            repo1.addNote(bookId, ch1.id, "Secret note from Gmail 1", imagePath = img1)
            repo1.addOrUpdateParagraph(null, "Gmail 1 Para", "Alpha line. Beta line.", 10, false, true)
            repo1.addVocabList("Gmail 1 Vocab", 15, false, "candid - স্পষ্টভাষী")
            repo1.saveAlarm(RevisionAlarmEntity(id = "al_u1", title = "Gmail 1 Alarm"))

            // Account 2 starts clean with ZERO leakage from Account 1
            assertTrue(db2.appDao().getAllChaptersOnce().isEmpty())
            assertTrue(db2.appDao().getAllNotesOnce().isEmpty())
            assertTrue(db2.appDao().getAllParagraphsOnce().isEmpty())
            assertTrue(db2.appDao().getAllVocabListsOnce().isEmpty())
            assertTrue(db2.appDao().getAllAlarmsOnce().isEmpty())

            // Guest/local mode also starts clean with ZERO leakage
            assertTrue(dbLocal.appDao().getAllChaptersOnce().isEmpty())
            assertTrue(dbLocal.appDao().getAllNotesOnce().isEmpty())

            // Account 2 adds its own data
            repo2.addChapter(bookId, "Gmail 2 Chapter")
            val ch2 = db2.appDao().getAllChaptersOnce().single()
            repo2.addNote(bookId, ch2.id, "Note from Gmail 2", imagePath = img2)
            assertEquals(1, db2.appDao().getAllNotesOnce().size)
            assertEquals("Note from Gmail 2", db2.appDao().getAllNotesOnce().single().content)

            // Verify Account 1 still has ONLY Account 1 data
            assertEquals(1, db1.appDao().getAllChaptersOnce().size)
            assertEquals("Gmail 1 Chapter", db1.appDao().getAllChaptersOnce().single().title)
            assertEquals(1, db1.appDao().getAllNotesOnce().size)
            assertEquals("Secret note from Gmail 1", db1.appDao().getAllNotesOnce().single().content)

            // Active scope helper switches background receiver database dynamically
            AppDatabase.setActiveUserScope(context, uid1)
            assertEquals("Secret note from Gmail 1", AppDatabase.getInstance(context).appDao().getAllNotesOnce().single().content)
            AppDatabase.setActiveUserScope(context, uid2)
            assertEquals("Note from Gmail 2", AppDatabase.getInstance(context).appDao().getAllNotesOnce().single().content)

            repo1.deleteUnusedLocalImage(img1)
            repo2.deleteUnusedLocalImage(img2)
            tempInput.delete()
        } finally {
            AppDatabase.closeCachedInstance(uid1)
            AppDatabase.closeCachedInstance(uid2)
            AppDatabase.closeCachedInstance(null)
            context.deleteDatabase(AppDatabase.dbNameForUser(uid1))
            context.deleteDatabase(AppDatabase.dbNameForUser(uid2))
            context.deleteDatabase(AppDatabase.dbNameForUser(null))
        }
    }
}

