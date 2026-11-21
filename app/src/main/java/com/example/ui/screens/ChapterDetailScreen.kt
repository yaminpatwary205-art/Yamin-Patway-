package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.DriveFileMove
import androidx.compose.material.icons.automirrored.rounded.NoteAdd
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.alarm.AlarmScheduler
import com.example.data.RevisionRepository
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.NoteEntity
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.DrawingDiagramDialog
import com.example.ui.components.GlassCard
import com.example.ui.components.NoteAttachmentImagePreview
import com.example.ui.components.NoteEditorBlock
import com.example.ui.components.NoteImageViewerDialog
import com.example.ui.components.RichNoteCodec
import com.example.ui.components.RichNoteContentView
import com.example.ui.components.RichNoteSegment
import com.example.ui.components.adaptiveAccent
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
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val accent = parseColorHex(book.colorHex)
    val displayAccent = adaptiveAccent(accent)

    val sortedNotes = remember(notes) {
        notes.sortedWith(compareBy<NoteEntity>({ it.sortOrder }, { it.updatedAtEpochMs }, { it.id }))
    }

    var showAddNote by remember { mutableStateOf(false) }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var movingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var scheduleNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showChapterSchedule by remember { mutableStateOf(false) }
    var bulkMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewingImage by remember { mutableStateOf<Pair<RichNoteSegment.Image, String>?>(null) }

    val chapterEnabled = if (chapter.overrideEnabled != -1) chapter.overrideEnabled == 1 else book.enabled
    val chapterInterval = if (chapter.overrideIntervalMinutes > 0) chapter.overrideIntervalMinutes else book.intervalMinutes

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
                        IconButton(
                            onClick = { showChapterSchedule = true },
                            modifier = Modifier.testTag("chapter_schedule_button")
                        ) {
                            Icon(Icons.Rounded.Timer, contentDescription = "অধ্যায়ের সময়সূচি")
                        }
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
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        contentColor = MaterialTheme.colorScheme.onSurface,
                        tonalElevation = 6.dp,
                        modifier = Modifier.fillMaxWidth().navigationBarsPadding()
                    ) {
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
                item {
                    GlassCard(accentColor = accent, modifier = Modifier.fillMaxWidth()) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            Icon(
                                if (chapterEnabled) Icons.Rounded.NotificationsActive else Icons.Rounded.NotificationsOff,
                                contentDescription = null,
                                tint = if (chapterEnabled) displayAccent else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = if (chapterEnabled) "অধ্যায়ের অ্যালার্ম: প্রতি $chapterInterval মি. পরপর" else "অধ্যায়ের অ্যালার্ম বন্ধ আছে",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "মোট ${sortedNotes.size}টি নোট • লেখার মাঝে ছবি বা আঁকা চিত্র যোগ করুন",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            FilledTonalButton(
                                onClick = { showChapterSchedule = true },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp).testTag("chapter_timer_quick_button")
                            ) {
                                Text("সময় সেট", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }

                if (sortedNotes.isEmpty()) {
                    item {
                        GlassCard(modifier = Modifier.fillMaxWidth()) {
                            Column(
                                modifier = Modifier.padding(24.dp).fillMaxWidth(),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(Icons.AutoMirrored.Rounded.NoteAdd, contentDescription = null, modifier = Modifier.size(40.dp), tint = displayAccent)
                                Text("এই অধ্যায়ে এখনো কোনো নোট নেই", style = MaterialTheme.typography.titleMedium)
                                Text(
                                    "নোট লিখুন এবং প্রয়োজনীয় ছবি বা ডায়াগ্রাম লেখার মাঝখানে বা যেকোনো স্থানে যোগ করুন।",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                } else {
                    itemsIndexed(sortedNotes, key = { _, note -> note.id }) { index, note ->
                        val isSelected = note.id in selectedIds
                        val effectiveSched = AlarmScheduler.resolveNoteSchedule(book, chapter, note)
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
                                    } else {
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = displayAccent.copy(alpha = 0.16f),
                                            contentColor = displayAccent,
                                            modifier = Modifier
                                                .clickable { scheduleNote = note }
                                                .testTag("note_order_badge_${note.id}")
                                        ) {
                                            Text(
                                                text = "#${index + 1}",
                                                style = MaterialTheme.typography.labelMedium,
                                                fontWeight = FontWeight.Bold,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                    RichNoteContentView(
                                        content = note.content,
                                        legacyImagePath = note.imagePath,
                                        accentColor = accent,
                                        imageHeightDp = 160,
                                        onImageClick = { seg ->
                                            viewingImage = seg to RichNoteCodec.stripImageMarkers(note.content)
                                        },
                                        modifier = Modifier.weight(1f)
                                    )
                                    IconButton(onClick = {
                                        scope.launch { repository.updateNote(note.copy(starred = !note.starred)) }
                                    }) {
                                        Icon(
                                            if (note.starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                                            contentDescription = "তারকা",
                                            tint = if (note.starred) adaptiveAccent(Color(0xFFF59E0B)) else MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                }

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    val isNotePaused = !effectiveSched.enabled
                                    val statusText = buildString {
                                        append("পড়া: ${note.revisionCount}")
                                        if (isNotePaused) append(" • বন্ধ")
                                        else if (note.overrideIntervalMinutes > 0) append(" • নিজস্ব ${note.overrideIntervalMinutes} মি.")
                                        else append(" • ${effectiveSched.intervalMinutes} মি.")
                                    }
                                    TextButton(
                                        onClick = {
                                            val nextOverride = if (note.overrideEnabled == 0) 1 else 0
                                            scope.launch { repository.updateNote(note.copy(overrideEnabled = nextOverride)) }
                                        },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 2.dp),
                                        modifier = Modifier.weight(1f, fill = false).height(34.dp).testTag("note_toggle_alarm_${note.id}")
                                    ) {
                                        Icon(
                                            if (isNotePaused) Icons.Rounded.NotificationsOff else Icons.Rounded.NotificationsActive,
                                            contentDescription = "অ্যালার্ম চালু/বন্ধ",
                                            modifier = Modifier.size(15.dp),
                                            tint = if (isNotePaused) MaterialTheme.colorScheme.onSurfaceVariant else displayAccent
                                        )
                                        Spacer(Modifier.width(4.dp))
                                        Text(
                                            text = statusText,
                                            style = MaterialTheme.typography.labelSmall,
                                            maxLines = 1,
                                            color = if (isNotePaused) MaterialTheme.colorScheme.onSurfaceVariant else displayAccent
                                        )
                                    }

                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.horizontalScroll(rememberScrollState())
                                    ) {
                                        if (index > 0) {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        repository.swapNoteOrder(chapter.id, note.id, sortedNotes[index - 1].id)
                                                    }
                                                },
                                                modifier = Modifier.size(34.dp).testTag("note_move_up_${note.id}")
                                            ) {
                                                Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "আগে নিন", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        if (index < sortedNotes.lastIndex) {
                                            IconButton(
                                                onClick = {
                                                    scope.launch {
                                                        repository.swapNoteOrder(chapter.id, note.id, sortedNotes[index + 1].id)
                                                    }
                                                },
                                                modifier = Modifier.size(34.dp).testTag("note_move_down_${note.id}")
                                            ) {
                                                Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "পরে নিন", modifier = Modifier.size(20.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = { scheduleNote = note },
                                            modifier = Modifier.size(34.dp).testTag("note_schedule_button_${note.id}")
                                        ) {
                                            Icon(Icons.Rounded.Timer, contentDescription = "নোট ক্রম ও সময়", modifier = Modifier.size(19.dp))
                                        }
                                        IconButton(
                                            onClick = { scope.launch { repository.duplicateNote(note) } },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Rounded.ContentCopy, contentDescription = "ডুপ্লিকেট", modifier = Modifier.size(18.dp))
                                        }
                                        if (allChaptersInBook.size > 1) {
                                            IconButton(
                                                onClick = { movingNote = note },
                                                modifier = Modifier.size(34.dp)
                                            ) {
                                                Icon(Icons.AutoMirrored.Rounded.DriveFileMove, contentDescription = "অন্য অধ্যায়ে নিন", modifier = Modifier.size(19.dp))
                                            }
                                        }
                                        IconButton(
                                            onClick = { scope.launch { repository.deleteNote(note.id) } },
                                            modifier = Modifier.size(34.dp)
                                        ) {
                                            Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন", modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.error)
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
        val editing = editingNote
        val blocks = remember(editing?.id, showAddNote) {
            mutableStateListOf<NoteEditorBlock>().apply {
                addAll(RichNoteCodec.toEditorBlocks(editing?.content.orEmpty(), editing?.imagePath.orEmpty()))
            }
        }
        var activeTextBlockId by remember(editing?.id, showAddNote) {
            mutableStateOf(blocks.firstOrNull { it is NoteEditorBlock.TextBlock }?.id)
        }
        var starred by remember(editing?.id, showAddNote) { mutableStateOf(editing?.starred ?: false) }
        var multipleMode by remember(editing?.id, showAddNote) { mutableStateOf(false) }
        var showDrawingDialog by remember(editing?.id, showAddNote) { mutableStateOf(false) }
        val newlyAddedRefs = remember(editing?.id, showAddNote) { mutableStateListOf<String>() }

        val dialogImagePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val savedRef = repository.savePickedNoteImage(uri)
                    if (!savedRef.isNullOrBlank()) {
                        val (updated, afterId) = RichNoteCodec.insertImageAtCursor(blocks.toList(), activeTextBlockId, savedRef)
                        blocks.clear()
                        blocks.addAll(updated)
                        activeTextBlockId = afterId
                        newlyAddedRefs.add(savedRef)
                        multipleMode = false
                    }
                }
            }
        }

        val hasImages = blocks.any { it is NoteEditorBlock.ImageBlock }
        val hasContent = blocks.any {
            (it is NoteEditorBlock.TextBlock && it.value.text.isNotBlank()) ||
                (it is NoteEditorBlock.ImageBlock && it.imageRef.isNotBlank())
        }
        val firstTextBlockId = blocks.firstOrNull { it is NoteEditorBlock.TextBlock }?.id

        fun dismissDialogCleanup() {
            newlyAddedRefs.forEach { ref -> repository.deleteUnusedLocalImage(ref, sortedNotes) }
            newlyAddedRefs.clear()
            showAddNote = false
            editingNote = null
        }

        AlertDialog(
            onDismissRequest = { dismissDialogCleanup() },
            title = { Text(if (editing == null) "নতুন নোট লিখুন" else "নোট সম্পাদনা") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    // Inline Picture Insertion Toolbar: Gallery or Finger Drawing
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        FilledTonalButton(
                            onClick = {
                                dialogImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).testTag("insert_gallery_image_button")
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("গ্যালারি ছবি", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                        OutlinedButton(
                            onClick = { showDrawingDialog = true },
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            modifier = Modifier.weight(1f).testTag("open_draw_diagram_button")
                        ) {
                            Icon(Icons.Rounded.Draw, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("চিত্র আঁকুন", maxLines = 1, style = MaterialTheme.typography.labelMedium)
                        }
                    }

                    val totalWords = blocks
                        .filterIsInstance<NoteEditorBlock.TextBlock>()
                        .sumOf { RichNoteCodec.countWords(it.value.text) }
                    Text(
                        text = "কার্সর যেখানে রাখবেন ঠিক সেখানেই (শুরুতে, লেখার মাঝখানে বা শেষে) ছবি ও আঁকা চিত্র বসবে।",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "শব্দ সংখ্যা: $totalWords / ১০,০০০ শব্দ",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.SemiBold,
                        color = if (totalWords > RichNoteCodec.MAX_NOTE_WORDS) {
                            MaterialTheme.colorScheme.error
                        } else {
                            displayAccent
                        },
                        modifier = Modifier.testTag("note_word_count_label")
                    )

                    blocks.forEachIndexed { idx, block ->
                        when (block) {
                            is NoteEditorBlock.TextBlock -> {
                                val isFirst = block.id == firstTextBlockId
                                val fieldTag = if (isFirst) "note_content_input" else "note_content_input_${block.id}"
                                OutlinedTextField(
                                    value = block.value,
                                    onValueChange = { newValue ->
                                        activeTextBlockId = block.id
                                        val pos = blocks.indexOfFirst { it.id == block.id }
                                        if (pos >= 0) {
                                            blocks[pos] = block.copy(value = newValue)
                                        }
                                    },
                                    label = {
                                        Text(
                                            when {
                                                multipleMode && !hasImages && isFirst -> "প্রতি লাইনে একটি করে একাধিক নোট লিখুন"
                                                hasImages && !isFirst -> "ছবির পরের লেখা (ঐচ্ছিক)"
                                                else -> "নোটের বিষয়বস্তু (বাংলা বা ইংরেজি)"
                                            }
                                        )
                                    },
                                    minLines = if (multipleMode && !hasImages && isFirst) 5 else 2,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .onFocusChanged { if (it.isFocused) activeTextBlockId = block.id }
                                        .testTag(fieldTag)
                                )
                            }

                            is NoteEditorBlock.ImageBlock -> {
                                val imgFile = RichNoteCodec.resolveImageFile(context.filesDir, block.imageRef)
                                Surface(
                                    shape = RoundedCornerShape(14.dp),
                                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                                    modifier = Modifier.fillMaxWidth().testTag("editor_image_block_${block.id}")
                                ) {
                                    Column(
                                        modifier = Modifier.padding(8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        NoteAttachmentImagePreview(
                                            imageFile = imgFile,
                                            contentDescription = "নোটের চিত্র",
                                            accentColor = accent,
                                            heightDp = 148,
                                            onClick = {
                                                viewingImage = RichNoteSegment.Image(block.imageRef, imgFile) to "নোটের চিত্র"
                                            }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                                OutlinedButton(
                                                    onClick = {
                                                        val pos = blocks.indexOfFirst { it.id == block.id }
                                                        if (pos > 0) {
                                                            val item = blocks.removeAt(pos)
                                                            blocks.add(pos - 1, item)
                                                        }
                                                    },
                                                    enabled = idx > 0,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "উপরে নিন", modifier = Modifier.size(16.dp))
                                                    Text("আগে", style = MaterialTheme.typography.labelSmall)
                                                }
                                                OutlinedButton(
                                                    onClick = {
                                                        val pos = blocks.indexOfFirst { it.id == block.id }
                                                        if (pos >= 0 && pos < blocks.lastIndex) {
                                                            val item = blocks.removeAt(pos)
                                                            blocks.add(pos + 1, item)
                                                        }
                                                    },
                                                    enabled = idx < blocks.lastIndex,
                                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                    modifier = Modifier.height(32.dp)
                                                ) {
                                                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "নিচে নিন", modifier = Modifier.size(16.dp))
                                                    Text("পরে", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            TextButton(
                                                onClick = {
                                                    val pos = blocks.indexOfFirst { it.id == block.id }
                                                    if (pos >= 0) {
                                                        blocks.removeAt(pos)
                                                        if (block.imageRef in newlyAddedRefs) {
                                                            repository.deleteUnusedLocalImage(block.imageRef, sortedNotes)
                                                            newlyAddedRefs.remove(block.imageRef)
                                                        }
                                                        if (blocks.none { it is NoteEditorBlock.TextBlock }) {
                                                            val fresh = NoteEditorBlock.TextBlock(RichNoteCodec.newBlockId(), androidx.compose.ui.text.input.TextFieldValue(""))
                                                            blocks.add(fresh)
                                                            activeTextBlockId = fresh.id
                                                        }
                                                    }
                                                },
                                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                modifier = Modifier.height(32.dp).testTag("remove_editor_image_${block.id}")
                                            ) {
                                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("সরান", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    if (editing == null && !hasImages) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = multipleMode,
                                onCheckedChange = { multipleMode = it },
                                modifier = Modifier.testTag("multiple_notes_checkbox")
                            )
                            Text("একাধিক নোট একসাথে যোগ করুন (প্রতি লাইনে ১টি নোট)")
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = starred, onCheckedChange = { starred = it })
                        Text("গুরুত্বপূর্ণ (Starred)")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val serialized = RichNoteCodec.serializeBlocks(blocks.toList())
                        scope.launch {
                            if (editing == null) {
                                if (multipleMode && !hasImages) {
                                    repository.addMultipleNotes(book.id, chapter.id, serialized, starred)
                                } else {
                                    repository.addNote(
                                        bookId = book.id,
                                        chapterId = chapter.id,
                                        content = serialized,
                                        starred = starred,
                                        imagePath = ""
                                    )
                                }
                            } else {
                                repository.updateNote(
                                    editing.copy(
                                        content = serialized,
                                        imagePath = "",
                                        starred = starred
                                    )
                                )
                            }
                        }
                        newlyAddedRefs.clear()
                        showAddNote = false
                        editingNote = null
                    },
                    enabled = hasContent,
                    modifier = Modifier.testTag("save_note_button")
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { dismissDialogCleanup() }) { Text("বাতিল") }
            }
        )

        if (showDrawingDialog) {
            DrawingDiagramDialog(
                onSaveBitmap = { bitmap ->
                    showDrawingDialog = false
                    scope.launch {
                        val savedRef = repository.saveDrawnDiagramBitmap(bitmap)
                        if (!savedRef.isNullOrBlank()) {
                            val (updated, afterId) = RichNoteCodec.insertImageAtCursor(blocks.toList(), activeTextBlockId, savedRef)
                            blocks.clear()
                            blocks.addAll(updated)
                            activeTextBlockId = afterId
                            newlyAddedRefs.add(savedRef)
                            multipleMode = false
                        }
                    }
                },
                onDismiss = { showDrawingDialog = false }
            )
        }
    }

    if (viewingImage != null) {
        NoteImageViewerDialog(
            imageFile = viewingImage!!.first.file,
            title = viewingImage!!.second,
            onDismiss = { viewingImage = null }
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

    if (showChapterSchedule) {
        var overrideEnabled by remember(chapter.id) { mutableStateOf(chapter.overrideEnabled) }
        var overrideInterval by remember(chapter.id) {
            mutableStateOf(chapter.overrideIntervalMinutes.takeIf { it > 0 }?.toString().orEmpty())
        }
        AlertDialog(
            onDismissRequest = { showChapterSchedule = false },
            title = { Text("অধ্যায়ের অ্যালার্ম: ${chapter.title}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("বইয়ের মতো রাখবেন নাকি এই অধ্যায়ের সব নোট আলাদা মিনিট পরপর আসবে?", style = MaterialTheme.typography.bodySmall)
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
                        label = { Text("কত মিনিট পরপর (খালি = বইয়ের মতো)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("chapter_interval_input")
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val mins = (overrideInterval.toIntOrNull() ?: 0).coerceIn(0, 1440)
                    scope.launch {
                        repository.updateChapter(chapter.copy(overrideEnabled = overrideEnabled, overrideIntervalMinutes = mins))
                    }
                    showChapterSchedule = false
                }) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { showChapterSchedule = false }) { Text("বাতিল") }
            }
        )
    }

    if (scheduleNote != null) {
        val note = scheduleNote!!
        val currentIdx = sortedNotes.indexOfFirst { it.id == note.id }.coerceAtLeast(0)
        var overrideEnabled by remember(note.id) { mutableStateOf(note.overrideEnabled) }
        var overrideInterval by remember(note.id) { mutableStateOf(note.overrideIntervalMinutes.takeIf { it > 0 }?.toString().orEmpty()) }
        var positionText by remember(note.id) { mutableStateOf((currentIdx + 1).toString()) }

        AlertDialog(
            onDismissRequest = { scheduleNote = null },
            title = { Text("নোটের ক্রম ও সময়সূচি") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "ক্রম (আগে-পরে): মোট ${sortedNotes.size}টির মধ্যে কত নম্বরে আসবে?",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = positionText,
                            onValueChange = { positionText = it.filter { c -> c.isDigit() } },
                            label = { Text("ক্রম (১-${sortedNotes.size.coerceAtLeast(1)})") },
                            singleLine = true,
                            modifier = Modifier.weight(1f).testTag("note_position_input")
                        )
                        FilledTonalButton(
                            onClick = { positionText = "1" },
                            modifier = Modifier.testTag("note_move_first_button")
                        ) { Text("সবার আগে (#১)") }
                    }

                    HorizontalDivider()

                    Text("অ্যালার্ম অবস্থা ও মিনিট:", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        FilterChip(selected = overrideEnabled == -1, onClick = { overrideEnabled = -1 }, label = { Text("অধ্যায়ের মতো") })
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
                                label = { Text("$mins মি.") },
                                modifier = Modifier.testTag("note_interval_chip_$mins")
                            )
                        }
                    }
                    OutlinedTextField(
                        value = overrideInterval,
                        onValueChange = { overrideInterval = it.filter { c -> c.isDigit() } },
                        label = { Text("আলাদা ইন্টারভ্যাল (মিনিট, খালি = অধ্যায়ের মতো)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth().testTag("note_interval_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val mins = (overrideInterval.toIntOrNull() ?: 0).coerceIn(0, 1440)
                        val targetPos = ((positionText.toIntOrNull() ?: (currentIdx + 1)) - 1)
                            .coerceIn(0, (sortedNotes.size - 1).coerceAtLeast(0))
                        scope.launch {
                            repository.updateNote(note.copy(overrideEnabled = overrideEnabled, overrideIntervalMinutes = mins))
                            if (targetPos != currentIdx) {
                                repository.moveNoteToPosition(chapter.id, note.id, targetPos)
                            }
                        }
                        scheduleNote = null
                    },
                    modifier = Modifier.testTag("save_note_schedule_button")
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { scheduleNote = null }) { Text("বাতিল") }
            }
        )
    }
}
