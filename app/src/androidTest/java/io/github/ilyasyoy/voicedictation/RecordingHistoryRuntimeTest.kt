package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.semantics.SemanticsActions
import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.*
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.MockResponse
import java.util.concurrent.TimeUnit
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Synthetic PCM fixture, actual ledger, sheet, MediaPlayer and clipboard. No OpenAI requests. */
class RecordingHistoryRuntimeTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    private val repository get()=AndroidRecordingStorage.repository(context)
    private val ids=mutableListOf<String>()
    private val transcript="A quiet place for a thought. This is a synthetic transcript for history acceptance."
    private fun seed(status: SavedRecordingStatus, text: String=""):String {
        val store=repository
        val file=store.allocate()
        file.writeBytes(wav())
        store.finalized(file,12);store.retain(file)
        val id=checkNotNull(store.record(file)).id;ids+=id
        if(status==SavedRecordingStatus.COMPLETE) store.complete(checkNotNull(store.beginUpload(file)),text)
        if(status==SavedRecordingStatus.FAILED) store.markFailed(file,SavedRecordingFailure.NETWORK)
        return id
    }
    private fun wav():ByteArray {
        val count=16000*12
        val b=ByteBuffer.allocate(44+count*2).order(ByteOrder.LITTLE_ENDIAN)
        b.put("RIFF".toByteArray()).putInt(36+count*2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16)
            .put("data".toByteArray()).putInt(count*2)
        repeat(count) {
            val envelope = .08 + .8 * kotlin.math.abs(kotlin.math.sin(it / 8100.0)) * kotlin.math.abs(kotlin.math.sin(it / 23000.0))
            b.putShort((kotlin.math.sin(it*2*Math.PI*220/16000) * 12000 * envelope).toInt().toShort())
        }
        return b.array()
    }
    private fun android.app.Instrumentation.argumentsSuffix():String = InstrumentationRegistry.getArguments().getString("evidenceSuffix")?.let { "-$it" }.orEmpty()
    private fun capture(name:String) {
        compose.waitForIdle()
        for(cmd in listOf("mkdir -p /sdcard/Download/vd008-revision","screencap -p /sdcard/Download/vd008-revision/$name${instrumentation.argumentsSuffix()}.png"))
            instrumentation.uiAutomation.executeShellCommand(cmd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @Before fun before() { instrumentation.runOnMainSync {
        DictationSessions.prepare(context)
        // Dedicated emulator only; previous fixture history is removed explicitly.
        repository.records().forEach {repository.discard(it.id)}
        repository.saveText(RecordingTextSlot.MAIN, "")
        MainActivity.testNotice="TEST DATA · synthetic audio and transcript"
    } }
    @After fun after() { instrumentation.runOnMainSync {
        AndroidRecordingPlayback.interrupt()
        ids.forEach {repository.discard(it)}
        MainActivity.testNotice=null;MainActivity.testFactory=null
    } }
    @Test fun historyTapPlaybackCopyDeleteAndSwipeUseDurableRecords() {
        lateinit var success:String;lateinit var pending:String
        instrumentation.runOnMainSync { pending=seed(SavedRecordingStatus.PENDING);success=seed(SavedRecordingStatus.COMPLETE,transcript) }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            capture("01-main-history-entry")
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_sheet").assertExists()
            compose.onNodeWithText("History").assertDoesNotExist()
            compose.onNodeWithText("Close").assertDoesNotExist()
            compose.onNodeWithText("Transcribed").assertDoesNotExist()
            compose.onNodeWithTag("history_text_$success").assertDoesNotExist()
            compose.waitUntil(15000) { compose.onAllNodesWithTag("history_waveform_$success").fetchSemanticsNodes().isNotEmpty() }
            capture("02-history-collapsed")
            compose.onNodeWithTag("history_play_$success").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("Pause", substring=true)).fetchSemanticsNodes().isNotEmpty() }
            SystemClock.sleep(1000)
            capture("03-history-success-playing")
            compose.onNodeWithTag("history_play_$success").performClick()
            compose.onNodeWithTag("history_play_$success").assert(hasContentDescription("Play", substring=true))
            compose.onNodeWithTag("history_expand_$success").performScrollTo().performClick()
            compose.onNodeWithTag("history_text_$success").assertTextEquals(transcript)
            capture("04-history-expanded")
            compose.onNodeWithTag("history_copy_$success").performScrollTo().performClick()
            scenario.onActivity { assertEquals(transcript,it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            compose.onNodeWithTag("history_menu_$success").performScrollTo().performClick()
            compose.onNodeWithTag("history_delete_$success").performClick()
            compose.onNodeWithText("Cancel").performClick()
            assertNotNull(repository.record(success))
            compose.onNodeWithTag("history_menu_$success").performScrollTo().performClick()
            compose.onNodeWithTag("history_delete_$success").performClick()
            compose.onNodeWithTag("history_confirm_delete").performClick()
            compose.waitUntil {repository.record(success)==null}
            compose.onNodeWithTag("history_retry_$pending").performScrollTo().assertIsEnabled()
            capture("05-history-pending")
            compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.onNodeWithTag("history_sheet").assertDoesNotExist()
            compose.onNodeWithTag("history_swipe").performTouchInput { swipeUp() }
            compose.onNodeWithTag("history_sheet").assertExists()
        }
        assertNotNull(repository.record(pending))
    }
    @Test fun deleteSynchronizesMainBeforeBackgroundAndRecreationWithoutClosingHistory() {
        lateinit var id:String
        lateinit var model:DictationViewModel
        instrumentation.runOnMainSync {
            id=seed(SavedRecordingStatus.COMPLETE,transcript)
            repository.saveText(RecordingTextSlot.MAIN,transcript,id)
            MainActivity.testFactory={ app -> DictationViewModel { scope ->
                DictationSessions.create(app,scope,mainDraft=true)
            }.also {model=it} }
        }
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_menu_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_delete_$id").performClick()
            compose.onNodeWithTag("history_confirm_delete").performClick()
            compose.runOnIdle {assertEquals("",model.state.value.text)}
            compose.onNodeWithTag("history_sheet").assertExists()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.recreate()
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            assertNull(repository.record(id))
            assertEquals("",repository.savedText(RecordingTextSlot.MAIN))
            compose.runOnIdle {assertEquals("",model.state.value.text)}
            compose.onNodeWithTag("history_sheet").assertExists()
        }
    }
    @Test fun explicitRetryIsSingleFlightAndKeepsAudioAndTranscript() {
        val server=MockWebServer()
        val release = java.util.concurrent.CountDownLatch(1)
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                release.await(40, TimeUnit.SECONDS)
                return MockResponse().setBody("{\"text\":\"A synthetic retry result.\"}")
            }
        }
        server.start()
        val key=AndroidApiKeyStore(context,".test.crossapp")
        runBlocking {key.save("synthetic-history-fixture")}
        context.getSharedPreferences("floating_fixture",0).edit().putBoolean("enabled",true).putInt("port",server.port).commit()
        lateinit var id:String
        instrumentation.runOnMainSync {id=seed(SavedRecordingStatus.FAILED)}
        val bytes=repository.audio(id).readBytes()
        try {
            ActivityScenario.launch(SavedRecordingsActivity::class.java).use {
                compose.onNodeWithText("Transcription failed").assertDoesNotExist()
                compose.onNodeWithTag("history_expand_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_retry_$id").performScrollTo().assert(hasContentDescription("Retry transcription"))
                capture("12-history-failed-retry")
                compose.onNodeWithTag("history_retry_$id").performClick()
                compose.waitUntil(15000) {repository.record(id)?.status==SavedRecordingStatus.UPLOADING}
                compose.onNodeWithTag("history_retry_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_busy_$id").assertIsNotEnabled()
                compose.onNodeWithTag("history_menu_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_delete_$id").assertIsNotEnabled()
                androidx.test.uiautomator.UiDevice.getInstance(instrumentation).pressBack()
                compose.waitUntil(15000) {server.requestCount==1}
                capture("07-history-processing")
                release.countDown()
                compose.waitUntil(20000) {repository.record(id)?.status==SavedRecordingStatus.COMPLETE}
                assertEquals(1,server.requestCount)
                assertEquals("A synthetic retry result.",repository.record(id)?.transcript)
                assertArrayEquals(bytes,repository.audio(id).readBytes())
                compose.onNodeWithTag("history_retry_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_text_$id").assertTextEquals("A synthetic retry result.")
                capture("08-history-explicit-retry-success")
                assertEquals("Opening a saved transcript must not upload",1,server.requestCount)
            }
        } finally {
            release.countDown()
            context.getSharedPreferences("floating_fixture",0).edit().clear().commit()
            runBlocking {key.forget()};server.shutdown()
        }
    }
    @Test fun navigationAndMicrophoneInterruptionReleaseActualPlayer() {
        lateinit var id:String
        instrumentation.runOnMainSync {id=seed(SavedRecordingStatus.COMPLETE,transcript)}
        val player=AndroidRecordingPlayback.create(context)
        instrumentation.runOnMainSync {AndroidRecordingPlayback.claim(player);player.toggle(id,repository.audio(id))}
        compose.waitUntil(15000) {player.state.value.playing}
        compose.waitUntil(15000) {
            instrumentation.runOnMainSync {player.tick()}
            player.state.value.position>0
        }
        instrumentation.runOnMainSync {AndroidRecordingPlayback.interrupt()}
        assertNull(player.state.value.id)
        ActivityScenario.launch(SavedRecordingsActivity::class.java).use { scenario ->
            compose.onNodeWithTag("history_play_$id").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("Pause", substring=true)).fetchSemanticsNodes().isNotEmpty() }
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("history_play_$id").performScrollTo().assert(hasContentDescription("Play", substring=true))
        }
    }
    @Test fun longTextAndLargeFontActionsRemainReachableWithCollapsedPreview() {
        val id="large-font-fixture"
        val text="Synthetic long transcript. ".repeat(70)
        val row=SavedRecording(id,1700000000000,1700000000000,SavedRecordingStatus.COMPLETE,12,text)
        ActivityScenario.launch(MainActivity::class.java).use {scenario ->
            for(font in listOf(1f,2f)) {
                var copied="";var deleted=false
                scenario.onActivity {activity ->activity.setContent {
                    val density=LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density,font)) {VoiceDictationTheme { key(font) {
                        Box(Modifier.width(280.dp).height(480.dp)) {
                            RecordingHistory(RecordingRecoveryState(records=listOf(row)),PlaybackState(),{true},{},{},{deleted=true},{copied=it},{})
                        }
                    }}}
                }}
                compose.onNodeWithTag("history_play_$id").performScrollTo()
                val play = compose.onNodeWithTag("history_play_$id").fetchSemanticsNode().boundsInRoot
                val wave = compose.onNodeWithTag("history_waveform_pending_$id").fetchSemanticsNode().boundsInRoot
                val action = compose.onNodeWithTag("history_expand_$id").fetchSemanticsNode().boundsInRoot
                assertEquals(play.center.y, wave.center.y, 1f)
                assertEquals(play.center.y, action.center.y, 1f)
                assertEquals(play.width, action.width, 1f)
                capture("13-history-aligned-collapsed-font-$font")
                compose.onNodeWithTag("history_expand_$id").performClick()
                compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick()
                assertEquals(text,copied)
                capture("06-history-long-text-font-$font")
                compose.onNodeWithTag("history_menu_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_delete_$id").performClick()
                compose.onNodeWithTag("history_confirm_delete").performClick();assertTrue(deleted)
            }
        }
    }

    @Test fun compactRetryReplacesToggleAtNormalAndLargeFontAndRetainsReceivedText() {
        val id = "compact-retry-fixture"
        val row = SavedRecording(id, 1700000000000, 1700000000000, SavedRecordingStatus.FAILED, 12, "")
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for (font in listOf(1f, 2f)) {
                var retried = 0
                var received by mutableStateOf(false)
                scenario.onActivity { activity -> activity.setContent {
                    val density = LocalDensity.current.density
                    CompositionLocalProvider(LocalDensity provides Density(density, font)) { VoiceDictationTheme { key(font) {
                        Box(Modifier.width(280.dp).height(480.dp)) {
                            RecordingHistory(RecordingRecoveryState(records = listOf(row),
                                receivedTexts = if (received) mapOf(id to "Already received synthetic words") else emptyMap()),
                                PlaybackState(), { true }, {}, { retried++ }, {}, {}, {})
                        }
                    } } }
                } }
                compose.onNodeWithTag("history_retry_$id").performScrollTo().assertIsEnabled()
                compose.onNodeWithTag("history_expand_$id").assertDoesNotExist()
                compose.onNodeWithText("Transcription failed").assertDoesNotExist()
                compose.onNodeWithText("Retry upload (may cost again)").assertDoesNotExist()
                val play = compose.onNodeWithTag("history_play_$id").fetchSemanticsNode().boundsInRoot
                val wave = compose.onNodeWithTag("history_waveform_pending_$id").fetchSemanticsNode().boundsInRoot
                val retry = compose.onNodeWithTag("history_retry_$id").fetchSemanticsNode().boundsInRoot
                assertEquals(play.center.y, wave.center.y, 1f)
                assertEquals(play.center.y, retry.center.y, 1f)
                assertEquals(play.width, retry.width, 1f)
                capture("14-history-failed-retry-font-$font")
                compose.onNodeWithTag("history_retry_$id").performClick();compose.runOnIdle { assertEquals(1, retried) }
                compose.runOnIdle { received = true }
                compose.onNodeWithTag("history_text_$id").assertTextEquals("Already received synthetic words")
                compose.onNodeWithTag("history_retry_$id").assert(hasContentDescription(context.getString(R.string.saved_save_received)))
            }
        }
    }

    @Test fun historyDismissesByBackDownwardGestureAndAccessibleAction() {
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_sheet").assertIsDisplayed()
            compose.waitForIdle()
            val device = androidx.test.uiautomator.UiDevice.getInstance(instrumentation)
            device.waitForIdle(); device.pressBack()
            compose.onNodeWithTag("history_sheet").assertDoesNotExist()
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_sheet").performTouchInput { swipeDown() }
            compose.waitUntil(10000) { compose.onAllNodesWithTag("history_sheet").fetchSemanticsNodes().isEmpty() }
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.onNodeWithTag("history_sheet").assertDoesNotExist()
        }
    }

    @Test fun waveformReadsActualLocalPcmWithoutChangingAudio() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed(SavedRecordingStatus.PENDING) }
        val file = repository.audio(id)
        val before = file.readBytes()
        val peaks = runBlocking { AndroidAudioWaveform.read(file) }
        assertNotNull("A decodable local recording has a sampled waveform", peaks)
        assertEquals(40, peaks!!.size)
        assertTrue(peaks.maxOrNull()!! > .9f)
        assertTrue(peaks.minOrNull()!! < .5f)
        assertArrayEquals(before, file.readBytes())
        val invalid = File(context.cacheDir, "invalid-waveform-fixture").apply { writeText("not audio") }
        try { assertNull(runBlocking { AndroidAudioWaveform.read(invalid) }) } finally { invalid.delete() }
    }

    @Test fun linkedReplacementCannotReappearAfterDeleteBackgroundAndReopen() {
        lateinit var id: String
        lateinit var model: DictationViewModel
        instrumentation.runOnMainSync {
            id = seed(SavedRecordingStatus.COMPLETE, transcript)
            repository.saveText(RecordingTextSlot.MAIN, "Previously edited words", id)
            MainActivity.testFactory = { app -> DictationViewModel { scope ->
                DictationSessions.create(app, scope, mainDraft = true)
            }.also { model = it } }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("transcript").assertDoesNotExist()
            compose.onNodeWithTag("copy").assertDoesNotExist()
            compose.onNodeWithTag("clear").assertDoesNotExist()
            compose.onNodeWithTag("add_key").assertDoesNotExist()
            compose.onNodeWithTag("floating_setup").assertDoesNotExist()
            compose.onNodeWithText("Swipe up for history").assertDoesNotExist()
            capture("09-main-with-saved-result")
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_draft").assertDoesNotExist()
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").assertTextEquals("Previously edited words")
            compose.onNodeWithTag("transcript").performTextReplacement("Edited in the message")
            compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick()
            scenario.onActivity { assertEquals("Edited in the message", it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
            assertEquals("Edited in the message", repository.savedText(RecordingTextSlot.MAIN))
            // A temporarily empty edit remains linked to the message that owns it.
            compose.onNodeWithTag("transcript").performScrollTo().performTextReplacement("")
            compose.onNodeWithTag("transcript").performTextReplacement("Replacement in the same message")
            assertEquals(id, repository.savedDraft(RecordingTextSlot.MAIN).sourceId)
            scenario.recreate()
            compose.onNodeWithTag("history_draft").assertDoesNotExist()
            if (compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").assertTextEquals("Replacement in the same message")
            capture("10-linked-text-after-recreation")
            compose.onNodeWithTag("history_menu_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_delete_$id").performClick()
            compose.onNodeWithTag("history_confirm_delete").performClick()
            compose.runOnIdle { assertEquals("", model.state.value.text) }
            assertEquals("", repository.savedText(RecordingTextSlot.MAIN))
            scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
            scenario.recreate();scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
            compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_draft").assertDoesNotExist()
            compose.onNodeWithText("Replacement in the same message").assertDoesNotExist()
            assertNull(repository.record(id))
        }
    }

    @Test fun liveTextAndItsEditedDraftOpenInSharedHistoryWithoutFakeAudio() {
        lateinit var id: String
        instrumentation.runOnMainSync {
            id = checkNotNull(repository.liveText(null, "Synthetic live words", false, 5))
            ids += id
            repository.saveText(RecordingTextSlot.MAIN, "Edited synthetic live words", id)
        }
        ActivityScenario.launch(SavedRecordingsActivity::class.java).use {
            compose.onNodeWithTag("history_play_$id").assertDoesNotExist()
            compose.onNodeWithTag("history_text_$id").assertDoesNotExist()
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_text_$id").assertTextEquals("Edited synthetic live words")
            capture("11-live-text-message")
            compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
            compose.onNodeWithTag("history_text_$id").assertDoesNotExist()
        }
    }

    @Test fun unlinkedDraftRemainsEditableAndClearDoesNotDeleteRecordings() {
        lateinit var model: DictationViewModel
        lateinit var id: String
        instrumentation.runOnMainSync {
            id = seed(SavedRecordingStatus.COMPLETE, transcript)
            repository.saveText(RecordingTextSlot.MAIN, "Unlinked synthetic text")
            MainActivity.testFactory = { app -> DictationViewModel { scope ->
                DictationSessions.create(app, scope, mainDraft = true)
            }.also { model = it } }
        }
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("saved_recordings").performClick()
            compose.onNodeWithTag("history_expand_draft").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").performTextReplacement("Edited unlinked synthetic text")
            scenario.recreate()
            if (compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isEmpty())
                compose.onNodeWithTag("history_expand_draft").performScrollTo().performClick()
            compose.onNodeWithTag("transcript").assertTextEquals("Edited unlinked synthetic text")
            capture("12-unlinked-text-after-recreation")
            compose.onNodeWithTag("history_menu_draft").performScrollTo().performClick()
            compose.onNodeWithTag("clear").performClick()
            compose.runOnIdle { assertEquals("", model.state.value.text) }
            assertEquals("", repository.savedText(RecordingTextSlot.MAIN))
            assertEquals(transcript, repository.record(id)?.transcript)
        }
    }

    @Test fun failedDraftWriteRemainsCopyableAfterHistoryDismissAndReopen() {
        var failDraft = false
        val root = File(context.cacheDir, "history-draft-failure-${java.util.UUID.randomUUID()}")
        val store = RecordingRepository(root, commit = RecordingCommit { target, values ->
            if (failDraft && target.name == "main-text.properties") throw java.io.IOException("synthetic draft failure")
            AtomicRecordingCommit.write(target, values)
        })
        store.saveText(RecordingTextSlot.MAIN, "Saved synthetic draft")
        lateinit var model: DictationViewModel
        instrumentation.runOnMainSync {
            MainActivity.testFactory = { _ -> DictationViewModel { scope ->
                DictationSession(scope, object : AudioRecorder {
                    override fun start(onLimit: () -> Unit) { error("No recording in this fixture") }
                    override fun stop(): File = error("No recording in this fixture")
                    override fun cancel() = Unit
                }, Transcriber { _, _ -> error("No HTTP in this fixture") }, recordings = StoredRecordingRetention(store, true))
            }.also { model = it } }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                compose.onNodeWithTag("saved_recordings").performClick()
                compose.onNodeWithTag("history_expand_draft").performScrollTo().performClick()
                scenario.onActivity { failDraft = true }
                compose.onNodeWithTag("transcript").performTextReplacement("Unsaved synthetic words remain copyable")
                assertEquals("Saved synthetic draft", store.savedText(RecordingTextSlot.MAIN))
                compose.onNodeWithTag("history_problem").assertTextEquals(context.getString(R.string.error))
                compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
                compose.onNodeWithTag("saved_recordings").performClick()
                compose.onNodeWithTag("history_expand_draft").performScrollTo().performClick()
                compose.onNodeWithTag("transcript").assertTextEquals("Unsaved synthetic words remain copyable")
                scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
                scenario.recreate();scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
                if (compose.onAllNodesWithTag("transcript").fetchSemanticsNodes().isEmpty())
                    compose.onNodeWithTag("history_expand_draft").performScrollTo().performClick()
                compose.onNodeWithTag("transcript").assertTextEquals("Unsaved synthetic words remain copyable")
                compose.onNodeWithTag("history_problem").assertTextEquals(context.getString(R.string.error))
                compose.onNodeWithTag("copy").performScrollTo().performClick()
                scenario.onActivity { assertEquals(model.state.value.text, it.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()) }
                capture("13-unsaved-draft-remains-copyable")
                scenario.onActivity { failDraft = false; model.edit("Recovered synthetic draft") }
                assertEquals("Recovered synthetic draft", store.savedText(RecordingTextSlot.MAIN))
                compose.onNodeWithTag("history_problem").assertDoesNotExist()
            }
        } finally { root.deleteRecursively() }
    }
}
