package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.example.ui.components.parseColorHex
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChapterDetailScreen(
    book: BookEntity,
    chapter: ChapterEntity,
    allChaptersInBook: List<ChapterEntity>,
    notes: List<NoteEntity>,
    repository: RevisionRepository,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val accent = parseColorHex(book.colorHex)

    var showAddNote by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var movingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var scheduleNote by remember { mutableStateOf<NoteEntity?>(null) }
    var bulkMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    AnimatedMeshBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_chapter")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "ফিরে যান")
                        }
                    },
                    title = {
                        Column {
                            Text(chapter.title, maxLines = 1)
                            Text(book.title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    actions = {
                        IconButton(onClick = {
                            bulkMode = !bulkMode
                            selectedIds = emptySet()
                        }) {
                            Icon(
                                if (bulkMode) Icons.Rounded.Close else Icons.Rounded.Checklist,
                                contentDescription = "একাধিক বাছাই"
                            )
                        }
                    }
                )
            },
            bottomBar = {
                if (bulkMode && selectedIds.isNotEmpty()) {
                    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("${selectedIds.size}টি নির্বাচিত", modifier = Modifier.weight(1f), fontWeight = FontWeight.Bold)
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    repository.bulkPauseNotes(selectedIds, pause = true)
                                    bulkMode = false
                                    selectedIds = emptySet()
                                }
                            }) { Text("বিরতি দিন") }
                            FilledTonalButton(onClick = {
                                scope.launch {
                                    repository.bulkPauseNotes(selectedIds, pause = false)
                                    bulkMode = false
                                    selectedIds = emptySet()
                                }
                            }) { Text("চালু করুন") }
                            Button(
                                onClick = {
                                    scope.launch {
                                        repository.bulkDeleteNotes(selectedIds)
                                        bulkMode = false
                                        selectedIds = emptySet()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("মুছুন") }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!bulkMode) {
                    ExtendedFloatingActionButton(
                        onClick = { showAddNote = true },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("নতুন নোট") },
                        modifier = Modifier.testTag("add_note_fab")
                    )
                }
            }
        ) { innerPadding ->
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                contentPadding = PaddingValues(bottom = 96.dp)
            ) {
                if (notes.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(40.dp), tint = accent)
                                Text("এই অধ্যায়ে এখনো কোনো নোট নেই", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "নোট যোগ করলে নির্ধারিত সময় পরপর অ্যালার্ম হিসেবে স্ক্রিনে দেখাবে।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    items(notes, key = { it.id }) { note ->
                        val isSelected = note.id in selectedIds
                        GlassCard(
                            accentColor = accent,
                            onClick = {
                                if (bulkMode) {
                                    selectedIds = if (isSelected) selectedIds - note.id else selectedIds + note.id
                                } else {
                                    editingNote = note
                                }
                            },
                            modifier = Modifier.fillMaxWidth().testTag("note_card_${note.id}")
                        ) {
                            Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    if (bulkMode) {
                                        Checkbox(
                                            checked = isSelected,
                                            onCheckedChange = { checked ->
                                                selectedIds = if (checked) selectedIds + note.id else selectedIds - note.id
                                            }
                                        )
                                    }
                                    Text(
                                        text = note.content,
                                        style = MaterialTheme.typography.bodyLarge,
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        scope.launch { repository.updateNote(note.copy(starred = !note.starred)) }
                                    }) {
                                        Icon(
                                            if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                            contentDescription = "তারকা",
                                            tint = if (note.starred) Color(0xFFF59E0B) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val statusText = buildString {
                                        append("রিভিশন: ${note.revisionCount}")
                                        if (note.overrideEnabled == 0) append(" • বিরতিতে")
                                        else if (note.overrideIntervalMinutes > 0) append(" • ${note.overrideIntervalMinutes} মি.")
                                    }
                                    Text(
                                        text = statusText,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )

                                    Row {
                                        IconButton(onClick = { scheduleNote = note }) {
                                            Icon(Icons.Rounded.Timer, contentDescription = "নোট সময়", modifier = Modifier.size(20.dp))
                                        }
                                        IconButton(onClick = {
                                            scope.launch { repository.duplicateNote(note) }
                                        }) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "ডুপ্লিকেট", modifier = Modifier.size(20.dp))
                                        }
                                        if (allChaptersInBook.size > 1) {
                                            IconButton(onClick = { movingNote = note }) {
                                                Icon(Icons.Rounded.DriveFileMove, contentDescription = "অন্য অধ্যায়ে নিন", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        IconButton(onClick = {
                                            scope.launch { repository.deleteNote(note.id) }
                                        }) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন", modifier = Modifier.size(20.dp), tint = MaterialTheme.colorScheme.error)
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

    if (showAddNote || editingNote != null) {
        var content by remember(editingNote) { mutableStateOf(editingNote?.content.orEmpty()) }
        var starred by remember(editingNote) { mutableStateOf(editingNote?.starred ?: false) }
        AlertDialog(
            onDismissRequest = { showAddNote = false; editingNote = null },
            title = { Text(if (editingNote == null) "নতুন নোট যোগ করুন" else "নোট সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = { Text("নোটের বিষয়বস্তু (বাংলা বা ইংরেজি)") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth().testTag("note_content_input")
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = starred, onCheckedChange = { starred = it })
                        Text("গুরুত্বপূর্ণ (Starred)")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val existing = editingNote
                        scope.launch {
                            if (existing == null) repository.addNote(book.id, chapter.id, content, starred)
                            else repository.updateNote(existing.copy(content = content, starred = starred))
                        }
                        showAddNote = false
                        editingNote = null
                    },
                    enabled = content.isNotBlank()
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { showAddNote = false; editingNote = null }) { Text("বাতিল") }
            }
        )
    }

    if (movingNote != null) {
        val noteToMove = movingNote!!
        AlertDialog(
            onDismissRequest = { movingNote = null },
            title = { Text("কোন অধ্যায়ে সরাবেন?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    allChaptersInBook.filter { it.id != chapter.id }.forEach { target ->
                        FilledTonalButton(
                            onClick = {
                                scope.launch { repository.moveNoteToChapter(noteToMove, target) }
                                movingNote = null
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp)
                        ) { Text(target.title) }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { movingNote = null }) { Text("বন্ধ করুন") }
            }
        )
    }

    if (scheduleNote != null) {
        val note = scheduleNote!!
        var overrideEnabled by remember(note.id) { mutableStateOf(note.overrideEnabled) }
        var overrideInterval by remember(note.id) { mutableStateOf(note.overrideIntervalMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
        AlertDialog(
            onDismissRequest = { scheduleNote = null },
            title = { Text("নোটের নিজস্ব সময়সূচি") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = overrideEnabled == -1, onClick = { overrideEnabled = -1 }, label = { Text("অধ্যায়ের মতো") })
                        FilterChip(selected = overrideEnabled == 1, onClick = { overrideEnabled = 1 }, label = { Text("চালু") })
                        FilterChip(selected = overrideEnabled == 0, onClick = { overrideEnabled = 0 }, label = { Text("বন্ধ") })
                    }
                    OutlinedTextField(
                        value = overrideInterval,
                        onValueChange = { overrideInterval = it.filter { c -> c.isDigit() } },
                        label = { Text("আলাদা ইন্টারভ্যাল (মিনিট)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mins = (overrideInterval.toIntOrNull() ?: 0).coerceIn(0, 1440)
                    scope.launch { repository.updateNote(note.copy(overrideEnabled = overrideEnabled, overrideIntervalMinutes = mins)) }
                    scheduleNote = null
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { scheduleNote = null }) { Text("বাতিল") }
            }
        )
    }
}
