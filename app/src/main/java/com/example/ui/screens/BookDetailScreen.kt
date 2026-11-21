package com.example.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Article
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.RevisionRepository
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.NoteEntity
import com.example.data.local.ParagraphFileEntity
import com.example.data.local.VocabListEntity
import com.example.data.local.VocabWordEntity
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.GlassCard
import com.example.ui.components.RichNoteCodec
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
    onOpenChapter: (String) -> Unit,
    paragraphs: List<ParagraphFileEntity> = emptyList(),
    vocabLists: List<VocabListEntity> = emptyList(),
    vocabWords: List<VocabWordEntity> = emptyList(),
    onOpenVocabList: (String) -> Unit = {},
    onPracticeParagraph: (ParagraphFileEntity) -> Unit = {},
    onPracticeVocab: (VocabListEntity) -> Unit = {}
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

    var expandedChapterIds by remember(book.id) { mutableStateOf<Set<String>>(emptySet()) }
    var expandedParagraphIds by remember(book.id) { mutableStateOf<Set<String>>(emptySet()) }
    var expandedVocabListIds by remember(book.id) { mutableStateOf<Set<String>>(emptySet()) }
    var chaptersSectionExpanded by remember(book.id) { mutableStateOf(true) }
    var paragraphsSectionExpanded by remember(book.id) { mutableStateOf(false) }
    var vocabSectionExpanded by remember(book.id) { mutableStateOf(false) }

    val hasAnyExpandableContent = chapters.isNotEmpty() || paragraphs.isNotEmpty() || vocabLists.isNotEmpty()
    val isAllExpanded = hasAnyExpandableContent &&
        chaptersSectionExpanded &&
        (chapters.isEmpty() || expandedChapterIds.containsAll(chapters.map { it.id })) &&
        (paragraphs.isEmpty() || (paragraphsSectionExpanded && expandedParagraphIds.containsAll(paragraphs.map { it.id }))) &&
        (vocabLists.isEmpty() || (vocabSectionExpanded && expandedVocabListIds.containsAll(vocabLists.map { it.id })))

    fun toggleExpandCollapseAll() {
        if (isAllExpanded) {
            chaptersSectionExpanded = false
            paragraphsSectionExpanded = false
            vocabSectionExpanded = false
            expandedChapterIds = emptySet()
            expandedParagraphIds = emptySet()
            expandedVocabListIds = emptySet()
        } else {
            chaptersSectionExpanded = true
            paragraphsSectionExpanded = true
            vocabSectionExpanded = true
            expandedChapterIds = chapters.map { it.id }.toSet()
            expandedParagraphIds = paragraphs.map { it.id }.toSet()
            expandedVocabListIds = vocabLists.map { it.id }.toSet()
        }
    }

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
                            Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    },
                    actions = {
                        if (hasAnyExpandableContent) {
                            IconButton(
                                onClick = { toggleExpandCollapseAll() },
                                modifier = Modifier.testTag("book_top_expand_all_button")
                            ) {
                                Icon(
                                    imageVector = if (isAllExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                                    contentDescription = if (isAllExpanded) "সব গুটিয়ে নিন" else "সব খুলুন"
                                )
                            }
                        }
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
                    .padding(horizontal = 16.dp)
                    .testTag("book_detail_list"),
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

                // Single-click Collapse / Expand All bar for all book content (Chapters, Paragraphs, Vocab Lists)
                if (hasAnyExpandableContent) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "বইয়ের সকল বিষয়বস্তু",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                            FilledTonalButton(
                                onClick = { toggleExpandCollapseAll() },
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                                modifier = Modifier.testTag("book_toggle_expand_all_button")
                            ) {
                                Icon(
                                    imageVector = if (isAllExpanded) Icons.Rounded.UnfoldLess else Icons.Rounded.UnfoldMore,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    text = if (isAllExpanded) "সব গুটিয়ে নিন" else "সব খুলুন",
                                    style = MaterialTheme.typography.labelLarge
                                )
                            }
                        }
                    }
                }

                // Chapters section header
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .clickable { chaptersSectionExpanded = !chaptersSectionExpanded }
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "অধ্যায়সমূহ (${chapters.size})",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Icon(
                            imageVector = if (chaptersSectionExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                            contentDescription = if (chaptersSectionExpanded) "অধ্যায় গুটিয়ে নিন" else "অধ্যায় খুলুন",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                if (chaptersSectionExpanded) {
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
                            val chapterNotes = remember(notes, chapter.id) {
                                notes.filter { it.chapterId == chapter.id }
                                    .sortedWith(compareBy<NoteEntity>({ it.sortOrder }, { it.updatedAtEpochMs }, { it.id }))
                            }
                            val revisedToday = chapterNotes.count { it.lastRevisedDay == today }
                            val progress = if (chapterNotes.isEmpty()) 0f else revisedToday.toFloat() / chapterNotes.size
                            val isChapExpanded = chapter.id in expandedChapterIds

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
                                            IconButton(
                                                onClick = {
                                                    expandedChapterIds = if (isChapExpanded) {
                                                        expandedChapterIds - chapter.id
                                                    } else {
                                                        expandedChapterIds + chapter.id
                                                    }
                                                },
                                                modifier = Modifier.testTag("chapter_expand_${chapter.id}")
                                            ) {
                                                Icon(
                                                    if (isChapExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = if (isChapExpanded) "নোট লুকান" else "নোট দেখুন"
                                                )
                                            }
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

                                    AnimatedVisibility(visible = isChapExpanded) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                if (chapterNotes.isEmpty()) {
                                                    Text(
                                                        "এই অধ্যায়ে এখনো কোনো নোট নেই",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                } else {
                                                    chapterNotes.forEachIndexed { nIdx, note ->
                                                        val preview = RichNoteCodec.stripImageMarkers(note.content)
                                                        Text(
                                                            text = "#${nIdx + 1}. $preview",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            maxLines = 2,
                                                            overflow = TextOverflow.Ellipsis,
                                                            color = MaterialTheme.colorScheme.onSurface
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

                // Paragraphs section within the book (expandable/collapsible)
                if (paragraphs.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { paragraphsSectionExpanded = !paragraphsSectionExpanded }
                                .padding(top = 6.dp, bottom = 4.dp)
                                .testTag("book_paragraphs_header"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.AutoMirrored.Rounded.Article, contentDescription = null, tint = displayAccent, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "প্যারাগ্রাফ অনুশীলন (${paragraphs.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                imageVector = if (paragraphsSectionExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (paragraphsSectionExpanded) "প্যারাগ্রাফ গুটিয়ে নিন" else "প্যারাগ্রাফ খুলুন",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (paragraphsSectionExpanded) {
                        items(paragraphs, key = { "bk_para_${it.id}" }) { para ->
                            val isParaExpanded = para.id in expandedParagraphIds
                            val sentenceLines = remember(para.rawText) {
                                RevisionRepository.splitParagraphIntoLines(para.rawText).ifEmpty { para.lines }
                            }
                            GlassCard(
                                accentColor = accent,
                                onClick = {
                                    expandedParagraphIds = if (isParaExpanded) {
                                        expandedParagraphIds - para.id
                                    } else {
                                        expandedParagraphIds + para.id
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("book_paragraph_card_${para.id}")
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(para.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                "${sentenceLines.size}টি বাক্য • প্রতি ${para.intervalMinutes} মি.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            FilledTonalButton(
                                                onClick = { onPracticeParagraph(para) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text("অনুশীলন", style = MaterialTheme.typography.labelSmall)
                                            }
                                            IconButton(
                                                onClick = {
                                                    expandedParagraphIds = if (isParaExpanded) {
                                                        expandedParagraphIds - para.id
                                                    } else {
                                                        expandedParagraphIds + para.id
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    if (isParaExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = if (isParaExpanded) "লাইন লুকান" else "লাইন দেখুন"
                                                )
                                            }
                                        }
                                    }

                                    AnimatedVisibility(visible = isParaExpanded) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                            ) {
                                                sentenceLines.forEachIndexed { idx, line ->
                                                    Text(
                                                        text = "${idx + 1}. $line",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        fontWeight = if (idx == para.currentLineIndex) FontWeight.SemiBold else FontWeight.Normal,
                                                        color = if (idx == para.currentLineIndex) displayAccent else MaterialTheme.colorScheme.onSurface
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

                // Vocabulary Lists section within the book (expandable/collapsible)
                if (vocabLists.isNotEmpty()) {
                    item {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { vocabSectionExpanded = !vocabSectionExpanded }
                                .padding(top = 6.dp, bottom = 4.dp)
                                .testTag("book_vocab_header"),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Icon(Icons.Rounded.Translate, contentDescription = null, tint = displayAccent, modifier = Modifier.size(20.dp))
                                Text(
                                    text = "ভোকাবুলারি তালিকা (${vocabLists.size})",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Icon(
                                imageVector = if (vocabSectionExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                contentDescription = if (vocabSectionExpanded) "ভোকাবুলারি গুটিয়ে নিন" else "ভোকাবুলারি খুলুন",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    if (vocabSectionExpanded) {
                        items(vocabLists, key = { "bk_voc_${it.id}" }) { list ->
                            val isListExpanded = list.id in expandedVocabListIds
                            val listWords = remember(vocabWords, list.id) {
                                vocabWords.filter { it.listId == list.id }
                            }
                            GlassCard(
                                accentColor = accent,
                                onClick = {
                                    expandedVocabListIds = if (isListExpanded) {
                                        expandedVocabListIds - list.id
                                    } else {
                                        expandedVocabListIds + list.id
                                    }
                                },
                                modifier = Modifier.fillMaxWidth().testTag("book_vocab_card_${list.id}")
                            ) {
                                Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(list.title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                                            Text(
                                                "মোট ${listWords.size}টি শব্দ • ${list.intervalMinutes} মি.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            FilledTonalButton(
                                                onClick = { onOpenVocabList(list.id) },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp)
                                            ) {
                                                Text("খুলুন", style = MaterialTheme.typography.labelSmall)
                                            }
                                            IconButton(
                                                onClick = {
                                                    expandedVocabListIds = if (isListExpanded) {
                                                        expandedVocabListIds - list.id
                                                    } else {
                                                        expandedVocabListIds + list.id
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    if (isListExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = if (isListExpanded) "শব্দ লুকান" else "শব্দ দেখুন"
                                                )
                                            }
                                        }
                                    }

                                    AnimatedVisibility(visible = isListExpanded) {
                                        Surface(
                                            shape = RoundedCornerShape(12.dp),
                                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(10.dp),
                                                verticalArrangement = Arrangement.spacedBy(4.dp)
                                            ) {
                                                if (listWords.isEmpty()) {
                                                    Text(
                                                        "এই তালিকায় এখনো কোনো শব্দ যোগ করা হয়নি",
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                } else {
                                                    listWords.forEach { w ->
                                                        Text(
                                                            text = if (w.meaningBn.isNotBlank()) "• ${w.word} — ${w.meaningBn}" else "• ${w.word}",
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurface
                                                        )
                                                    }
                                                    if (listWords.any { it.enabled }) {
                                                        TextButton(
                                                            onClick = { onPracticeVocab(list) },
                                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 2.dp),
                                                            modifier = Modifier.height(32.dp)
                                                        ) {
                                                            Text("শব্দ অনুশীলন শুরু করুন", style = MaterialTheme.typography.labelMedium)
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
