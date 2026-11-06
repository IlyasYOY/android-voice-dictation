package io.github.ilyasyoy.voicedictation

import android.app.Activity
import android.app.Instrumentation
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.os.ParcelFileDescriptor
import androidx.activity.compose.setContent
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.data.ApiKeyStore
import io.github.ilyasyoy.voicedictation.data.AndroidAudioRecorder
import io.github.ilyasyoy.voicedictation.data.Transcriber
import io.github.ilyasyoy.voicedictation.data.TextProcessingSettings
import io.github.ilyasyoy.voicedictation.session.DictationState
import io.github.ilyasyoy.voicedictation.ui.DictationScreen
import io.github.ilyasyoy.voicedictation.ui.DictationViewModel
import io.github.ilyasyoy.voicedictation.ui.OpenAiBilling
import io.github.ilyasyoy.voicedictation.ui.theme.VoiceDictationTheme
import java.util.Locale
import org.junit.*
import org.junit.Assert.*

/** No browser network request: intercept the real Activity launch before dispatch. */
class BillingNavigationTest {
    @get:Rule val setup: org.junit.rules.TestRule = ExistingFeatureSetupRule()
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var launches = 0
    private var animation = "1"
    @Before fun before() {
        animation = shell("settings get global animator_duration_scale")
        shell("settings put global animator_duration_scale 0")
        MainActivity.testFactory = { DictationViewModel(AndroidAudioRecorder(it),
            Transcriber { _, _ -> error("No provider call authorized") }, keyStore = object : ApiKeyStore {
                override suspend fun load() = "synthetic-billing-test-key"
                override suspend fun save(key: String) = error("Navigation must not save a key")
                override suspend fun forget() = error("Navigation must not forget a key")
            }) }
    }
    @After fun after() {
        MainActivity.testFactory = null
        shell("settings put global animator_duration_scale $animation")
    }

    @Test fun settingsLaunchesOnlyThePublicBillingUrlAndKeepsSettingsOnReturn() {
        ActivityScenario.launch(MainActivity::class.java).use {
            var outgoing: Intent? = null
            val monitor = object : Instrumentation.ActivityMonitor() {
                override fun onStartActivity(intent: Intent): Instrumentation.ActivityResult? {
                    if (intent.action != Intent.ACTION_VIEW) return null
                    outgoing = Intent(intent); launches++
                    return Instrumentation.ActivityResult(Activity.RESULT_CANCELED, null)
                }
            }
            instrumentation.addMonitor(monitor)
            try {
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithTag("open_billing").performScrollTo().assertIsEnabled().performClick()
                compose.waitUntil(10_000) { outgoing != null }
                val intent = checkNotNull(outgoing)
                assertEquals(1, launches)
                assertEquals("https://platform.openai.com/settings/organization/billing/overview", intent.dataString)
                assertTrue(intent.hasCategory(Intent.CATEGORY_BROWSABLE))
                assertTrue(intent.extras == null || intent.extras!!.isEmpty)
                assertNull(intent.clipData); assertNull(intent.component); assertNull(intent.`package`)
                assertNull(intent.data!!.query); assertNull(intent.data!!.fragment); assertNull(intent.data!!.userInfo)
                compose.onNodeWithTag("billing_error").assertDoesNotExist()
                compose.onNodeWithTag("close_settings").assertExists()
                capture("billing-settings-light")
            } finally { instrumentation.removeMonitor(monitor) }
        }
    }

    @Test fun missingOrBlockedBrowserIsRecoverableAndRetryClearsTheError() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var blocked = true
            scenario.onActivity { activity -> activity.setContent {
                VoiceDictationTheme { BillingScreen { OpenAiBilling.open {
                    if (blocked) throw ActivityNotFoundException("Synthetic missing browser")
                    launches++
                } } }
            } }
            compose.onNodeWithTag("open_settings").performClick()
            compose.onNodeWithTag("open_billing").performScrollTo().performClick()
            compose.onNodeWithTag("billing_error").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("close_settings").assertExists()
            capture("billing-no-browser")
            blocked = false
            compose.onNodeWithTag("open_billing").performScrollTo().performClick()
            compose.onNodeWithTag("billing_error").assertDoesNotExist()
            assertEquals(1, launches)
            assertFalse(OpenAiBilling.open { throw SecurityException("Synthetic browser restriction") })
        }
    }

    @Test fun russianButtonFitsDarkAndLargeFontSettings() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            for ((suffix, dark, scale) in listOf(Triple("dark", true, 1f), Triple("large-font", false, 2f))) {
                scenario.onActivity { activity -> activity.setContent {
                    val config = Configuration(activity.resources.configuration).apply {
                        setLocale(Locale.forLanguageTag("ru"))
                        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                            if (dark) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
                    }
                    val density = LocalDensity.current
                    CompositionLocalProvider(LocalConfiguration provides config,
                        LocalContext provides activity.createConfigurationContext(config),
                        LocalDensity provides Density(density.density, scale)) {
                        androidx.compose.runtime.key(suffix) { VoiceDictationTheme { BillingScreen { true } } }
                    }
                } }
                compose.onNodeWithTag("open_settings").performScrollTo().performClick()
                compose.onNodeWithTag("open_billing").performScrollTo().assertIsDisplayed()
                    .assertHasClickAction().assertTextEquals("Посмотреть баланс")
                capture("billing-settings-ru-$suffix")
            }
        }
    }

    @androidx.compose.runtime.Composable private fun BillingScreen(open: () -> Boolean) {
        DictationScreen(DictationState(key = "synthetic-key", keySaved = true, hasSavedKey = true),
            {}, {}, {}, {}, {}, {}, {}, {}, {}, {},
            textProcessingSettings = TextProcessingSettings(), onSaveTextProcessing = { true }, onOpenBilling = open)
    }
    private fun capture(name: String) {
        compose.onNode(hasText("Opens OpenAI Billing", substring = true) or
            hasText("Откроется Billing OpenAI", substring = true)).performScrollTo()
        compose.onNodeWithTag("open_billing").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(400); compose.waitForIdle()
        for (command in listOf("mkdir -p /sdcard/Download/billing-evidence", "screencap -p /sdcard/Download/billing-evidence/$name.png")) {
            instrumentation.uiAutomation.executeShellCommand(command).use {
                ParcelFileDescriptor.AutoCloseInputStream(it).readBytes()
            }
        }
    }
    private fun shell(command: String) = instrumentation.uiAutomation.executeShellCommand(command).use {
        ParcelFileDescriptor.AutoCloseInputStream(it).bufferedReader().readText().trim()
    }
}
