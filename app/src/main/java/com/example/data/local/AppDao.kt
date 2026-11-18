package com.example.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface AppDao {
    // Books
    @Query("SELECT * FROM books ORDER BY sortOrder ASC")
    fun observeBooks(): Flow<List<BookEntity>>

    @Query("SELECT * FROM books ORDER BY sortOrder ASC")
    suspend fun getAllBooksOnce(): List<BookEntity>

    @Query("SELECT * FROM books WHERE id = :id LIMIT 1")
    suspend fun getBookById(id: String): BookEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBook(book: BookEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertBooks(books: List<BookEntity>)

    // Chapters
    @Query("SELECT * FROM chapters ORDER BY sortOrder ASC")
    fun observeAllChapters(): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters WHERE bookId = :bookId ORDER BY sortOrder ASC")
    fun observeChaptersForBook(bookId: String): Flow<List<ChapterEntity>>

    @Query("SELECT * FROM chapters ORDER BY sortOrder ASC")
    suspend fun getAllChaptersOnce(): List<ChapterEntity>

    @Query("SELECT * FROM chapters WHERE id = :id LIMIT 1")
    suspend fun getChapterById(id: String): ChapterEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapter(chapter: ChapterEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertChapters(chapters: List<ChapterEntity>)

    @Query("DELETE FROM chapters WHERE id = :id")
    suspend fun deleteChapterById(id: String)

    // Notes
    @Query("SELECT * FROM notes ORDER BY updatedAtEpochMs DESC")
    fun observeAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE chapterId = :chapterId ORDER BY starred DESC, updatedAtEpochMs DESC")
    fun observeNotesForChapter(chapterId: String): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes")
    suspend fun getAllNotesOnce(): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE id = :id LIMIT 1")
    suspend fun getNoteById(id: String): NoteEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNote(note: NoteEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertNotes(notes: List<NoteEntity>)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: String)

    @Query("DELETE FROM notes WHERE chapterId = :chapterId")
    suspend fun deleteNotesForChapter(chapterId: String)

    // Paragraphs
    @Query("SELECT * FROM paragraphs ORDER BY updatedAtEpochMs DESC")
    fun observeParagraphs(): Flow<List<ParagraphFileEntity>>

    @Query("SELECT * FROM paragraphs")
    suspend fun getAllParagraphsOnce(): List<ParagraphFileEntity>

    @Query("SELECT * FROM paragraphs WHERE id = :id LIMIT 1")
    suspend fun getParagraphById(id: String): ParagraphFileEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParagraph(paragraph: ParagraphFileEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertParagraphs(paragraphs: List<ParagraphFileEntity>)

    @Query("DELETE FROM paragraphs WHERE id = :id")
    suspend fun deleteParagraphById(id: String)

    // Vocab Lists
    @Query("SELECT * FROM vocab_lists ORDER BY updatedAtEpochMs DESC")
    fun observeVocabLists(): Flow<List<VocabListEntity>>

    @Query("SELECT * FROM vocab_lists")
    suspend fun getAllVocabListsOnce(): List<VocabListEntity>

    @Query("SELECT * FROM vocab_lists WHERE id = :id LIMIT 1")
    suspend fun getVocabListById(id: String): VocabListEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVocabList(list: VocabListEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVocabLists(lists: List<VocabListEntity>)

    @Query("DELETE FROM vocab_lists WHERE id = :id")
    suspend fun deleteVocabListById(id: String)

    // Vocab Words
    @Query("SELECT * FROM vocab_words ORDER BY updatedAtEpochMs DESC")
    fun observeAllVocabWords(): Flow<List<VocabWordEntity>>

    @Query("SELECT * FROM vocab_words WHERE listId = :listId ORDER BY updatedAtEpochMs DESC")
    fun observeVocabWordsForList(listId: String): Flow<List<VocabWordEntity>>

    @Query("SELECT * FROM vocab_words")
    suspend fun getAllVocabWordsOnce(): List<VocabWordEntity>

    @Query("SELECT * FROM vocab_words WHERE id = :id LIMIT 1")
    suspend fun getVocabWordById(id: String): VocabWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVocabWord(word: VocabWordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertVocabWords(words: List<VocabWordEntity>)

    @Query("DELETE FROM vocab_words WHERE id = :id")
    suspend fun deleteVocabWordById(id: String)

    @Query("DELETE FROM vocab_words WHERE listId = :listId")
    suspend fun deleteVocabWordsForList(listId: String)

    // Weak Words
    @Query("SELECT * FROM weak_words ORDER BY mistakeCount DESC, lastMistakeEpochMs DESC")
    fun observeWeakWords(): Flow<List<WeakWordEntity>>

    @Query("SELECT * FROM weak_words")
    suspend fun getAllWeakWordsOnce(): List<WeakWordEntity>

    @Query("SELECT * FROM weak_words WHERE id = :id LIMIT 1")
    suspend fun getWeakWordById(id: String): WeakWordEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeakWord(word: WeakWordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertWeakWords(words: List<WeakWordEntity>)

    @Query("DELETE FROM weak_words WHERE id = :id")
    suspend fun deleteWeakWordById(id: String)

    // Translations Cache
    @Query("SELECT * FROM translations")
    fun observeTranslations(): Flow<List<TranslationCacheEntity>>

    @Query("SELECT * FROM translations")
    suspend fun getAllTranslationsOnce(): List<TranslationCacheEntity>

    @Query("SELECT * FROM translations WHERE id = :id LIMIT 1")
    suspend fun getTranslationById(id: String): TranslationCacheEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslation(translation: TranslationCacheEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertTranslations(translations: List<TranslationCacheEntity>)

    // User Stats & Settings
    @Query("SELECT * FROM user_stats WHERE id = 'main' LIMIT 1")
    fun observeUserStats(): Flow<UserStatsEntity?>

    @Query("SELECT * FROM user_stats WHERE id = 'main' LIMIT 1")
    suspend fun getUserStatsOnce(): UserStatsEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertUserStats(stats: UserStatsEntity)

    // Pending Deletes
    @Query("SELECT * FROM pending_deletes")
    suspend fun getPendingDeletesOnce(): List<PendingDeleteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertPendingDelete(delete: PendingDeleteEntity)

    @Query("DELETE FROM pending_deletes WHERE compositeKey = :compositeKey")
    suspend fun removePendingDelete(compositeKey: String)
}
