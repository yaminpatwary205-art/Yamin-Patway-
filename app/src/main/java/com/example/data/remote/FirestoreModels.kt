package com.example.data.remote

import androidx.annotation.Keep
import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.IgnoreExtraProperties
import com.google.firebase.firestore.ServerTimestamp

@Keep
@IgnoreExtraProperties
data class FsUserProfile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val displayName: String = "",
    val email: String = "",
    val themeMode: String = "system",
    val soundEnabled: Boolean = true,
    val alarmVolume: Double = 0.85,
    val vibrationEnabled: Boolean = true,
    val defaultSnoozeMinutes: Long = 5L,
    val vocabOrderMode: String = "ordered",
    val streakCount: Long = 0L,
    val lastActiveDay: String = "",
    val todayRevisionCount: Long = 0L,
    val todayDate: String = "",
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsBook(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val colorHex: String = "#4F46E5",
    val iconKey: String = "menu_book",
    val sortOrder: Long = 0L,
    val enabled: Boolean = true,
    val intervalMinutes: Long = 30L,
    val useActiveHours: Boolean = true,
    val activeStartMinutes: Long = 480L,
    val activeEndMinutes: Long = 1320L,
    val examDateEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsChapter(
    @DocumentId val id: String = "",
    val userId: String = "",
    val bookId: String = "",
    val title: String = "",
    val sortOrder: Long = 0L,
    val overrideEnabled: Long = -1L,
    val overrideIntervalMinutes: Long = 0L,
    val useActiveHours: Long = -1L,
    val activeStartMinutes: Long = 480L,
    val activeEndMinutes: Long = 1320L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsNote(
    @DocumentId val id: String = "",
    val userId: String = "",
    val bookId: String = "",
    val chapterId: String = "",
    val content: String = "",
    val starred: Boolean = false,
    val overrideEnabled: Long = -1L,
    val overrideIntervalMinutes: Long = 0L,
    val revisionCount: Long = 0L,
    val lastRevisedEpochMs: Long = 0L,
    val lastRevisedDay: String = "",
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsParagraphFile(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val rawText: String = "",
    val lines: List<String> = emptyList(),
    val currentLineIndex: Long = 0L,
    val enabled: Boolean = true,
    val intervalMinutes: Long = 15L,
    val memoryMode: Boolean = false,
    val lastTriggeredEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsVocabList(
    @DocumentId val id: String = "",
    val userId: String = "",
    val title: String = "",
    val enabled: Boolean = true,
    val intervalMinutes: Long = 15L,
    val reverseMode: Boolean = false,
    val lastTriggeredEpochMs: Long = 0L,
    val currentIndex: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsVocabWord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val listId: String = "",
    val word: String = "",
    val meaningBn: String = "",
    val enabled: Boolean = true,
    val mastered: Boolean = false,
    val weakCount: Long = 0L,
    val practiceCount: Long = 0L,
    val lastPracticedEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsWeakWord(
    @DocumentId val id: String = "",
    val userId: String = "",
    val word: String = "",
    val meaningBn: String = "",
    val sourceType: String = "vocab",
    val mistakeCount: Long = 1L,
    val lastMistakeEpochMs: Long = 0L,
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)

@Keep
@IgnoreExtraProperties
data class FsTranslationCache(
    @DocumentId val id: String = "",
    val userId: String = "",
    val sourceText: String = "",
    val sourceLang: String = "en",
    val targetLang: String = "bn",
    val translatedText: String = "",
    val updatedAtEpochMs: Long = 0L,
    @ServerTimestamp val createdAt: Timestamp? = null,
    @ServerTimestamp val updatedAt: Timestamp? = null
)
