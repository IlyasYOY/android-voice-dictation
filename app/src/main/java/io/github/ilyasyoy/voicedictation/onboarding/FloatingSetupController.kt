package io.github.ilyasyoy.voicedictation.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class FloatingSetupUi(val grants: SetupGrants = SetupGrants(false, false, false, false),
    val progress: SetupProgress = SetupProgress(), val pending: SetupPermission? = null,
    val navigating: Boolean = false, val problem: Boolean = false) {
    val step get() = FloatingSetupPolicy.step(grants, progress)
    val blocked get() = pending != null || navigating
}

/** Explicit actions only. Retained by the Activity ViewModel, never stores a Context or starts a recorder. */
internal class FloatingSetupController(private val store: SetupStore) {
    private val mutableState = MutableStateFlow(FloatingSetupUi(progress = store.load()))
    val state = mutableState.asStateFlow()
    fun refresh(grants: SetupGrants) {
        val old = state.value
        mutableState.value = old.copy(grants = grants, progress = old.progress.merge(store.load()),
            pending = old.pending?.takeUnless { if (it == SetupPermission.MICROPHONE) grants.microphone else grants.notifications })
    }
    private fun write(progress: SetupProgress): Boolean {
        val next = progress.merge(store.load())
        val saved = store.save(next)
        mutableState.value = state.value.copy(progress = if (saved) next else state.value.progress, problem = !saved)
        return saved
    }
    fun requested(permission: SetupPermission) = state.value.progress.requested and
        (if (permission == SetupPermission.MICROPHONE) 1 else 2) != 0
    fun beginPermission(permission: SetupPermission): Boolean {
        if (state.value.blocked) return false
        val bit = if (permission == SetupPermission.MICROPHONE) 1 else 2
        val next = state.value.progress.copy(requested = state.value.progress.requested or bit)
        write(next)
        // Keep the attempted flag in memory even if this small metadata commit failed.
        mutableState.value = state.value.copy(progress = state.value.progress.merge(next), pending = permission)
        return true
    }
    fun permissionReturned(grants: SetupGrants) {
        mutableState.value = state.value.copy(pending = null); refresh(grants)
    }
    fun beginNavigation(): Boolean {
        if (state.value.blocked) return false
        mutableState.value = state.value.copy(navigating = true, problem = false); return true
    }
    fun resumed(grants: SetupGrants) { mutableState.value = state.value.copy(navigating = false); refresh(grants) }
    fun failedAction() { mutableState.value = state.value.copy(pending = null, navigating = false, problem = true) }
    fun chooseFloating() { write(state.value.progress.copy(floatingChoiceResolved = true)) }
    fun acknowledgeBackground() { write(state.value.progress.copy(backgroundAcknowledged = true)) }
}
