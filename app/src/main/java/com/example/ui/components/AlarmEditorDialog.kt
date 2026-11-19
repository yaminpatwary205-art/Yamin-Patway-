package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.alarm.AlarmScheduler
import com.example.data.local.*
import java.util.Calendar
import java.util.UUID

@Composable
fun AlarmEditorDialog(
    existing: RevisionAlarmEntity?,
    books: List<BookEntity>,
    chapters: List<ChapterEntity>,
    notes: List<NoteEntity>,
    paragraphs: List<ParagraphFileEntity>,
    vocabLists: List<VocabListEntity>,
    onDismiss: () -> Unit,
    onSave: (RevisionAlarmEntity) -> Unit
) {
    var title by remember(existing?.id) { mutableStateOf(existing?.title ?: "") }
    var scheduleType by remember(existing?.id) { mutableStateOf(existing?.scheduleType ?: "interval") }
    var intervalText by remember(existing?.id) { mutableStateOf((existing?.intervalMinutes ?: 15).toString()) }
    var hourOfDay by remember(existing?.id) { mutableIntStateOf(existing?.hourOfDay ?: 8) }
    var minute by remember(existing?.id) { mutableIntStateOf(existing?.minute ?: 0) }
    var selectedDays by remember(existing?.id) {
        mutableStateOf(AlarmScheduler.parseRepeatDays(existing?.repeatDaysCsv.orEmpty()))
    }
    var useActiveHours by remember(existing?.id) { mutableStateOf(existing?.useActiveHours ?: false) }

    var selectedBookIds by remember(existing?.id) { mutableStateOf(existing?.selectedBookIds.orEmpty().toSet()) }
    var selectedChapterIds by remember(existing?.id) { mutableStateOf(existing?.selectedChapterIds.orEmpty().toSet()) }
    var selectedNoteIds by remember(existing?.id) { mutableStateOf(existing?.selectedNoteIds.orEmpty().toSet()) }
    var selectedParagraphIds by remember(existing?.id) { mutableStateOf(existing?.selectedParagraphIds.orEmpty().toSet()) }
    var selectedVocabListIds by remember(existing?.id) { mutableStateOf(existing?.selectedVocabListIds.orEmpty().toSet()) }

    var pickerTab by remember { mutableIntStateOf(0) } // 0 = Books/Chapters/Notes, 1 = Paragraphs, 2 = Vocab
    var expandedBooks by remember { mutableStateOf<Set<String>>(emptySet()) }
    var expandedChapters by remember { mutableStateOf<Set<String>>(emptySet()) }

    val chaptersByBook = remember(chapters) { chapters.groupBy { it.bookId } }
    val notesByChapter = remember(notes) { notes.groupBy { it.chapterId } }

    fun effectiveNotesSelectedCount(): Int {
        return notes.count { n ->
            n.id in selectedNoteIds || n.chapterId in selectedChapterIds || n.bookId in selectedBookIds
        }
    }

    val totalSelectionSummary = remember(
        selectedBookIds,
        selectedChapterIds,
        selectedNoteIds,
        selectedParagraphIds,
        selectedVocabListIds,
        notes
    ) {
        val noteCount = effectiveNotesSelectedCount()
        val pCount = selectedParagraphIds.size
        val vCount = selectedVocabListIds.size
        val parentScopeCount = selectedBookIds.size + selectedChapterIds.size
        if (noteCount + pCount + vCount + parentScopeCount == 0) {
            "এখনো কোনো বিষয় নির্বাচন করা হয়নি"
        } else {
            buildList {
                if (noteCount > 0) add("নোট ${noteCount}টি")
                else if (selectedBookIds.isNotEmpty() || selectedChapterIds.isNotEmpty()) {
                    add("বই/অধ্যায় ${selectedBookIds.size + selectedChapterIds.size}টি")
                }
                if (pCount > 0) add("প্যারাগ্রাফ ${pCount}টি")
                if (vCount > 0) add("ভোকাব ${vCount}টি")
            }.joinToString(" • ")
        }
    }

    val canSave = title.isNotBlank() && (
        selectedBookIds.isNotEmpty() ||
            selectedChapterIds.isNotEmpty() ||
            selectedNoteIds.isNotEmpty() ||
            selectedParagraphIds.isNotEmpty() ||
            selectedVocabListIds.isNotEmpty()
        )

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .widthIn(max = 580.dp)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            tonalElevation = 8.dp,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(18.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Icon(Icons.Rounded.AlarmAdd, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Text(
                            text = if (existing == null) "নতুন অ্যালার্ম সেট করুন" else "অ্যালার্ম সম্পাদনা",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Rounded.Close, contentDescription = "বন্ধ করুন")
                    }
                }

                Spacer(Modifier.height(10.dp))

                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxWidth().testTag("alarm_editor_list"),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = title,
                            onValueChange = { title = it },
                            label = { Text("অ্যালার্মের নাম (যেমন: সকালের ইংরেজি বা গণিত)") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth().testTag("alarm_title_input")
                        )
                    }

                    // Schedule mode & timing controls
                    item {
                        Surface(
                            shape = RoundedCornerShape(16.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Text(
                                    "কখন ও কতক্ষণ পরপর অ্যালার্ম বাজবে",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    FilterChip(
                                        selected = scheduleType == "interval",
                                        onClick = { scheduleType = "interval" },
                                        label = { Text("বিরতি পরপর") },
                                        modifier = Modifier.weight(1f).testTag("alarm_mode_interval_chip")
                                    )
                                    FilterChip(
                                        selected = scheduleType == "clock",
                                        onClick = { scheduleType = "clock" },
                                        label = { Text("নির্দিষ্ট ঘড়ির সময়") },
                                        modifier = Modifier.weight(1f).testTag("alarm_mode_clock_chip")
                                    )
                                }

                                if (scheduleType == "interval") {
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        listOf(1, 5, 10, 15, 30, 60).forEach { mins ->
                                            FilterChip(
                                                selected = intervalText == mins.toString(),
                                                onClick = { intervalText = mins.toString() },
                                                label = { Text("$mins মি.") }
                                            )
                                        }
                                    }
                                    OutlinedTextField(
                                        value = intervalText,
                                        onValueChange = { intervalText = it.filter { c -> c.isDigit() } },
                                        label = { Text("কাস্টম সময় (মিনিট)") },
                                        singleLine = true,
                                        modifier = Modifier.fillMaxWidth().testTag("alarm_interval_input")
                                    )
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.fillMaxWidth().clickable { useActiveHours = !useActiveHours }
                                    ) {
                                        Checkbox(checked = useActiveHours, onCheckedChange = { useActiveHours = it })
                                        Text(
                                            "রাতের বিরতি (শুধু সকাল ৮টা – রাত ১০টা)",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurface
                                        )
                                    }
                                } else {
                                    // Clock Time Picker Steppers
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surface,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Column(
                                            modifier = Modifier.padding(12.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                formatClockTimeBn(hourOfDay, minute),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.primary
                                            )
                                            Row(
                                                horizontalArrangement = Arrangement.spacedBy(16.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("ঘণ্টা", style = MaterialTheme.typography.labelSmall)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        FilledTonalIconButton(onClick = { hourOfDay = (hourOfDay + 23) % 24 }) {
                                                            Icon(Icons.Rounded.Remove, contentDescription = "ঘণ্টা কমান")
                                                        }
                                                        Text(
                                                            hourOfDay.toString().padStart(2, '0'),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp)
                                                        )
                                                        FilledTonalIconButton(onClick = { hourOfDay = (hourOfDay + 1) % 24 }) {
                                                            Icon(Icons.Rounded.Add, contentDescription = "ঘণ্টা বাড়ান")
                                                        }
                                                    }
                                                }
                                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                                    Text("মিনিট", style = MaterialTheme.typography.labelSmall)
                                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                                        FilledTonalIconButton(onClick = { minute = (minute + 55) % 60 }) {
                                                            Icon(Icons.Rounded.Remove, contentDescription = "মিনিট কমান")
                                                        }
                                                        Text(
                                                            minute.toString().padStart(2, '0'),
                                                            style = MaterialTheme.typography.titleMedium,
                                                            fontWeight = FontWeight.Bold,
                                                            modifier = Modifier.padding(horizontal = 8.dp)
                                                        )
                                                        FilledTonalIconButton(onClick = { minute = (minute + 5) % 60 }) {
                                                            Icon(Icons.Rounded.Add, contentDescription = "মিনিট বাড়ান")
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }

                                    Text(
                                        "বার নির্বাচন (খালি রাখলে প্রতিদিন বাজবে):",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Row(
                                        modifier = Modifier.horizontalScroll(rememberScrollState()),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        WEEKDAYS_BN.forEach { (calDay, label) ->
                                            val isSelected = calDay in selectedDays
                                            FilterChip(
                                                selected = isSelected,
                                                onClick = {
                                                    selectedDays = if (isSelected) selectedDays - calDay else selectedDays + calDay
                                                },
                                                label = { Text(label) }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Granular Content Picker Header + Tabs
                    item {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(
                                "কোন কোন অধ্যায়ের নোট / প্যারাগ্রাফ / ভোকাবুলারি আসবে?",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "নির্বাচিত: $totalSelectionSummary",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.SemiBold
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                listOf("বই ও নোট", "প্যারাগ্রাফ", "ভোকাবুলারি").forEachIndexed { idx, label ->
                                    FilterChip(
                                        selected = pickerTab == idx,
                                        onClick = { pickerTab = idx },
                                        label = { Text(label, maxLines = 1) },
                                        modifier = Modifier.weight(1f).testTag("alarm_picker_tab_$idx")
                                    )
                                }
                            }
                        }
                    }

                    when (pickerTab) {
                        0 -> {
                            items(books, key = { it.id }) { book ->
                                val bookChapters = chaptersByBook[book.id].orEmpty().sortedBy { it.sortOrder }
                                val isBookChecked = book.id in selectedBookIds
                                val hasAnyChildChecked = isBookChecked ||
                                    bookChapters.any { it.id in selectedChapterIds } ||
                                    notes.any { it.bookId == book.id && it.id in selectedNoteIds }
                                val isBookExpanded = book.id in expandedBooks

                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceVariant,
                                    contentColor = MaterialTheme.colorScheme.onSurface,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Column(modifier = Modifier.padding(10.dp)) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.fillMaxWidth()
                                        ) {
                                            Checkbox(
                                                checked = isBookChecked,
                                                onCheckedChange = { checked ->
                                                    if (checked) {
                                                        selectedBookIds = selectedBookIds + book.id
                                                    } else {
                                                        selectedBookIds = selectedBookIds - book.id
                                                        val chapIdsInBook = bookChapters.map { it.id }.toSet()
                                                        val noteIdsInBook = notes.filter { it.bookId == book.id }.map { it.id }.toSet()
                                                        selectedChapterIds = selectedChapterIds - chapIdsInBook
                                                        selectedNoteIds = selectedNoteIds - noteIdsInBook
                                                    }
                                                },
                                                modifier = Modifier.testTag("alarm_select_book_${book.id}")
                                            )
                                            Column(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .clickable {
                                                        expandedBooks = if (isBookExpanded) expandedBooks - book.id else expandedBooks + book.id
                                                    }
                                            ) {
                                                Text(book.title, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyLarge)
                                                Text(
                                                    "${bookChapters.size}টি অধ্যায়" + if (hasAnyChildChecked && !isBookChecked) " • আংশিক নির্বাচিত" else "",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            IconButton(
                                                onClick = {
                                                    expandedBooks = if (isBookExpanded) expandedBooks - book.id else expandedBooks + book.id
                                                },
                                                modifier = Modifier.testTag("alarm_expand_book_${book.id}")
                                            ) {
                                                Icon(
                                                    if (isBookExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                    contentDescription = "অধ্যায় দেখুন"
                                                )
                                            }
                                        }

                                        if (isBookExpanded) {
                                            if (bookChapters.isEmpty()) {
                                                Text(
                                                    "এই বইয়ে এখনো কোনো অধ্যায় নেই",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                                    modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 4.dp)
                                                )
                                            } else {
                                                bookChapters.forEach { chapter ->
                                                    val chNotes = notesByChapter[chapter.id].orEmpty()
                                                    val isChChecked = isBookChecked || chapter.id in selectedChapterIds
                                                    val isChExpanded = chapter.id in expandedChapters

                                                    Row(
                                                        verticalAlignment = Alignment.CenterVertically,
                                                        modifier = Modifier.fillMaxWidth().padding(start = 14.dp)
                                                    ) {
                                                        Checkbox(
                                                            checked = isChChecked,
                                                            onCheckedChange = { checked ->
                                                                if (checked) {
                                                                    selectedChapterIds = selectedChapterIds + chapter.id
                                                                } else {
                                                                    if (isBookChecked) {
                                                                        // Expand book selection to all OTHER chapters
                                                                        selectedBookIds = selectedBookIds - book.id
                                                                        val otherChaps = bookChapters.filter { it.id != chapter.id }.map { it.id }
                                                                        selectedChapterIds = selectedChapterIds + otherChaps - chapter.id
                                                                    } else {
                                                                        selectedChapterIds = selectedChapterIds - chapter.id
                                                                    }
                                                                    selectedNoteIds = selectedNoteIds - chNotes.map { it.id }.toSet()
                                                                }
                                                            },
                                                            modifier = Modifier.testTag("alarm_select_chapter_${chapter.id}")
                                                        )
                                                        Text(
                                                            "${chapter.title} (${chNotes.size} নোট)",
                                                            style = MaterialTheme.typography.bodyMedium,
                                                            fontWeight = FontWeight.Medium,
                                                            modifier = Modifier
                                                                .weight(1f)
                                                                .clickable {
                                                                    expandedChapters = if (isChExpanded) expandedChapters - chapter.id else expandedChapters + chapter.id
                                                                }
                                                        )
                                                        if (chNotes.isNotEmpty()) {
                                                            IconButton(
                                                                onClick = {
                                                                    expandedChapters = if (isChExpanded) expandedChapters - chapter.id else expandedChapters + chapter.id
                                                                },
                                                                modifier = Modifier.testTag("alarm_expand_chapter_${chapter.id}")
                                                            ) {
                                                                Icon(
                                                                    if (isChExpanded) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
                                                                    contentDescription = "নোট দেখুন"
                                                                )
                                                            }
                                                        }
                                                    }

                                                    if (isChExpanded) {
                                                        val sortedChNotes = chNotes.sortedWith(
                                                            compareBy<NoteEntity>({ it.sortOrder }, { it.updatedAtEpochMs }, { it.id })
                                                        )
                                                        sortedChNotes.forEachIndexed { idx, note ->
                                                            val isNoteChecked = isChChecked || note.id in selectedNoteIds
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.fillMaxWidth().padding(start = 36.dp)
                                                            ) {
                                                                Checkbox(
                                                                    checked = isNoteChecked,
                                                                    onCheckedChange = { checked ->
                                                                        if (checked) {
                                                                            selectedNoteIds = selectedNoteIds + note.id
                                                                        } else {
                                                                            if (isBookChecked) {
                                                                                selectedBookIds = selectedBookIds - book.id
                                                                                val otherChaps = bookChapters.filter { it.id != chapter.id }.map { it.id }
                                                                                selectedChapterIds = selectedChapterIds + otherChaps
                                                                            }
                                                                            if (chapter.id in selectedChapterIds || isBookChecked) {
                                                                                selectedChapterIds = selectedChapterIds - chapter.id
                                                                                val siblingNotes = chNotes.filter { it.id != note.id }.map { it.id }
                                                                                selectedNoteIds = selectedNoteIds + siblingNotes - note.id
                                                                            } else {
                                                                                selectedNoteIds = selectedNoteIds - note.id
                                                                            }
                                                                        }
                                                                    },
                                                                    modifier = Modifier.testTag("alarm_select_note_${note.id}")
                                                                )
                                                                Text(
                                                                    text = "#${idx + 1}. ${note.content}",
                                                                    style = MaterialTheme.typography.bodySmall,
                                                                    maxLines = 2,
                                                                    overflow = TextOverflow.Ellipsis,
                                                                    modifier = Modifier.weight(1f)
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

                        1 -> {
                            if (paragraphs.isEmpty()) {
                                item {
                                    Text(
                                        "এখনো কোনো প্যারাগ্রাফ যোগ করা হয়নি। 'প্যারাগ্রাফ' ট্যাবে গিয়ে প্যারাগ্রাফ যোগ করুন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            } else {
                                items(paragraphs, key = { it.id }) { para ->
                                    val checked = para.id in selectedParagraphIds
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedParagraphIds = if (checked) selectedParagraphIds - para.id else selectedParagraphIds + para.id
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { ch ->
                                                    selectedParagraphIds = if (ch) selectedParagraphIds + para.id else selectedParagraphIds - para.id
                                                },
                                                modifier = Modifier.testTag("alarm_select_para_${para.id}")
                                            )
                                            Column(modifier = Modifier.weight(1f)) {
                                                Text(para.title, fontWeight = FontWeight.Bold)
                                                Text(
                                                    "মোট ${para.lines.size}টি লাইন",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        2 -> {
                            if (vocabLists.isEmpty()) {
                                item {
                                    Text(
                                        "এখনো কোনো ভোকাবুলারি তালিকা যোগ করা হয়নি। 'ভোকাবুলারি' ট্যাবে তালিকা তৈরি করুন।",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        modifier = Modifier.padding(12.dp)
                                    )
                                }
                            } else {
                                items(vocabLists, key = { it.id }) { list ->
                                    val checked = list.id in selectedVocabListIds
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = MaterialTheme.colorScheme.surfaceVariant,
                                        contentColor = MaterialTheme.colorScheme.onSurface,
                                        modifier = Modifier.fillMaxWidth().clickable {
                                            selectedVocabListIds = if (checked) selectedVocabListIds - list.id else selectedVocabListIds + list.id
                                        }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(10.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Checkbox(
                                                checked = checked,
                                                onCheckedChange = { ch ->
                                                    selectedVocabListIds = if (ch) selectedVocabListIds + list.id else selectedVocabListIds - list.id
                                                },
                                                modifier = Modifier.testTag("alarm_select_vocab_${list.id}")
                                            )
                                            Text(list.title, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                    OutlinedButton(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("বাতিল")
                    }
                    Button(
                        onClick = {
                            val alarm = RevisionAlarmEntity(
                                id = existing?.id ?: "al_${UUID.randomUUID().toString().replace("-", "").take(12)}",
                                title = title.trim(),
                                enabled = existing?.enabled ?: true,
                                scheduleType = scheduleType,
                                intervalMinutes = (intervalText.toIntOrNull() ?: 15).coerceIn(1, 1440),
                                hourOfDay = hourOfDay,
                                minute = minute,
                                repeatDaysCsv = selectedDays.sorted().joinToString(","),
                                useActiveHours = useActiveHours,
                                activeStartMinutes = existing?.activeStartMinutes ?: (8 * 60),
                                activeEndMinutes = existing?.activeEndMinutes ?: (22 * 60),
                                selectedBookIds = selectedBookIds.toList(),
                                selectedChapterIds = selectedChapterIds.toList(),
                                selectedNoteIds = selectedNoteIds.toList(),
                                selectedParagraphIds = selectedParagraphIds.toList(),
                                selectedVocabListIds = selectedVocabListIds.toList(),
                                rotationCursor = existing?.rotationCursor ?: 0,
                                lastTriggeredEpochMs = existing?.lastTriggeredEpochMs ?: 0L,
                                snoozedUntilEpochMs = existing?.snoozedUntilEpochMs ?: 0L
                            )
                            onSave(alarm)
                        },
                        enabled = canSave,
                        modifier = Modifier.weight(1f).height(48.dp).testTag("save_alarm_button")
                    ) {
                        Text(if (existing == null) "অ্যালার্ম সংরক্ষণ" else "আপডেট করুন")
                    }
                }
            }
        }
    }
}

private val WEEKDAYS_BN = listOf(
    Calendar.SATURDAY to "শনি",
    Calendar.SUNDAY to "রবি",
    Calendar.MONDAY to "সোম",
    Calendar.TUESDAY to "মঙ্গল",
    Calendar.WEDNESDAY to "বুধ",
    Calendar.THURSDAY to "বৃহ",
    Calendar.FRIDAY to "শুক্র"
)

fun formatClockTimeBn(hourOfDay: Int, minute: Int): String {
    val period = when {
        hourOfDay in 4..11 -> "সকাল"
        hourOfDay in 12..15 -> "দুপুর"
        hourOfDay in 16..18 -> "বিকাল"
        hourOfDay in 19..23 -> "রাত"
        else -> "ভোর"
    }
    val h12 = when {
        hourOfDay == 0 -> 12
        hourOfDay > 12 -> hourOfDay - 12
        else -> hourOfDay
    }
    return "$period ${h12.toString().padStart(2, '0')}:${minute.toString().padStart(2, '0')}"
}
