package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.alarm.AlarmAudioManager
import com.example.alarm.AlarmScheduler
import com.example.alarm.ScheduledTrigger
import com.example.data.ParagraphCheckResult
import com.example.data.RevisionRepository
import com.example.data.local.*
import com.example.data.remote.SyncStatus
import com.example.translation.TranslationOutcome
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

sealed interface ActivePopup {
    data class NotePopup(
        val book: BookEntity,
        val chapter: ChapterEntity,
        val note: NoteEntity
    ) : ActivePopup

    data class ParagraphPopup(
        val paragraph: ParagraphFileEntity,
        val lineIndex: Int,
        val lineText: String,
        val translationState: PopupTranslationState = PopupTranslationState.Loading,
        val checkResult: ParagraphCheckResult? = null
    ) : ActivePopup

    data class VocabPopup(
        val list: VocabListEntity,
        val word: VocabWordEntity,
        val translationState: PopupTranslationState = PopupTranslationState.Loading
    ) : ActivePopup
}

sealed interface PopupTranslationState {
    data object Idle : PopupTranslationState
    data object Loading : PopupTranslationState
    data class Ready(val meaningBn: String, val fromCache: Boolean) : PopupTranslationState
    data class Error(val message: String) : PopupTranslationState
}

data class RevisionUiState(
    val books: List<BookEntity> = emptyList(),
    val chapters: List<ChapterEntity> = emptyList(),
    val notes: List<NoteEntity> = emptyList(),
    val paragraphs: List<ParagraphFileEntity> = emptyList(),
    val vocabLists: List<VocabListEntity> = emptyList(),
    val vocabWords: List<VocabWordEntity> = emptyList(),
    val weakWords: List<WeakWordEntity> = emptyList(),
    val stats: UserStatsEntity = UserStatsEntity(),
    val syncStatus: SyncStatus = SyncStatus.LOCAL_ONLY,
    val nextTrigger: ScheduledTrigger? = null,
    val countdownSeconds: Long = -1L,
    val activePopup: ActivePopup? = null,
    val showCelebration: Boolean = false,
    val bannerMessage: String? = null
)

class RevisionViewModel(
    private val context: Context,
    val repository: RevisionRepository
) : ViewModel() {

    private val audioManager = AlarmAudioManager(context)

    private val _syncStatus = MutableStateFlow(SyncStatus.LOCAL_ONLY)
    private val _activePopup = MutableStateFlow<ActivePopup?>(null)
    private val _showCelebration = MutableStateFlow(false)
    private val _bannerMessage = MutableStateFlow<String?>(null)
    private val _nowTicker = MutableStateFlow(System.currentTimeMillis())

    private val baseDataFlow = combine(
        repository.booksFlow,
        repository.chaptersFlow,
        repository.notesFlow,
        repository.paragraphsFlow,
        repository.vocabListsFlow
    ) { books, chapters, notes, paragraphs, vocabLists ->
        BaseDataSlice(books, chapters, notes, paragraphs, vocabLists)
    }

    private val secondaryDataFlow = combine(
        repository.vocabWordsFlow,
        repository.weakWordsFlow,
        repository.statsFlow,
        _syncStatus,
        _nowTicker
    ) { vocabWords, weakWords, stats, syncStatus, nowMs ->
        SecondaryDataSlice(vocabWords, weakWords, stats, syncStatus, nowMs)
    }

    val uiState: StateFlow<RevisionUiState> = combine(
        baseDataFlow,
        secondaryDataFlow,
        _activePopup,
        _showCelebration,
        _bannerMessage
    ) { base, sec, popup, celebration, banner ->
        val next = AlarmScheduler.computeNextTrigger(
            nowMs = sec.nowMs,
            stats = sec.stats,
            books = base.books,
            chapters = base.chapters,
            notes = base.notes,
            paragraphs = base.paragraphs,
            vocabLists = base.vocabLists,
            vocabWords = sec.vocabWords
        )
        val countdown = if (next != null) {
            ((next.nextTriggerEpochMs - sec.nowMs) / 1000L).coerceAtLeast(0L)
        } else {
            -1L
        }
        RevisionUiState(
            books = base.books,
            chapters = base.chapters,
            notes = base.notes,
            paragraphs = base.paragraphs,
            vocabLists = base.vocabLists,
            vocabWords = sec.vocabWords,
            weakWords = sec.weakWords,
            stats = sec.stats,
            syncStatus = sec.syncStatus,
            nextTrigger = next,
            countdownSeconds = countdown,
            activePopup = popup,
            showCelebration = celebration,
            bannerMessage = banner
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = RevisionUiState()
    )

    init {
        viewModelScope.launch {
            repository.ensureSeeded()
            repository.rescheduleAlarms()
        }
        // Timestamp-based scheduler tick: fires missed/overdue timers immediately when app is open
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val now = System.currentTimeMillis()
                _nowTicker.value = now
                if (_activePopup.value == null) {
                    val state = uiState.value
                    val next = state.nextTrigger
                    if (next != null && next.nextTriggerEpochMs <= now && state.stats.snoozedUntilEpochMs <= now) {
                        openTriggerPopup(next)
                    }
                }
            }
        }
    }

    fun onUserSessionChanged(uid: String?) {
        repository.setActiveUser(uid)
        if (uid.isNullOrBlank()) {
            _syncStatus.value = SyncStatus.LOCAL_ONLY
        } else {
            syncNow()
        }
    }

    fun syncNow() {
        viewModelScope.launch {
            _syncStatus.value = SyncStatus.SYNCING
            val res = repository.syncWithCloudIfSignedIn()
            _syncStatus.value = res.getOrDefault(SyncStatus.ERROR)
        }
    }

    fun clearBanner() {
        _bannerMessage.value = null
    }

    fun triggerCelebration() {
        viewModelScope.launch {
            _showCelebration.value = true
            delay(2200L)
            _showCelebration.value = false
        }
    }

    fun handleIncomingNotificationTrigger(type: String?, targetId: String?) {
        AlarmScheduler.cancelRepeatReminder(context)
        if (type == null || targetId.isNullOrBlank()) {
            triggerNextAvailableNow()
            return
        }
        viewModelScope.launch {
            when (type) {
                "note" -> {
                    val note = repository.dao.getNoteById(targetId)
                    val book = note?.let { repository.dao.getBookById(it.bookId) }
                    val chapter = note?.let { repository.dao.getChapterById(it.chapterId) }
                    if (note != null && book != null && chapter != null) {
                        showNotePopup(book, chapter, note)
                    } else {
                        triggerNextAvailableNow()
                    }
                }
                "paragraph" -> {
                    val para = repository.dao.getParagraphById(targetId)
                    if (para != null && para.lines.isNotEmpty()) {
                        showParagraphPopup(para)
                    } else {
                        triggerNextAvailableNow()
                    }
                }
                "vocab" -> {
                    val list = repository.dao.getVocabListById(targetId)
                    if (list != null) {
                        showVocabPopup(list)
                    } else {
                        triggerNextAvailableNow()
                    }
                }
                else -> triggerNextAvailableNow()
            }
        }
    }

    fun triggerNextAvailableNow() {
        val state = uiState.value
        val next = AlarmScheduler.computeNextTrigger(
            nowMs = System.currentTimeMillis(),
            stats = state.stats,
            books = state.books,
            chapters = state.chapters,
            notes = state.notes,
            paragraphs = state.paragraphs,
            vocabLists = state.vocabLists,
            vocabWords = state.vocabWords
        )
        if (next != null) {
            openTriggerPopup(next)
        } else {
            _bannerMessage.value = "সক্রিয় কোনো নোট, প্যারাগ্রাফ বা ভোকাবুলারি নেই। প্রথমে অন্তত একটি যুক্ত করুন।"
        }
    }

    fun launchParagraphNow(paragraph: ParagraphFileEntity) {
        if (paragraph.lines.isEmpty()) return
        showParagraphPopup(paragraph)
    }

    fun launchVocabListNow(list: VocabListEntity) {
        viewModelScope.launch {
            showVocabPopup(list)
        }
    }

    private fun openTriggerPopup(trigger: ScheduledTrigger) {
        viewModelScope.launch {
            when (trigger) {
                is ScheduledTrigger.NoteScope -> {
                    val note = repository.dao.getNoteById(trigger.noteId) ?: return@launch
                    val book = repository.dao.getBookById(trigger.bookId) ?: return@launch
                    val chapter = repository.dao.getChapterById(trigger.chapterId) ?: return@launch
                    showNotePopup(book, chapter, note)
                }
                is ScheduledTrigger.ParagraphScope -> {
                    val para = repository.dao.getParagraphById(trigger.paragraphId) ?: return@launch
                    showParagraphPopup(para)
                }
                is ScheduledTrigger.VocabScope -> {
                    val list = repository.dao.getVocabListById(trigger.listId) ?: return@launch
                    showVocabPopup(list)
                }
            }
        }
    }

    private fun showNotePopup(book: BookEntity, chapter: ChapterEntity, note: NoteEntity) {
        AlarmScheduler.cancelRepeatReminder(context)
        val stats = uiState.value.stats
        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)
        _activePopup.value = ActivePopup.NotePopup(book, chapter, note)
    }

    private fun showParagraphPopup(para: ParagraphFileEntity) {
        AlarmScheduler.cancelRepeatReminder(context)
        val stats = uiState.value.stats
        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)
        val idx = para.currentLineIndex.coerceIn(0, (para.lines.size - 1).coerceAtLeast(0))
        val line = para.lines.getOrElse(idx) { "" }
        _activePopup.value = ActivePopup.ParagraphPopup(
            paragraph = para,
            lineIndex = idx,
            lineText = line,
            translationState = PopupTranslationState.Loading
        )
        translateCurrentParagraphLine(line)
    }

    fun retryParagraphTranslation() {
        val popup = _activePopup.value as? ActivePopup.ParagraphPopup ?: return
        _activePopup.value = popup.copy(translationState = PopupTranslationState.Loading)
        translateCurrentParagraphLine(popup.lineText)
    }

    private fun translateCurrentParagraphLine(lineText: String) {
        viewModelScope.launch {
            val outcome = repository.translateSingleItem(lineText)
            val current = _activePopup.value as? ActivePopup.ParagraphPopup ?: return@launch
            if (current.lineText != lineText) return@launch
            val state = when (outcome) {
                is TranslationOutcome.Success -> PopupTranslationState.Ready(outcome.translatedText, outcome.fromCache)
                is TranslationOutcome.Error -> PopupTranslationState.Error(outcome.message)
            }
            _activePopup.value = current.copy(translationState = state)
        }
    }

    fun saveManualParagraphMeaning(meaningBn: String) {
        val initial = _activePopup.value as? ActivePopup.ParagraphPopup ?: return
        val clean = meaningBn.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.saveManualSingleTranslation(initial.lineText, clean)
            val latest = _activePopup.value as? ActivePopup.ParagraphPopup ?: return@launch
            if (latest.lineText == initial.lineText) {
                _activePopup.value = latest.copy(
                    translationState = PopupTranslationState.Ready(clean, fromCache = true)
                )
            }
        }
    }

    fun checkParagraphTyping(typedLine: String) {
        val current = _activePopup.value as? ActivePopup.ParagraphPopup ?: return
        val result = RevisionRepository.compareParagraphWords(current.lineText, typedLine)
        _activePopup.value = current.copy(checkResult = result)
        viewModelScope.launch {
            if (result.wrongWords.isNotEmpty()) {
                result.wrongWords.forEach { w ->
                    repository.addOrIncrementWeakWord(w, "", "paragraph")
                }
            }
            if (result.accuracyPercent >= 80) {
                repository.incrementStatsAndStreak()
                triggerCelebration()
            }
        }
    }

    fun resetParagraphRetry() {
        val current = _activePopup.value as? ActivePopup.ParagraphPopup ?: return
        _activePopup.value = current.copy(checkResult = null)
    }

    fun completeParagraphLineAndClose() {
        val current = _activePopup.value as? ActivePopup.ParagraphPopup ?: return
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            repository.advanceParagraphLine(current.paragraph.id)
            triggerCelebration()
        }
    }

    private suspend fun showVocabPopup(list: VocabListEntity, excludeWordId: String? = null) {
        AlarmScheduler.cancelRepeatReminder(context)
        val activeWords = repository.dao.getAllVocabWordsOnce()
            .filter { it.listId == list.id && it.enabled }
        if (activeWords.isEmpty()) {
            _bannerMessage.value = "${list.title} তালিকায় কোনো সক্রিয় শব্দ নেই।"
            return
        }
        val pool = if (excludeWordId != null && activeWords.size > 1) {
            activeWords.filter { it.id != excludeWordId }
        } else {
            activeWords
        }
        val stats = uiState.value.stats
        // Prefer weak and least-practiced words; order or random according to setting
        val picked = if (stats.vocabOrderMode == "random") {
            val maxWeak = pool.maxOf { it.weakCount }
            val prioritized = pool.filter { it.weakCount == maxWeak || !it.mastered }
            (prioritized.ifEmpty { pool }).random()
        } else {
            pool.minWithOrNull(
                compareByDescending<VocabWordEntity> { it.weakCount }
                    .thenBy { it.practiceCount }
                    .thenBy { it.lastPracticedEpochMs }
            ) ?: pool.first()
        }

        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)

        val initialTrans = if (picked.meaningBn.isNotBlank()) {
            PopupTranslationState.Ready(picked.meaningBn, fromCache = true)
        } else {
            PopupTranslationState.Loading
        }
        _activePopup.value = ActivePopup.VocabPopup(list, picked, initialTrans)

        if (picked.meaningBn.isBlank()) {
            translateCurrentVocabWord(picked)
        }
    }

    fun retryVocabTranslation() {
        val current = _activePopup.value as? ActivePopup.VocabPopup ?: return
        _activePopup.value = current.copy(translationState = PopupTranslationState.Loading)
        translateCurrentVocabWord(current.word)
    }

    private fun translateCurrentVocabWord(word: VocabWordEntity) {
        viewModelScope.launch {
            val outcome = repository.translateSingleItem(word.word)
            val current = _activePopup.value as? ActivePopup.VocabPopup ?: return@launch
            if (current.word.id != word.id) return@launch
            val state = when (outcome) {
                is TranslationOutcome.Success -> {
                    repository.updateVocabWord(word.copy(meaningBn = outcome.translatedText))
                    PopupTranslationState.Ready(outcome.translatedText, outcome.fromCache)
                }
                is TranslationOutcome.Error -> PopupTranslationState.Error(outcome.message)
            }
            _activePopup.value = current.copy(translationState = state)
        }
    }

    fun saveManualVocabMeaning(meaningBn: String) {
        val initial = _activePopup.value as? ActivePopup.VocabPopup ?: return
        val clean = meaningBn.trim()
        if (clean.isEmpty()) return
        viewModelScope.launch {
            repository.saveManualSingleTranslation(initial.word.word, clean)
            val updatedWord = initial.word.copy(meaningBn = clean)
            repository.updateVocabWord(updatedWord)
            val latest = _activePopup.value as? ActivePopup.VocabPopup ?: return@launch
            if (latest.word.id == initial.word.id) {
                _activePopup.value = latest.copy(
                    word = updatedWord,
                    translationState = PopupTranslationState.Ready(clean, fromCache = true)
                )
            }
        }
    }

    fun submitVocabSpelling(typedWord: String): Boolean {
        val current = _activePopup.value as? ActivePopup.VocabPopup ?: return false
        val isCorrect = typedWord.trim().equals(current.word.word.trim(), ignoreCase = true)
        viewModelScope.launch {
            repository.recordVocabPractice(current.list.id, current.word.id, isCorrect)
            if (isCorrect) triggerCelebration()
        }
        return isCorrect
    }

    fun nextVocabWordInPopup() {
        val current = _activePopup.value as? ActivePopup.VocabPopup ?: return
        viewModelScope.launch {
            showVocabPopup(current.list, excludeWordId = current.word.id)
        }
    }

    fun markNoteDoneAndClose() {
        val current = _activePopup.value as? ActivePopup.NotePopup ?: return
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            repository.markNoteRevised(current.note.id)
            triggerCelebration()
        }
    }

    fun snoozePopup(minutes: Int) {
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            repository.snoozeAll(minutes)
            _bannerMessage.value = "$minutes মিনিটের জন্য স্নুজ করা হয়েছে।"
        }
    }

    fun stopPopupAlarm() {
        val current = _activePopup.value
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            val now = System.currentTimeMillis()
            repository.updateStats { it.copy(snoozedUntilEpochMs = 0L) }
            when (current) {
                is ActivePopup.NotePopup -> {
                    val noteHasOverride = current.note.overrideEnabled != -1 || current.note.overrideIntervalMinutes > 0
                    val chapHasOverride = current.chapter.overrideEnabled != -1 || current.chapter.overrideIntervalMinutes > 0
                    repository.updateNote(current.note.copy(lastTriggeredEpochMs = now))
                    if (!noteHasOverride) {
                        repository.updateChapter(current.chapter.copy(lastTriggeredEpochMs = now))
                    }
                    if (!noteHasOverride && !chapHasOverride) {
                        repository.updateBook(current.book.copy(lastTriggeredEpochMs = now))
                    }
                }
                is ActivePopup.ParagraphPopup -> {
                    repository.updateParagraph(current.paragraph.copy(lastTriggeredEpochMs = now))
                }
                is ActivePopup.VocabPopup -> {
                    repository.updateVocabList(current.list.copy(lastTriggeredEpochMs = now))
                }
                null -> Unit
            }
            repository.rescheduleAlarms()
        }
    }

    fun muteAlarmSoundNow() {
        audioManager.stopAlarmFeedback()
    }

    override fun onCleared() {
        super.onCleared()
        audioManager.stopAlarmFeedback()
    }

    private data class BaseDataSlice(
        val books: List<BookEntity>,
        val chapters: List<ChapterEntity>,
        val notes: List<NoteEntity>,
        val paragraphs: List<ParagraphFileEntity>,
        val vocabLists: List<VocabListEntity>
    )

    private data class SecondaryDataSlice(
        val vocabWords: List<VocabWordEntity>,
        val weakWords: List<WeakWordEntity>,
        val stats: UserStatsEntity,
        val syncStatus: SyncStatus,
        val nowMs: Long
    )
}
