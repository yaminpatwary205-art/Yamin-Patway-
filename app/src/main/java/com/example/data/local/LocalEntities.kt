package com.example.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import org.json.JSONArray

class RoomConverters {
    @TypeConverter
    fun fromStringList(list: List<String>): String {
        val arr = JSONArray()
        list.forEach { arr.put(it) }
        return arr.toString()
    }

    @TypeConverter
    fun toStringList(json: String): List<String> {
        if (json.isBlank()) return emptyList()
        return try {
            val arr = JSONArray(json)
            (0 until arr.length()).map { arr.getString(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

@Entity(tableName = "books")
data class BookEntity(
    @PrimaryKey val id: String,
    val title: String,
    val colorHex: String,
    val iconKey: String,
    val sortOrder: Int,
    val enabled: Boolean = true,
    val intervalMinutes: Int = 30,
    val useActiveHours: Boolean = false,
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 22 * 60,
    val examDateEpochMs: Long = 0L,
    val lastTriggeredEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "chapters")
data class ChapterEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val title: String,
    val sortOrder: Int,
    val overrideEnabled: Int = -1, // -1 inherit, 0 off, 1 on
    val overrideIntervalMinutes: Int = 0, // 0 inherit
    val useActiveHours: Int = -1, // -1 inherit, 0 off, 1 on
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 22 * 60,
    val lastTriggeredEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "notes")
data class NoteEntity(
    @PrimaryKey val id: String,
    val bookId: String,
    val chapterId: String,
    val content: String,
    val imagePath: String = "",
    val starred: Boolean = false,
    val sortOrder: Int = 0,
    val overrideEnabled: Int = -1, // -1 inherit, 0 off, 1 on
    val overrideIntervalMinutes: Int = 0, // 0 inherit
    val revisionCount: Int = 0,
    val lastRevisedEpochMs: Long = 0L,
    val lastRevisedDay: String = "",
    val lastTriggeredEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "paragraphs")
data class ParagraphFileEntity(
    @PrimaryKey val id: String,
    val title: String,
    val rawText: String,
    val lines: List<String>,
    val currentLineIndex: Int = 0,
    val enabled: Boolean = true,
    val intervalMinutes: Int = 15,
    val memoryMode: Boolean = false,
    val lastTriggeredEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "vocab_lists")
data class VocabListEntity(
    @PrimaryKey val id: String,
    val title: String,
    val enabled: Boolean = true,
    val intervalMinutes: Int = 15,
    val reverseMode: Boolean = false,
    val lastTriggeredEpochMs: Long = 0L,
    val currentIndex: Int = 0,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "vocab_words")
data class VocabWordEntity(
    @PrimaryKey val id: String,
    val listId: String,
    val word: String,
    val meaningBn: String = "",
    val enabled: Boolean = true,
    val mastered: Boolean = false,
    val weakCount: Int = 0,
    val practiceCount: Int = 0,
    val lastPracticedEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "weak_words")
data class WeakWordEntity(
    @PrimaryKey val id: String,
    val word: String,
    val meaningBn: String = "",
    val sourceType: String = "vocab", // "paragraph" or "vocab"
    val mistakeCount: Int = 1,
    val lastMistakeEpochMs: Long = System.currentTimeMillis(),
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "translations")
data class TranslationCacheEntity(
    @PrimaryKey val id: String,
    val sourceText: String,
    val sourceLang: String = "en",
    val targetLang: String = "bn",
    val translatedText: String,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "user_stats")
data class UserStatsEntity(
    @PrimaryKey val id: String = "main",
    val displayName: String = "",
    val email: String = "",
    val themeMode: String = "system",
    val soundEnabled: Boolean = true,
    val alarmVolume: Float = 0.85f,
    val vibrationEnabled: Boolean = true,
    val defaultSnoozeMinutes: Int = 5,
    val vocabOrderMode: String = "ordered", // "ordered" or "random"
    val streakCount: Int = 0,
    val lastActiveDay: String = "",
    val todayRevisionCount: Int = 0,
    val todayDate: String = "",
    val alarmModeActive: Boolean = false,
    val snoozedUntilEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

@Entity(tableName = "pending_deletes")
data class PendingDeleteEntity(
    @PrimaryKey val compositeKey: String, // e.g. "notes:note_123"
    val collectionName: String,
    val docId: String
)

@Entity(tableName = "revision_alarms")
data class RevisionAlarmEntity(
    @PrimaryKey val id: String,
    val title: String,
    val enabled: Boolean = true,
    val scheduleType: String = "interval", // "interval" or "clock"
    val intervalMinutes: Int = 15,
    val hourOfDay: Int = 8,
    val minute: Int = 0,
    val repeatDaysCsv: String = "", // "" = daily, else comma-separated Calendar.DAY_OF_WEEK (1..7)
    val useActiveHours: Boolean = false,
    val activeStartMinutes: Int = 8 * 60,
    val activeEndMinutes: Int = 22 * 60,
    val selectedBookIds: List<String> = emptyList(),
    val selectedChapterIds: List<String> = emptyList(),
    val selectedNoteIds: List<String> = emptyList(),
    val selectedParagraphIds: List<String> = emptyList(),
    val selectedVocabListIds: List<String> = emptyList(),
    val rotationCursor: Int = 0,
    val lastTriggeredEpochMs: Long = 0L,
    val snoozedUntilEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = System.currentTimeMillis()
)

object DefaultBooksSeed {
    val books: List<BookEntity> = listOf(
        BookEntity("book_01", "বাংলা প্রথম পত্র", "#E11D48", "auto_stories", 0),
        BookEntity("book_02", "বাংলা দ্বিতীয় পত্র", "#7C3AED", "edit_note", 1),
        BookEntity("book_03", "ইংলিশ প্রথম পত্র", "#2563EB", "menu_book", 2),
        BookEntity("book_04", "ইংলিশ দ্বিতীয় পত্র", "#0891B2", "spellcheck", 3),
        BookEntity("book_05", "সাধারণ গণিত", "#059669", "calculate", 4),
        BookEntity("book_06", "সাধারণ বিজ্ঞান", "#D97706", "science", 5),
        BookEntity("book_07", "হিসাববিজ্ঞান", "#EA580C", "account_balance", 6),
        BookEntity("book_08", "ব্যবসায় উদ্যোগ", "#DB2777", "storefront", 7),
        BookEntity("book_09", "ফিন্যান্স ও ব্যাংকিং", "#4F46E5", "payments", 8),
        BookEntity("book_10", "ইসলাম শিক্ষা", "#0D9488", "mosque", 9),
        BookEntity("book_11", "আইসিটি", "#0284C7", "computer", 10)
    )
}
