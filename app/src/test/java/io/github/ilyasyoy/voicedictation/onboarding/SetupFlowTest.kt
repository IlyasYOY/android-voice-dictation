package io.github.ilyasyoy.voicedictation.onboarding

import org.junit.Assert.*
import org.junit.Test

class SetupFlowTest {
    private class Store(var value: SetupProgress = SetupProgress(), var fail: Boolean = false) : SetupStore {
        var writes = 0
        override fun load() = value
        override fun save(progress: SetupProgress): Boolean { writes++; if (!fail) value = progress; return !fail }
    }
    private fun snapshot(key: Boolean = false, mic: Boolean = false, wanted: Boolean = false,
        access: Boolean = false, notifications: Boolean = true, loading: Boolean = false) =
        SetupSnapshot(loading, key, SetupGrants(mic, wanted, access, notifications))

    @Test fun firstLaunchShowsOnlyFirstMissingStep() {
        val f = SetupFlow(Store()); f.foreground(snapshot())
        assertEquals(SetupStep.KEY, f.state.value.step); assertNull(f.state.value.pending)
    }
    @Test fun savedKeyIsNotRequestedAgainWhenOnlyMicrophoneIsMissing() {
        val f = SetupFlow(Store()); f.foreground(snapshot(key = true))
        assertEquals(SetupStep.MICROPHONE, f.state.value.step)
    }
    @Test fun initialKeyReadIsNotMistakenForMissingCredential() {
        val store = Store(); val f = SetupFlow(store)
        f.foreground(snapshot(loading = true, mic = true))
        assertEquals(SetupStep.LOADING, f.state.value.step); assertFalse(store.value.seen)
        f.update(snapshot(key = true, mic = true))
        assertNull(f.state.value.step)
    }
    @Test fun completeExistingMainSetupMigratesDirectlyWithoutOptingIntoFloating() {
        val store = Store(); val f = SetupFlow(store)
        f.foreground(snapshot(key = true, mic = true))
        assertNull(f.state.value.step); assertTrue(store.value.seen)
        assertTrue(store.value.floatingChoiceResolved); assertFalse(store.value.backgroundAcknowledged)
        SetupFlow(store).also { it.foreground(snapshot(key = true, mic = true)); assertNull(it.state.value.step) }
    }
    @Test fun completeExistingFloatingSetupDoesNotPretendBackgroundStateIsKnown() {
        val store = Store(); val f = SetupFlow(store)
        f.foreground(snapshot(true, true, true, true))
        assertNull(f.state.value.step); assertFalse(store.value.backgroundAcknowledged)
        assertTrue(f.hasMissing()) // Optional explanation remains reachable from Settings.
        f.manual(); assertEquals(SetupStep.BACKGROUND, f.state.value.step)
        f.acknowledgeBackground(); assertNull(f.state.value.step); assertTrue(store.value.backgroundAcknowledged)
    }
    @Test fun newSetupAdvancesSequentiallyFromObservedGrantsOnly() {
        val store = Store(); val f = SetupFlow(store); f.foreground(snapshot())
        f.update(snapshot(key = true)); assertEquals(SetupStep.MICROPHONE, f.state.value.step)
        f.update(snapshot(true, true)); assertEquals(SetupStep.FLOATING, f.state.value.step)
        f.chooseFloating(); f.update(snapshot(true, true, true, false, false))
        assertEquals(SetupStep.FLOATING, f.state.value.step)
        f.update(snapshot(true, true, true, true, false)); assertEquals(SetupStep.NOTIFICATIONS, f.state.value.step)
        f.update(snapshot(true, true, true, true)); assertEquals(SetupStep.BACKGROUND, f.state.value.step)
        f.acknowledgeBackground(); assertNull(f.state.value.step)
    }
    @Test fun notNowAllowsOfflineAccessEvenWhileKeyReadIsPending() {
        val f = SetupFlow(Store()); f.foreground(snapshot(loading = true))
        f.defer(); assertNull(f.state.value.step)
        f.update(snapshot()); assertNull(f.state.value.step)
        repeat(3) { f.foreground(snapshot()); assertNull(f.state.value.step) }
    }
    @Test fun normalAppReopeningReoffersReliablyMissingRequiredSetup() {
        val f = SetupFlow(Store()); f.foreground(snapshot()); f.defer()
        f.background(); f.foreground(snapshot())
        assertEquals(SetupStep.KEY, f.state.value.step)
    }
    @Test fun newProcessDoesNotPersistDeferralOfRequiredPermissions() {
        val store = Store(); val f = SetupFlow(store)
        f.foreground(snapshot(key = true)); f.defer()
        SetupFlow(Store(SetupProgress.decode(store.value.encode()))).also {
            it.foreground(snapshot(key = true)); assertEquals(SetupStep.MICROPHONE, it.state.value.step)
        }
    }
    @Test fun configurationChangeKeepsCurrentVisitDeferral() {
        val f = SetupFlow(Store()); f.foreground(snapshot()); f.defer()
        f.background(continuation = true); f.foreground(snapshot())
        assertNull(f.state.value.step)
    }
    @Test fun returningFromOwnedBrowserOrSettingsDoesNotNagAgain() {
        val f = SetupFlow(Store()); f.foreground(snapshot()); f.defer()
        assertTrue(f.beginNavigation()); assertFalse(f.beginNavigation())
        f.background(); f.navigationReturned(); f.foreground(snapshot())
        assertNull(f.state.value.step)
        f.background(); f.foreground(snapshot()); assertEquals(SetupStep.KEY, f.state.value.step)
    }
    @Test fun optionalFloatingOptOutIsDurableButRequiredMicIsNotHiddenForever() {
        val store = Store(); val f = SetupFlow(store); f.foreground(snapshot())
        f.update(snapshot(true, true)); assertEquals(SetupStep.FLOATING, f.state.value.step)
        f.defer()
        val restarted = SetupFlow(store); restarted.foreground(snapshot(true, true, notifications = false))
        assertNull(restarted.state.value.step)
        restarted.background(); restarted.foreground(snapshot(key = true, notifications = false))
        assertEquals(SetupStep.MICROPHONE, restarted.state.value.step)
    }
    @Test fun revokedAccessibilityIsRelevantOnlyWhenFloatingWasChosen() {
        val store = Store(SetupProgress(seen = true, floatingChoiceResolved = true, backgroundAcknowledged = true))
        val f = SetupFlow(store); f.foreground(snapshot(true, true, true, true))
        assertNull(f.state.value.step)
        f.background(); f.foreground(snapshot(true, true, true, false))
        assertEquals(SetupStep.FLOATING, f.state.value.step)
        f.update(snapshot(true, true, false, false, false)); assertNull(f.state.value.step)
    }
    @Test fun notificationRevocationIsRecheckedOnNextVisitAndIgnoredWhenOptionalFeatureIsOff() {
        val f = SetupFlow(Store(SetupProgress(true, true, true)))
        f.foreground(snapshot(true, true, true, true)); assertNull(f.state.value.step)
        f.background(); f.foreground(snapshot(true, true, true, true, false))
        assertEquals(SetupStep.NOTIFICATIONS, f.state.value.step)
        f.defer(); f.foreground(snapshot(true, true, true, true, false)); assertNull(f.state.value.step)
        f.background(); f.foreground(snapshot(true, true, true, true, false)); assertEquals(SetupStep.NOTIFICATIONS, f.state.value.step)
    }
    @Test fun repeatedPermissionClicksAndRotationDoNotDuplicateRequest() {
        val f = SetupFlow(Store()); val s = snapshot(key = true); f.foreground(s)
        assertTrue(f.beginPermission(SetupPermission.MICROPHONE))
        repeat(5) { assertFalse(f.beginPermission(SetupPermission.MICROPHONE)); f.background(continuation = true); f.foreground(s) }
        assertEquals(SetupPermission.MICROPHONE, f.state.value.pending)
    }
    @Test fun denialOrCancelClearsPendingWithoutGrantingOrRelaunching() {
        val f = SetupFlow(Store()); val s = snapshot(key = true); f.foreground(s)
        f.beginPermission(SetupPermission.MICROPHONE); f.permissionReturned(s)
        assertNull(f.state.value.pending); assertEquals(SetupStep.MICROPHONE, f.state.value.step)
        assertTrue(f.requested(SetupPermission.MICROPHONE)); f.defer(); assertNull(f.state.value.step)
    }
    @Test fun permissionReturnCannotOptimisticallyAdvanceWithoutObservedGrant() {
        val f = SetupFlow(Store()); f.foreground(snapshot(key = true)); f.beginPermission(SetupPermission.MICROPHONE)
        f.permissionReturned(snapshot(key = true)); assertEquals(SetupStep.MICROPHONE, f.state.value.step)
        f.update(snapshot(true, true)); assertNull(f.state.value.pending); assertEquals(SetupStep.FLOATING, f.state.value.step)
    }
    @Test fun processRestartMidPermissionDoesNotRestoreAnAutomaticRequest() {
        val store = Store(); SetupFlow(store).also { it.foreground(snapshot(key = true)); it.beginPermission(SetupPermission.MICROPHONE) }
        val fresh = SetupFlow(store); fresh.foreground(snapshot(key = true))
        assertNull(fresh.state.value.pending); assertTrue(fresh.requested(SetupPermission.MICROPHONE))
    }
    @Test fun unknownBackgroundAcknowledgementIsOnceOnlyAndNotAGrant() {
        val store = Store(SetupProgress(seen = true, floatingChoiceResolved = true))
        val s = snapshot(true, true, true, true); val f = SetupFlow(store); f.foreground(s)
        assertEquals(SetupStep.BACKGROUND, f.state.value.step)
        f.acknowledgeBackground(); assertTrue(store.value.backgroundAcknowledged)
        val fresh = SetupFlow(store); fresh.foreground(s); assertNull(fresh.state.value.step)
        fresh.background(); fresh.foreground(s.copy(grants = s.grants.copy(microphone = false)))
        assertEquals(SetupStep.MICROPHONE, fresh.state.value.step)
    }
    @Test fun failedAcknowledgementCommitIsNotShownAsPersistedAndDoesNotBlockDeferral() {
        val store = Store(SetupProgress(seen = true, floatingChoiceResolved = true)); val f = SetupFlow(store)
        f.foreground(snapshot(true, true, true, true)); store.fail = true; f.acknowledgeBackground()
        assertFalse(store.value.backgroundAcknowledged); assertTrue(f.state.value.storageProblem)
        assertEquals(SetupStep.BACKGROUND, f.state.value.step)
        f.defer(); assertNull(f.state.value.step)
    }
    @Test fun settingsGuideCanReenterDuringDeferredVisit() {
        val f = SetupFlow(Store()); f.foreground(snapshot()); f.defer(); f.manual()
        assertEquals(SetupStep.KEY, f.state.value.step)
    }
    @Test fun requiredSetupRevokedAfterOldDismissalIsNotPermanentlySuppressed() {
        val store = Store(); val f = SetupFlow(store); f.foreground(snapshot(key = true)); f.defer()
        f.update(snapshot(true, true)); f.background(); f.foreground(snapshot(key = true))
        assertEquals(SetupStep.MICROPHONE, f.state.value.step)
    }
    @Test fun progressCodecContainsOnlyMetadataAndRejectsInvalidLegacyValues() {
        val value = SetupProgress(true, true, true, 3, false)
        assertEquals(value, SetupProgress.decode(value.encode()))
        for (raw in listOf(null, "", "0|true|true|true|0|false", "1|maybe|true|false|0|true", "1|true|true|false|9|false", "1|true|true|false|0", "sk-test-not-real-credential"))
            assertEquals(SetupProgress(), SetupProgress.decode(raw))
    }
}
