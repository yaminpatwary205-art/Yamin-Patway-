package com.example.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import com.example.data.local.VocabListEntity
import com.example.data.local.VocabWordEntity
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.GlassCard
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VocabListDetailScreen(
    vocabList: VocabListEntity,
    words: List<VocabWordEntity>,
    repository: RevisionRepository,
    onBack: () -> Unit,
    onPracticeNow: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var showBulkAdd by remember { mutableStateOf(false) }
    var editingWord by remember { mutableStateOf<VocabWordEntity?>(null) }
    var bulkMode by remember { mutableStateOf(false) }
    var selectedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val masteredCount = words.count { it.mastered }
    val weakCount = words.count { it.weakCount > 0 }

    AnimatedMeshBackground {
        Scaffold(
            containerColor = Color.Transparent,
            topBar = {
                TopAppBar(
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent),
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "ফিরে যান")
                        }
                    },
                    title = { Text(vocabList.title, maxLines = 1) },
                    actions = {
                        IconButton(onClick = onPracticeNow, enabled = words.any { it.enabled }) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = "অনুশীলন শুরু")
                        }
                        IconButton(onClick = { bulkMode = !bulkMode; selectedIds = emptySet() }) {
                            Icon(if (bulkMode) Icons.Rounded.Close else Icons.Rounded.Checklist, contentDescription = "একাধিক বাছাই")
                        }
                    }
                )
            },
            bottomBar = {
                if (bulkMode && selectedIds.isNotEmpty()) {
                    Surface(tonalElevation = 6.dp, modifier = Modifier.fillMaxWidth().navigationBarsPadding()) {
                        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("${selectedIds.size}টি শব্দ নির্বাচিত", fontWeight = FontWeight.Bold)
                            Button(
                                onClick = {
                                    scope.launch {
                                        repository.deleteVocabWords(selectedIds)
                                        bulkMode = false
                                        selectedIds = emptySet()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                            ) { Text("মুছে ফেলুন") }
                        }
                    }
                }
            },
            floatingActionButton = {
                if (!bulkMode) {
                    ExtendedFloatingActionButton(
                        onClick = { showBulkAdd = true },
                        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                        text = { Text("শব্দ যোগ করুন") },
                        modifier = Modifier.testTag("add_vocab_words_fab")
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
                    GlassCard(accentColor = Color(0xFF7C3AED), modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("মোট: ${words.size} • আয়ত্ত: $masteredCount • দুর্বল: $weakCount", fontWeight = FontWeight.SemiBold)
                                Switch(
                                    checked = vocabList.enabled,
                                    onCheckedChange = { en ->
                                        scope.launch { repository.updateVocabList(vocabList.copy(enabled = en)) }
                                    }
                                )
                            }
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                FilterChip(
                                    selected = vocabList.reverseMode,
                                    onClick = {
                                        scope.launch { repository.updateVocabList(vocabList.copy(reverseMode = !vocabList.reverseMode)) }
                                    },
                                    label = { Text("রিভার্স মোড (বাংলা দেখে ইংরেজি)") }
                                )
                                Text("প্রতি ${vocabList.intervalMinutes} মি.", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }
                }

                items(words, key = { it.id }) { word ->
                    val isSelected = word.id in selectedIds
                    GlassCard(
                        accentColor = Color(0xFF7C3AED),
                        onClick = {
                            if (bulkMode) {
                                selectedIds = if (isSelected) selectedIds - word.id else selectedIds + word.id
                            } else {
                                editingWord = word
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(14.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            if (bulkMode) {
                                Checkbox(
                                    checked = isSelected,
                                    onCheckedChange = { ch ->
                                        selectedIds = if (ch) selectedIds + word.id else selectedIds - word.id
                                    }
                                )
                            }
                            Column(modifier = Modifier.weight(1f)) {
                                Text(word.word, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                Text(
                                    text = word.meaningBn.ifBlank { "অর্থ পপ-আপে স্বয়ংক্রিয়ভাবে অনুবাদ হবে" },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            Switch(
                                checked = word.enabled,
                                onCheckedChange = { en ->
                                    scope.launch { repository.updateVocabWord(word.copy(enabled = en)) }
                                }
                            )
                            IconButton(onClick = {
                                scope.launch { repository.deleteVocabWords(setOf(word.id)) }
                            }) {
                                Icon(Icons.Rounded.DeleteOutline, contentDescription = "মুছুন", tint = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showBulkAdd) {
        var bulkInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showBulkAdd = false },
            title = { Text("একসাথে অনেক শব্দ যোগ করুন") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "প্রতি লাইনে একটি করে বা কমা (,) দিয়ে শব্দ লিখুন। চাইলে 'word - বাংলা অর্থ' ফরম্যাটেও দিতে পারেন।",
                        style = MaterialTheme.typography.bodySmall
                    )
                    OutlinedTextField(
                        value = bulkInput,
                        onValueChange = { bulkInput = it },
                        label = { Text("উদাহরণ: resilient - দৃঢ়চেতা\nserene, eloquent") },
                        minLines = 4,
                        modifier = Modifier.fillMaxWidth().testTag("bulk_vocab_input")
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { repository.addBulkVocabWords(vocabList.id, bulkInput) }
                        showBulkAdd = false
                    },
                    enabled = bulkInput.isNotBlank()
                ) { Text("যোগ করুন") }
            },
            dismissButton = {
                TextButton(onClick = { showBulkAdd = false }) { Text("বাতিল") }
            }
        )
    }

    if (editingWord != null) {
        val w = editingWord!!
        var wordText by remember(w.id) { mutableStateOf(w.word) }
        var meaningText by remember(w.id) { mutableStateOf(w.meaningBn) }
        AlertDialog(
            onDismissRequest = { editingWord = null },
            title = { Text("শব্দ সম্পাদনা") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(value = wordText, onValueChange = { wordText = it }, label = { Text("ইংরেজি শব্দ") }, singleLine = true)
                    OutlinedTextField(value = meaningText, onValueChange = { meaningText = it }, label = { Text("বাংলা অর্থ (ঐচ্ছিক)") }, singleLine = true)
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch { repository.updateVocabWord(w.copy(word = wordText, meaningBn = meaningText)) }
                        editingWord = null
                    },
                    enabled = wordText.isNotBlank()
                ) { Text("সংরক্ষণ") }
            },
            dismissButton = {
                TextButton(onClick = { editingWord = null }) { Text("বাতিল") }
            }
        )
    }
}
