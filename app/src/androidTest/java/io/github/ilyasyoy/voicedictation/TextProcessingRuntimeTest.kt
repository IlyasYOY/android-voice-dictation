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
import io.github.ilyasyoy.voicedictation.playback.PlaybackState
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
        // Include popup enter transitions before taking evidence, rather than an initial transparent frame.
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle(); shell("mkdir -p /sdcard/Download/postprocessing")
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
            TextProcessingSettingsViewModel.testModelList = null
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
    @Test fun repeatStartsImmediatelyWithCurrentGlobalSettingsAndBothVersionsStayCopyable() {
        val global = TextProcessingSettings(false, "gpt-6-luna", "Current global prompt")
        TextProcessingPreferences(context).save(global)
        lateinit var id: String
        instrumentation.runOnMainSync {
            id = seed(); val token = checkNotNull(repository.beginProcessing(id, global.copy(model = "gpt-4.1", prompt = "Old row prompt")))
            repository.completeProcessing(token, "Перенесём дизайн-ревью на утро четверга.")
        }
        var calls = 0; var sent: TextProcessingSettings? = null
        val response = CompletableDeferred<String>()
        HistoryViewModel.testProcess = { record, settings ->
            processSavedText(repository, record, settings, { "synthetic" }, TextProcessor { original, _, options ->
                calls++; sent = options
                assertEquals(repository.record(id)!!.transcript, original)
                if (calls == 1) response.await() else "Вторая обработка с новыми настройками."
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
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 1 && repository.record(id)!!.processingStatus == TextProcessingStatus.PROCESSING }
            compose.onNodeWithTag("confirm_processing").assertDoesNotExist()
            compose.onNodeWithTag("processing_prompt").assertDoesNotExist()
            compose.onNodeWithTag("process_text_$id").assertIsNotEnabled()
            assertEquals(global.copy(enabled = true), sent)
            scenario.recreate(); assertEquals(1, calls)
            response.complete("Обновлённый текст для этой попытки.")
            compose.waitUntil(15_000) { repository.record(id)!!.processedText == "Обновлённый текст для этой попытки." }
            assertEquals(global, TextProcessingPreferences(context).read())
            val next = global.copy(model = "gpt-6.1-sol", prompt = "New global prompt")
            TextProcessingPreferences(context).save(next)
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 2 && repository.record(id)!!.processingStatus == TextProcessingStatus.COMPLETE }
            assertEquals(next.copy(enabled = true), sent); assertEquals(next, TextProcessingPreferences(context).read())
            assertTrue(repository.pending().isEmpty()); capture("history-direct-repeat-light")
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
            compose.onNodeWithTag("problem").assertTextEquals("Error")
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
    @Test fun dropdownRefreshAndExplicitSaveKeepTheChoiceAcrossErrorsMissingModelsAndRecreation() {
        TextProcessingPreferences(context).save(TextProcessingSettings())
        var calls = 0; var forbidden = true
        TextProcessingSettingsViewModel.testModelList = {
            calls++; if (forbidden) throw ApiFailure(403) else listOf("gpt-6-luna", "gpt-transcribe", "unsupported-model")
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("open_settings").performClick()
            assertEquals(0, calls)
            compose.onNodeWithTag("processing_enabled").performScrollTo().assertIsOff().performClick()
            compose.onNodeWithTag("processing_model").performScrollTo()
                .assert(SemanticsMatcher.keyNotDefined(SemanticsActions.SetText)).performClick()
            capture("models-dropdown-light")
            compose.onNodeWithTag("processing_model_option_gpt-6.1-sol").performClick()
            compose.onNodeWithTag("processing_prompt").performScrollTo().performTextReplacement("Исправь пунктуацию, сохрани смысл.")
            assertFalse(TextProcessingPreferences(context).read().enabled)
            compose.onNodeWithTag("save_processing_settings").performScrollTo().performClick()
            compose.onNodeWithTag("refresh_processing_models").performScrollTo().performClick()
            compose.onNodeWithTag("models_problem").performScrollTo().assertIsDisplayed()
            assertEquals(1, calls); assertEquals("gpt-6.1-sol", TextProcessingPreferences(context).read().model)
            forbidden = false
            compose.onNodeWithTag("refresh_processing_models").performScrollTo().performClick()
            compose.onNodeWithTag("models_status").performScrollTo().assertTextEquals(context.getString(R.string.text_models_missing))
            assertEquals(2, calls); assertEquals("gpt-6.1-sol", TextProcessingPreferences(context).read().model)
            scenario.recreate()
            compose.onNodeWithTag("processing_enabled").performScrollTo().assertIsOn()
            compose.onNodeWithTag("processing_model").performScrollTo().assertContentDescriptionEquals("Text model: GPT-6.1 Sol")
            assertEquals(2, calls); capture("settings-light")
            shell("cmd uimode night yes")
            compose.onNodeWithTag("processing_model").performScrollTo().performClick()
            compose.onNodeWithTag("processing_model_option_gpt-6.1-sol").assertExists()
            compose.onNodeWithTag("processing_model_option_gpt-6-luna").assertExists()
            compose.onNodeWithTag("processing_model_option_unsupported-model").assertDoesNotExist()
            capture("models-dropdown-dark")
            assertEquals("Исправь пунктуацию, сохрани смысл.", TextProcessingPreferences(context).read().prompt)
        }
    }
    @Test fun linkedEditsRemainAccessibleAfterRepeatCancellationFailureAndSuccess() {
        lateinit var id: String
        val edits = "My retained manual edits"
        instrumentation.runOnMainSync {
            id = seed(); repository.saveText(RecordingTextSlot.MAIN, edits, id)
            MainActivity.testFactory = { app -> DictationViewModel { scope -> DictationSessions.create(app, scope, mainDraft = true) } }
        }
        var calls = 0; var response = CompletableDeferred<String>()
        HistoryViewModel.testProcess = { record, settings ->
            processSavedText(repository, record, settings, { "synthetic" }, TextProcessor { source, _, _ ->
                assertEquals(repository.record(id)!!.transcript, source); calls++; response.await()
            })
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").assertTextEquals(edits)
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 1 && repository.record(id)!!.processingStatus == TextProcessingStatus.PROCESSING }
            compose.onNodeWithTag("text_version_edits_$id").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").performScrollTo().assertTextEquals(edits)
            compose.onNodeWithTag("cancel_processing_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { repository.record(id)!!.processingStatus == TextProcessingStatus.INTERRUPTED }
            scenario.recreate()
            compose.onNodeWithTag("transcript").performScrollTo().assertTextEquals(edits)
            compose.onNodeWithTag("history_copy_$id").performScrollTo()
                .assertContentDescriptionEquals(context.getString(R.string.text_copy_edits)).performClick()
            scenario.onActivity { assertEquals(edits, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            response = CompletableDeferred()
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 2 }
            response.completeExceptionally(java.io.IOException("synthetic offline"))
            compose.waitUntil(15_000) { repository.record(id)!!.processingStatus == TextProcessingStatus.FAILED }
            compose.onNodeWithTag("transcript").performScrollTo().assertTextEquals(edits)
            response = CompletableDeferred()
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 3 }; response.complete("New processed version")
            compose.waitUntil(15_000) { repository.record(id)!!.processingStatus == TextProcessingStatus.COMPLETE }
            compose.onNodeWithTag("history_text_$id").performScrollTo().assertTextEquals("New processed version")
            for ((version, expected) in listOf("original" to repository.record(id)!!.transcript,
                "processed" to "New processed version", "edits" to edits)) {
                compose.onNodeWithTag("text_version_${version}_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick()
                scenario.onActivity { assertEquals(expected, context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            }
            compose.onNodeWithTag("transcript").performScrollTo().assertTextEquals(edits)
            capture("history-retained-edits-light")
            assertEquals(edits, repository.savedText(RecordingTextSlot.MAIN))
            assertEquals(3, calls)
        }
    }

    @Test fun automaticRequestHasNoIneffectiveHistoryCancelAndStillCancelsFromBlob() {
        instrumentation.uiAutomation.grantRuntimePermission(context.packageName, Manifest.permission.RECORD_AUDIO)
        val response = CompletableDeferred<String>(); lateinit var model: DictationViewModel
        MainActivity.testFactory = {
            DictationViewModel { scope -> DictationSession(scope, object : AudioRecorder {
                lateinit var file: java.io.File
                override fun start(onLimit: () -> Unit) { file = repository.allocate().apply { writeText("synthetic") } }
                override fun stop() = file.also { repository.finalized(it) }
                override fun cancel() = Unit
            }, Transcriber { _, _ -> "Original retained on cancellation" }, recordings = StoredRecordingRetention(repository, true),
                processingSettings = { TextProcessingSettings(true) }, textProcessor = TextProcessor { _, _, _ -> response.await() })
            }.also { model = it; it.setKey("synthetic") }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("record").performClick(); compose.onNodeWithTag("stop").performScrollTo().performClick()
            compose.waitUntil(15_000) { model.state.value.phase == Phase.PROCESSING }
            val id = repository.records().single().id
            // The compact drawer is disabled during automatic work. Exercise the same real
            // history presentation as a wide pane without replacing its session/request owner.
            scenario.onActivity { it.setContent { VoiceDictationTheme {
                val recovery by RecordingRecovery.processOwned.state.collectAsState()
                val controller: HistoryViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                HistoryPresentation(true, true, {}, recovery, controller, rememberHistoryUiState())
            } } }
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_processing_$id").assertExists()
            compose.onNodeWithTag("cancel_processing_$id").assertDoesNotExist()
            scenario.recreate()
            assertEquals(Phase.PROCESSING, model.state.value.phase)
            compose.onNodeWithTag("processing").performScrollTo().performSemanticsAction(SemanticsActions.OnLongClick) { it() }
            compose.onNodeWithTag("cancel").performScrollTo().performClick()
            compose.waitUntil(15_000) { !model.state.value.audioBusy }
            assertEquals(TextProcessingStatus.INTERRUPTED, repository.record(id)!!.processingStatus)
            assertEquals("Original retained on cancellation", repository.record(id)!!.transcript)
        }
    }

    @Test fun anotherHistoryActivityDoesNotAdvertiseCancellationForTheFirstOwnersRequest() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed() }
        val response = CompletableDeferred<String>(); var calls = 0
        HistoryViewModel.testProcess = { record, settings ->
            processSavedText(repository, record, settings, { "synthetic" }, TextProcessor { _, _, _ -> calls++; response.await() })
        }
        ActivityScenario.launch(MainActivity::class.java).use { main ->
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("process_text_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { calls == 1 }
            // ActivityScenario's invoker adds CLEAR_TASK even to supplied intents. Launch through
            // the real first Activity so its ViewModel and in-flight request remain alive.
            main.onActivity { it.startActivity(android.content.Intent(it, SavedRecordingsActivity::class.java)) }
            compose.waitUntil(15_000) {
                var ready = false
                instrumentation.runOnMainSync {
                    ready = androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED).any { it is SavedRecordingsActivity }
                }
                ready
            }
            try {
                assertEquals(TextProcessingStatus.PROCESSING, repository.record(id)!!.processingStatus)
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_processing_$id").assertExists()
                compose.onNodeWithTag("cancel_processing_$id").assertDoesNotExist()
                assertEquals(TextProcessingStatus.PROCESSING, repository.record(id)!!.processingStatus)
            } finally {
                instrumentation.runOnMainSync {
                    androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED)
                        .filterIsInstance<SavedRecordingsActivity>().forEach { it.finish() }
                }
                instrumentation.waitForIdleSync()
            }
            compose.onNodeWithTag("cancel_processing_$id").performScrollTo().performClick()
            compose.waitUntil(15_000) { repository.record(id)!!.processingStatus == TextProcessingStatus.INTERRUPTED }
            assertEquals(1, calls)
        }
    }

    @Test fun latestLinkedMessageHasNoOverflowAndCopiesOnlyTheSelectedVersion() {
        lateinit var id: String
        lateinit var model: DictationViewModel
        val original = "Давайте перенесём встречу по дизайн-ревью на четверг утром."
        val processed = "Перенесём дизайн-ревью на утро четверга."
        instrumentation.runOnMainSync {
            seed(); seed(); id = seed()
            val token = checkNotNull(repository.beginProcessing(id, TextProcessingSettings(true)))
            check(repository.completeProcessing(token, processed))
            repository.saveText(RecordingTextSlot.MAIN, processed, id)
            MainActivity.testFactory = { app -> DictationViewModel { scope ->
                DictationSessions.create(app, scope, mainDraft = true)
            }.also { model = it } }
        }
        for (dark in listOf(false, true)) {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                assertEquals(id, model.draftSourceId())
                assertEquals(processed, model.state.value.text)
                compose.onNodeWithTag("saved_recordings").performClick()
                compose.onNodeWithTag("history_draft").assertDoesNotExist()
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_menu_$id").assertDoesNotExist()
                compose.onNodeWithText("Clear current text").assertDoesNotExist()
                compose.onNodeWithText("Current text").assertDoesNotExist()
                compose.onNodeWithTag("history_copy_$id").performScrollTo()
                    .assertContentDescriptionEquals(context.getString(R.string.text_processing_copy_processed)).performClick()
                scenario.onActivity { assertEquals(processed, context.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()) }
                compose.onNodeWithTag("history_delete_$id").performScrollTo().assertIsDisplayed()
                capture("history-linked-processed-${if (dark) "dark" else "light"}")
                compose.onNodeWithTag("text_version_original_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_copy_$id").performScrollTo()
                    .assertContentDescriptionEquals(context.getString(R.string.text_processing_copy_original)).performClick()
                scenario.onActivity { assertEquals(original, context.getSystemService(ClipboardManager::class.java)
                    .primaryClip!!.getItemAt(0).text.toString()) }
                compose.onNodeWithTag("history_delete_$id").performScrollTo().performClick()
                compose.onNodeWithText(context.getString(android.R.string.cancel)).performClick()
                assertEquals(processed, repository.record(id)!!.processedText)
                assertEquals(original, repository.record(id)!!.transcript)
                assertEquals(processed, repository.savedText(RecordingTextSlot.MAIN))
                scenario.recreate()
                compose.onNodeWithTag("history_menu_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
                capture("history-collapsed-${if (dark) "dark" else "light"}")
            }
        }
    }

    @Test fun footerKeepsTimeAndDeleteApartAtNarrowWidthsAndLargeFontOnBothThemes() {
        val id = "synthetic-footer"
        for (dark in listOf(false, true)) {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            var font by mutableFloatStateOf(1f)
            var width by mutableIntStateOf(320)
            val row = SavedRecording(id, 1, 1, SavedRecordingStatus.PENDING, 28)
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, font)) { VoiceDictationTheme {
                        Box(Modifier.width(width.dp).fillMaxHeight()) {
                            RecordingHistory(RecordingRecoveryState(records = listOf(row)), PlaybackState(),
                                { true }, {}, {}, {}, {}, {})
                        }
                    } }
                } }
                for (w in listOf(320, 240)) for (scale in listOf(1f, 2f)) {
                    compose.runOnIdle { width = w; font = scale }
                    val delete = compose.onNodeWithTag("history_delete_$id").performScrollTo().assertIsDisplayed()
                    val time = compose.onNodeWithTag("history_time_$id").assertIsDisplayed()
                    val d = delete.getUnclippedBoundsInRoot(); val t = time.getUnclippedBoundsInRoot()
                    assertTrue("Time needs space before Delete: $t / $d", d.left - t.right >= 16.dp)
                    assertTrue(d.right - d.left >= 48.dp && d.bottom - d.top >= 48.dp)
                    delete.assertContentDescriptionEquals(
                        java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT)
                            .format(java.util.Date(1)) + ", " + context.getString(R.string.history_delete))
                    compose.onNodeWithTag("history_menu_$id").assertDoesNotExist()
                    if (w == 240 && scale == 2f) capture("history-footer-large-font-${if (dark) "dark" else "light"}")
                }
            }
        }
    }

    @Test fun alignedIconActionsAndDirectDeleteStayAccessibleAtLargeFontOnBothThemes() {
        val id = "synthetic-action-row"
        for (dark in listOf(false, true)) {
            shell("cmd uimode night ${if (dark) "yes" else "no"}")
            var compact by mutableStateOf(false)
            var calls = 0; var deletes = 0; var copied = ""
            var rows by mutableStateOf(listOf(SavedRecording(id, 1, 1, SavedRecordingStatus.COMPLETE,
                transcript = "Recognized synthetic text", processedText = "Processed synthetic text",
                processingStatus = TextProcessingStatus.FAILED)))
            val ui = HistoryUiState().apply { expanded.add(id) }
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { it.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, if (compact) 2f else 1f)) {
                        VoiceDictationTheme {
                            Box(if (compact) Modifier.size(240.dp,360.dp) else Modifier.fillMaxSize()) {
                                RecordingHistory(RecordingRecoveryState(records = rows), PlaybackState(), { false }, {}, {},
                                    { deletes++; rows = emptyList() }, { copied = it }, {}, ui = ui, onProcess = { calls++ })
                            }
                        }
                    }
                } }
                compose.onNodeWithTag("history_menu_$id").assertDoesNotExist()
                val copy = compose.onNodeWithTag("history_copy_$id").performScrollTo().assertIsDisplayed()
                val process = compose.onNodeWithTag("process_text_$id").assertIsDisplayed()
                val c = copy.getUnclippedBoundsInRoot(); val p = process.getUnclippedBoundsInRoot()
                assertEquals(c.top.value, p.top.value, 1f); assertTrue(c.right <= p.left)
                assertTrue(c.right - c.left >= 48.dp && p.right - p.left >= 48.dp)
                copy.assertContentDescriptionEquals(context.getString(R.string.text_processing_copy_processed)).performClick()
                assertEquals("Processed synthetic text", copied)
                process.assertContentDescriptionEquals(context.getString(R.string.text_processing_repeat)).performClick(); assertEquals(1, calls)
                compose.onNodeWithText(context.getString(R.string.text_processing_failed)).assertTextEquals("Error")
                capture("history-actions-${if (dark) "dark" else "light"}")
                compose.runOnIdle { compact = true }
                compose.onNodeWithTag("history_copy_$id").performScrollTo().assertIsDisplayed()
                compose.onNodeWithTag("process_text_$id").assertIsDisplayed()
                capture("history-actions-large-font-${if (dark) "dark" else "light"}")
                compose.onNodeWithTag("history_delete_$id").performScrollTo().performClick()
                assertEquals(0, deletes)
                compose.onNodeWithTag("history_confirm_delete").performScrollTo().performClick()
                compose.waitForIdle(); assertEquals(1, deletes)
            }
        }
    }
}
