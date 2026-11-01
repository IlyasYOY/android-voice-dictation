package io.github.ilyasyoy.voicedictation

import android.content.ClipboardManager
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.*
import org.junit.Assert.*

/** Real private ledger/settings, actual Activity/sheet/player. Only audio and transcript fixtures are synthetic. */
class HistoryCapacityRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val store get() = AndroidRecordingStorage.repository(context)
    @Before fun before() { instrumentation.runOnMainSync {
        DictationSessions.prepare(context)
        store.records().forEach { store.discard(it.id) }; store.setHistoryLimit(100)
        store.saveText(RecordingTextSlot.MAIN, "")
        MainActivity.testNotice = "TEST DATA · history capacity · no OpenAI call"
        MainActivity.testFactory = { app -> DictationViewModel { scope -> DictationSessions.create(app, scope, initialMode = Mode.RECORD, mainDraft = true) } }
    } }
    @After fun after() { instrumentation.runOnMainSync {
        store.setHistoryLimit(100)
        store.records().forEach { store.discard(it.id) }
        MainActivity.testNotice = null; MainActivity.testFactory = null
    } }
    private fun seed(failed: Boolean): String {
        val file = store.allocate()
        val count = 16000 * 2
        val bytes = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
            .putShort(1).putShort(1).putInt(16000).putInt(32000).putShort(2).putShort(16).put("data".toByteArray()).putInt(count * 2)
        repeat(count) { n -> bytes.putShort((kotlin.math.sin(n * 2 * Math.PI * 220 / 16000) * 10000 * (.2 + .8 * kotlin.math.abs(kotlin.math.sin(n / 3700.0)))).toInt().toShort()) }
        file.writeBytes(bytes.array()); store.finalized(file, 2); store.retain(file)
        if (failed) store.markFailed(file, SavedRecordingFailure.NETWORK)
        else store.complete(checkNotNull(store.beginUpload(file)), "A synthetic saved result.")
        return checkNotNull(store.record(file)).id
    }
    private fun capture(name: String) {
        compose.waitForIdle()
        for (command in listOf("mkdir -p /sdcard/Download/vd009", "screencap -p /sdcard/Download/vd009/$name.png"))
            instrumentation.uiAutomation.executeShellCommand(command).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    @Test fun fullMainCanLowerLimitWithoutLossAndOpenHistoryForPlayCopyAndExplicitCleanup() {
        lateinit var success: String; lateinit var failed: String
        instrumentation.runOnMainSync { success = seed(false); failed = seed(true); store.setHistoryLimit(2) }
        val bytes = store.records().associate { it.id to store.audio(it.id).readBytes() }
        ActivityScenario.launch(MainActivity::class.java).use {
            compose.onNodeWithTag("history_capacity_notice").assertIsDisplayed()
            compose.onNodeWithTag("record").assertIsNotEnabled()
            compose.onNodeWithTag("problem").assertDoesNotExist()
            capture("01-main-full")
            compose.onNodeWithTag("capacity_open_limit").performClick()
            compose.onNodeWithTag("history_limit_input").performScrollTo().performTextReplacement("0")
            compose.onNodeWithTag("history_limit_invalid").assertExists()
            compose.onNodeWithTag("save_history_limit").assertIsNotEnabled()
            compose.onNodeWithTag("history_limit_input").performTextReplacement("1")
            compose.onNodeWithTag("save_history_limit").performScrollTo().performClick()
            assertEquals(1, store.capacity().limit); assertEquals(2, store.records().size)
            bytes.forEach { (id, expected) -> assertArrayEquals(expected, store.audio(id).readBytes()) }
            capture("02-limit-lowered-existing-kept")
            compose.onNodeWithTag("close_settings").performScrollTo().performClick()
            compose.onNodeWithTag("capacity_open_history").performClick()
            compose.onNodeWithTag("history_retry_$failed").performScrollTo().assertIsEnabled()
            compose.onNodeWithTag("history_play_$success").performScrollTo().performClick()
            compose.waitUntil(15000) { compose.onAllNodes(hasContentDescription("Pause", substring = true)).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("history_play_$success").performClick()
            compose.onNodeWithTag("history_expand_$success").performClick()
            compose.onNodeWithTag("history_copy_$success").performScrollTo().performClick()
            assertEquals("A synthetic saved result.", context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString())
            capture("03-full-history-existing-actions")
            for (id in listOf(failed, success)) {
                compose.onNodeWithTag("history_menu_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_delete_$id").performClick()
                compose.onNodeWithTag("history_confirm_delete").performClick()
            }
            assertTrue(store.records().isEmpty()); assertTrue(store.capacity().canStart)
            compose.onNodeWithTag("history_content").performSemanticsAction(SemanticsActions.Dismiss) { it() }
            compose.onNodeWithTag("record").assertIsEnabled()
            compose.onNodeWithTag("history_capacity_notice").assertDoesNotExist()
        }
    }
    @Test fun sharedHistoryCanIncreaseLimitAndSettingSurvivesActivityRecreation() {
        instrumentation.runOnMainSync { seed(true); store.setHistoryLimit(1) }
        ActivityScenario.launch(SavedRecordingsActivity::class.java).use { scenario ->
            compose.onNodeWithTag("history_capacity_notice").assertIsDisplayed()
            compose.onNodeWithTag("capacity_open_limit").performClick()
            compose.onNodeWithTag("history_limit_input").performTextReplacement("2")
            capture("04-shared-limit-editor")
            compose.onNodeWithTag("save_history_limit").performClick()
            assertEquals(2, store.capacity().limit); assertEquals(1, store.records().size)
            compose.onNodeWithTag("history_capacity_notice").assertDoesNotExist()
            scenario.recreate()
            assertEquals(2, store.capacity().limit)
            compose.onNodeWithTag("history_capacity_notice").assertDoesNotExist()
        }
    }
    @Test fun defaultSettingIsVisibleAndPersistedAfterRecreation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("history_limit_input").assertTextContains("100")
            capture("05-default-limit-100")
            compose.onNodeWithTag("history_limit_input").performTextReplacement("200")
            compose.onNodeWithTag("save_history_limit").performScrollTo().performClick()
            scenario.recreate()
            compose.onNodeWithTag("history_limit_input").assertTextContains("200")
            assertEquals(200, store.capacity().limit)
        }
    }
    @Test fun fullDoesNotPreventExplicitRetryOrDeleteOfExistingFailedAudio() {
        lateinit var id: String
        instrumentation.runOnMainSync { id = seed(true); store.setHistoryLimit(1) }
        val audio = store.audio(id).readBytes()
        val owner = RecordingRecovery.processOwned
        var calls = 0
        kotlinx.coroutines.runBlocking { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
            owner.retry(id) { calls++; "Explicit capacity-safe retry." }
        } }
        assertEquals(1, calls); assertArrayEquals(audio, store.audio(id).readBytes())
        assertEquals("Explicit capacity-safe retry.", store.record(id)?.transcript)
        assertTrue(store.capacity().full)
        instrumentation.runOnMainSync { assertTrue(owner.discard(id)) }
        assertTrue(store.capacity().canStart)
    }
}
