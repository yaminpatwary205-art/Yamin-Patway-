package com.example.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.example.alarm.AlarmScheduler
import com.example.data.RevisionRepository
import com.example.data.local.BookEntity
import com.example.data.local.ChapterEntity
import com.example.data.local.NoteEntity
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.FloatingDraggableNoteImageOverlay
import com.example.ui.components.GlassCard
import com.example.ui.components.NoteAttachmentImagePreview
import com.example.ui.components.NoteImageViewerDialog
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
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val accent = parseColorHex(book.colorHex)
    val displayAccent = adaptiveAccent(accent)

    val sortedNotes = remember(notes) {
        notes.sortedWith(compareBy<NoteEntity>({ it.sortOrder }, { it.updatedAtEpochMs }, { it.id }))
    }

    var draggingNoteId by remember { mutableStateOf<String?>(null) }
    var dragOffsetY by remember { mutableFloatStateOf(0f) }
    var displayNotes by remember { mutableStateOf(sortedNotes) }
    var optimisticOrderIds by remember { mutableStateOf<List<String>?>(null) }
    val itemHeights = remember { mutableStateMapOf<String, Int>() }

    LaunchedEffect(sortedNotes) {
        val pendingIds = optimisticOrderIds
        if (pendingIds != null && sortedNotes.map { it.id } == pendingIds) {
            optimisticOrderIds = null
        }
    }

    val effectiveNotes = remember(sortedNotes, draggingNoteId, displayNotes, optimisticOrderIds) {
        when {
            draggingNoteId != null -> displayNotes
            optimisticOrderIds != null -> {
                val rank = optimisticOrderIds!!.withIndex().associate { it.value to it.index }
                sortedNotes.sortedBy { rank[it.id] ?: Int.MAX_VALUE }
            }
            else -> sortedNotes
        }
    }

    var showAddNote by remember { mutableStateOf(false) }
    var prefilledImagePath by remember { mutableStateOf("") }
    var editingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var movingNote by remember { mutableStateOf<NoteEntity?>(null) }
    var scheduleNote by remember { mutableStateOf<NoteEntity?>(null) }
    var showChapterSchedule by remember { mutableStateOf(false) }
    var bulkMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var viewingImageNote by remember { mutableStateOf<NoteEntity?>(null) }
    var floatingImageNoteId by remember { mutableStateOf<String?>(null) }

    val quickImagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            scope.launch {
                val savedPath = repository.savePickedNoteImage(uri)
                if (!savedPath.isNullOrBlank()) {
                    prefilledImagePath = savedPath
                    editingNote = null
                    showAddNote = true
                }
            }
        }
    }

    val floatingImageNote = remember(sortedNotes, floatingImageNoteId) {
        floatingImageNoteId?.let { id ->
            sortedNotes.firstOrNull { it.id == id && it.imagePath.isNotBlank() }
        }
    }

    val chapterEnabled = if (chapter.overrideEnabled != -1) chapter.overrideEnabled == 1 else book.enabled
    val chapterInterval = if (chapter.overrideIntervalMinutes > 0) chapter.overrideIntervalMinutes else book.intervalMinutes

    AnimatedMeshBackground {
        Box(modifier = Modifier.fillMaxSize()) {
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
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            SmallFloatingActionButton(
                                onClick = {
                                    quickImagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                                contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                modifier = Modifier.testTag("add_image_note_fab")
                            ) {
                                Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = "অধ্যায়ের ছবি যোগ করুন")
                            }
                            ExtendedFloatingActionButton(
                                onClick = {
                                    prefilledImagePath = ""
                                    showAddNote = true
                                },
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                text = { Text("নতুন নোট") },
                                modifier = Modifier.testTag("add_note_fab")
                            )
                        }
                    }
                }
            ) { innerPadding ->
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(innerPadding).padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(bottom = 104.dp)
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
                                        text = "মোট ${sortedNotes.size}টি নোট • টেনে (#) ক্রম বদলান বা ছবি যেকোনো স্থানে রাখুন",
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

                    if (effectiveNotes.isEmpty()) {
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
                                        "নোট বা গুরুত্বপূর্ণ ছবি যোগ করলে #১, #২... ক্রমে এবং টেনে যেকোনো স্থানে সাজিয়ে রাখা যাবে।",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    } else {
                        itemsIndexed(effectiveNotes, key = { _, note -> note.id }) { index, note ->
                            val isSelected = note.id in selectedIds
                            val isDragging = draggingNoteId == note.id
                            val isFloating = floatingImageNoteId == note.id
                            val effectiveSched = AlarmScheduler.resolveNoteSchedule(book, chapter, note)
                            val spacingPx = with(density) { 10.dp.toPx() }

                            GlassCard(
                                accentColor = accent,
                                onClick = {
                                    if (bulkMode) {
                                        selectedIds = if (isSelected) selectedIds - note.id else selectedIds + note.id
                                    } else {
                                        editingNote = note
                                    }
                                },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .zIndex(if (isDragging) 12f else 0f)
                                    .onSizeChanged { itemHeights[note.id] = it.height }
                                    .graphicsLayer {
                                        if (isDragging) {
                                            translationY = dragOffsetY
                                            scaleX = 1.02f
                                            scaleY = 1.02f
                                            shadowElevation = 18f
                                        }
                                    }
                                    .testTag("note_card_${note.id}")
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
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Box(
                                                    contentAlignment = Alignment.Center,
                                                    modifier = Modifier
                                                        .size(36.dp)
                                                        .pointerInput(note.id) {
                                                            detectDragGestures(
                                                                onDragStart = {
                                                                    displayNotes = effectiveNotes
                                                                    draggingNoteId = note.id
                                                                    dragOffsetY = 0f
                                                                },
                                                                onDragCancel = {
                                                                    draggingNoteId = null
                                                                    dragOffsetY = 0f
                                                                    displayNotes = sortedNotes
                                                                },
                                                                onDragEnd = {
                                                                    val finalList = displayNotes
                                                                    val finalIdx = finalList.indexOfFirst { it.id == note.id }
                                                                    optimisticOrderIds = finalList.map { it.id }
                                                                    draggingNoteId = null
                                                                    dragOffsetY = 0f
                                                                    if (finalIdx >= 0) {
                                                                        scope.launch {
                                                                            repository.moveNoteToPosition(chapter.id, note.id, finalIdx)
                                                                        }
                                                                    }
                                                                },
                                                                onDrag = { change, dragAmount ->
                                                                    change.consume()
                                                                    val currentIdx = displayNotes.indexOfFirst { it.id == note.id }
                                                                    if (currentIdx != -1) {
                                                                        dragOffsetY += dragAmount.y
                                                                        if (dragOffsetY > 0f && currentIdx < displayNotes.lastIndex) {
                                                                            val nextNote = displayNotes[currentIdx + 1]
                                                                            val nextHeight = (itemHeights[nextNote.id] ?: 160).toFloat()
                                                                            if (dragOffsetY > nextHeight / 2f + spacingPx) {
                                                                                val mutable = displayNotes.toMutableList()
                                                                                mutable[currentIdx] = nextNote
                                                                                mutable[currentIdx + 1] = note
                                                                                displayNotes = mutable
                                                                                dragOffsetY -= (nextHeight + spacingPx)
                                                                            }
                                                                        } else if (dragOffsetY < 0f && currentIdx > 0) {
                                                                            val prevNote = displayNotes[currentIdx - 1]
                                                                            val prevHeight = (itemHeights[prevNote.id] ?: 160).toFloat()
                                                                            if (-dragOffsetY > prevHeight / 2f + spacingPx) {
                                                                                val mutable = displayNotes.toMutableList()
                                                                                mutable[currentIdx] = prevNote
                                                                                mutable[currentIdx - 1] = note
                                                                                displayNotes = mutable
                                                                                dragOffsetY += (prevHeight + spacingPx)
                                                                            }
                                                                        }
                                                                    }
                                                                }
                                                            )
                                                        }
                                                        .semantics(mergeDescendants = true) {}
                                                        .testTag("note_drag_handle_${note.id}")
                                                ) {
                                                    Icon(
                                                        imageVector = Icons.Rounded.DragIndicator,
                                                        contentDescription = "টেনে যেকোনো স্থানে সাজান",
                                                        tint = if (isDragging) displayAccent else MaterialTheme.colorScheme.onSurfaceVariant,
                                                        modifier = Modifier.size(22.dp)
                                                    )
                                                }
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
                                                tint = if (note.starred) adaptiveAccent(Color(0xFFF59E0B)) else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    if (note.imagePath.isNotBlank()) {
                                        NoteAttachmentImagePreview(
                                            imagePath = note.imagePath,
                                            contentDescription = "নোটের সংযুক্ত ছবি",
                                            accentColor = accent,
                                            heightDp = 172,
                                            modifier = Modifier.testTag("note_image_preview_${note.id}"),
                                            onClick = { viewingImageNote = note }
                                        )
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            FilledTonalButton(
                                                onClick = {
                                                    floatingImageNoteId = if (isFloating) null else note.id
                                                },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .height(34.dp)
                                                    .testTag("pin_drag_image_${note.id}"),
                                                colors = if (isFloating) {
                                                    ButtonDefaults.filledTonalButtonColors(
                                                        containerColor = displayAccent.copy(alpha = 0.22f),
                                                        contentColor = displayAccent
                                                    )
                                                } else {
                                                    ButtonDefaults.filledTonalButtonColors()
                                                }
                                            ) {
                                                Icon(Icons.Rounded.OpenWith, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text(
                                                    if (isFloating) "ভাসমান চলছে" else "ভাসমান রাখুন",
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1
                                                )
                                            }
                                            OutlinedButton(
                                                onClick = { viewingImageNote = note },
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                                modifier = Modifier.height(34.dp).testTag("zoom_note_image_${note.id}")
                                            ) {
                                                Icon(Icons.Rounded.ZoomIn, contentDescription = null, modifier = Modifier.size(15.dp))
                                                Spacer(Modifier.width(4.dp))
                                                Text("বড় করে দেখুন", style = MaterialTheme.typography.labelSmall, maxLines = 1)
                                            }
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
                                                            repository.swapNoteOrder(chapter.id, note.id, effectiveNotes[index - 1].id)
                                                        }
                                                    },
                                                    modifier = Modifier.size(34.dp).testTag("note_move_up_${note.id}")
                                                ) {
                                                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "আগে নিন", modifier = Modifier.size(20.dp))
                                                }
                                            }
                                            if (index < effectiveNotes.lastIndex) {
                                                IconButton(
                                                    onClick = {
                                                        scope.launch {
                                                            repository.swapNoteOrder(chapter.id, note.id, effectiveNotes[index + 1].id)
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

            if (floatingImageNote != null) {
                FloatingDraggableNoteImageOverlay(
                    note = floatingImageNote,
                    accentColor = accent,
                    onOpenFullScreen = { viewingImageNote = floatingImageNote },
                    onClose = { floatingImageNoteId = null }
                )
            }
        }
    }

    if (showAddNote || editingNote != null) {
        val editing = editingNote
        var content by remember(editing, prefilledImagePath) { mutableStateOf(editing?.content.orEmpty()) }
        var starred by remember(editing) { mutableStateOf(editing?.starred ?: false) }
        var pickedImagePath by remember(editing, prefilledImagePath) {
            mutableStateOf(editing?.imagePath ?: prefilledImagePath)
        }
        var multipleMode by remember(editing) { mutableStateOf(false) }

        val dialogImagePicker = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.PickVisualMedia()
        ) { uri ->
            if (uri != null) {
                scope.launch {
                    val saved = repository.savePickedNoteImage(uri)
                    if (!saved.isNullOrBlank()) {
                        pickedImagePath = saved
                        multipleMode = false
                    }
                }
            }
        }

        AlertDialog(
            onDismissRequest = {
                if (editing == null && pickedImagePath.isNotBlank()) {
                    repository.deleteUnusedLocalImage(pickedImagePath, sortedNotes)
                }
                showAddNote = false
                editingNote = null
                prefilledImagePath = ""
            },
            title = { Text(if (editing == null) "নতুন নোট / চিত্র যোগ করুন" else "নোট সম্পাদনা") },
            text = {
                Column(
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    modifier = Modifier.verticalScroll(rememberScrollState())
                ) {
                    OutlinedTextField(
                        value = content,
                        onValueChange = { content = it },
                        label = {
                            Text(
                                if (multipleMode && editing == null)
                                    "প্রতি লাইনে একটি করে একাধিক নোট লিখুন"
                                else if (pickedImagePath.isNotBlank())
                                    "ছবির বিবরণ বা ক্যাপশন (ঐচ্ছিক)"
                                else
                                    "নোটের বিষয়বস্তু (বাংলা বা ইংরেজি)"
                            )
                        },
                        minLines = if (multipleMode) 5 else 3,
                        modifier = Modifier.fillMaxWidth().testTag("note_content_input")
                    )

                    if (pickedImagePath.isNotBlank()) {
                        NoteAttachmentImagePreview(
                            imagePath = pickedImagePath,
                            contentDescription = "নির্বাচিত ছবি",
                            accentColor = accent,
                            heightDp = 150
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            OutlinedButton(
                                onClick = {
                                    dialogImagePicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                modifier = Modifier.weight(1f).testTag("add_note_image_button")
                            ) {
                                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("বদলান", maxLines = 1)
                            }
                            TextButton(
                                onClick = {
                                    if (editing == null || editing.imagePath != pickedImagePath) {
                                        repository.deleteUnusedLocalImage(pickedImagePath, sortedNotes)
                                    }
                                    pickedImagePath = ""
                                },
                                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                modifier = Modifier.testTag("remove_note_image_button")
                            ) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("ছবি সরান")
                            }
                        }
                    } else {
                        FilledTonalButton(
                            onClick = {
                                dialogImagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            modifier = Modifier.fillMaxWidth().testTag("add_note_image_button")
                        ) {
                            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("গ্যালারি থেকে ছবি / চিত্র যোগ করুন")
                        }
                    }

                    if (editing == null && pickedImagePath.isBlank()) {
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
                        scope.launch {
                            if (editing == null) {
                                if (multipleMode && pickedImagePath.isBlank()) {
                                    repository.addMultipleNotes(book.id, chapter.id, content, starred)
                                } else {
                                    repository.addNote(
                                        bookId = book.id,
                                        chapterId = chapter.id,
                                        content = content,
                                        starred = starred,
                                        imagePath = pickedImagePath
                                    )
                                }
                            } else {
                                repository.updateNote(
                                    editing.copy(
                                        content = content,
                                        imagePath = pickedImagePath,
                                        starred = starred
                                    )
                                )
                            }
                        }
                        showAddNote = false
                        editingNote = null
                        prefilledImagePath = ""
                    },
                    enabled = content.isNotBlank() || pickedImagePath.isNotBlank(),
                    modifier = Modifier.testTag("save_note_button")
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        if (editing == null && pickedImagePath.isNotBlank()) {
                            repository.deleteUnusedLocalImage(pickedImagePath, sortedNotes)
                        }
                        showAddNote = false
                        editingNote = null
                        prefilledImagePath = ""
                    }
                ) { Text("বাতিল") }
            }
        )
    }

    if (viewingImageNote != null && viewingImageNote!!.imagePath.isNotBlank()) {
        NoteImageViewerDialog(
            imagePath = viewingImageNote!!.imagePath,
            title = viewingImageNote!!.content,
            onDismiss = { viewingImageNote = null }
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
