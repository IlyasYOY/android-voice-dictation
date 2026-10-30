package io.github.ilyasyoy.voicedictation

import android.content.Context
import android.os.ParcelFileDescriptor
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

/** Dedicated fixture namespace; never reads or changes a user's actual saved credential. */
class KeyPersistenceRestartTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext
    private val store get() = AndroidApiKeyStore(context, ".test.restart")
    private lateinit var model: DictationViewModel
    private fun shell(command: String) { instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
    } }
    @Test fun encryptedKeySaveRestoreForget() {
        val phase = InstrumentationRegistry.getArguments().getString("keyPhase")
        val phases = phase?.let(::listOf) ?: listOf("save", "restore", "forgotten")
        try {
            for (step in phases) {
                if (step == "save") runBlocking { store.forget() }
                MainActivity.testNotice = "KEY STORAGE TEST · synthetic credential · no API call"
                MainActivity.testFactory = { DictationViewModel(AndroidAudioRecorder(it), Transcriber { _, _ -> error("No network expected") },
                    keyStore = AndroidApiKeyStore(it, ".test.restart")).also { m -> model = m } }
                ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                    scenario.onActivity { it.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
                    compose.waitUntil(30_000) { !model.state.value.keyBusy }
                    when (step) {
                        "save" -> {
                            compose.onNodeWithTag("api_key").performScrollTo().performTextReplacement(FIXTURE)
                            compose.onNodeWithTag("save_key").performScrollTo().performClick()
                            compose.waitUntil(30_000) { model.state.value.keySaved && !model.state.value.keyBusy }
                            val preferences = context.getSharedPreferences("encrypted_api_key.test.restart", Context.MODE_PRIVATE)
                            val first = preferences.getString("payload", null)!!
                            assertFalse(first.contains(FIXTURE))
                            runBlocking { store.save(FIXTURE) }
                            assertNotEquals(first, preferences.getString("payload", null)) // Fresh random IV.
                            compose.onNodeWithTag("key_saved").performScrollTo()
                            compose.onNodeWithTag("forget").performScrollTo()
                            shell("mkdir -p /sdcard/Download/dictation-evidence")
                            shell("screencap -p /sdcard/Download/dictation-evidence/11-key-saved.png")
                        }
                        "restore" -> {
                            assertEquals(FIXTURE, model.state.value.key); assertTrue(model.state.value.keySaved)
                            compose.onNodeWithTag("forget").performScrollTo().performClick()
                            compose.waitUntil(30_000) { !model.state.value.keyBusy }
                            assertEquals("", model.state.value.key); assertNull(runBlocking { store.load() })
                        }
                        "forgotten" -> { assertEquals("", model.state.value.key); assertFalse(model.state.value.hasSavedKey) }
                        else -> fail("Unknown test phase")
                    }
                }
            }
        } finally { MainActivity.testFactory = null; MainActivity.testNotice = null }
    }
    companion object { private const val FIXTURE = "synthetic-restart-key-only" }
}
