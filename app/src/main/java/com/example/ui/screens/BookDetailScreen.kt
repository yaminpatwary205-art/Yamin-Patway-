package com.example.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.RevisionRepository
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.NoteEntity
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.GlassCard
import com.example.ui.components.adaptiveAccent
import com.example.ui.components.bookIconForKey
import com.example.ui.components.parseColorHex
import com.example.ui.theme.SubjectColors
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    book: BookEntity,
    chapters: List<ChapterEntity>,
    notes: List<NoteEntity>,
    repository: RevisionRepository,
    onBack: () -> Unit,
    onOpenChapter: (String) -> Unit
) {
    val scope = rememberCoroutineScope()
    val accent = parseColorHex(book.colorHex)
    val displayAccent = adaptiveAccent(accent)
    val today = remember { RevisionRepository.todayDayString() }

    var showAddChapter by remember { mutableStateOf(false) }
    var showEditBook by remember { mutableStateOf(false) }
    var showExamDatePicker by remember { mutableStateOf(false) }
    var editingChapter by remember { mutableStateOf<ChapterEntity?>(null) }
    var scheduleChapter by remember { mutableStateOf<ChapterEntity?>(null) }

    AnimatedMeshBackground {
        Scaffold(
            containerColor = Color.Transparent,
            contentColor = MaterialTheme.colorScheme.onBackground,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Transparent,
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
                        actionIconContentColor = MaterialTheme.colorScheme.onBackground
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_book")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "ফিরে যান")
                        }
                    },
                    title = {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Icon(bookIconForKey(book.iconKey), contentDescription = null, tint = displayAccent)
                            Text(book.title, maxLines = 1)
                        }
                    },
                    actions = {
                        IconButton(onClick = { showEditBook = true }, modifier = Modifier.testTag("edit_book_button")) {
                            Icon(Icons.Rounded.Palette, contentDescription = "বই সম্পাদনা")
                        }
                    }
                )
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = { showAddChapter = true },
                    icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                    text = { Text("নতুন অধ্যায়") },
                    modifier = Modifier.testTag("add_chapter_fab")
                )
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                // Book-level schedule & exam date card
                item {
                    GlassCard(accentColor = accent, modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("বইয়ের রিমাইন্ডার সময়সূচি", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                    Text(
                                        if (book.enabled) "প্রতি ${book.intervalMinutes} মিনিটে সক্রিয়" else "বিরতিতে রাখা আছে",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = book.enabled,
                                    onCheckedChange = { enabled ->
                                        scope.launch { repository.updateBook(book.copy(enabled = enabled)) }
                                    },
                                    modifier = Modifier.testTag("book_enabled_switch")
                                )
                            }

                            // Interval quick chips
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                listOf(1, 5, 15, 30, 60).forEach { mins ->
                                    FilterChip(
                                        selected = book.intervalMinutes == mins,
                                        onClick = {
                                            scope.launch { repository.updateBook(book.copy(intervalMinutes = mins)) }
                                        },
                                        label = { Text("$mins মি.") }
                                    )
                                }
                                FilterChip(
                                    selected = book.intervalMinutes !in listOf(1, 5, 15, 30, 60),
                                    onClick = { showEditBook = true },
                                    label = {
                                        Text(
                                            if (book.intervalMinutes !in listOf(1, 5, 15, 30, 60)) "${book.intervalMinutes} মি." else "কাস্টম"
                                        )
                                    }
                                )
                            }

                            // Active hours toggle & exam countdown
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = book.useActiveHours,
                                        onClick = {
                                            scope.launch { repository.updateBook(book.copy(useActiveHours = !book.useActiveHours)) }
                                        },
                                        leadingIcon = { Icon(Icons.Rounded.NightsStay, contentDescription = null, modifier = Modifier.size(16.dp)) },
                                        label = { Text(if (book.useActiveHours) "রাতের বিরতি (সকাল ৮-রাত ১০)" else "সক্রিয় সময়: ২৪ ঘণ্টা") }
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val examLabel = if (book.examDateEpochMs > 0L) {
                                    val days = ((book.examDateEpochMs - System.currentTimeMillis()) / 86_400_000L).coerceAtLeast(0)
                                    val df = SimpleDateFormat("dd MMM yyyy", Locale.getDefault())
                                    "পরীক্ষা: ${df.format(Date(book.examDateEpochMs))} ($days দিন বাকি)"
                                } else {
                                    "পরীক্ষার তারিখ সেট করুন (ঐচ্ছিক)"
                                }
                                Text(examLabel, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    if (book.examDateEpochMs > 0L) {
                                        TextButton(onClick = {
                                            scope.launch { repository.updateBook(book.copy(examDateEpochMs = 0L)) }
                                        }) { Text("মুছুন") }
                                    }
                                    FilledTonalButton(onClick = { showExamDatePicker = true }) {
                                        Icon(Icons.Rounded.Event, contentDescription = null, modifier = Modifier.size(16.dp))
                                        Spacer(Modifier.width(4.dp))
                                        Text("তারিখ")
                                    }
                                }
                            }
                        }
                    }
                }

                item {
                    Text(
                        text = "অধ্যায়সমূহ (${chapters.size})",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }

                if (chapters.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.LibraryBooks, contentDescription = null, modifier = Modifier.size(40.dp), tint = displayAccent)
                                Text("কোনো অধ্যায় যোগ করা হয়নি", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "নিচের 'নতুন অধ্যায়' বোতামে ট্যাপ করে অধ্যায় তৈরি করুন ও নোট যোগ করুন।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(chapters, key = { _, ch -> ch.id }) { index, chapter ->
                        val chapterNotes = notes.filter { it.chapterId == chapter.id }
                        val revisedToday = chapterNotes.count { it.lastRevisedDay == today }
                        val progress = if (chapterNotes.isEmpty()) 0f else revisedToday.toFloat() / chapterNotes.size

                        GlassCard(
                            accentColor = accent,
                            onClick = { onOpenChapter(chapter.id) },
                            modifier = Modifier.fillMaxWidth().testTag("chapter_card_${chapter.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(chapter.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                        Text(
                                            "আজ রিভিশন: $revisedToday / ${chapterNotes.size} নোট",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (index > 0) {
                                            IconButton(onClick = {
                                                scope.launch { repository.swapChapterOrder(chapter, chapters[index - 1]) }
                                            }) {
                                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "উপরে নিন")
                                            }
                                        }
                                        if (index < chapters.lastIndex) {
                                            IconButton(onClick = {
                                                scope.launch { repository.swapChapterOrder(chapter, chapters[index + 1]) }
                                            }) {
                                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "নিচে নিন")
                                            }
                                        }
                                        IconButton(onClick = { scheduleChapter = chapter }) {
                                            Icon(Icons.Rounded.Timer, contentDescription = "অধ্যায়ের সময়")
                                        }
                                        IconButton(onClick = { editingChapter = chapter }) {
                                            Icon(Icons.Rounded.Edit, contentDescription = "অধ্যায় সম্পাদনা")
                                        }
                                        IconButton(onClick = {
                                            scope.launch { repository.deleteChapter(chapter.id) }
                                        }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "অধ্যায় মুছুন", tint = MaterialTheme.colorScheme.error)
                                        }
                                    }
                                }
                                LinearProgressIndicator(
                                    progress = { progress },
                                    modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)),
                                    color = displayAccent
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAddChapter || editingChapter != null) {
        var title by remember(editingChapter) { mutableStateOf(editingChapter?.title.orEmpty()) }
        AlertDialog(
            onDismissRequest = { showAddChapter = false; editingChapter = null },
            title = { Text(if (editingChapter == null) "নতুন অধ্যায় যোগ করুন" else "অধ্যায় নাম পরিবর্তন") },
            text = {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("অধ্যায়ের নাম") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("chapter_title_input")
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val existing = editingChapter
                        scope.launch {
                            if (existing == null) repository.addChapter(book.id, title)
                            else repository.updateChapter(existing.copy(title = title))
                        }
                        showAddChapter = false
                        editingChapter = null
                    },
                    enabled = title.isNotBlank()
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { showAddChapter = false; editingChapter = null }) { Text("বাতিল") }
            }
        )
    }

    if (showEditBook) {
        var name by remember(book.id) { mutableStateOf(book.title) }
        var colorHex by remember(book.id) { mutableStateOf(book.colorHex) }
        var customMinutes by remember(book.id) { mutableStateOf(book.intervalMinutes.toString()) }
        AlertDialog(
            onDismissRequest = { showEditBook = false },
            title = { Text("বই সম্পাদনা করুন") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("বইয়ের নাম") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = customMinutes,
                        onValueChange = { customMinutes = it.filter { c -> c.isDigit() } },
                        label = { Text("কাস্টম ইন্টারভ্যাল (মিনিট)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text("রঙ বাছাই করুন", style = MaterialTheme.typography.labelMedium)
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        SubjectColors.forEach { hex ->
                            val c = parseColorHex(hex)
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .clip(CircleShape)
                                    .background(c)
                                    .border(
                                        width = if (colorHex == hex) 3.dp else 0.dp,
                                        color = MaterialTheme.colorScheme.onSurface,
                                        shape = CircleShape
                                    )
                                    .clickable { colorHex = hex }
                            )
                        }
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = (customMinutes.toIntOrNull() ?: book.intervalMinutes).coerceIn(1, 1440)
                        scope.launch {
                            repository.updateBook(book.copy(title = name, colorHex = colorHex, intervalMinutes = mins))
                        }
                        showEditBook = false
                    },
                    enabled = name.isNotBlank()
                ) { Text("আপডেট") }
            },
            dismissButton = {
                TextButton(onClick = { showEditBook = false }) { Text("বাতিল") }
            }
        )
    }

    if (scheduleChapter != null) {
        val chap = scheduleChapter!!
        var overrideEnabled by remember(chap.id) { mutableStateOf(chap.overrideEnabled) }
        var overrideInterval by remember(chap.id) { mutableStateOf(chap.overrideIntervalMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { scheduleChapter = null },
            title = { Text("অধ্যায় অ্যালার্ম: ${chap.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("বইয়ের সেটিংস অনুযায়ী চলবে নাকি আলাদা সেটিংস দেবেন?", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = overrideEnabled == -1, onClick = { overrideEnabled = -1 }, label = { Text("বইয়ের মতো") })
                        FilterChip(selected = overrideEnabled == 1, onClick = { overrideEnabled = 1 }, label = { Text("চালু") })
                        FilterChip(selected = overrideEnabled == 0, onClick = { overrideEnabled = 0 }, label = { Text("বন্ধ") })
                    }
                    Row(
                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        listOf(1, 5, 10, 15, 30).forEach { mins ->
                            FilterChip(
                                selected = overrideInterval == mins.toString(),
                                onClick = { overrideInterval = mins.toString() },
                                label = { Text("$mins মি.") }
                            )
                        }
                    }
                    OutlinedTextField(
                        value = overrideInterval,
                        onValueChange = { overrideInterval = it.filter { c -> c.isDigit() } },
                        label = { Text("আলাদা ইন্টারভ্যাল (মিনিট, খালি রাখলে বইয়ের মতো)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mins = (overrideInterval.toIntOrNull() ?: 0).coerceIn(0, 1440)
                    scope.launch {
                        repository.updateChapter(chap.copy(overrideEnabled = overrideEnabled, overrideIntervalMinutes = mins))
                    }
                    scheduleChapter = null
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { scheduleChapter = null }) { Text("বাতিল") }
            }
        )
    }

    if (showExamDatePicker) {
        val datePickerState = rememberDatePickerState(
            initialSelectedDateMillis = book.examDateEpochMs.takeIf { it > 0L } ?: (System.currentTimeMillis() + 7 * 86_400_000L)
        )
        DatePickerDialog(
            onDismissRequest = { showExamDatePicker = false },
            confirmButton = {
                Button(onClick = {
                    val selected = datePickerState.selectedDateMillis ?: 0L
                    scope.launch { repository.updateBook(book.copy(examDateEpochMs = selected)) }
                    showExamDatePicker = false
                }) { Text("নির্বাচন করুন") }
            },
            dismissButton = {
                TextButton(onClick = { showExamDatePicker = false }) { Text("বাতিল") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }
}
