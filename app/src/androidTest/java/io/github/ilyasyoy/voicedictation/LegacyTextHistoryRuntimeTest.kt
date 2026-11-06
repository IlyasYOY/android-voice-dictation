package io.github.ilyasyoy.voicedictation

import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.playback.PlaybackState
import io.github.ilyasyoy.voicedictation.session.RecordingRecoveryState
import io.github.ilyasyoy.voicedictation.ui.RecordingHistory
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import java.io.File
import java.util.Properties
import java.util.UUID
import org.junit.*
import org.junit.Assert.*

class LegacyTextHistoryRuntimeTest {
    @get:Rule val compose=createEmptyComposeRule()
    @get:Rule val existingSetup:org.junit.rules.TestRule=ExistingFeatureSetupRule()
    @Test fun oldTextOpensCopiesAndDeletesWithoutPlayOrUploadAction() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"legacy-text-ui-${UUID.randomUUID()}").apply {mkdirs()}
        val id=UUID.randomUUID().toString();val directory=File(root,id).apply {mkdirs()}
        val text="Synthetic historical words"
        val p=Properties().apply {
            setProperty("format","1");setProperty("id",id);setProperty("createdAt","1");setProperty("updatedAt","2")
            setProperty("status","COMPLETE");setProperty("seconds","5");setProperty("attempt","0")
            setProperty("kind","LIVE");setProperty("partial","false");setProperty("transcript",text)
        }
        File(directory,"record.properties").outputStream().use {p.store(it,null)}
        val store=RecordingRepository(root);var rows by mutableStateOf(store.records());var copied=""
        try {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { activity -> activity.setContent { VoiceDictationTheme {
                    RecordingHistory(RecordingRecoveryState(records=rows,capacity=store.capacity()),PlaybackState(),
                        audioAvailable={false},onPlay={error("No legacy audio")},onRetry={error("No legacy upload")},
                        onDelete={assertTrue(store.discard(it));rows=store.records()},onCopy={copied=it},onClose={})
                } } }
                compose.onNodeWithTag("history_play_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_retry_$id").assertDoesNotExist()
                compose.onNodeWithTag("history_expand_$id").performScrollTo().performClick()
                compose.onNodeWithTag("history_text_$id").assertTextEquals(text)
                compose.onNodeWithTag("history_copy_$id").performScrollTo().performClick();assertEquals(text,copied)
                compose.openHistoryDelete("$id")
                compose.onNodeWithTag("history_confirm_delete").performClick()
                compose.waitForIdle();assertTrue(store.records().isEmpty());assertEquals(0,store.capacity().used)
            }
        } finally {root.deleteRecursively()}
    }
}
