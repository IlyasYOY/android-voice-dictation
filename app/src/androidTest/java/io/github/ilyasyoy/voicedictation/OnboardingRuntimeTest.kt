package io.github.ilyasyoy.voicedictation

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.os.Parcel
import android.os.ParcelFileDescriptor
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.onboarding.*
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import org.junit.*
import org.junit.Assert.*
import java.io.File

/** Actual Compose/lifecycle with synthetic credential store and grant observations; no provider call. */
class OnboardingRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private lateinit var model: DictationViewModel
    private val synthetic = "sk-test-fixture-" + "c".repeat(32)
    private var grants = SetupGrants(true, false, false, true)
    private var saved: String? = null
    private var loads = 0; private var saves = 0; private var reads = 0; private var starts = 0
    private val actions = mutableListOf<SetupAction>()
    private var progress = SetupProgress()
    private val store = object : SetupStore {
        override fun load() = progress
        override fun save(progress: SetupProgress): Boolean { this@OnboardingRuntimeTest.progress = progress; return true }
    }
    @Before fun before() {
        MainActivity.testSkipOnboarding = false
        MainActivity.testSetupStore = store
        MainActivity.testSetupGrants = { grants }
        MainActivity.testSetupAction = { actions += it }
        MainActivity.testPasteReader = { reads++; synthetic }
        MainActivity.testFactory = { DictationViewModel(object : AudioRecorder {
            override fun start(onLimit: () -> Unit) { starts++ }
            override fun stop(): File = error("No capture expected")
            override fun cancel() = Unit
        }, Transcriber { _, _ -> error("No provider call authorized") }, keyStore = object : ApiKeyStore {
            override suspend fun load(): String? { loads++; return saved }
            override suspend fun save(key: String) { saves++; saved = key }
            override suspend fun forget() { saved = null }
        }).also { model = it } }
    }
    @After fun after() {
        MainActivity.testFactory = null; MainActivity.testSkipOnboarding = false
        MainActivity.testSetupStore = null; MainActivity.testSetupGrants = null
        MainActivity.testSetupAction = null; MainActivity.testPasteReader = null
    }
    private fun ready() { compose.waitUntil(30_000) { ::model.isInitialized && model.state.value.keyLoaded } }
    private fun capture(name: String) {
        compose.waitForIdle()
        for (cmd in listOf("mkdir -p /sdcard/Download/vd011", "screencap -p /sdcard/Download/vd011/$name.png"))
            instrumentation.uiAutomation.executeShellCommand(cmd).use { ParcelFileDescriptor.AutoCloseInputStream(it).readBytes() }
    }
    private fun contains(haystack: ByteArray, needle: ByteArray) = (0..haystack.size-needle.size).any { offset ->
        needle.indices.all { haystack[offset+it] == needle[it] }
    }
    @Test fun maskedDraftSurvivesRotationAndOnlySaveConfirmsItForSession() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("setup_key").assertExists()
            compose.onNodeWithTag("setup_key").assert(SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit))
            compose.onNodeWithTag("setup_key").performTextReplacement(synthetic)
            assertEquals("", model.state.value.key); assertEquals(0, saves)
            scenario.recreate(); ready()
            assertEquals(synthetic, model.setupKey.value.text); assertEquals("", model.state.value.key)
            compose.onNodeWithTag("setup_primary").performScrollTo().performClick()
            compose.waitUntil(30000) { saves == 1 && model.state.value.keySaved && !model.state.value.keyBusy }
            compose.onNodeWithTag("setup_title").assertTextEquals(instrumentation.targetContext.getString(R.string.setup_keyboard_button))
            assertEquals(0, starts)
        }
    }
    @Test fun savedInstanceStateDoesNotContainTheUnconfirmedKey() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("setup_key").performTextReplacement(synthetic)
            scenario.onActivity { activity ->
                val bundle = Bundle(); instrumentation.callActivityOnSaveInstanceState(activity, bundle)
                val parcel = Parcel.obtain()
                try {
                    parcel.writeBundle(bundle); val bytes = parcel.marshall()
                    assertFalse(contains(bytes, synthetic.toByteArray(Charsets.UTF_8)))
                    assertFalse(contains(bytes, synthetic.toByteArray(Charsets.UTF_16LE)))
                } finally { parcel.recycle() }
            }
            assertEquals("", model.state.value.key); assertEquals(0, saves)
        }
    }
    @Test fun existingCompleteSetupGoesToMainAcrossRecreationWithoutChangingKey() {
        saved = "synthetic-existing-key"
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("onboarding").assertDoesNotExist()
            compose.onNodeWithTag("open_settings").assertExists()
            scenario.recreate(); ready(); compose.onNodeWithTag("onboarding").assertDoesNotExist()
            assertEquals("synthetic-existing-key", saved); assertEquals(1, loads); assertEquals(0, saves)
        }
    }
    @Test fun deferralAllowsMainAndHistoryButRequiredSetupReappearsOnNewVisit() {
        grants = grants.copy(microphone = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("setup_defer").performScrollTo().performClick()
            compose.onNodeWithTag("onboarding").assertDoesNotExist(); compose.onNodeWithTag("record").assertExists()
            scenario.recreate(); ready(); compose.onNodeWithTag("onboarding").assertDoesNotExist()
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("setup_key").assertExists()
            assertEquals(0, starts); assertEquals(0, saves); assertEquals(0, reads)
        }
    }
    @Test fun permissionDenialRotationAndReturnNeverDuplicatePromptOrStartCapture() {
        saved = "synthetic-existing-key"; grants = grants.copy(microphone = false)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("setup_primary").performClick()
            assertEquals(listOf(SetupAction.MICROPHONE), actions)
            scenario.recreate(); compose.onNodeWithTag("setup_primary").assertIsNotEnabled()
            scenario.onActivity { it.setupPermissionReturned() }
            compose.onNodeWithTag("setup_primary").assertIsEnabled(); assertEquals(1, actions.size)
            grants = grants.copy(microphone = true)
            scenario.moveToState(Lifecycle.State.STARTED); scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("setup_title").assertTextEquals(instrumentation.targetContext.getString(R.string.setup_keyboard_button))
            assertEquals(0, starts); assertEquals(0, reads)
        }
    }
    @Test fun browserReturnKeepsDraftWithoutReadingClipboardOrSaving() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready(); compose.onNodeWithTag("setup_key").performTextReplacement(synthetic)
            compose.onNodeWithTag("setup_browser").performScrollTo().performClick()
            assertEquals(listOf(SetupAction.KEY_PAGE), actions)
            scenario.moveToState(Lifecycle.State.CREATED); scenario.moveToState(Lifecycle.State.RESUMED)
            compose.onNodeWithTag("setup_key").assertExists(); assertEquals(synthetic, model.setupKey.value.text)
            assertEquals(0, reads); assertEquals(0, saves); assertEquals("", model.state.value.key)
        }
    }
    @Test fun focusedExplicitPasteUsesRealSyntheticClipboardAndDeferralDiscardsCandidate() {
        MainActivity.testPasteReader = null
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            ready()
            var focused = false; scenario.onActivity { focused = it.window.decorView.hasWindowFocus() }
            Assume.assumeTrue("Emulator has no app input focus; real clipboard interaction is unverified", focused)
            val clipboard = instrumentation.targetContext.getSystemService(ClipboardManager::class.java)
            val clip = ClipData.newPlainText("synthetic onboarding fixture", synthetic)
            if (android.os.Build.VERSION.SDK_INT >= 33) clip.description.extras = android.os.PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
            clipboard.setPrimaryClip(clip)
            try {
                compose.onNodeWithTag("setup_paste").performClick()
                assertEquals(synthetic, model.setupKey.value.text); assertEquals("", model.state.value.key); assertEquals(0, saves)
                capture("01-masked-key-explicit-paste")
                compose.onNodeWithTag("setup_defer").performScrollTo().performClick()
                assertEquals("", model.setupKey.value.text); assertEquals("", model.state.value.key)
                compose.onNodeWithTag("onboarding").assertDoesNotExist()
            } finally { clipboard.setPrimaryClip(ClipData.newPlainText("", "")) }
        }
    }
}
