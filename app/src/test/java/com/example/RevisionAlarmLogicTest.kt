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
    fun `JSON backup export and import round-trip preserves all data including weakWords and stats`() = runBlocking {
        repo.ensureSeeded()
        val bookId = DefaultBooksSeed.books[0].id
        repo.addChapter(bookId, "ব্যাকআপ অধ্যায়")
        val chId = dao.getAllChaptersOnce().single().id
        repo.addNote(bookId, chId, "গুরুত্বপূর্ণ নোট", starred = true)
        repo.addOrUpdateParagraph(null, "Para 1", "First. Second.", 20, memoryMode = true, enabled = true)
        repo.addVocabList("Vocab 1", 10, reverseMode = true, rawBulkWords = "lucid - স্পষ্ট")
        repo.addOrIncrementWeakWord("ephemeral", "ক্ষণস্থায়ী", "vocab")
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
            vocabLists = listOf(VocabListEntity("v1", "Unit 1"))
        )
        assertTrue(ics.contains("BEGIN:VCALENDAR"))
        assertTrue(ics.contains("BEGIN:VEVENT"))
        assertTrue(ics.contains("BEGIN:VALARM"))
        assertTrue(ics.contains("END:VCALENDAR"))
    }
}
