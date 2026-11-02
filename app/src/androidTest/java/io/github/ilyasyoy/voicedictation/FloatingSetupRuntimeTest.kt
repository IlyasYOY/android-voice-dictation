package io.github.ilyasyoy.voicedictation

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.floating.FloatingSetupActivity
import io.github.ilyasyoy.voicedictation.onboarding.*
import org.junit.*
import org.junit.Assert.*

/** Actual shared Compose screen and Activity; grant observations/actions are synthetic, never a provider request. */
class FloatingSetupRuntimeTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var grants = SetupGrants(true, true, true, true)
    private var progress = SetupProgress(true, true, true)
    private val actions = mutableListOf<SetupAction>()
    private var disabled = 0
    private fun node(name: String) = compose.onNodeWithTag("${context.packageName}:id/$name")
    @Before fun before() {
        FloatingSetupActivity.testGrants = { grants }
        FloatingSetupActivity.testStore = object : SetupStore {
            override fun load() = progress
            override fun save(progress: SetupProgress): Boolean { this@FloatingSetupRuntimeTest.progress = progress; return true }
        }
        FloatingSetupActivity.testAction = { actions += it }
        FloatingSetupActivity.testDisable = { disabled++; grants = grants.copy(floatingWanted = false, accessibility = false) }
        FloatingSetupActivity.testEnable = { grants = grants.copy(floatingWanted = true) }
    }
    @After fun after() {
        FloatingSetupActivity.testGrants = null; FloatingSetupActivity.testStore = null
        FloatingSetupActivity.testAction = null; FloatingSetupActivity.testDisable = null; FloatingSetupActivity.testEnable = null
    }
    @Test fun completeEnabledScreenHasOnlySecondaryDisableAndNormalBack() {
        ActivityScenario.launch(FloatingSetupActivity::class.java).use { scenario ->
            node("floating_setup_title").assertTextEquals(context.getString(R.string.floating_button_title))
            for (id in listOf("floating_enable", "floating_mic", "floating_notifications", "floating_background_settings")) node(id).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.floating_setup_summary)).assertDoesNotExist()
            compose.onNodeWithText(context.getString(R.string.current_bubble_help)).assertDoesNotExist()
            node("floating_disable").assertExists(); assertTrue(actions.isEmpty())
            compose.onNodeWithTag("setup_back").performClick()
            compose.waitUntil(10000) { scenario.state == Lifecycle.State.DESTROYED }
        }
    }
    @Test fun missingMicrophoneThenAccessibilityThenNotificationsUseOneSlot() {
        grants = grants.copy(microphone = false, accessibility = false, notifications = false)
        ActivityScenario.launch(FloatingSetupActivity::class.java).use { scenario ->
            node("floating_mic").assertExists(); node("floating_enable").assertDoesNotExist(); node("floating_notifications").assertDoesNotExist()
            node("floating_mic").performClick(); assertEquals(listOf(SetupAction.MICROPHONE), actions)
            scenario.recreate(); node("floating_mic").assertIsNotEnabled()
            scenario.onActivity { it.permissionReturned() }; node("floating_mic").assertIsEnabled()
            // A real permanently denied callback produces this same false-rationale/attempted path.
            node("floating_mic").assertTextEquals(context.getString(R.string.setup_app_settings))
            grants = grants.copy(microphone = true)
            scenario.moveToState(Lifecycle.State.STARTED); scenario.moveToState(Lifecycle.State.RESUMED)
            node("floating_enable").assertExists(); node("floating_mic").assertDoesNotExist()
            node("floating_enable").performClick(); assertEquals(SetupAction.ACCESSIBILITY, actions.last())
            grants = grants.copy(accessibility = true)
            scenario.moveToState(Lifecycle.State.STARTED); scenario.moveToState(Lifecycle.State.RESUMED)
            node("floating_notifications").assertExists(); node("floating_enable").assertDoesNotExist()
            node("floating_notifications").performClick(); assertEquals(SetupAction.NOTIFICATIONS, actions.last())
        }
    }
    @Test fun disableAndReenableRequireExplicitActionAndDisclosureConsent() {
        ActivityScenario.launch(FloatingSetupActivity::class.java).use {
            node("floating_disable").performClick(); assertEquals(1, disabled)
            node("floating_setup_status").assertTextEquals(context.getString(R.string.floating_off))
            node("floating_enable").performClick()
            assertFalse(grants.floatingWanted); node("floating_consent").assertExists()
            node("floating_consent").performClick(); assertTrue(grants.floatingWanted)
            node("floating_enable").assertExists(); assertFalse(grants.accessibility)
            assertTrue(actions.isEmpty()) // Consent alone neither grants access nor starts recording.
        }
    }
    @Test fun accessDisclosureAndBackgroundHelpStayAvailableWithoutPermanentParagraphs() {
        ActivityScenario.launch(FloatingSetupActivity::class.java).use {
            compose.onNodeWithText(context.getString(R.string.floating_disclosure)).assertDoesNotExist()
            node("floating_more").performScrollTo().performClick(); node("floating_about").performClick()
            compose.onNodeWithText(context.getString(R.string.floating_disclosure)).assertExists()
            node("floating_consent").performClick()
            node("floating_more").performScrollTo().performClick(); node("floating_background_help").performClick()
            compose.onNodeWithText(context.getString(R.string.setup_app_settings)).performClick()
            assertEquals(listOf(SetupAction.APP_SETTINGS), actions)
        }
    }
}
