package com.example.data.remote

import android.content.Context
import com.example.R
import com.example.data.local.*
import com.example.firebase.OperationType
import com.example.firebase.handleFirestoreError
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.DocumentReference
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.snapshots
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

enum class SyncStatus {
    LOCAL_ONLY,
    SYNCING,
    SYNCED,
    OFFLINE,
    ERROR
}

class FirestoreSyncRepository(
    private val db: FirebaseFirestore,
    private val dao: AppDao,
    private val auth: FirebaseAuth = Firebase.auth
) {
    constructor(context: Context, dao: AppDao) : this(
        FirebaseFirestore.getInstance(
            context.applicationContext.getString(R.string.firestore_database_id)
        ),
        dao
    )

    private fun requireUserId(): String {
        return auth.currentUser?.uid?.takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("User must be signed in with Google before accessing Firestore.")
    }

    private fun userDoc(uid: String) = db.collection("users").document(uid)

    fun observeBooks(uid: String): Flow<List<FsBook>> =
        userDoc(uid).collection("books").snapshots()
            .map { it.toObjects(FsBook::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/books", uid)

    fun observeChapters(uid: String): Flow<List<FsChapter>> =
        userDoc(uid).collection("chapters").snapshots()
            .map { it.toObjects(FsChapter::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/chapters", uid)

    fun observeNotes(uid: String): Flow<List<FsNote>> =
        userDoc(uid).collection("notes").snapshots()
            .map { it.toObjects(FsNote::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/notes", uid)

    fun observeParagraphs(uid: String): Flow<List<FsParagraphFile>> =
        userDoc(uid).collection("paragraphs").snapshots()
            .map { it.toObjects(FsParagraphFile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/paragraphs", uid)

    fun observeVocabLists(uid: String): Flow<List<FsVocabList>> =
        userDoc(uid).collection("vocab_lists").snapshots()
            .map { it.toObjects(FsVocabList::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/vocab_lists", uid)

    fun observeVocabWords(uid: String): Flow<List<FsVocabWord>> =
        userDoc(uid).collection("vocab_words").snapshots()
            .map { it.toObjects(FsVocabWord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/vocab_words", uid)

    fun observeWeakWords(uid: String): Flow<List<FsWeakWord>> =
        userDoc(uid).collection("weak_words").snapshots()
            .map { it.toObjects(FsWeakWord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE) }
            .wrapFirestoreErrors("users/$uid/weak_words", uid)

    private fun <T> Flow<T>.wrapFirestoreErrors(path: String, expectedUid: String): Flow<T> =
        this.catch { error ->
            val firestoreError = generateSequence(error) { it.cause }
                .filterIsInstance<FirebaseFirestoreException>()
                .firstOrNull()
            if (firestoreError == null && error is CancellationException) throw error
            val toLog = firestoreError ?: (error as? Exception)
            if (toLog != null && auth.currentUser?.uid == expectedUid) {
                handleFirestoreError(toLog, OperationType.LIST, path)
            }
            throw error
        }

    /**
     * Full bidirectional offline-first merge using latest-updated-wins (updatedAtEpochMs).
     * Called on sign-in, reconnect, or manual sync.
     */
    suspend fun syncBidirectional(userId: String): Result<Unit> {
        return try {
            val uid = requireUserId()
            check(uid == userId) { "Signed-in user mismatch during sync." }

            // 1. Flush any offline pending deletes first
            val pendingDeletes = dao.getPendingDeletesOnce()
            for (del in pendingDeletes) {
                deleteDocInternal(uid, del.collectionName, del.docId)
                dao.removePendingDelete(del.compositeKey)
            }

            // 2. User profile & stats
            val localStats = dao.getUserStatsOnce() ?: UserStatsEntity()
            val remoteProfileSnap = userDoc(uid).get().await()
            val remoteProfile = remoteProfileSnap.toObject(FsUserProfile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE)
            if (remoteProfile != null && remoteProfile.updatedAtEpochMs > localStats.updatedAtEpochMs) {
                dao.upsertUserStats(
                    localStats.copy(
                        displayName = remoteProfile.displayName,
                        email = remoteProfile.email,
                        themeMode = remoteProfile.themeMode,
                        soundEnabled = remoteProfile.soundEnabled,
                        alarmVolume = remoteProfile.alarmVolume.toFloat(),
                        vibrationEnabled = remoteProfile.vibrationEnabled,
                        defaultSnoozeMinutes = remoteProfile.defaultSnoozeMinutes.toInt(),
                        vocabOrderMode = remoteProfile.vocabOrderMode,
                        streakCount = remoteProfile.streakCount.toInt(),
                        lastActiveDay = remoteProfile.lastActiveDay,
                        todayRevisionCount = remoteProfile.todayRevisionCount.toInt(),
                        todayDate = remoteProfile.todayDate,
                        updatedAtEpochMs = remoteProfile.updatedAtEpochMs
                    )
                )
            } else {
                pushUserProfile(localStats)
            }

            // 3. Books
            mergeCollection(
                uid = uid,
                collection = "books",
                localItems = dao.getAllBooksOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("books").get().await()
                    .toObjects(FsBook::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertBook(
                        BookEntity(
                            id = r.id,
                            title = r.title,
                            colorHex = r.colorHex,
                            iconKey = r.iconKey,
                            sortOrder = r.sortOrder.toInt(),
                            enabled = r.enabled,
                            intervalMinutes = r.intervalMinutes.toInt().coerceIn(1, 1440),
                            useActiveHours = r.useActiveHours,
                            activeStartMinutes = r.activeStartMinutes.toInt().coerceIn(0, 1439),
                            activeEndMinutes = r.activeEndMinutes.toInt().coerceIn(0, 1439),
                            examDateEpochMs = r.examDateEpochMs,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 4. Chapters
            mergeCollection(
                uid = uid,
                collection = "chapters",
                localItems = dao.getAllChaptersOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("chapters").get().await()
                    .toObjects(FsChapter::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertChapter(
                        ChapterEntity(
                            id = r.id,
                            bookId = r.bookId,
                            title = r.title,
                            sortOrder = r.sortOrder.toInt(),
                            overrideEnabled = r.overrideEnabled.toInt(),
                            overrideIntervalMinutes = r.overrideIntervalMinutes.toInt(),
                            useActiveHours = r.useActiveHours.toInt(),
                            activeStartMinutes = r.activeStartMinutes.toInt(),
                            activeEndMinutes = r.activeEndMinutes.toInt(),
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 5. Notes
            val localNoteOrderById = dao.getAllNotesOnce().associate { it.id to it.sortOrder }
            mergeCollection(
                uid = uid,
                collection = "notes",
                localItems = dao.getAllNotesOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("notes").get().await()
                    .toObjects(FsNote::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertNote(
                        NoteEntity(
                            id = r.id,
                            bookId = r.bookId,
                            chapterId = r.chapterId,
                            content = r.content,
                            starred = r.starred,
                            sortOrder = localNoteOrderById[r.id] ?: 0,
                            overrideEnabled = r.overrideEnabled.toInt(),
                            overrideIntervalMinutes = r.overrideIntervalMinutes.toInt(),
                            revisionCount = r.revisionCount.toInt(),
                            lastRevisedEpochMs = r.lastRevisedEpochMs,
                            lastRevisedDay = r.lastRevisedDay,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 6. Paragraphs
            mergeCollection(
                uid = uid,
                collection = "paragraphs",
                localItems = dao.getAllParagraphsOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("paragraphs").get().await()
                    .toObjects(FsParagraphFile::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertParagraph(
                        ParagraphFileEntity(
                            id = r.id,
                            title = r.title,
                            rawText = r.rawText,
                            lines = r.lines,
                            currentLineIndex = r.currentLineIndex.toInt(),
                            enabled = r.enabled,
                            intervalMinutes = r.intervalMinutes.toInt().coerceIn(1, 1440),
                            memoryMode = r.memoryMode,
                            lastTriggeredEpochMs = r.lastTriggeredEpochMs,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 7. Vocab Lists
            mergeCollection(
                uid = uid,
                collection = "vocab_lists",
                localItems = dao.getAllVocabListsOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("vocab_lists").get().await()
                    .toObjects(FsVocabList::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertVocabList(
                        VocabListEntity(
                            id = r.id,
                            title = r.title,
                            enabled = r.enabled,
                            intervalMinutes = r.intervalMinutes.toInt().coerceIn(1, 1440),
                            reverseMode = r.reverseMode,
                            lastTriggeredEpochMs = r.lastTriggeredEpochMs,
                            currentIndex = r.currentIndex.toInt(),
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 8. Vocab Words
            mergeCollection(
                uid = uid,
                collection = "vocab_words",
                localItems = dao.getAllVocabWordsOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("vocab_words").get().await()
                    .toObjects(FsVocabWord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertVocabWord(
                        VocabWordEntity(
                            id = r.id,
                            listId = r.listId,
                            word = r.word,
                            meaningBn = r.meaningBn,
                            enabled = r.enabled,
                            mastered = r.mastered,
                            weakCount = r.weakCount.toInt(),
                            practiceCount = r.practiceCount.toInt(),
                            lastPracticedEpochMs = r.lastPracticedEpochMs,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 9. Weak Words
            mergeCollection(
                uid = uid,
                collection = "weak_words",
                localItems = dao.getAllWeakWordsOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("weak_words").get().await()
                    .toObjects(FsWeakWord::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertWeakWord(
                        WeakWordEntity(
                            id = r.id,
                            word = r.word,
                            meaningBn = r.meaningBn,
                            sourceType = r.sourceType,
                            mistakeCount = r.mistakeCount.toInt().coerceAtLeast(1),
                            lastMistakeEpochMs = r.lastMistakeEpochMs,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            // 10. Translation Cache
            mergeCollection(
                uid = uid,
                collection = "translations",
                localItems = dao.getAllTranslationsOnce(),
                localId = { it.id },
                localUpdated = { it.updatedAtEpochMs },
                toFsFields = { it.toFsMap() },
                remoteItems = userDoc(uid).collection("translations").get().await()
                    .toObjects(FsTranslationCache::class.java, DocumentSnapshot.ServerTimestampBehavior.ESTIMATE),
                remoteId = { it.id },
                remoteUpdated = { it.updatedAtEpochMs },
                upsertLocal = { r ->
                    dao.upsertTranslation(
                        TranslationCacheEntity(
                            id = r.id,
                            sourceText = r.sourceText,
                            sourceLang = r.sourceLang,
                            targetLang = r.targetLang,
                            translatedText = r.translatedText,
                            updatedAtEpochMs = r.updatedAtEpochMs
                        )
                    )
                }
            )

            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, "users/$userId")
            Result.failure(e)
        }
    }

    private suspend fun <L, R> mergeCollection(
        uid: String,
        collection: String,
        localItems: List<L>,
        localId: (L) -> String,
        localUpdated: (L) -> Long,
        toFsFields: (L) -> Map<String, Any>,
        remoteItems: List<R>,
        remoteId: (R) -> String,
        remoteUpdated: (R) -> Long,
        upsertLocal: suspend (R) -> Unit
    ) {
        val remoteById = remoteItems.associateBy(remoteId)
        val localById = localItems.associateBy(localId)

        // Pull newer remote items into local Room
        for (remote in remoteItems) {
            val id = remoteId(remote)
            val local = localById[id]
            if (local == null || remoteUpdated(remote) > localUpdated(local)) {
                upsertLocal(remote)
            }
        }

        // Push local-only or newer local items to Firestore
        for (local in localItems) {
            val id = localId(local)
            val remote = remoteById[id]
            if (remote == null || localUpdated(local) > remoteUpdated(remote)) {
                val docRef = userDoc(uid).collection(collection).document(id)
                upsertWithTimestampSplit(docRef, uid, toFsFields(local), isNewRemote = (remote == null))
            }
        }
    }

    suspend fun pushUserProfile(stats: UserStatsEntity): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val path = "users/$uid"
        return try {
            val currentUser = auth.currentUser
            val fields = mapOf(
                "displayName" to (stats.displayName.ifBlank { currentUser?.displayName.orEmpty() }).take(100),
                "email" to (stats.email.ifBlank { currentUser?.email.orEmpty() }).take(150),
                "themeMode" to stats.themeMode.takeIf { it in listOf("system", "light", "dark") }.orEmpty().ifBlank { "system" },
                "soundEnabled" to stats.soundEnabled,
                "alarmVolume" to stats.alarmVolume.toDouble().coerceIn(0.0, 1.0),
                "vibrationEnabled" to stats.vibrationEnabled,
                "defaultSnoozeMinutes" to stats.defaultSnoozeMinutes.coerceIn(1, 120),
                "vocabOrderMode" to stats.vocabOrderMode.takeIf { it in listOf("ordered", "random") }.orEmpty().ifBlank { "ordered" },
                "streakCount" to stats.streakCount.coerceAtLeast(0),
                "lastActiveDay" to stats.lastActiveDay.take(20),
                "todayRevisionCount" to stats.todayRevisionCount.coerceAtLeast(0),
                "todayDate" to stats.todayDate.take(20),
                "updatedAtEpochMs" to stats.updatedAtEpochMs.coerceAtLeast(0L)
            )
            val docRef = userDoc(uid)
            val exists = docRef.get().await().exists()
            upsertWithTimestampSplit(docRef, uid, fields, isNewRemote = !exists)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun pushDoc(collection: String, docId: String, fields: Map<String, Any>): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        val path = "users/$uid/$collection/$docId"
        return try {
            val docRef = userDoc(uid).collection(collection).document(docId)
            val exists = docRef.get().await().exists()
            upsertWithTimestampSplit(docRef, uid, fields, isNewRemote = !exists)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            handleFirestoreError(e, OperationType.WRITE, path)
            Result.failure(e)
        }
    }

    suspend fun deleteDocOrQueue(collection: String, docId: String): Result<Unit> {
        val uid = auth.currentUser?.uid?.takeIf { it.isNotBlank() } ?: return Result.success(Unit)
        return try {
            deleteDocInternal(uid, collection, docId)
            Result.success(Unit)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            dao.upsertPendingDelete(PendingDeleteEntity("$collection:$docId", collection, docId))
            handleFirestoreError(e, OperationType.DELETE, "users/$uid/$collection/$docId")
            Result.failure(e)
        }
    }

    private suspend fun deleteDocInternal(uid: String, collection: String, docId: String) {
        userDoc(uid).collection(collection).document(docId).delete().await()
    }

    private suspend fun upsertWithTimestampSplit(
        docRef: DocumentReference,
        uid: String,
        fields: Map<String, Any>,
        isNewRemote: Boolean
    ) {
        if (isNewRemote) {
            val createPayload = fields + mapOf(
                "userId" to uid,
                "createdAt" to FieldValue.serverTimestamp(),
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.set(createPayload).await()
        } else {
            val updatePayload = fields + mapOf(
                "updatedAt" to FieldValue.serverTimestamp()
            )
            docRef.update(updatePayload).await()
        }
    }
}

fun BookEntity.toFsMap(): Map<String, Any> = mapOf(
    "title" to title.trim().ifBlank { "Untitled" }.take(120),
    "colorHex" to colorHex.take(12),
    "iconKey" to iconKey.take(40),
    "sortOrder" to sortOrder.coerceIn(0, 100),
    "enabled" to enabled,
    "intervalMinutes" to intervalMinutes.coerceIn(1, 1440),
    "useActiveHours" to useActiveHours,
    "activeStartMinutes" to activeStartMinutes.coerceIn(0, 1439),
    "activeEndMinutes" to activeEndMinutes.coerceIn(0, 1439),
    "examDateEpochMs" to examDateEpochMs.coerceAtLeast(0L),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun ChapterEntity.toFsMap(): Map<String, Any> = mapOf(
    "bookId" to bookId,
    "title" to title.trim().ifBlank { "অধ্যায়" }.take(120),
    "sortOrder" to sortOrder.coerceIn(0, 10000),
    "overrideEnabled" to overrideEnabled.coerceIn(-1, 1),
    "overrideIntervalMinutes" to overrideIntervalMinutes.coerceIn(0, 1440),
    "useActiveHours" to useActiveHours.coerceIn(-1, 1),
    "activeStartMinutes" to activeStartMinutes.coerceIn(0, 1439),
    "activeEndMinutes" to activeEndMinutes.coerceIn(0, 1439),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun NoteEntity.toFsMap(): Map<String, Any> = mapOf(
    "bookId" to bookId,
    "chapterId" to chapterId,
    "content" to content.trim().ifBlank { "-" }.take(5000),
    "starred" to starred,
    "overrideEnabled" to overrideEnabled.coerceIn(-1, 1),
    "overrideIntervalMinutes" to overrideIntervalMinutes.coerceIn(0, 1440),
    "revisionCount" to revisionCount.coerceAtLeast(0),
    "lastRevisedEpochMs" to lastRevisedEpochMs.coerceAtLeast(0L),
    "lastRevisedDay" to lastRevisedDay.take(20),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun ParagraphFileEntity.toFsMap(): Map<String, Any> = mapOf(
    "title" to title.trim().ifBlank { "Paragraph" }.take(160),
    "rawText" to rawText.trim().ifBlank { "-" }.take(15000),
    "lines" to lines.take(300).ifEmpty { listOf("-") },
    "currentLineIndex" to currentLineIndex.coerceIn(0, 300),
    "enabled" to enabled,
    "intervalMinutes" to intervalMinutes.coerceIn(1, 1440),
    "memoryMode" to memoryMode,
    "lastTriggeredEpochMs" to lastTriggeredEpochMs.coerceAtLeast(0L),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun VocabListEntity.toFsMap(): Map<String, Any> = mapOf(
    "title" to title.trim().ifBlank { "Word List" }.take(160),
    "enabled" to enabled,
    "intervalMinutes" to intervalMinutes.coerceIn(1, 1440),
    "reverseMode" to reverseMode,
    "lastTriggeredEpochMs" to lastTriggeredEpochMs.coerceAtLeast(0L),
    "currentIndex" to currentIndex.coerceIn(0, 10000),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun VocabWordEntity.toFsMap(): Map<String, Any> = mapOf(
    "listId" to listId,
    "word" to word.trim().ifBlank { "word" }.take(120),
    "meaningBn" to meaningBn.take(300),
    "enabled" to enabled,
    "mastered" to mastered,
    "weakCount" to weakCount.coerceAtLeast(0),
    "practiceCount" to practiceCount.coerceAtLeast(0),
    "lastPracticedEpochMs" to lastPracticedEpochMs.coerceAtLeast(0L),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun WeakWordEntity.toFsMap(): Map<String, Any> = mapOf(
    "word" to word.trim().ifBlank { "word" }.take(120),
    "meaningBn" to meaningBn.take(300),
    "sourceType" to if (sourceType == "paragraph") "paragraph" else "vocab",
    "mistakeCount" to mistakeCount.coerceAtLeast(1),
    "lastMistakeEpochMs" to lastMistakeEpochMs.coerceAtLeast(0L),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)

fun TranslationCacheEntity.toFsMap(): Map<String, Any> = mapOf(
    "sourceText" to sourceText.trim().take(1000),
    "sourceLang" to sourceLang.take(10),
    "targetLang" to targetLang.take(10),
    "translatedText" to translatedText.trim().take(2000),
    "updatedAtEpochMs" to updatedAtEpochMs.coerceAtLeast(0L)
)
