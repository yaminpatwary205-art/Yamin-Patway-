package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.alarm.AlarmScheduler
import com.example.data.RevisionRepository
import com.example.data.local.*
import com.example.data.remote.SyncStatus
import com.example.ui.RevisionUiState
import com.example.ui.RevisionViewModel
import com.example.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    state: RevisionUiState,
    viewModel: RevisionViewModel,
    onOpenBook: (String) -> Unit,
    onOpenChapter: (String, String) -> Unit,
    onOpenVocabList: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selectedSection by remember { mutableIntStateOf(0) } // 0 = Books, 1 = Paragraphs, 2 = Vocab
    var searchQuery by remember { mutableStateOf("") }
    var noteFilter by remember { mutableStateOf("all") } // all, starred, active, paused
    var showWeakWordsSheet by remember { mutableStateOf(false) }

    var showAddParagraph by remember { mutableStateOf(false) }
    var editingParagraph by remember { mutableStateOf<ParagraphFileEntity?>(null) }
    var showAddVocabList by remember { mutableStateOf(false) }

    KeepScreenOnEffect(enabled = state.stats.alarmModeActive)

    AnimatedMeshBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text("রিভিশন অ্যালার্ম", fontWeight = FontWeight.Bold)
                            SyncBadge(state.syncStatus)
                        }
                    },
                    actions = {
                        BadgedBox(
                            badge = {
                                if (state.weakWords.isNotEmpty()) {
                                    Badge { Text("${state.weakWords.size}") }
                                }
                            }
                        ) {
                            IconButton(
                                onClick = { showWeakWordsSheet = true },
                                modifier = Modifier.testTag("weak_words_button")
                            ) {
                                Icon(Icons.Rounded.WarningAmber, contentDescription = "দুর্বল শব্দ")
                            }
                        }
                        IconButton(
                            onClick = onOpenSettings,
                            modifier = Modifier.testTag("settings_button")
                        ) {
                            Icon(Icons.Rounded.Settings, contentDescription = "সেটিংস")
                        }
                    }
                )
            },
            floatingActionButton = {
                when (selectedSection) {
                    1 -> ExtendedFloatingActionButton(
                        onClick = { showAddParagraph = true },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("নতুন প্যারাগ্রাফ") },
                        modifier = Modifier.testTag("add_paragraph_fab")
                    )
                    2 -> ExtendedFloatingActionButton(
                        onClick = { showAddVocabList = true },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("নতুন শব্দ তালিকা") },
                        modifier = Modifier.testTag("add_vocab_list_fab")
                    )
                }
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
            ) {
                state.bannerMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(msg, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                            IconButton(onClick = { viewModel.clearBanner() }, modifier = Modifier.size(28.dp)) {
                                Icon(Icons.Rounded.Close, contentDescription = "বন্ধ", modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }

                // Dashboard Summary Card
                DashboardCard(
                    state = state,
                    onToggleAlarmMode = { active ->
                        scope.launch {
                            viewModel.repository.updateStats { it.copy(alarmModeActive = active) }
                        }
                    },
                    onTriggerNow = { viewModel.triggerNextAvailableNow() }
                )

                Spacer(Modifier.height(10.dp))

                // 3-Section Switcher: Books / Paragraphs / Vocab
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    listOf("বই ও নোট", "প্যারাগ্রাফ", "ভোকাবুলারি").forEachIndexed { idx, label ->
                        SegmentedButton(
                            selected = selectedSection == idx,
                            onClick = { selectedSection = idx },
                            shape = SegmentedButtonDefaults.itemShape(index = idx, count = 3),
                            modifier = Modifier.testTag("tab_section_$idx")
                        ) {
                            Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                when (selectedSection) {
                    0 -> BooksAndNotesSection(
                        state = state,
                        searchQuery = searchQuery,
                        onSearchQueryChange = { searchQuery = it },
                        noteFilter = noteFilter,
                        onFilterChange = { noteFilter = it },
                        onOpenBook = onOpenBook,
                        onOpenChapter = onOpenChapter
                    )
                    1 -> ParagraphPracticeSection(
                        paragraphs = state.paragraphs,
                        repository = viewModel.repository,
                        onEdit = { editingParagraph = it },
                        onPracticeNow = { viewModel.launchParagraphNow(it) }
                    )
                    2 -> VocabularySection(
                        lists = state.vocabLists,
                        words = state.vocabWords,
                        repository = viewModel.repository,
                        onOpenList = onOpenVocabList,
                        onPracticeNow = { viewModel.launchVocabListNow(it) }
                    )
                }
            }
        }
    }

    if (showWeakWordsSheet) {
        ModalBottomSheet(onDismissRequest = { showWeakWordsSheet = false }) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "দুর্বল শব্দসমূহ (Weak Words)",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                if (state.weakWords.isEmpty()) {
                    Text(
                        "এখনো কোনো ভুল শব্দ জমা হয়নি। প্যারাগ্রাফ বা ভোকাবুলারি প্র্যাকটিসে ভুল হলে এখানে যুক্ত হবে।",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.heightIn(max = 360.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(state.weakWords, key = { it.id }) { weak ->
                            GlassCard(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier.padding(12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(weak.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                        Text(
                                            "${weak.meaningBn.ifBlank { "অর্থ অনুশীলনে যুক্ত হবে" }} • ভুল: ${weak.mistakeCount} বার",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    IconButton(onClick = {
                                        scope.launch { viewModel.repository.deleteWeakWord(weak.id) }
                                    }) {
                                        Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন")
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddParagraph || editingParagraph != null) {
        ParagraphEditorDialog(
            existing = editingParagraph,
            onDismiss = { showAddParagraph = false; editingParagraph = null },
            onSave = { title, rawText, intervalMin, memoryMode, enabled ->
                val old = editingParagraph
                scope.launch {
                    viewModel.repository.addOrUpdateParagraph(old?.id, title, rawText, intervalMin, memoryMode, enabled)
                }
                showAddParagraph = false
                editingParagraph = null
            }
        )
    }

    if (showAddVocabList) {
        var title by remember { mutableStateOf("") }
        var intervalText by remember { mutableStateOf("15") }
        var reverseMode by remember { mutableStateOf(false) }
        var bulkWords by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showAddVocabList = false },
            title = { Text("নতুন ভোকাবুলারি তালিকা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = title,
                        onValueChange = { title = it },
                        label = { Text("তালিকার নাম (যেমন: Unit 1 Words)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("vocab_list_title_input")
                    )
                    OutlinedTextField(
                        value = intervalText,
                        onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                        label = { Text("ইন্টারভ্যাল (মিনিট)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = reverseMode, onCheckedChange = { reverseMode = it })
                        Text("রিভার্স মোড (বাংলা দেখে ইংরেজি লিখুন)")
                    }
                    OutlinedTextField(
                        value = bulkWords,
                        onValueChange = { bulkWords = it },
                        label = { Text("শব্দ পেস্ট করুন (word বা word - বাংলা অর্থ)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = (intervalText.toIntOrNull() ?: 15).coerceIn(1, 1440)
                        scope.launch {
                            viewModel.repository.addVocabList(title, mins, reverseMode, bulkWords)
                        }
                        showAddVocabList = false
                    },
                    enabled = title.isNotBlank()
                ) { Text("তৈরি করুন") }
            },
            dismissButton = {
                TextButton(onClick = { showAddVocabList = false }) { Text("বাতিল") }
            }
        )
    }
}

@Composable
private fun SyncBadge(status: SyncStatus) {
    val (label, color) = when (status) {
        SyncStatus.SYNCED -> "সিঙ্কড" to Color(0xFF10B981)
        SyncStatus.SYNCING -> "সিঙ্ক হচ্ছে" to Color(0xFFF59E0B)
        SyncStatus.OFFLINE -> "অফলাইন" to Color(0xFF6B7280)
        SyncStatus.ERROR -> "সিঙ্ক ত্রুটি" to Color(0xFFEF4444)
        SyncStatus.LOCAL_ONLY -> "লোকাল" to Color(0xFF6366F1)
    }
    Surface(
        shape = RoundedCornerShape(50),
        color = color.copy(alpha = 0.16f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Box(modifier = Modifier.size(6.dp).clip(CircleShape).background(color))
            Text(label, style = MaterialTheme.typography.labelSmall, color = color)
        }
    }
}

@Composable
private fun DashboardCard(
    state: RevisionUiState,
    onToggleAlarmMode: (Boolean) -> Unit,
    onTriggerNow: () -> Unit
) {
    GlassCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "পরবর্তী অ্যালার্ম: ${formatCountdownBn(state.countdownSeconds)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = state.nextTrigger?.labelBn ?: "যেকোনো বই বা প্যারাগ্রাফে নোট যোগ করুন",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                FilledTonalButton(
                    onClick = onTriggerNow,
                    modifier = Modifier.testTag("trigger_now_button")
                ) {
                    Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("রিভিশন")
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                StatChip(
                    icon = Icons.Rounded.LocalFireDepartment,
                    text = "স্ট্রিক: ${state.stats.streakCount} দিন",
                    color = Color(0xFFF59E0B),
                    modifier = Modifier.weight(1f)
                )
                StatChip(
                    icon = Icons.Rounded.TaskAlt,
                    text = "আজ: ${state.stats.todayRevisionCount} বার",
                    color = Color(0xFF10B981),
                    modifier = Modifier.weight(1f)
                )
                FilterChip(
                    selected = state.stats.alarmModeActive,
                    onClick = { onToggleAlarmMode(!state.stats.alarmModeActive) },
                    leadingIcon = {
                        Icon(
                            if (state.stats.alarmModeActive) Icons.Rounded.ScreenLockPortrait else Icons.Rounded.Bedtime,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                    },
                    label = { Text("অ্যালার্ম মোড") },
                    modifier = Modifier.testTag("alarm_mode_toggle")
                )
            }

            AnimatedVisibility(visible = state.stats.alarmModeActive) {
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = MaterialTheme.colorScheme.tertiaryContainer
                ) {
                    Text(
                        text = "অ্যালার্ম মোড চালু: স্ক্রিন জাগ্রত থাকবে এবং নির্ধারিত সময় হলেই সরাসরি ফুল-স্ক্রিন রিভিশন চালু হবে।",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onTertiaryContainer,
                        modifier = Modifier.padding(10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun StatChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    text: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        color = color.copy(alpha = 0.14f)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Icon(icon, contentDescription = null, tint = color, modifier = Modifier.size(16.dp))
            Text(text, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

@Composable
private fun BooksAndNotesSection(
    state: RevisionUiState,
    searchQuery: String,
    onSearchQueryChange: (String) -> Unit,
    noteFilter: String,
    onFilterChange: (String) -> Unit,
    onOpenBook: (String) -> Unit,
    onOpenChapter: (String, String) -> Unit
) {
    val bookMap = remember(state.books) { state.books.associateBy { it.id } }
    val chapterMap = remember(state.chapters) { state.chapters.associateBy { it.id } }
    val today = remember { RevisionRepository.todayDayString() }

    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            placeholder = { Text("সব নোট খুঁজুন...") },
            leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Rounded.Clear, contentDescription = "মুছুন")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("search_notes_input")
        )

        Row(
            modifier = Modifier.horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            listOf(
                "all" to "সব বই",
                "starred" to "তারকাচিহ্নিত",
                "active" to "সক্রিয় নোট",
                "paused" to "বিরতি নোট"
            ).forEach { (key, label) ->
                FilterChip(
                    selected = noteFilter == key,
                    onClick = { onFilterChange(key) },
                    label = { Text(label) }
                )
            }
        }

        if (searchQuery.isNotBlank() || noteFilter != "all") {
            val filteredNotes = state.notes.filter { note ->
                val book = bookMap[note.bookId]
                val chap = chapterMap[note.chapterId]
                val sched = if (book != null && chap != null) AlarmScheduler.resolveNoteSchedule(book, chap, note) else null
                val matchesQuery = searchQuery.isBlank() || note.content.contains(searchQuery, ignoreCase = true)
                val matchesFilter = when (noteFilter) {
                    "starred" -> note.starred
                    "active" -> sched?.enabled == true
                    "paused" -> sched?.enabled == false
                    else -> true
                }
                matchesQuery && matchesFilter
            }

            if (filteredNotes.isEmpty()) {
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        "খোঁজার সাথে মেলে এমন কোনো নোট পাওয়া যায়নি।",
                        modifier = Modifier.padding(20.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            } else {
                LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(bottom = 24.dp)) {
                    items(filteredNotes, key = { it.id }) { note ->
                        val book = bookMap[note.bookId]
                        val chap = chapterMap[note.chapterId]
                        GlassCard(
                            accentColor = parseColorHex(book?.colorHex ?: "#4F46E5"),
                            onClick = { onOpenChapter(note.bookId, note.chapterId) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    "${book?.title.orEmpty()} • ${chap?.title.orEmpty()}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = parseColorHex(book?.colorHex ?: "#4F46E5")
                                )
                                Text(note.content, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 156.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 24.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(state.books, key = { it.id }) { book ->
                    val accent = parseColorHex(book.colorHex)
                    val bookNotes = state.notes.filter { it.bookId == book.id }
                    val revisedToday = bookNotes.count { it.lastRevisedDay == today }
                    val progress = if (bookNotes.isEmpty()) 0f else revisedToday.toFloat() / bookNotes.size
                    val activeCount = bookNotes.count { note ->
                        val chap = chapterMap[note.chapterId] ?: return@count false
                        AlarmScheduler.resolveNoteSchedule(book, chap, note).enabled
                    }

                    GlassCard(
                        accentColor = accent,
                        onClick = { onOpenBook(book.id) },
                        modifier = Modifier.fillMaxWidth().testTag("book_card_${book.id}")
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    Brush.verticalGradient(
                                        listOf(accent.copy(alpha = 0.14f), Color.Transparent)
                                    )
                                )
                                .padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = accent.copy(alpha = 0.18f),
                                    modifier = Modifier.size(38.dp)
                                ) {
                                    Box(contentAlignment = Alignment.Center) {
                                        Icon(
                                            bookIconForKey(book.iconKey),
                                            contentDescription = book.title,
                                            tint = accent,
                                            modifier = Modifier.size(22.dp)
                                        )
                                    }
                                }
                                AnimatedProgressRing(
                                    progress = progress,
                                    color = accent,
                                    size = 40.dp,
                                    strokeWidth = 4.dp,
                                    label = "${(progress * 100).toInt()}%"
                                )
                            }

                            Text(
                                text = book.title,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                minLines = 2
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (book.enabled) "সক্রিয়: ${activeCount}টি" else "বিরতিতে",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (book.enabled) accent else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                if (book.examDateEpochMs > 0L) {
                                    val days = ((book.examDateEpochMs - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                                    Surface(shape = RoundedCornerShape(6.dp), color = accent.copy(alpha = 0.16f)) {
                                        Text(
                                            "${days}দি",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = accent,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphPracticeSection(
    paragraphs: List<ParagraphFileEntity>,
    repository: RevisionRepository,
    onEdit: (ParagraphFileEntity) -> Unit,
    onPracticeNow: (ParagraphFileEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    if (paragraphs.isEmpty()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Article, contentDescription = null, modifier = Modifier.size(44.dp), tint = MaterialTheme.colorScheme.primary)
                Text("ইংরেজি প্যারাগ্রাফ বা কম্পোজিশন যোগ করুন", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "বড় প্যারাগ্রাফ পেস্ট করলে অ্যাপ লাইনে লাইনে ভাগ করবে এবং প্রতিবার অ্যালার্মে মাত্র ১টি লাইন দেখাবে ও অনুবাদ করবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(paragraphs, key = { it.id }) { para ->
                val total = para.lines.size.coerceAtLeast(1)
                val current = (para.currentLineIndex + 1).coerceAtMost(total)
                val progress = current.toFloat() / total
                GlassCard(accentColor = Color(0xFF2563EB), modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(para.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "লাইন $current / $total • প্রতি ${para.intervalMinutes} মি.${if (para.memoryMode) " • মেমোরি মোড" else ""}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = para.enabled,
                                onCheckedChange = { en ->
                                    scope.launch { repository.updateParagraph(para.copy(enabled = en)) }
                                }
                            )
                        }
                        LinearProgressIndicator(
                            progress = { progress },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50))
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(onClick = { onPracticeNow(para) }) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("এখনই অনুশীলন")
                            }
                            Row {
                                IconButton(onClick = { onEdit(para) }) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "সম্পাদনা")
                                }
                                IconButton(onClick = { scope.launch { repository.deleteParagraph(para.id) } }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VocabularySection(
    lists: List<VocabListEntity>,
    words: List<VocabWordEntity>,
    repository: RevisionRepository,
    onOpenList: (String) -> Unit,
    onPracticeNow: (VocabListEntity) -> Unit
) {
    val scope = rememberCoroutineScope()
    if (lists.isEmpty()) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(Icons.Rounded.Translate, contentDescription = null, modifier = Modifier.size(44.dp), tint = Color(0xFF7C3AED))
                Text("ইংরেজি ভোকাবুলারি তালিকা তৈরি করুন", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(
                    "একসাথে অনেক ইংরেজি শব্দ যোগ করুন। অ্যালার্মে একবারে মাত্র ১টি শব্দ আসবে ও তাৎক্ষণিক বাংলা অর্থ দেখাবে।",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    } else {
        LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), contentPadding = PaddingValues(bottom = 96.dp)) {
            items(lists, key = { it.id }) { list ->
                val listWords = words.filter { it.listId == list.id }
                val mastered = listWords.count { it.mastered }
                val weak = listWords.count { it.weakCount > 0 }

                GlassCard(
                    accentColor = Color(0xFF7C3AED),
                    onClick = { onOpenList(list.id) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(list.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    "মোট ${listWords.size} • আয়ত্ত $mastered • দুর্বল $weak • ${list.intervalMinutes} মি.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = list.enabled,
                                onCheckedChange = { en ->
                                    scope.launch { repository.updateVocabList(list.copy(enabled = en)) }
                                }
                            )
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            FilledTonalButton(
                                onClick = { onPracticeNow(list) },
                                enabled = listWords.any { it.enabled }
                            ) {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("শব্দ অনুশীলন")
                            }
                            Row {
                                IconButton(onClick = { onOpenList(list.id) }) {
                                    Icon(Icons.Rounded.ListAlt, contentDescription = "শব্দ তালিকা দেখুন")
                                }
                                IconButton(onClick = { scope.launch { repository.deleteVocabList(list.id) } }) {
                                    Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParagraphEditorDialog(
    existing: ParagraphFileEntity?,
    onDismiss: () -> Unit,
    onSave: (String, String, Int, Boolean, Boolean) -> Unit
) {
    var title by remember(existing) { mutableStateOf(existing?.title.orEmpty()) }
    var rawText by remember(existing) { mutableStateOf(existing?.rawText.orEmpty()) }
    var intervalText by remember(existing) { mutableStateOf((existing?.intervalMinutes ?: 15).toString()) }
    var memoryMode by remember(existing) { mutableStateOf(existing?.memoryMode ?: false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing == null) "নতুন ইংরেজি প্যারাগ্রাফ" else "প্যারাগ্রাফ সম্পাদনা") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("শিরোনাম (যেমন: A Winter Morning)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("paragraph_title_input")
                )
                OutlinedTextField(
                    value = rawText,
                    onValueChange = { rawText = it },
                    label = { Text("সম্পূর্ণ ইংরেজি প্যারাগ্রাফ পেস্ট করুন") },
                    minLines = 4,
                    modifier = Modifier.fillMaxWidth().testTag("paragraph_text_input")
                )
                OutlinedTextField(
                    value = intervalText,
                    onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                    label = { Text("ইন্টারভ্যাল (মিনিট)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = memoryMode, onCheckedChange = { memoryMode = it })
                    Text("মেমোরি মোড (৫ সেকেন্ড পর লাইন লুকাবে)")
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val mins = (intervalText.toIntOrNull() ?: 15).coerceIn(1, 1440)
                    onSave(title, rawText, mins, memoryMode, existing?.enabled ?: true)
                },
                enabled = title.isNotBlank() && rawText.isNotBlank()
            ) { Text("সংরক্ষণ") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("বাতিল") }
        }
    )
}
