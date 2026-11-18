package com.example.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.NoteEntity
import com.example.ui.ActivePopup
import com.example.ui.PopupTranslationState
import com.example.ui.RevisionViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun RevisionPopupOverlay(
    popup: ActivePopup,
    defaultSnoozeMinutes: Int,
    viewModel: RevisionViewModel,
    onToggleNoteStar: (NoteEntity) -> Unit
) {
    // Non-dismissible by accidental system back press
    BackHandler(enabled = true) {}
    KeepScreenOnEffect(enabled = true)

    val gradientColors = when (popup) {
        is ActivePopup.NotePopup -> {
            val accent = adaptiveAccent(parseColorHex(popup.book.colorHex))
            listOf(accent.copy(alpha = 0.18f), MaterialTheme.colorScheme.background)
        }
        is ActivePopup.ParagraphPopup -> listOf(adaptiveAccent(Color(0xFF2563EB)).copy(alpha = 0.18f), MaterialTheme.colorScheme.background)
        is ActivePopup.VocabPopup -> listOf(adaptiveAccent(Color(0xFF7C3AED)).copy(alpha = 0.18f), MaterialTheme.colorScheme.background)
    }

    CompositionLocalProvider(LocalContentColor provides MaterialTheme.colorScheme.onBackground) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
                .background(Brush.verticalGradient(gradientColors))
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .padding(16.dp)
                .testTag("revision_popup_overlay")
        ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 600.dp)
                .align(Alignment.Center)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Top Alarm Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primaryContainer),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.AlarmOn,
                            contentDescription = "রিভিশন অ্যালার্ম",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    Column {
                        Text(
                            text = popup.alarmTitle?.takeIf { it.isNotBlank() } ?: "রিভিশন অ্যালার্ম",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "মনোযোগ দিয়ে রিভিশন সম্পন্ন করুন",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                IconButton(
                    onClick = { viewModel.muteAlarmSoundNow() },
                    modifier = Modifier.testTag("mute_alarm_button")
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Rounded.VolumeOff,
                        contentDescription = "শব্দ বন্ধ করুন"
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (popup) {
                is ActivePopup.NotePopup -> NotePopupContent(
                    popup = popup,
                    defaultSnoozeMinutes = defaultSnoozeMinutes,
                    viewModel = viewModel,
                    onToggleStar = { onToggleNoteStar(popup.note) }
                )
                is ActivePopup.ParagraphPopup -> ParagraphPopupContent(
                    popup = popup,
                    defaultSnoozeMinutes = defaultSnoozeMinutes,
                    viewModel = viewModel
                )
                is ActivePopup.VocabPopup -> VocabPopupContent(
                    popup = popup,
                    defaultSnoozeMinutes = defaultSnoozeMinutes,
                    viewModel = viewModel
                )
            }
        }
        }
    }
}

@Composable
private fun NotePopupContent(
    popup: ActivePopup.NotePopup,
    defaultSnoozeMinutes: Int,
    viewModel: RevisionViewModel,
    onToggleStar: () -> Unit
) {
    val accent = parseColorHex(popup.book.colorHex)
    val displayAccent = adaptiveAccent(accent)
    val starColor = adaptiveAccent(Color(0xFFF59E0B))
    var starred by remember(popup.note.id) { mutableStateOf(popup.note.starred) }

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = accent
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(50),
                    color = displayAccent.copy(alpha = 0.16f),
                    contentColor = displayAccent
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = bookIconForKey(popup.book.iconKey),
                            contentDescription = null,
                            tint = displayAccent,
                            modifier = Modifier.size(16.dp)
                        )
                        Text(
                            text = popup.book.title,
                            style = MaterialTheme.typography.labelLarge,
                            color = displayAccent
                        )
                    }
                }

                IconButton(onClick = {
                    starred = !starred
                    onToggleStar()
                }) {
                    Icon(
                        imageVector = if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                        contentDescription = "গুরুত্বপূর্ণ তারকা",
                        tint = if (starred) starColor else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Text(
                text = "অধ্যায়: ${popup.chapter.title}",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = popup.note.content,
                    style = MaterialTheme.typography.headlineSmall.copy(lineHeight = 30.sp),
                    modifier = Modifier.padding(18.dp),
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            Text(
                text = "আগের রিভিশন: ${popup.note.revisionCount} বার",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Spacer(modifier = Modifier.height(24.dp))

    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Button(
            onClick = { viewModel.markNoteDoneAndClose() },
            modifier = Modifier
                .fillMaxWidth()
                .height(54.dp)
                .testTag("note_popup_done_button"),
            shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF059669),
                contentColor = Color.White
            )
        ) {
            Icon(Icons.Rounded.CheckCircle, contentDescription = null)
            Spacer(Modifier.width(8.dp))
            Text("পড়া হয়েছে", style = MaterialTheme.typography.titleMedium, color = Color.White)
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { viewModel.snoozePopup(5) },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("note_popup_snooze_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.Snooze, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("৫ মিনিট পরে")
            }

            FilledTonalButton(
                onClick = { viewModel.stopPopupAlarm() },
                modifier = Modifier
                    .weight(1f)
                    .height(50.dp)
                    .testTag("note_popup_stop_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Icon(Icons.Rounded.StopCircle, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("বন্ধ করো")
            }
        }
    }
}

@Composable
private fun ParagraphPopupContent(
    popup: ActivePopup.ParagraphPopup,
    defaultSnoozeMinutes: Int,
    viewModel: RevisionViewModel
) {
    var typedText by remember(popup.paragraph.id, popup.lineIndex) { mutableStateOf("") }
    var manualMeaningInput by remember(popup.paragraph.id, popup.lineIndex) { mutableStateOf("") }
    var retryCounter by remember(popup.paragraph.id, popup.lineIndex) { mutableIntStateOf(0) }
    var memorySecondsLeft by remember(popup.paragraph.id, popup.lineIndex, popup.paragraph.memoryMode, retryCounter) {
        mutableStateOf(if (popup.paragraph.memoryMode) 5 else -1)
    }

    LaunchedEffect(popup.paragraph.id, popup.lineIndex, popup.paragraph.memoryMode, retryCounter) {
        if (popup.paragraph.memoryMode) {
            memorySecondsLeft = 5
            while (memorySecondsLeft > 0) {
                delay(1000L)
                memorySecondsLeft--
            }
        }
    }

    val hideEnglishLine = popup.paragraph.memoryMode && memorySecondsLeft == 0 && popup.checkResult == null
    val isLastLine = popup.lineIndex >= popup.paragraph.lines.lastIndex

    GlassCard(
        modifier = Modifier.fillMaxWidth(),
        accentColor = Color(0xFF2563EB)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = popup.paragraph.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Surface(
                    shape = RoundedCornerShape(50),
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ) {
                    Text(
                        text = if (isLastLine) "শেষ লাইন (${popup.lineIndex + 1}/${popup.paragraph.lines.size})"
                        else "লাইন ${popup.lineIndex + 1} / ${popup.paragraph.lines.size}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            if (popup.paragraph.memoryMode && memorySecondsLeft > 0) {
                Text(
                    text = "মেমোরি মোড: $memorySecondsLeft সেকেন্ড পর ইংরেজি লাইন লুকিয়ে যাবে!",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.secondary
                )
            }

            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = if (hideEnglishLine) "•••••• (স্মৃতি থেকে ইংরেজি লাইনটি লিখুন) ••••••" else popup.lineText,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(14.dp),
                    color = if (hideEnglishLine) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
                )
            }

            // Lazy single-line Bengali translation block
            TranslationBox(
                translationState = popup.translationState,
                manualInput = manualMeaningInput,
                onManualInputChange = { manualMeaningInput = it },
                onRetry = { viewModel.retryParagraphTranslation() },
                onSaveManual = { viewModel.saveManualParagraphMeaning(manualMeaningInput) }
            )

            OutlinedTextField(
                value = typedText,
                onValueChange = { typedText = it },
                label = { Text("ইংরেজি লাইনটি হুবহু টাইপ করুন") },
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("paragraph_typing_input"),
                minLines = 2
            )

            if (popup.checkResult == null) {
                Button(
                    onClick = { viewModel.checkParagraphTyping(typedText) },
                    enabled = typedText.isNotBlank(),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("paragraph_check_button"),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(Icons.Rounded.Spellcheck, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("যাচাই করুন")
                }
            } else {
                val res = popup.checkResult
                val passColor = adaptiveAccent(Color(0xFF059669))
                val failColor = adaptiveAccent(Color(0xFFDC2626))
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "সঠিকতার হার: ${res.accuracyPercent}%",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (res.accuracyPercent >= 80) passColor else failColor
                        )
                        Text(
                            text = buildAnnotatedString {
                                res.tokens.forEachIndexed { i, token ->
                                    withStyle(
                                        SpanStyle(
                                            color = if (token.correct) passColor else failColor,
                                            fontWeight = FontWeight.SemiBold
                                        )
                                    ) {
                                        append(token.expected)
                                    }
                                    if (i < res.tokens.lastIndex) append(" ")
                                }
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                        if (res.wrongWords.isNotEmpty()) {
                            Text(
                                text = "ভুল শব্দগুলো Weak Words তালিকায় সংরক্ষণ করা হয়েছে।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    typedText = ""
                    retryCounter++
                    viewModel.resetParagraphRetry()
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("আবার চেষ্টা")
            }
            Button(
                onClick = { viewModel.completeParagraphLineAndClose() },
                modifier = Modifier.weight(1f).height(48.dp).testTag("paragraph_next_done_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF059669),
                    contentColor = Color.White
                )
            ) {
                Text(
                    if (isLastLine) "সম্পন্ন • আবার ১ম লাইন" else "পড়া হয়েছে • পরের লাইন",
                    color = Color.White,
                    maxLines = 1
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.snoozePopup(defaultSnoozeMinutes) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("স্নুজ ($defaultSnoozeMinutes মি.)")
            }
            FilledTonalButton(
                onClick = { viewModel.stopPopupAlarm() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("বন্ধ করো")
            }
        }
    }
}

@Composable
private fun VocabPopupContent(
    popup: ActivePopup.VocabPopup,
    defaultSnoozeMinutes: Int,
    viewModel: RevisionViewModel
) {
    var typedWord by remember(popup.word.id) { mutableStateOf("") }
    var checkedCorrect by remember(popup.word.id) { mutableStateOf<Boolean?>(null) }
    var manualMeaningInput by remember(popup.word.id) { mutableStateOf("") }
    val shakeOffset = remember { Animatable(0f) }

    LaunchedEffect(checkedCorrect) {
        if (checkedCorrect == false) {
            listOf(-18f, 18f, -12f, 12f, -6f, 0f).forEach { target ->
                shakeOffset.animateTo(target, tween(45))
            }
        }
    }

    val vocabAccent = adaptiveAccent(Color(0xFF7C3AED))

    GlassCard(
        modifier = Modifier
            .fillMaxWidth()
            .offset { IntOffset(shakeOffset.value.roundToInt(), 0) },
        accentColor = Color(0xFF7C3AED)
    ) {
        Column(modifier = Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = popup.list.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = vocabAccent
                )
                if (popup.list.reverseMode) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        contentColor = MaterialTheme.colorScheme.onSecondaryContainer
                    ) {
                        Text(
                            text = "রিভার্স মোড",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            if (!popup.list.reverseMode || checkedCorrect != null) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    contentColor = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp).fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "ইংরেজি শব্দ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = popup.word.word,
                            style = MaterialTheme.typography.displaySmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth().testTag("vocab_front_word")
                        )
                    }
                }
            } else {
                Text(
                    text = "বাংলা অর্থ দেখে ইংরেজি শব্দটি লিখুন",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            TranslationBox(
                translationState = popup.translationState,
                manualInput = manualMeaningInput,
                onManualInputChange = { manualMeaningInput = it },
                onRetry = { viewModel.retryVocabTranslation() },
                onSaveManual = { viewModel.saveManualVocabMeaning(manualMeaningInput) }
            )

            OutlinedTextField(
                value = typedWord,
                onValueChange = { typedWord = it },
                label = {
                    Text(
                        if (!popup.list.reverseMode) "ওপরের ইংরেজি শব্দটি দেখে টাইপ করুন"
                        else "ইংরেজি বানান লিখুন"
                    )
                },
                singleLine = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("vocab_typing_input")
            )

            AnimatedVisibility(visible = checkedCorrect != null) {
                val feedbackAccent = if (checkedCorrect == true) {
                    adaptiveAccent(Color(0xFF059669))
                } else {
                    adaptiveAccent(Color(0xFFDC2626))
                }
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = feedbackAccent.copy(alpha = 0.16f),
                    contentColor = feedbackAccent,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (checkedCorrect == true) {
                            "চমৎকার! সঠিক বানান: ${popup.word.word}"
                        } else {
                            "ভুল হয়েছে! সঠিক বানান: ${popup.word.word} (Weak Words-এ যোগ হয়েছে)"
                        },
                        color = feedbackAccent,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }

            Button(
                onClick = {
                    checkedCorrect = viewModel.submitVocabSpelling(typedWord)
                },
                enabled = typedWord.isNotBlank(),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .testTag("vocab_check_button"),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("বানান যাচাই করুন")
            }
        }
    }

    Spacer(modifier = Modifier.height(16.dp))

    Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = {
                    typedWord = ""
                    checkedCorrect = null
                },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("আবার")
            }
            Button(
                onClick = {
                    typedWord = ""
                    checkedCorrect = null
                    viewModel.nextVocabWordInPopup()
                },
                modifier = Modifier.weight(1f).height(48.dp).testTag("vocab_next_button"),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color(0xFF6D28D9),
                    contentColor = Color.White
                )
            ) {
                Text("পরবর্তী", color = Color.White)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(
                onClick = { viewModel.snoozePopup(defaultSnoozeMinutes) },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("স্নুজ ($defaultSnoozeMinutes মি.)")
            }
            FilledTonalButton(
                onClick = { viewModel.stopPopupAlarm() },
                modifier = Modifier.weight(1f).height(48.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                Text("বন্ধ করো")
            }
        }
    }
}

@Composable
private fun TranslationBox(
    translationState: PopupTranslationState,
    manualInput: String,
    onManualInputChange: (String) -> Unit,
    onRetry: () -> Unit,
    onSaveManual: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(14.dp))
    ) {
        Column(modifier = Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (translationState) {
                PopupTranslationState.Idle -> Unit
                PopupTranslationState.Loading -> {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                        Text("বাংলা অর্থ অনুবাদ হচ্ছে...", style = MaterialTheme.typography.bodyMedium)
                    }
                }
                is PopupTranslationState.Ready -> {
                    Text(
                        text = "বাংলা অর্থ:",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                    )
                    Text(
                        text = translationState.meaningBn,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
                is PopupTranslationState.Error -> {
                    Text(
                        text = translationState.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(onClick = onRetry, modifier = Modifier.height(40.dp)) {
                            Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("আবার চেষ্টা")
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            value = manualInput,
                            onValueChange = onManualInputChange,
                            label = { Text("নিজে বাংলা অর্থ লিখুন") },
                            singleLine = true,
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = onSaveManual,
                            enabled = manualInput.isNotBlank(),
                            modifier = Modifier.height(48.dp)
                        ) {
                            Text("সংরক্ষণ")
                        }
                    }
                }
            }
        }
    }
}
