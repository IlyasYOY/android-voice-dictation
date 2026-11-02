package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.Problem
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.*
import org.junit.Assert.*

class SettingsStorageFailureTest {
    @get:org.junit.Rule val existingFeatureSetup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    @Test fun saveAndForgetFailuresStayVisibleInSettings() {
        val saves = AtomicInteger(); val forgets = AtomicInteger()
        val store = object : ApiKeyStore {
            override suspend fun load() = "synthetic-original-key"
            override suspend fun save(key: String) { saves.incrementAndGet(); throw IOException("Synthetic storage failure") }
            override suspend fun forget() { forgets.incrementAndGet(); throw IOException("Synthetic storage failure") }
        }
        lateinit var model: DictationViewModel
        MainActivity.testNotice = "STORAGE ERROR TEST · synthetic key · no API call"
        MainActivity.testFactory = { DictationViewModel(AndroidAudioRecorder(it), Transcriber { _, _ -> error("No network expected") }, keyStore=store).also { m -> model=m } }
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        fun capture(name: String) {
            compose.waitForIdle()
            for (command in listOf("mkdir -p /sdcard/Download/soft-current-evidence", "screencap -p /sdcard/Download/soft-current-evidence/$name.png")) {
                instrumentation.uiAutomation.executeShellCommand(command).use { android.os.ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
            }
        }
        try {
            ActivityScenario.launch(MainActivity::class.java).use {
                compose.waitUntil(30_000) { !model.state.value.keyBusy }
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithTag("api_key").performScrollTo().performTextReplacement("synthetic-changed-key")
                compose.onNodeWithTag("save_key").performScrollTo().performClick()
                compose.waitUntil(30_000) { saves.get()==1 && model.state.value.problem==Problem.STORAGE && !model.state.value.keyBusy }
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("problem").assertIsDisplayed(); true }.getOrDefault(false) }
                compose.onNodeWithTag("problem").assertIsDisplayed().assertTextEquals(instrumentation.targetContext.getString(R.string.storage_error))
                compose.onNodeWithTag("close_settings").assertExists()
                assertFalse(model.state.value.keySaved)
                capture("05-settings-save-error")
                compose.onNodeWithTag("forget").performScrollTo().performClick()
                compose.waitUntil(30_000) { forgets.get()==1 && model.state.value.problem==Problem.STORAGE && !model.state.value.keyBusy }
                compose.waitUntil(10_000) { runCatching { compose.onNodeWithTag("problem").assertIsDisplayed(); true }.getOrDefault(false) }
                compose.onNodeWithTag("problem").assertIsDisplayed()
                compose.onNodeWithTag("close_settings").assertExists()
                assertTrue("Failed Forget cannot claim the persisted key was removed",model.state.value.hasSavedKey)
                capture("06-settings-forget-error")
            }
        } finally { MainActivity.testFactory=null; MainActivity.testNotice=null }
    }
}
