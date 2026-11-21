package com.example

import android.app.Application
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.credentials.CredentialManager
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.example.alarm.AlarmScheduler
import com.example.data.RevisionRepository
import com.example.data.local.AppDatabase
import com.example.data.remote.FirestoreSyncRepository
import com.example.firebase.attemptAutoSignIn
import com.example.firebase.authStateFlow
import com.example.firebase.configureAppCheck
import com.example.ui.RevisionViewModel
import com.example.ui.components.ConfettiBurstOverlay
import com.example.ui.components.RevisionPopupOverlay
import com.example.ui.screens.*
import com.example.ui.theme.RevisionAlarmTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingAlarmIntent by mutableStateOf<Triple<String?, String?, String?>?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // STEP 1: Configure Firebase App Check with launch intent before UI or ViewModels
        configureAppCheck(this, intent)

        enableEdgeToEdge()
        captureAlarmIntent(intent)

        setContent {
            val context = this
            val scope = rememberCoroutineScope()
            val prefs = remember { context.getSharedPreferences("revision_alarm_prefs", MODE_PRIVATE) }
            val auth = remember { Firebase.auth }
            val currentUser by auth.authStateFlow().collectAsStateWithLifecycle(initialValue = auth.currentUser)
            var localOnlyMode by remember { mutableStateOf(prefs.getBoolean("skip_login", false)) }

            LaunchedEffect(Unit) {
                if (auth.currentUser == null && !localOnlyMode) {
                    val cm = CredentialManager.create(context)
                    attemptAutoSignIn(
                        context = context,
                        credentialManager = cm,
                        scope = scope,
                        onAuthSuccess = {
                            prefs.edit().putBoolean("skip_login", false).apply()
                            localOnlyMode = false
                        },
                        onUnauthenticated = { }
                    )
                }
            }

            if (currentUser == null && !localOnlyMode) {
                RevisionAlarmTheme {
                    AuthScreen(
                        onAuthSuccess = {
                            prefs.edit().putBoolean("skip_login", false).apply()
                            localOnlyMode = false
                        },
                        onContinueWithoutLogin = {
                            prefs.edit().putBoolean("skip_login", true).apply()
                            localOnlyMode = true
                        }
                    )
                }
            } else {
                val sessionKey = currentUser?.uid ?: "local_only"
                key(sessionKey) {
                    val vm: RevisionViewModel = viewModel(
                        key = sessionKey,
                        factory = viewModelFactory {
                            initializer {
                                val app = checkNotNull(this[APPLICATION_KEY])
                                val dao = AppDatabase.getInstance(app).appDao()
                                val fsSync = FirestoreSyncRepository(app, dao)
                                val repo = RevisionRepository(app, dao, fsSync)
                                RevisionViewModel(app, repo)
                            }
                        }
                    )

                    LaunchedEffect(currentUser?.uid) {
                        vm.onUserSessionChanged(currentUser?.uid)
                    }

                    LaunchedEffect(pendingAlarmIntent) {
                        val trigger = pendingAlarmIntent
                        if (trigger != null) {
                            vm.handleIncomingNotificationTrigger(trigger.first, trigger.second, trigger.third)
                            pendingAlarmIntent = null
                        }
                    }

                    val state by vm.uiState.collectAsStateWithLifecycle()

                    RevisionAlarmTheme(themeMode = state.stats.themeMode) {
                        Box(modifier = Modifier.fillMaxSize()) {
                            RevisionAlarmNavHost(
                                vm = vm,
                                state = state,
                                currentUser = currentUser,
                                onSignedOut = {
                                    prefs.edit().putBoolean("skip_login", false).apply()
                                    localOnlyMode = false
                                }
                            )

                            state.activePopup?.let { popup ->
                                RevisionPopupOverlay(
                                    popup = popup,
                                    defaultSnoozeMinutes = state.stats.defaultSnoozeMinutes,
                                    viewModel = vm,
                                    onToggleNoteStar = { note ->
                                        scope.launch {
                                            vm.repository.updateNote(note.copy(starred = !note.starred))
                                        }
                                    }
                                )
                            }

                            ConfettiBurstOverlay(visible = state.showCelebration)
                        }
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        captureAlarmIntent(intent)
    }

    private fun captureAlarmIntent(intent: Intent?) {
        if (intent?.getBooleanExtra(AlarmScheduler.EXTRA_ALARM_TRIGGERED, false) == true) {
            val type = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TYPE)
            val targetId = intent.getStringExtra(AlarmScheduler.EXTRA_TRIGGER_TARGET_ID)
            val alarmId = intent.getStringExtra(AlarmScheduler.EXTRA_ALARM_ID)
            pendingAlarmIntent = Triple(type, targetId, alarmId)
        }
    }
}

@Composable
private fun RevisionAlarmNavHost(
    vm: RevisionViewModel,
    state: com.example.ui.RevisionUiState,
    currentUser: com.google.firebase.auth.FirebaseUser?,
    onSignedOut: () -> Unit
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                state = state,
                viewModel = vm,
                onOpenBook = { bookId -> navController.navigate("book/$bookId") },
                onOpenChapter = { bookId, chapterId -> navController.navigate("chapter/$bookId/$chapterId") },
                onOpenVocabList = { listId -> navController.navigate("vocab/$listId") },
                onOpenSettings = { navController.navigate("settings") }
            )
        }

        composable("book/{bookId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            val book = state.books.find { it.id == bookId }
            if (book != null) {
                val bookChapters = state.chapters.filter { it.bookId == bookId }.sortedBy { it.sortOrder }
                val bookNotes = state.notes.filter { it.bookId == bookId }
                BookDetailScreen(
                    book = book,
                    chapters = bookChapters,
                    notes = bookNotes,
                    repository = vm.repository,
                    onBack = { navController.popBackStack() },
                    onOpenChapter = { chapterId -> navController.navigate("chapter/$bookId/$chapterId") },
                    paragraphs = state.paragraphs,
                    vocabLists = state.vocabLists,
                    vocabWords = state.vocabWords,
                    onOpenVocabList = { listId -> navController.navigate("vocab/$listId") },
                    onPracticeParagraph = { para -> vm.launchParagraphNow(para) },
                    onPracticeVocab = { list -> vm.launchVocabListNow(list) }
                )
            } else if (state.books.isNotEmpty()) {
                LaunchedEffect(bookId) { navController.popBackStack() }
            }
        }

        composable("chapter/{bookId}/{chapterId}") { backStackEntry ->
            val bookId = backStackEntry.arguments?.getString("bookId").orEmpty()
            val chapterId = backStackEntry.arguments?.getString("chapterId").orEmpty()
            val book = state.books.find { it.id == bookId }
            val chapter = state.chapters.find { it.id == chapterId }
            if (book != null && chapter != null) {
                val allInBook = state.chapters.filter { it.bookId == bookId }.sortedBy { it.sortOrder }
                val chapterNotes = state.notes.filter { it.chapterId == chapterId }
                ChapterDetailScreen(
                    book = book,
                    chapter = chapter,
                    allChaptersInBook = allInBook,
                    notes = chapterNotes,
                    repository = vm.repository,
                    onBack = { navController.popBackStack() }
                )
            } else if (state.books.isNotEmpty()) {
                LaunchedEffect(chapterId) { navController.popBackStack() }
            }
        }

        composable("vocab/{listId}") { backStackEntry ->
            val listId = backStackEntry.arguments?.getString("listId").orEmpty()
            val list = state.vocabLists.find { it.id == listId }
            if (list != null) {
                val listWords = state.vocabWords.filter { it.listId == listId }
                VocabListDetailScreen(
                    vocabList = list,
                    words = listWords,
                    repository = vm.repository,
                    onBack = { navController.popBackStack() },
                    onPracticeNow = { vm.launchVocabListNow(list) }
                )
            } else if (state.books.isNotEmpty()) {
                LaunchedEffect(listId) { navController.popBackStack() }
            }
        }

        composable("settings") {
            SettingsScreen(
                state = state,
                currentUser = currentUser,
                viewModel = vm,
                onBack = { navController.popBackStack() },
                onSignedOut = onSignedOut
            )
        }
    }
}
