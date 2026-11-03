package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.*
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.diagnostics.*
import io.github.ilyasyoy.voicedictation.session.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import java.io.*
import java.util.zip.ZipInputStream
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class DiagnosticsRuntimeTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    @Before fun recoverCloudSystemUi() { dismissStaleSystemUiAnr(instrumentation) }
    @After fun clean() { MainActivity.testFactory=null;MainActivity.testNotice=null }
    private fun launch(): ActivityScenario<MainActivity> {
        MainActivity.testFactory={ DictationViewModel(AndroidAudioRecorder(it),Transcriber { _,_ -> error("No API calls allowed") }) }
        return ActivityScenario.launch(MainActivity::class.java)
    }
    @Test fun exportUsesSystemZipPickerAndCancellationAllowsAnotherAttemptAfterRecreation() {
        launch().use { scenario ->
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("export_logs").assertIsEnabled().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")),20_000))
            device.pressBack()
            compose.waitUntil(20_000) { compose.onAllNodesWithTag("export_logs").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("export_logs").assertIsEnabled()
            scenario.recreate()
            compose.onNodeWithTag("export_logs").assertIsEnabled().performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")),20_000))
            device.pressBack()
            compose.onNodeWithTag("export_logs").assertIsEnabled()
        }
    }
    @Test fun realDocumentProviderSavesReadableZipWithoutStoragePermission() {
        val name="diagnostic-runtime-${System.currentTimeMillis()}.zip"
        launch().use {
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("export_logs").performClick()
            assertTrue(device.wait(Until.hasObject(By.pkg("com.android.documentsui")),20_000))
            val filename=device.wait(Until.findObject(By.clazz("android.widget.EditText")),20_000)
            assertNotNull(filename)
            filename.text=name
            val save=device.wait(Until.findObject(By.res("com.android.documentsui","button1")),5000)
                ?: device.wait(Until.findObject(By.text(java.util.regex.Pattern.compile("save",java.util.regex.Pattern.CASE_INSENSITIVE))),5000)
            assertNotNull(save)
            save.click()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag("export_status").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("export_status").assertTextEquals(context.getString(R.string.diagnostics_saved))
            // UiAutomation reads only the freshly created synthetic test document.
            instrumentation.uiAutomation.executeShellCommand("cat /sdcard/Download/$name").use { descriptor ->
                android.os.ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { stream ->
                    val names=mutableSetOf<String>()
                    ZipInputStream(stream).use { zip -> while(true) { val entry=zip.nextEntry ?: break;names+=entry.name;zip.readBytes() } }
                    assertEquals(setOf("manifest.json","events.jsonl","README.txt"),names)
                }
            }
            device.executeShellCommand("rm /sdcard/Download/$name")
        }
    }
    @Test fun recoveryAndNetworkFailuresExportOnlyCodesAndNoPrivateContent() = runBlocking<Unit> {
        val secret="PRIVACY_SENTINEL_KEY_TEXT_AUDIO_WORDS_CLIPBOARD_SERVER"
        val file=File(context.cacheDir,"diagnostic-synthetic.m4a").apply { writeText(secret) }
        val recovery=RecordingRecovery();recovery.retain(file)
        try { recovery.retry { throw IOException(secret) };fail("Expected synthetic failure") } catch(_:IOException) { }
        Diagnostics.failure(DiagnosticArea.OVERLAY,DiagnosticAction.RENDER,IllegalStateException(secret))
        val logs=Diagnostics.snapshot().toString(Charsets.UTF_8)
        assertTrue(logs.contains("\"area\":\"RECOVERY\""));assertTrue(logs.contains("\"action\":\"RETRY\""))
        assertTrue(logs.contains("\"area\":\"OVERLAY\""));assertFalse(logs.contains(secret))
        assertTrue(File(context.noBackupFilesDir,"diagnostics").isDirectory)
        assertFalse(File(context.filesDir,"diagnostics").exists())
        recovery.discard();file.delete()
    }
    @Test fun settingsCaptureAtCurrentWindowSize() {
        launch().use { scenario ->
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("export_logs").assertIsDisplayed()
            scenario.recreate()
            compose.onNodeWithTag("export_logs").assertIsDisplayed()
            device.executeShellCommand("mkdir -p /sdcard/Download/diagnostics-evidence")
            val label=InstrumentationRegistry.getArguments().getString("captureLabel") ?: "settings"
            require(label.matches(Regex("[a-z-]+")))
            device.executeShellCommand("screencap -p /sdcard/Download/diagnostics-evidence/$label.png")
        }
    }
}
