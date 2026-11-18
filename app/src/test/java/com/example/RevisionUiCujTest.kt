package com.example

import android.content.Context
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.RevisionRepository
import com.example.data.local.*
import com.example.data.remote.FirestoreSyncRepository
import com.example.ui.ActivePopup
import com.example.ui.RevisionUiState
import com.example.ui.RevisionViewModel
import com.example.ui.components.RevisionPopupOverlay
import com.example.ui.screens.AuthScreen
import com.example.ui.screens.HomeScreen
import com.example.ui.theme.RevisionAlarmTheme
import com.google.firebase.FirebaseApp
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class RevisionUiCujTest {

    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var db: AppDatabase
    private lateinit var dao: AppDao
    private lateinit var repo: RevisionRepository
    private lateinit var viewModel: RevisionViewModel

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        if (FirebaseApp.getApps(context).isEmpty()) {
            FirebaseApp.initializeApp(context)
        }
        db = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        dao = db.appDao()
        val fsSync = FirestoreSyncRepository(
            db = FirebaseFirestore.getInstance(context.getString(R.string.firestore_database_id)),
            dao = dao,
            auth = FirebaseAuth.getInstance()
        )
        repo = RevisionRepository(context, dao, fsSync)
        runBlocking { repo.ensureSeeded() }
        viewModel = RevisionViewModel(context, repo)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `home screen renders 11 books and switches between all 3 sections`() {
        val state = RevisionUiState(
            books = DefaultBooksSeed.books,
            paragraphs = listOf(
                ParagraphFileEntity("p1", "A Winter Morning", "Cold morning.", listOf("Cold morning."))
            ),
            vocabLists = listOf(
                VocabListEntity("v1", "Unit 1 Words")
            ),
            vocabWords = listOf(
                VocabWordEntity("w1", "v1", "resilient", "দৃঢ়চেতা")
            )
        )

        composeRule.setContent {
            RevisionAlarmTheme {
                HomeScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenBook = {},
                    onOpenChapter = { _, _ -> },
                    onOpenVocabList = {},
                    onOpenSettings = {}
                )
            }
        }

        // Section A: Books visible
        composeRule.onNodeWithText("বাংলা প্রথম পত্র").assertIsDisplayed()
        composeRule.onNodeWithTag("book_card_book_01").assertIsDisplayed()

        // Switch to Section B: Paragraph Practice
        composeRule.onNodeWithTag("tab_section_1").performClick()
        composeRule.onNodeWithText("A Winter Morning").assertIsDisplayed()

        // Switch to Section C: Vocabulary
        composeRule.onNodeWithTag("tab_section_2").performClick()
        composeRule.onNodeWithText("Unit 1 Words").assertIsDisplayed()
    }

    @Test
    fun `auth screen has Skip button allowing users without Google account to enter`() {
        var skipped = false
        composeRule.setContent {
            RevisionAlarmTheme {
                AuthScreen(
                    onAuthSuccess = {},
                    onContinueWithoutLogin = { skipped = true }
                )
            }
        }
        composeRule.onNodeWithTag("skip_login_top_button").assertIsDisplayed()
        composeRule.onNodeWithTag("continue_local_button").performScrollTo().assertIsDisplayed().performClick()
        assertTrue(skipped)
    }

    @Test
    fun `note popup overlay shows book, chapter, note and all 3 required buttons`() {
        val book = DefaultBooksSeed.books[0]
        val chapter = ChapterEntity("ch1", book.id, "অধ্যায় ১", 0)
        val note = NoteEntity("n1", book.id, chapter.id, "রবীন্দ্রনাথ ঠাকুর ১৮৬১ সালে জন্মগ্রহণ করেন।")
        val popup = ActivePopup.NotePopup(book, chapter, note)

        composeRule.setContent {
            RevisionAlarmTheme {
                RevisionPopupOverlay(
                    popup = popup,
                    defaultSnoozeMinutes = 5,
                    viewModel = viewModel,
                    onToggleNoteStar = {}
                )
            }
        }

        composeRule.onNodeWithTag("revision_popup_overlay").assertIsDisplayed()
        composeRule.onNodeWithText("বাংলা প্রথম পত্র").assertIsDisplayed()
        composeRule.onNodeWithText("অধ্যায়: অধ্যায় ১").assertIsDisplayed()
        composeRule.onNodeWithText("রবীন্দ্রনাথ ঠাকুর ১৮৬১ সালে জন্মগ্রহণ করেন।").assertIsDisplayed()
        composeRule.onNodeWithTag("note_popup_done_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("note_popup_snooze_button").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("note_popup_stop_button").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `paragraph popup displays single line, Bengali meaning, and checks typing accuracy`() {
        val para = ParagraphFileEntity(
            id = "p1",
            title = "A Rainy Day",
            rawText = "The sky is cloudy. Rain falls softly.",
            lines = listOf("The sky is cloudy.", "Rain falls softly."),
            currentLineIndex = 0
        )

        runBlocking {
            dao.upsertParagraph(para)
            repo.saveManualSingleTranslation("The sky is cloudy.", "আকাশ মেঘলা।")
        }

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            RevisionAlarmTheme {
                state.activePopup?.let { popup ->
                    RevisionPopupOverlay(
                        popup = popup,
                        defaultSnoozeMinutes = 5,
                        viewModel = viewModel,
                        onToggleNoteStar = {}
                    )
                }
            }
        }

        composeRule.runOnUiThread {
            viewModel.launchParagraphNow(para)
        }

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("আকাশ মেঘলা।").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("The sky is cloudy.").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("আকাশ মেঘলা।").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithTag("paragraph_typing_input").performScrollTo().performTextInput("The sky is cloudy.")
        composeRule.onNodeWithTag("paragraph_check_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("সঠিকতার হার: 100%").fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Test
    fun `vocab popup next button advances to a different word even after mistake`() {
        val list = VocabListEntity("vl1", "Unit 1")
        val w1 = VocabWordEntity("w1", "vl1", "resilient", "দৃঢ়চেতা")
        val w2 = VocabWordEntity("w2", "vl1", "serene", "শান্ত")

        runBlocking {
            dao.upsertVocabList(list)
            dao.upsertVocabWords(listOf(w1, w2))
        }

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            RevisionAlarmTheme {
                state.activePopup?.let { popup ->
                    RevisionPopupOverlay(
                        popup = popup,
                        defaultSnoozeMinutes = 5,
                        viewModel = viewModel,
                        onToggleNoteStar = {}
                    )
                }
            }
        }

        composeRule.runOnUiThread {
            viewModel.launchVocabListNow(list)
        }
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("vocab_typing_input").fetchSemanticsNodes().isNotEmpty()
        }

        val firstWord = (viewModel.uiState.value.activePopup as ActivePopup.VocabPopup).word
        val expectedOtherWord = if (firstWord.id == w1.id) w2.word else w1.word

        // Both English word AND Bengali meaning must be visible in front by default
        composeRule.onNodeWithTag("vocab_front_word").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText(firstWord.meaningBn).performScrollTo().assertIsDisplayed()

        composeRule.onNodeWithTag("vocab_typing_input").performScrollTo().performTextInput("wrong")
        composeRule.onNodeWithTag("vocab_check_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("ভুল হয়েছে!", substring = true).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag("vocab_next_button").performScrollTo().performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText(expectedOtherWord).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText(expectedOtherWord).assertIsDisplayed()
    }

    @Test
    fun `dark and light themes provide high-contrast LocalContentColor and readable subject accents`() {
        var darkContentColor = androidx.compose.ui.graphics.Color.Unspecified
        var darkCardContentColor = androidx.compose.ui.graphics.Color.Unspecified
        var darkSurfaceColor = androidx.compose.ui.graphics.Color.Unspecified

        composeRule.setContent {
            RevisionAlarmTheme(themeMode = "dark") {
                darkContentColor = androidx.compose.material3.LocalContentColor.current
                darkSurfaceColor = androidx.compose.material3.MaterialTheme.colorScheme.surface
                com.example.ui.components.AnimatedMeshBackground {
                    com.example.ui.components.GlassCard {
                        darkCardContentColor = androidx.compose.material3.LocalContentColor.current
                    }
                }
            }
        }

        assertNotEquals(androidx.compose.ui.graphics.Color.Black, darkContentColor)
        assertNotEquals(androidx.compose.ui.graphics.Color.Black, darkCardContentColor)
        assertTrue(
            "Dark card text contrast must be high",
            contrastRatio(darkCardContentColor, darkSurfaceColor) >= 10.0
        )

        com.example.ui.theme.SubjectColors.forEach { hex ->
            val raw = com.example.ui.components.parseColorHex(hex)
            val onDark = com.example.ui.theme.readableAccentColor(raw, isDark = true)
            val onLight = com.example.ui.theme.readableAccentColor(raw, isDark = false)
            assertTrue(
                "Accent $hex on dark surface must have >= 4.5 contrast",
                contrastRatio(onDark, com.example.ui.theme.DarkSurface) >= 4.5
            )
            assertTrue(
                "Accent $hex on light surface must have >= 4.5 contrast",
                contrastRatio(onLight, com.example.ui.theme.LightSurface) >= 4.5
            )
        }
    }

    @Test
    fun `alarms tab renders multiple alarms and allows opening alarm creator dialog`() {
        val para = ParagraphFileEntity("p1", "Climate Change", "Line 1. Line 2.", listOf("Line 1.", "Line 2."))
        val alarm1 = RevisionAlarmEntity("a1", "মর্নিং অ্যালার্ম", scheduleType = "interval", intervalMinutes = 15, selectedParagraphIds = listOf("p1"))
        val alarm2 = RevisionAlarmEntity("a2", "ইভনিং অ্যালার্ম", scheduleType = "clock", hourOfDay = 20, minute = 30, selectedBookIds = listOf("book_01"))

        runBlocking {
            dao.upsertParagraph(para)
            dao.upsertAlarms(listOf(alarm1, alarm2))
        }

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            RevisionAlarmTheme {
                HomeScreen(
                    state = state,
                    viewModel = viewModel,
                    onOpenBook = {},
                    onOpenChapter = { _, _ -> },
                    onOpenVocabList = {},
                    onOpenSettings = {}
                )
            }
        }

        composeRule.onNodeWithTag("tab_section_3").performScrollTo().performClick()
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("মর্নিং অ্যালার্ম").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("মর্নিং অ্যালার্ম").assertIsDisplayed()
        composeRule.onNodeWithTag("alarms_list").performScrollToNode(hasText("ইভনিং অ্যালার্ম"))
        composeRule.onNodeWithText("ইভনিং অ্যালার্ম").assertIsDisplayed()

        // Open Alarm Creator dialog and verify granular selection tabs render
        composeRule.onNodeWithTag("add_alarm_fab").performClick()
        composeRule.onNodeWithTag("alarm_title_input").performTextInput("নতুন রুটিন")
        composeRule.onNodeWithTag("alarm_editor_list").performScrollToNode(hasTestTag("alarm_picker_tab_1"))
        composeRule.onNodeWithTag("alarm_picker_tab_1").performClick()
        composeRule.onNodeWithTag("alarm_editor_list").performScrollToNode(hasTestTag("alarm_select_para_p1"))
        composeRule.onNodeWithTag("alarm_select_para_p1").performClick()
        composeRule.onNodeWithTag("save_alarm_button").performClick()

        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithText("অ্যালার্ম (3)").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag("alarms_list").performScrollToNode(hasText("নতুন রুটিন"))
        composeRule.onNodeWithText("নতুন রুটিন").assertIsDisplayed()
    }

    @Test
    fun `paragraph popup displays loop label on final line`() {
        val para = ParagraphFileEntity(
            id = "p_last",
            title = "Tree Plantation",
            rawText = "Trees help us. Save trees.",
            lines = listOf("Trees help us.", "Save trees."),
            currentLineIndex = 1
        )
        runBlocking {
            dao.upsertParagraph(para)
            repo.saveManualSingleTranslation("Save trees.", "গাছ বাঁচান।")
        }

        composeRule.setContent {
            val state by viewModel.uiState.collectAsState()
            RevisionAlarmTheme {
                state.activePopup?.let { popup ->
                    RevisionPopupOverlay(
                        popup = popup,
                        defaultSnoozeMinutes = 5,
                        viewModel = viewModel,
                        onToggleNoteStar = {}
                    )
                }
            }
        }

        composeRule.runOnUiThread { viewModel.launchParagraphNow(para) }
        composeRule.waitUntil(timeoutMillis = 5000) {
            composeRule.onAllNodesWithTag("paragraph_next_done_button").fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithText("শেষ লাইন (2/2)").performScrollTo().assertIsDisplayed()
        composeRule.onNodeWithText("সম্পন্ন • আবার ১ম লাইন").performScrollTo().assertIsDisplayed()
    }

    private fun contrastRatio(fg: androidx.compose.ui.graphics.Color, bg: androidx.compose.ui.graphics.Color): Double {
        fun lum(c: androidx.compose.ui.graphics.Color): Double {
            fun ch(v: Float): Double {
                val d = v.toDouble()
                return if (d <= 0.03928) d / 12.92 else Math.pow((d + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * ch(c.red) + 0.7152 * ch(c.green) + 0.0722 * ch(c.blue)
        }
        val l1 = lum(fg)
        val l2 = lum(bg)
        val lighter = maxOf(l1, l2)
        val darker = minOf(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }
}

