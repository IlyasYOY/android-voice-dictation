package io.github.ilyasyoy.voicedictation.onboarding

import org.junit.Test
import org.junit.Assert.*

class FloatingSetupTest {
    private class Store(var value: SetupProgress = SetupProgress(true, true, true), var fail: Boolean = false) : SetupStore {
        override fun load() = value
        override fun save(progress: SetupProgress): Boolean { if (!fail) value = progress; return !fail }
    }
    private val ready = SetupGrants(true, true, true, true)
    @Test fun enabledWithAllGrantsHasNoRedundantPermissionAction() {
        assertEquals(FloatingSetupStep.READY, FloatingSetupPolicy.step(ready, SetupProgress(true, true, true)))
    }
    @Test fun onlyTheFirstActuallyMissingGrantIsOfferedInOnboardingOrder() {
        val p = SetupProgress(true, true, true)
        assertEquals(FloatingSetupStep.MICROPHONE, FloatingSetupPolicy.step(ready.copy(microphone = false, accessibility = false, notifications = false), p))
        assertEquals(FloatingSetupStep.ACCESSIBILITY, FloatingSetupPolicy.step(ready.copy(accessibility = false, notifications = false), p))
        assertEquals(FloatingSetupStep.NOTIFICATIONS, FloatingSetupPolicy.step(ready.copy(notifications = false), p))
        val firstRun = SetupFlow(Store(p)); firstRun.foreground(SetupSnapshot(false, true, ready.copy(microphone = false)))
        assertEquals(SetupStep.MICROPHONE, firstRun.state.value.step)
    }
    @Test fun disabledCapabilityDoesNotAskForItsMicrophoneOrNotificationGrant() {
        assertEquals(FloatingSetupStep.ENABLE, FloatingSetupPolicy.step(SetupGrants(false, false, false, false), SetupProgress()))
    }
    @Test fun unknownBackgroundIsAnExplanationNotAnObservedPermission() {
        assertEquals(FloatingSetupStep.BACKGROUND, FloatingSetupPolicy.step(ready, SetupProgress(true, true)))
        assertEquals(FloatingSetupStep.READY, FloatingSetupPolicy.step(ready, SetupProgress(true, true, legacyReady = true)))
        val store = Store(SetupProgress(true, true)); val c = FloatingSetupController(store); c.refresh(ready)
        c.acknowledgeBackground(); assertEquals(FloatingSetupStep.READY, c.state.value.step)
        assertEquals(ready, c.state.value.grants); assertTrue(store.value.backgroundAcknowledged)
    }
    @Test fun duplicateActionsAndRotationCannotCreateAnotherPermissionRequest() {
        val c = FloatingSetupController(Store()); c.refresh(ready.copy(microphone = false))
        assertTrue(c.beginPermission(SetupPermission.MICROPHONE))
        c.resumed(ready.copy(microphone = false))
        assertFalse(c.beginPermission(SetupPermission.MICROPHONE)); assertFalse(c.beginNavigation())
        assertTrue(c.requested(SetupPermission.MICROPHONE))
        c.permissionReturned(ready.copy(microphone = false)); assertFalse(c.state.value.blocked)
        assertEquals(FloatingSetupStep.MICROPHONE, c.state.value.step)
    }
    @Test fun observableGrantOnReturnAdvancesButDenialDoesNotLaunchAnotherAction() {
        val c = FloatingSetupController(Store()); c.refresh(ready.copy(microphone = false, accessibility = false))
        c.beginPermission(SetupPermission.MICROPHONE); c.permissionReturned(ready.copy(microphone = false, accessibility = false))
        assertNull(c.state.value.pending); assertEquals(FloatingSetupStep.MICROPHONE, c.state.value.step)
        c.resumed(ready.copy(accessibility = false)); assertEquals(FloatingSetupStep.ACCESSIBILITY, c.state.value.step)
        assertTrue(c.beginNavigation()); assertFalse(c.beginNavigation())
        c.resumed(ready); assertEquals(FloatingSetupStep.READY, c.state.value.step); assertFalse(c.state.value.blocked)
    }
    @Test fun processRestartRetainsAttemptMetadataButNeverAnAutomaticPrompt() {
        val store = Store(); FloatingSetupController(store).beginPermission(SetupPermission.MICROPHONE)
        val next = FloatingSetupController(store); next.refresh(ready.copy(microphone = false))
        assertNull(next.state.value.pending); assertTrue(next.requested(SetupPermission.MICROPHONE))
    }
    @Test fun cancellationAndNavigationFailureLeaveAnActionableScreen() {
        val c = FloatingSetupController(Store()); c.refresh(ready.copy(accessibility = false))
        c.beginNavigation(); c.failedAction()
        assertTrue(c.state.value.problem); assertFalse(c.state.value.blocked)
        assertEquals(FloatingSetupStep.ACCESSIBILITY, c.state.value.step)
    }
    @Test fun failedAcknowledgementDoesNotPersistOrHideTheExplanation() {
        val store = Store(SetupProgress(true, true), fail = true); val c = FloatingSetupController(store); c.refresh(ready)
        c.acknowledgeBackground(); assertFalse(store.value.backgroundAcknowledged)
        assertEquals(FloatingSetupStep.BACKGROUND, c.state.value.step); assertTrue(c.state.value.problem)
    }
    @Test fun firstRunAndSettingsMergeSharedAcknowledgementAndRequestFlags() {
        val store = Store(SetupProgress(true, true))
        val firstRun = SetupFlow(store); firstRun.foreground(SetupSnapshot(false, true, ready))
        assertEquals(SetupStep.BACKGROUND, firstRun.state.value.step)
        val later = FloatingSetupController(store); later.refresh(ready); later.acknowledgeBackground()
        firstRun.foreground(SetupSnapshot(false, true, ready)); assertNull(firstRun.state.value.step)
        firstRun.beginPermission(SetupPermission.MICROPHONE)
        later.beginPermission(SetupPermission.NOTIFICATIONS)
        assertEquals(3, store.value.requested); assertTrue(store.value.backgroundAcknowledged)
    }
}
