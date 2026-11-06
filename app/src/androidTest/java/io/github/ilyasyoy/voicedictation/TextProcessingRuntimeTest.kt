package io.github.ilyasyoy.voicedictation

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.graphics.Rect
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.*
import org.junit.Assert.*

/** Actual Compose, durable ledger/preferences and clipboard. Every model response is local synthetic data. */
class TextProcessingRuntimeTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val repository get() = AndroidRecordingStorage.repository(context)
    private lateinit var previousSettings: TextProcessingSettings
    private var animation = "1"
    private var night = "no"
    private var fontScale = "1.0"
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
    private fun capture(name: String) {
        compose.waitForIdle(); shell("mkdir -p /sdcard/Download/postprocessing")
        shell("screencap -p /sdcard/Download/postprocessing/$name.png")
    }
    @Before fun before() {
        animation = shell("settings get global animator_duration_scale"); night = shell("cmd uimode night").substringAfter(":").trim()
        fontScale = shell("settings get system font_scale").takeUnless { it == "null" } ?: "1.0"
        shell("settings put global animator_duration_scale 0")
        previousSettings = TextProcessingPreferences(context).read()
        instrumentation.runOnMainSync {
            DictationSessions.prepare(context)
            repository.records().forEach { repository.discard(it.id) }; repository.saveText(RecordingTextSlot.MAIN, "")
            MainActivity.testNotice = "TEST DATA · local synthetic responses · no API request"
        }
    }
    @After fun after() {
        instrumentation.runOnMainSync {
            MainActivity.testFactory = null; MainActivity.testNotice = null; HistoryViewModel.testProcess = null
            repository.records().forEach { repository.discard(it.id) }; repository.saveText(RecordingTextSlot.MAIN, "")
        }
        TextProcessingPreferences(context).save(previousSettings)
        shell("settings put global animator_duration_scale $animation"); shell("cmd uimode night $night")
        shell("settings put system font_scale $fontScale")
    }
    private fun seed(): String {
        val file = repository.allocate().apply { writeText("synthetic audio, no real microphone") }
        repository.finalized(file, 18)
        repository.complete(checkNotNull(repository.beginUpload(file)), "Давайте перенесём встречу по дизайн-ревью на четверг утром.")
        return repository.record(file)!!.id
    }
    @Test fun twoVersionsCanBeCopiedAndRepeatUsesEditedPromptWithoutChangingGlobalSettings() {
        val global = TextProcessingSettings(true, prompt = "Global editing prompt")
        TextProcessingPreferences(context).save(global)
        lateinit var id: String
        instrumentation.runOnMainSync {
            id = seed(); val token = checkNotNull(repository.beginProcessing(id, global))
            repository.completeProcessing(token, "Перенесём дизайн-ревью на утро четверга.")
        }
        var calls = 0; var sentPrompt = ""
        HistoryViewModel.testProcess = { record, settings ->
            processSavedText(repository, record, settings, { "synthetic" }, TextProcessor { original, _, options ->
                calls++; sentPrompt = options.prompt
                assertEquals(repository.record(id)!!.transcript, original)
                "Обновлённый текст для этой попытки."
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_text_$id").assertTextEquals(repository.record(id)!!.processedText)
            capture("history-processed-light")
            compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick()
            scenario.onActivity { assertEquals(repository.record(id)!!.processedText,
                context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            compose.onNodeWithTag("text_version_original_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick()
            scenario.onActivity { assertEquals(repository.record(id)!!.transcript,
                context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            capture("history-original-light")
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.onNodeWithTag("processing_prompt").performScrollTo().performTextReplacement("Сделай короче, в деловом стиле.")
            capture("repeat-prompt-light")
            scenario.recreate()
            compose.onNodeWithTag("processing_prompt").performScrollTo().assertTextContains("Сделай короче, в деловом стиле.")
            compose.onNodeWithTag("confirm_processing").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 1 && repository.record(id)!!.processedText == "Обновлённый текст для этой попытки." }
            assertEquals("Сделай короче, в деловом стиле.", sentPrompt)
            assertEquals(global, TextProcessingPreferences(context).read())
            assertEquals(1, calls); assertTrue(repository.pending().isEmpty())
        }
    }
    @Test fun mainProcessingFailureOffersOriginalAndHoldCancelsOnlySecondStage() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        var response = CompletableDeferred<String>(); var calls = 0
        lateinit var model: DictationViewModel
        MainActivity.testFactory = {
            DictationViewModel { scope -> DictationSession(scope, object : AudioRecorder {
                lateinit var file: java.io.File
                override fun start(onLimit: () -> Unit) { file = repository.allocate().apply { writeText("synthetic") } }
                override fun stop() = file.also { repository.finalized(it) }
                override fun cancel() = Unit
            }, Transcriber { _, _ -> "Распознанный исходник сохранён." }, recordings = StoredRecordingRetention(repository, true),
                processingSettings = { TextProcessingSettings(true) }, textProcessor = TextProcessor { _, _, _ -> calls++; response.await() })
            }.also { model = it; it.setKey("synthetic") }
        }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("record").performClick(); compose.onNodeWithTag("stop").performScrollTo().performClick()
            compose.waitUntil(15_000) { model.state.value.phase == Phase.PROCESSING }
            capture("processing-light")
            val action = compose.onNodeWithTag("processing").fetchSemanticsNode().config[SemanticsActions.OnLongClick]
            assertNotNull(action.label)
            response.completeExceptionally(java.io.IOException("synthetic offline"))
            compose.waitUntil(15_000) { model.state.value.processingFailed }
            compose.onNodeWithTag("copy_original").performScrollTo().assertIsDisplayed().performClick()
            compose.onNodeWithTag("processing_recovery").assertExists()
            capture("processing-failure-light")
            assertEquals(1, calls); assertEquals(SavedRecordingStatus.COMPLETE, repository.records().single().status)
            assertEquals("Распознанный исходник сохранён.", repository.records().single().transcript)
            response = CompletableDeferred()
            compose.onNodeWithTag("record").performScrollTo().performClick()
            compose.onNodeWithTag("stop").performScrollTo().performClick()
            compose.waitUntil(15_000) { model.state.value.phase == Phase.PROCESSING && calls == 2 }
            compose.onNodeWithTag("processing").performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("cancel").performScrollTo().assertContentDescriptionEquals(context.getString(R.string.text_processing_cancel)).performClick()
            compose.waitUntil(15_000) { !model.state.value.audioBusy }
            assertTrue(repository.records().all { it.transcript.isNotBlank() && it.status == SavedRecordingStatus.COMPLETE })
            assertTrue(repository.records().any { it.processingStatus == TextProcessingStatus.INTERRUPTED })
            assertEquals(2, calls)
        }
    }
    @SuppressLint("RestrictedApi")
    @Test fun foldChangesAndRecreationKeepTheSameSecondRequestAndBothTexts() {
        val layouts = MutableStateFlow(WindowLayoutInfo(emptyList()))
        WindowInfoTracker.overrideDecorator(object : WindowInfoTrackerDecorator {
            override fun decorate(tracker: WindowInfoTracker) = object : WindowInfoTracker {
                override fun windowLayoutInfo(activity: Activity) = layouts
            }
        })
        val response = CompletableDeferred<String>(); var calls = 0; lateinit var model: DictationViewModel
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        MainActivity.testFactory = {
            DictationViewModel { scope -> DictationSession(scope, object : AudioRecorder {
                lateinit var file: java.io.File
                override fun start(onLimit: () -> Unit) { file = repository.allocate().apply { writeText("synthetic") } }
                override fun stop() = file.also { repository.finalized(it) }
                override fun cancel() = Unit
            }, Transcriber { _, _ -> "Original across Fold" }, recordings = StoredRecordingRetention(repository, true),
                processingSettings = { TextProcessingSettings(true) }, textProcessor = TextProcessor { _, _, _ -> calls++; response.await() })
            }.also { model = it; it.setKey("synthetic") }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("record").performClick(); compose.onNodeWithTag("stop").performScrollTo().performClick()
                compose.waitUntil(15_000) { model.state.value.phase == Phase.PROCESSING }
                val retained = model
                lateinit var bounds: Rect
                scenario.onActivity { bounds = Rect(it.window.decorView.width / 2, 0, it.window.decorView.width / 2, it.window.decorView.height) }
                repeat(2) {
                    for (half in listOf(false, true)) {
                        compose.runOnIdle { layouts.value = WindowLayoutInfo(listOf(TestFold(bounds, half))) }
                        compose.onNodeWithTag("processing").performScrollTo().assertIsDisplayed()
                        assertEquals(1, calls); assertSame(retained, model)
                    }
                }
                capture("processing-fold-emulated")
                scenario.recreate(); compose.onNodeWithTag("processing").performScrollTo().assertIsDisplayed()
                assertSame(retained, model); assertEquals(1, calls)
                response.complete("Edited across Fold")
                compose.waitUntil(15_000) { model.state.value.phase == Phase.RESULT }
                assertEquals("Original across Fold", repository.records().single().transcript)
                assertEquals("Edited across Fold", repository.records().single().processedText)
            }
        } finally { WindowInfoTracker.reset() }
    }
    @Test fun preferencesExplicitSaveAndReopenPreservesModelPromptAndDefaultOff() {
        TextProcessingPreferences(context).save(TextProcessingSettings())
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("processing_enabled").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("processing_model").performScrollTo().performTextReplacement("gpt-4.1-nano")
            compose.onNodeWithTag("processing_prompt").performScrollTo().performTextReplacement("Исправь пунктуацию, сохрани смысл.")
            assertFalse(TextProcessingPreferences(context).read().enabled)
            compose.onNodeWithTag("save_processing_settings").performScrollTo().performClick()
            scenario.recreate()
            compose.onNodeWithTag("processing_enabled").performScrollTo().assertIsOn()
            compose.onNodeWithTag("processing_model").performScrollTo().assertTextContains("gpt-4.1-nano")
            capture("settings-light")
            assertEquals("Исправь пунктуацию, сохрани смысл.", TextProcessingPreferences(context).read().prompt)
        }
    }
    @Test fun repeatDialogAndSettingsRemainReachableInNarrowLargeFontAndWideThemeLayouts() {
        // A Dialog owns a separate window. Use the real system font setting so it too receives 200% text.
        shell("settings put system font_scale 2.0")
        for (dark in listOf(false, true)) {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            var compact by mutableStateOf(false)
            var form by mutableStateOf(false)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, if (compact) 2f else 1f)) {
                        VoiceDictationTheme {
                            if (form) CompositionLocalProvider(LocalHistoryDialogRegion provides
                                if (compact) HistoryRegion(0f, 0f, 240f, 360f) else null) {
                                RepeatProcessingDialog(TextProcessingSettings(true), { form = false }, { form = false })
                            }
                            Box(if (compact) Modifier.size(240.dp, 360.dp) else Modifier.fillMaxSize()) {
                                DictationScreen(DictationState(phase = Phase.PROCESSING), {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
                                    evidenceNotice = "TEST DATA · local synthetic state")
                            }
                        }
                    }
                } }
                capture("processing-${if (dark) "dark" else "light"}")
                compose.runOnIdle { compact = true; form = true }
                compose.onNodeWithTag("confirm_processing").performScrollTo().assertIsDisplayed()
                capture("repeat-large-font-${if (dark) "dark" else "light"}")
                compose.onNodeWithTag("confirm_processing").performClick()
                compose.onNodeWithTag("processing").performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
                compose.onNodeWithTag("cancel").performScrollTo().assertIsDisplayed()
            }
        }
    }
}
