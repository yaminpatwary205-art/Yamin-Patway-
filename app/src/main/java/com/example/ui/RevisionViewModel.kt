package com.example.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import android.content.Intent
import com.example.alarm.AlarmAudioManager
import com.example.alarm.AlarmContentItem
import com.example.alarm.AlarmReceiver
import com.example.alarm.AlarmRingingService
import com.example.alarm.AlarmScheduler
import com.example.alarm.ScheduledTrigger
import com.example.data.ParagraphCheckResult
import com.example.data.RevisionRepository
import com.example.data.local.*
import com.example.data.remote.SyncStatus
import com.example.translation.TranslationOutcome
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

sealed interface ActivePopup {
    val alarmId: String?
    val alarmTitle: String?

    data class NotePopup(
        val book: BookEntity,
        val chapter: ChapterEntity,
        val note: NoteEntity,
        override val alarmId: String? = null,
        override val alarmTitle: String? = null
    ) : ActivePopup

    data class ParagraphPopup(
        val paragraph: ParagraphFileEntity,
        val lineIndex: Int,
        val lineText: String,
        val translationState: PopupTranslationState = PopupTranslationState.Loading,
        val checkResult: ParagraphCheckResult? = null,
        override val alarmId: String? = null,
        override val alarmTitle: String? = null
    ) : ActivePopup

    data class VocabPopup(
        val list: VocabListEntity,
        val word: VocabWordEntity,
        val translationState: PopupTranslationState = PopupTranslationState.Loading,
        override val alarmId: String? = null,
        override val alarmTitle: String? = null
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
    val alarms: List<RevisionAlarmEntity> = emptyList(),
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

    @Volatile
    private var triggerLaunchInFlight = false

    @Volatile
    private var suppressAutoTriggerUntilMs = 0L

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
        _nowTicker
    ) { vocabWords, weakWords, stats, nowMs ->
        SecondaryDataSlice(vocabWords, weakWords, stats, nowMs)
    }

    private val metaDataFlow = combine(
        repository.alarmsFlow,
        _syncStatus,
        _activePopup,
        _showCelebration,
        _bannerMessage
    ) { alarms, syncStatus, popup, celebration, banner ->
        MetaDataSlice(alarms, syncStatus, popup, celebration, banner)
    }

    val uiState: StateFlow<RevisionUiState> = combine(
        baseDataFlow,
        secondaryDataFlow,
        metaDataFlow
    ) { base, sec, meta ->
        val next = AlarmScheduler.computeNextTrigger(
            nowMs = sec.nowMs,
            stats = sec.stats,
            books = base.books,
            chapters = base.chapters,
            notes = base.notes,
            paragraphs = base.paragraphs,
            vocabLists = base.vocabLists,
            vocabWords = sec.vocabWords,
            alarms = meta.alarms
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
            alarms = meta.alarms,
            stats = sec.stats,
            syncStatus = meta.syncStatus,
            nextTrigger = next,
            countdownSeconds = countdown,
            activePopup = meta.popup,
            showCelebration = meta.celebration,
            bannerMessage = meta.banner
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
        viewModelScope.launch {
            while (true) {
                delay(1000L)
                val now = System.currentTimeMillis()
                _nowTicker.value = now
                if (_activePopup.value == null && !triggerLaunchInFlight && now >= suppressAutoTriggerUntilMs) {
                    val state = uiState.value
                    val next = state.nextTrigger
                    if (next != null && next.nextTriggerEpochMs <= now && state.stats.snoozedUntilEpochMs <= now) {
                        val alarmSnoozeMs = if (next is ScheduledTrigger.CustomAlarmScope) {
                            state.alarms.find { it.id == next.alarmId }?.snoozedUntilEpochMs ?: 0L
                        } else {
                            0L
                        }
                        if (alarmSnoozeMs <= now) {
                            openTriggerPopup(next)
                        }
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

    fun handleIncomingNotificationTrigger(type: String?, targetId: String?, alarmId: String? = null) {
        AlarmRingingService.stop(context)
        AlarmScheduler.cancelRepeatReminder(context)
        if (type == null || targetId.isNullOrBlank()) {
            triggerNextAvailableNow()
            return
        }
        viewModelScope.launch {
            val cleanAlarmId = alarmId?.takeIf { it.isNotBlank() }
            val alarmTitle = cleanAlarmId?.let { repository.dao.getAlarmById(it)?.title }
            when (type) {
                "note" -> {
                    val note = repository.dao.getNoteById(targetId)
                    val book = note?.let { repository.dao.getBookById(it.bookId) }
                    val chapter = note?.let { repository.dao.getChapterById(it.chapterId) }
                    if (note != null && book != null && chapter != null) {
                        showNotePopup(book, chapter, note, cleanAlarmId, alarmTitle)
                    } else {
                        triggerNextAvailableNow()
                    }
                }
                "paragraph" -> {
                    val para = repository.dao.getParagraphById(targetId)
                    if (para != null && para.lines.isNotEmpty()) {
                        showParagraphPopup(para, cleanAlarmId, alarmTitle)
                    } else {
                        triggerNextAvailableNow()
                    }
                }
                "vocab" -> {
                    val list = repository.dao.getVocabListById(targetId)
                    if (list != null) {
                        showVocabPopup(list, alarmId = cleanAlarmId, alarmTitle = alarmTitle)
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
            vocabWords = state.vocabWords,
            alarms = state.alarms
        )
        if (next != null) {
            openTriggerPopup(next)
        } else {
            _bannerMessage.value = "সক্রিয় কোনো অ্যালার্ম, নোট, প্যারাগ্রাফ বা ভোকাবুলারি নেই। প্রথমে অন্তত একটি যুক্ত করুন।"
        }
    }

    fun launchCustomAlarmNow(alarm: RevisionAlarmEntity) {
        val state = uiState.value
        val items = AlarmScheduler.resolveAlarmItems(
            alarm = alarm,
            books = state.books,
            chapters = state.chapters,
            notes = state.notes,
            paragraphs = state.paragraphs,
            vocabLists = state.vocabLists,
            vocabWords = state.vocabWords
        )
        if (items.isEmpty()) {
            _bannerMessage.value = "'${alarm.title}' অ্যালার্মে কোনো সক্রিয় বিষয় নির্বাচিত নেই।"
            return
        }
        val picked = items[alarm.rotationCursor.coerceAtLeast(0) % items.size]
        viewModelScope.launch {
            when (picked) {
                is AlarmContentItem.NoteItem -> showNotePopup(picked.book, picked.chapter, picked.note, alarm.id, alarm.title)
                is AlarmContentItem.ParagraphItem -> showParagraphPopup(picked.paragraph, alarm.id, alarm.title)
                is AlarmContentItem.VocabItem -> showVocabPopup(picked.list, alarmId = alarm.id, alarmTitle = alarm.title)
            }
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
        if (triggerLaunchInFlight) return
        triggerLaunchInFlight = true
        viewModelScope.launch {
            try {
                when (trigger) {
                    is ScheduledTrigger.CustomAlarmScope -> {
                        val alarm = repository.dao.getAlarmById(trigger.alarmId)
                        val title = alarm?.title
                        when (trigger.resolvedType) {
                            "note" -> {
                                val note = repository.dao.getNoteById(trigger.resolvedTargetId) ?: return@launch
                                val book = repository.dao.getBookById(note.bookId) ?: return@launch
                                val chapter = repository.dao.getChapterById(note.chapterId) ?: return@launch
                                showNotePopup(book, chapter, note, trigger.alarmId, title)
                            }
                            "paragraph" -> {
                                val para = repository.dao.getParagraphById(trigger.resolvedTargetId) ?: return@launch
                                showParagraphPopup(para, trigger.alarmId, title)
                            }
                            "vocab" -> {
                                val list = repository.dao.getVocabListById(trigger.resolvedTargetId) ?: return@launch
                                showVocabPopup(list, alarmId = trigger.alarmId, alarmTitle = title)
                            }
                        }
                    }
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
            } finally {
                triggerLaunchInFlight = false
            }
        }
    }

    private fun showNotePopup(
        book: BookEntity,
        chapter: ChapterEntity,
        note: NoteEntity,
        alarmId: String? = null,
        alarmTitle: String? = null
    ) {
        AlarmRingingService.stop(context)
        AlarmScheduler.cancelRepeatReminder(context)
        val stats = uiState.value.stats
        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)
        _activePopup.value = ActivePopup.NotePopup(book, chapter, note, alarmId, alarmTitle)
    }

    private fun showParagraphPopup(
        para: ParagraphFileEntity,
        alarmId: String? = null,
        alarmTitle: String? = null
    ) {
        AlarmRingingService.stop(context)
        AlarmScheduler.cancelRepeatReminder(context)
        val stats = uiState.value.stats
        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)
        val resplit = RevisionRepository.splitParagraphIntoLines(para.rawText)
        val effectiveLines = if (resplit.size > 1 && (para.lines.size <= 1 || para.lines.any { it.length > 120 })) {
            resplit
        } else {
            para.lines.ifEmpty { resplit }
        }
        val normalizedPara = if (effectiveLines != para.lines) para.copy(lines = effectiveLines) else para
        val idx = normalizedPara.currentLineIndex.coerceIn(0, (effectiveLines.size - 1).coerceAtLeast(0))
        val line = effectiveLines.getOrElse(idx) { "" }
        val cached = repository.translationService.let { null }
        _activePopup.value = ActivePopup.ParagraphPopup(
            paragraph = normalizedPara,
            lineIndex = idx,
            lineText = line,
            translationState = PopupTranslationState.Idle,
            alarmId = alarmId,
            alarmTitle = alarmTitle
        )
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
        suppressAutoTriggerUntilMs = System.currentTimeMillis() + 3_000L
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    repository.advanceParagraphLine(current.paragraph.id)
                    current.alarmId?.let { repository.markAlarmTriggered(it, advanceCursor = true) }
                }
                triggerCelebration()
            } finally {
                suppressAutoTriggerUntilMs = 0L
            }
        }
    }

    private suspend fun showVocabPopup(
        list: VocabListEntity,
        excludeWordId: String? = null,
        alarmId: String? = null,
        alarmTitle: String? = null
    ) {
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

        AlarmRingingService.stop(context)
        audioManager.startAlarmFeedback(stats.soundEnabled, stats.alarmVolume, stats.vibrationEnabled)

        val initialTrans = if (picked.meaningBn.isNotBlank()) {
            PopupTranslationState.Ready(picked.meaningBn, fromCache = true)
        } else {
            PopupTranslationState.Idle
        }
        _activePopup.value = ActivePopup.VocabPopup(list, picked, initialTrans, alarmId, alarmTitle)
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
            showVocabPopup(
                list = current.list,
                excludeWordId = current.word.id,
                alarmId = current.alarmId,
                alarmTitle = current.alarmTitle
            )
        }
    }

    fun markNoteDoneAndClose() {
        val current = _activePopup.value as? ActivePopup.NotePopup ?: return
        suppressAutoTriggerUntilMs = System.currentTimeMillis() + 3_000L
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    repository.markNoteRevised(current.note.id)
                    current.alarmId?.let { repository.markAlarmTriggered(it, advanceCursor = true) }
                }
                triggerCelebration()
            } finally {
                suppressAutoTriggerUntilMs = 0L
            }
        }
    }

    fun snoozePopup(minutes: Int) {
        val current = _activePopup.value
        suppressAutoTriggerUntilMs = System.currentTimeMillis() + 3_000L
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val customAlarmId = current?.alarmId
                    if (customAlarmId != null) {
                        repository.snoozeSingleAlarm(customAlarmId, minutes)
                    } else {
                        repository.snoozeAll(minutes)
                    }
                }
                _bannerMessage.value = "$minutes মিনিটের জন্য স্নুজ করা হয়েছে।"
            } finally {
                suppressAutoTriggerUntilMs = 0L
            }
        }
    }

    fun stopPopupAlarm() {
        val current = _activePopup.value
        suppressAutoTriggerUntilMs = System.currentTimeMillis() + 3_000L
        audioManager.stopAlarmFeedback()
        _activePopup.value = null
        viewModelScope.launch {
            try {
                withContext(NonCancellable) {
                    val now = System.currentTimeMillis()
                    val customAlarmId = current?.alarmId
                    if (customAlarmId != null) {
                        repository.markAlarmTriggered(customAlarmId, advanceCursor = true)
                    } else {
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
            } finally {
                suppressAutoTriggerUntilMs = 0L
            }
        }
    }

    fun muteAlarmSoundNow() {
        AlarmRingingService.stop(context)
        audioManager.stopAlarmFeedback()
    }

    fun triggerTestNotificationNow() {
        val next = uiState.value.nextTrigger
        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE, next?.type ?: "note")
            putExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID, next?.targetId ?: "")
            putExtra(AlarmScheduler.EXTRA_TRIGGER_LABEL, next?.labelBn ?: "রিভিশন অ্যালার্ম টেস্ট")
            putExtra(AlarmScheduler.EXTRA_ALARM_ID, (next as? ScheduledTrigger.CustomAlarmScope)?.alarmId ?: "")
        }
        AlarmReceiver().onReceive(context, intent)
        _bannerMessage.value = "নোটিফিকেশন ও অ্যালার্ম পাঠানো হয়েছে!"
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
        val nowMs: Long
    )

    private data class MetaDataSlice(
        val alarms: List<RevisionAlarmEntity>,
        val syncStatus: SyncStatus,
        val popup: ActivePopup?,
        val celebration: Boolean,
        val banner: String?
    )
}
