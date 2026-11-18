package com.example.ui.screens

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.HelpOutline
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.credentials.CredentialManager
import coil.compose.AsyncImage
import com.example.alarm.AlarmScheduler
import com.example.firebase.onGoogleSignInClicked
import com.example.firebase.signOutFromGoogle
import com.example.ui.RevisionUiState
import com.example.ui.RevisionViewModel
import com.example.ui.components.AnimatedMeshBackground
import com.example.ui.components.GlassCard
import com.google.firebase.auth.FirebaseUser
import kotlinx.coroutines.launch
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    state: RevisionUiState,
    currentUser: FirebaseUser?,
    viewModel: RevisionViewModel,
    onBack: () -> Unit,
    onSignedOut: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val credentialManager = remember { CredentialManager.create(context) }

    var showImportDialog by remember { mutableStateOf(false) }
    var showGuideDialog by remember { mutableStateOf(false) }
    var statusMessage by remember { mutableStateOf<String?>(null) }

    val notifLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        statusMessage = if (granted) "নোটিফিকেশন অনুমতি চালু হয়েছে!" else "নোটিফিকেশন অনুমতি দেওয়া হয়নি।"
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
                        IconButton(onClick = onBack, modifier = Modifier.testTag("back_from_settings")) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "ফিরে যান")
                        }
                    },
                    title = { Text("সেটিংস ও ব্যাকআপ") }
                )
            }
        ) { innerPadding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                statusMessage?.let { msg ->
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    ) {
                        Text(
                            msg,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(12.dp).fillMaxWidth(),
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }

                // Account & Cloud Sync Card
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Google অ্যাকাউন্ট ও ক্লাউড সিঙ্ক", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        if (currentUser != null) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                if (currentUser.photoUrl != null) {
                                    AsyncImage(
                                        model = currentUser.photoUrl,
                                        contentDescription = "প্রোফাইল ছবি",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.size(48.dp).clip(CircleShape)
                                    )
                                } else {
                                    Surface(
                                        shape = CircleShape,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Box(contentAlignment = Alignment.Center) {
                                            Icon(Icons.Rounded.Person, contentDescription = null)
                                        }
                                    }
                                }
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(currentUser.displayName ?: "ব্যবহারকারী", fontWeight = FontWeight.Bold)
                                    Text(currentUser.email.orEmpty(), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                FilledTonalButton(
                                    onClick = { viewModel.syncNow() },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Rounded.Sync, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("এখনই সিঙ্ক")
                                }
                                OutlinedButton(
                                    onClick = {
                                        signOutFromGoogle(
                                            credentialManager = credentialManager,
                                            scope = scope,
                                            onSignOutComplete = onSignedOut
                                        )
                                    },
                                    modifier = Modifier.weight(1f).testTag("logout_button")
                                ) {
                                    Text("লগআউট")
                                }
                            }
                        } else {
                            Text(
                                "আপনি এখন লোকাল মোডে আছেন। Google দিয়ে সাইন-ইন করলে সব নোট, প্যারাগ্রাফ ও ভোকাবুলারি ক্লাউডে মার্জ ও সিঙ্ক হবে।",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Button(
                                onClick = {
                                    onGoogleSignInClicked(
                                        context = context,
                                        credentialManager = credentialManager,
                                        scope = scope,
                                        onAuthSuccess = { statusMessage = "সাইন-ইন সম্পন্ন! ক্লাউড ডেটা সিঙ্ক হচ্ছে।" },
                                        onAuthError = { err -> statusMessage = err }
                                    )
                                },
                                modifier = Modifier.fillMaxWidth().testTag("settings_google_signin_button")
                            ) {
                                Text("Google দিয়ে সাইন-ইন ও মার্জ করুন")
                            }
                        }
                    }
                }

                // Theme & Appearance
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("থিম (Theme)", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("system" to "সিস্টেম", "light" to "হালকা", "dark" to "গাঢ়").forEach { (key, label) ->
                                FilterChip(
                                    selected = state.stats.themeMode == key,
                                    onClick = {
                                        scope.launch { viewModel.repository.updateStats { it.copy(themeMode = key) } }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                // Sound, Vibration, Snooze, Vocab Order
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("অ্যালার্ম ও অনুশীলন সেটিংস", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("অ্যালার্ম সাউন্ড")
                            Switch(
                                checked = state.stats.soundEnabled,
                                onCheckedChange = { en ->
                                    scope.launch { viewModel.repository.updateStats { it.copy(soundEnabled = en) } }
                                }
                            )
                        }

                        if (state.stats.soundEnabled) {
                            var sliderVolume by remember(state.stats.alarmVolume) {
                                mutableFloatStateOf(state.stats.alarmVolume)
                            }
                            Text("শব্দের ভলিউম: ${(sliderVolume * 100).toInt()}%", style = MaterialTheme.typography.bodySmall)
                            Slider(
                                value = sliderVolume,
                                onValueChange = { sliderVolume = it },
                                onValueChangeFinished = {
                                    scope.launch { viewModel.repository.updateStats { it.copy(alarmVolume = sliderVolume) } }
                                }
                            )
                        }

                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("ভাইব্রেশন")
                            Switch(
                                checked = state.stats.vibrationEnabled,
                                onCheckedChange = { en ->
                                    scope.launch { viewModel.repository.updateStats { it.copy(vibrationEnabled = en) } }
                                }
                            )
                        }

                        Text("ডিফল্ট স্নুজ সময়", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(5, 10, 15, 30).forEach { mins ->
                                FilterChip(
                                    selected = state.stats.defaultSnoozeMinutes == mins,
                                    onClick = {
                                        scope.launch { viewModel.repository.updateStats { it.copy(defaultSnoozeMinutes = mins) } }
                                    },
                                    label = { Text("$mins মি.") }
                                )
                            }
                        }

                        Text("ভোকাবুলারি ক্রম", style = MaterialTheme.typography.labelLarge)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("ordered" to "ক্রমানুসারে", "random" to "এলোমেলো").forEach { (key, label) ->
                                FilterChip(
                                    selected = state.stats.vocabOrderMode == key,
                                    onClick = {
                                        scope.launch { viewModel.repository.updateStats { it.copy(vocabOrderMode = key) } }
                                    },
                                    label = { Text(label) }
                                )
                            }
                        }
                    }
                }

                // Export .ics, JSON Backup, Permissions & Bengali Guide
                GlassCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("এক্সপোর্ট, ক্যালেন্ডার ও অনুমতি", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)

                        FilledTonalButton(
                            onClick = {
                                val ics = AlarmScheduler.generateIcsContent(state.books, state.paragraphs, state.vocabLists)
                                shareCacheFile(context, "revision_alarm_schedule.ics", "text/calendar", ics)
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Rounded.CalendarMonth, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text("ক্যালেন্ডার রিমাইন্ডার এক্সপোর্ট (.ics)")
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            FilledTonalButton(
                                onClick = {
                                    scope.launch {
                                        val json = viewModel.repository.exportBackupJson()
                                        shareCacheFile(context, "revision_alarm_backup.json", "application/json", json)
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("JSON ব্যাকআপ") }

                            OutlinedButton(
                                onClick = { showImportDialog = true },
                                modifier = Modifier.weight(1f)
                            ) { Text("JSON ইমপোর্ট") }
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                                        notifLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                                    } else {
                                        statusMessage = "এই অ্যান্ড্রয়েড ভার্সনে নোটিফিকেশন স্বয়ংক্রিয়ভাবে চালু।"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("নোটিফিকেশন চালু") }

                            OutlinedButton(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                        val intent = Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                                            data = Uri.parse("package:${context.packageName}")
                                        }
                                        runCatching { context.startActivity(intent) }
                                    } else {
                                        statusMessage = "সঠিক সময়ে অ্যালার্ম আগে থেকেই সক্রিয়।"
                                    }
                                },
                                modifier = Modifier.weight(1f)
                            ) { Text("সঠিক অ্যালার্ম অনুমতি") }
                        }

                        TextButton(
                            onClick = { showGuideDialog = true },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.HelpOutline, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("ব্যবহারবিধি ও ফোনে সেটআপ গাইড (বাংলা)")
                        }
                    }
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showImportDialog) {
        var jsonInput by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showImportDialog = false },
            title = { Text("JSON ব্যাকআপ ইমপোর্ট") },
            text = {
                OutlinedTextField(
                    value = jsonInput,
                    onValueChange = { jsonInput = it },
                    label = { Text("ব্যাকআপ JSON টেক্সট এখানে পেস্ট করুন") },
                    minLines = 5,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        scope.launch {
                            val res = viewModel.repository.importBackupJson(jsonInput)
                            statusMessage = if (res.isSuccess) "ব্যাকআপ সফলভাবে ইমপোর্ট হয়েছে!" else "সঠিক JSON ফরম্যাট নয়।"
                        }
                        showImportDialog = false
                    },
                    enabled = jsonInput.isNotBlank()
                ) { Text("ইমপোর্ট করুন") }
            },
            dismissButton = {
                TextButton(onClick = { showImportDialog = false }) { Text("বাতিল") }
            }
        )
    }

    if (showGuideDialog) {
        AlertDialog(
            onDismissRequest = { showGuideDialog = false },
            title = { Text("রিভিশন অ্যালার্ম ব্যবহারবিধি") },
            text = {
                Text(
                    "১. হোম স্ক্রিন থেকে যেকোনো বই খুলুন, অধ্যায় ও নোট যোগ করুন এবং কত মিনিট পরপর অ্যালার্ম চান তা সেট করুন।\n" +
                    "২. 'প্যারাগ্রাফ' ট্যাবে ইংরেজি প্যারাগ্রাফ দিলে অ্যাপ লাইনে লাইনে ভাগ করবে এবং প্রদর্শনের মুহূর্তে বাংলা অর্থ অনুবাদ করবে।\n" +
                    "৩. 'ভোকাবুলারি' ট্যাবে একসাথে অনেক শব্দ পেস্ট করতে পারবেন।\n" +
                    "৪. নিরবচ্ছিন্ন অ্যালার্ম পেতে সেটিংস থেকে নোটিফিকেশন ও সঠিক অ্যালার্ম অনুমতি চালু রাখুন অথবা .ics ক্যালেন্ডার রিমাইন্ডার যোগ করুন।"
                )
            },
            confirmButton = {
                Button(onClick = { showGuideDialog = false }) { Text("বুঝেছি") }
            }
        )
    }
}

private fun shareCacheFile(context: android.content.Context, fileName: String, mimeType: String, content: String) {
    try {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(sendIntent, "এক্সপোর্ট শেয়ার করুন"))
    } catch (_: Exception) {
    }
}
