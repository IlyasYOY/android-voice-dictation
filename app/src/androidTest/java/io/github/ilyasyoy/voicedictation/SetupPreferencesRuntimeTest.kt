package io.github.ilyasyoy.voicedictation

import androidx.test.platform.app.InstrumentationRegistry
import io.github.ilyasyoy.voicedictation.onboarding.*
import java.io.File
import org.junit.*
import org.junit.Assert.*

/** Real private metadata commit. Only this synthetic namespace is cleared; no credential store is read. */
class SetupPreferencesRuntimeTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val preferences get() = context.getSharedPreferences("onboarding_v1.vd011-test", 0)
    @Before fun before() { preferences.edit().clear().commit() }
    @After fun after() { preferences.edit().clear().commit() }
    @Test fun metadataCommitRoundTripsAcrossFreshAdaptersWithoutCredentialFields() {
        val value = SetupProgress(true, true, true, 3, false)
        assertTrue(SetupPreferences(context, ".vd011-test").save(value))
        assertEquals(value, SetupPreferences(context, ".vd011-test").load())
        val file = File(context.applicationInfo.dataDir, "shared_prefs/onboarding_v1.vd011-test.xml")
        assertTrue(file.readText().contains(value.encode()))
        assertEquals(setOf("progress"), preferences.all.keys)
    }
    @Test fun requiredDeferralIsNotDurableButOptionalChoiceAndAcknowledgementAre() {
        val store = SetupPreferences(context, ".vd011-test")
        val s = SetupSnapshot(false, false, SetupGrants(false, false, false, true))
        val flow = SetupFlow(store); flow.foreground(s); flow.defer()
        assertNull(flow.state.value.step)
        val fresh = SetupFlow(SetupPreferences(context, ".vd011-test")); fresh.foreground(s)
        assertEquals(SetupStep.KEY, fresh.state.value.step)
        assertTrue(store.load().floatingChoiceResolved)
        assertFalse(store.load().backgroundAcknowledged)
    }
    @Test fun invalidMetadataTypeOrVersionDoesNotCrashOrPretendSetupComplete() {
        assertTrue(preferences.edit().putInt("progress", 7).commit())
        assertEquals(SetupProgress(), SetupPreferences(context, ".vd011-test").load())
        assertTrue(preferences.edit().putString("progress", "999|true|true").commit())
        val flow = SetupFlow(SetupPreferences(context, ".vd011-test"))
        flow.foreground(SetupSnapshot(false, false, SetupGrants(false, false, false, true)))
        assertEquals(SetupStep.KEY, flow.state.value.step)
    }
}
