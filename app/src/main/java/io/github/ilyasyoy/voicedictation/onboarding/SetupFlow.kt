package io.github.ilyasyoy.voicedictation.onboarding

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

internal enum class SetupStep { LOADING, KEY, MICROPHONE, FLOATING, NOTIFICATIONS, BACKGROUND }
internal enum class SetupPermission { MICROPHONE, NOTIFICATIONS }
internal enum class SetupAction { MICROPHONE, NOTIFICATIONS, ACCESSIBILITY, APP_SETTINGS, KEY_PAGE }

internal data class SetupGrants(val microphone: Boolean, val floatingWanted: Boolean,
    val accessibility: Boolean, val notifications: Boolean)
internal data class SetupSnapshot(val keyLoading: Boolean, val keyReady: Boolean, val grants: SetupGrants)
internal data class SetupProgress(val seen: Boolean = false, val floatingChoiceResolved: Boolean = false,
    val backgroundAcknowledged: Boolean = false, val requested: Int = 0, val legacyReady: Boolean = false) {
    fun encode() = "1|$seen|$floatingChoiceResolved|$backgroundAcknowledged|$requested|$legacyReady"
    companion object {
        fun decode(raw: String?): SetupProgress {
            val p = raw?.split('|') ?: return SetupProgress()
            if (p.size != 6 || p[0] != "1") return SetupProgress()
            val seen = p[1].toBooleanStrictOrNull() ?: return SetupProgress()
            val choice = p[2].toBooleanStrictOrNull() ?: return SetupProgress()
            val ack = p[3].toBooleanStrictOrNull() ?: return SetupProgress()
            val requested = p[4].toIntOrNull()?.takeIf { it in 0..3 } ?: return SetupProgress()
            val legacy = p[5].toBooleanStrictOrNull() ?: return SetupProgress()
            return SetupProgress(seen, choice, ack, requested, legacy)
        }
    }
}
internal interface SetupStore { fun load(): SetupProgress; fun save(progress: SetupProgress): Boolean }
internal data class SetupUi(val step: SetupStep? = null, val pending: SetupPermission? = null,
    val navigationPending: Boolean = false, val storageProblem: Boolean = false, val missing: Int = 0)

/** Grant/key-presence metadata only. No token, editor, clipboard or network port is held here. */
internal class SetupFlow(private val store: SetupStore) {
    private var progress = store.load()
    private var latest: SetupSnapshot? = null
    private var active = false
    private var foregroundPending = true
    private var deferredThisVisit = 0
    private var newVisitPending = false
    private var manualHelp = false
    private val mutableState = MutableStateFlow(SetupUi())
    val state = mutableState.asStateFlow()
    private fun write(next: SetupProgress, required: Boolean = false): Boolean {
        if (next == progress) return true
        val saved = store.save(next)
        if (!saved && required) { mutableState.value = mutableState.value.copy(storageProblem = true); return false }
        progress = next // Deferral must still allow offline access when metadata storage fails.
        return saved
    }
    fun foreground(snapshot: SetupSnapshot) {
        if (newVisitPending) { deferredThisVisit = 0; manualHelp = false; newVisitPending = false }
        foregroundPending = true; update(snapshot)
    }
    fun background(continuation: Boolean = false) {
        if (!continuation && !mutableState.value.navigationPending && mutableState.value.pending == null) newVisitPending = true
    }
    fun update(snapshot: SetupSnapshot) {
        latest = snapshot
        if (!snapshot.keyLoading && !progress.seen) {
            val legacyComplete = snapshot.keyReady && snapshot.grants.microphone &&
                (!snapshot.grants.floatingWanted || (snapshot.grants.accessibility && snapshot.grants.notifications))
            write(progress.copy(seen = true, floatingChoiceResolved = legacyComplete || snapshot.grants.floatingWanted,
                legacyReady = legacyComplete))
        }
        if (snapshot.grants.floatingWanted && !progress.floatingChoiceResolved) write(progress.copy(floatingChoiceResolved = true))
        val missing = missing(snapshot, manualHelp)
        val actionable = missing and deferredThisVisit.inv()
        if (foregroundPending) {
            if (snapshot.keyLoading) active = !progress.seen
            else { active = actionable != 0; foregroundPending = false }
        }
        if (!snapshot.keyLoading && actionable == 0) active = false
        val pending = mutableState.value.pending?.takeUnless {
            (it == SetupPermission.MICROPHONE && snapshot.grants.microphone) ||
                (it == SetupPermission.NOTIFICATIONS && snapshot.grants.notifications)
        }
        val step = if (!active) null else if (snapshot.keyLoading) SetupStep.LOADING else when {
            actionable and KEY != 0 -> SetupStep.KEY
            actionable and MIC != 0 -> SetupStep.MICROPHONE
            actionable and FLOATING != 0 -> SetupStep.FLOATING
            actionable and NOTIFICATIONS != 0 -> SetupStep.NOTIFICATIONS
            actionable and BACKGROUND != 0 -> SetupStep.BACKGROUND
            else -> null
        }
        mutableState.value = mutableState.value.copy(step = step, pending = pending, missing = missing(snapshot, true))
    }
    fun hasMissing() = latest?.let { missing(it, true) != 0 } == true
    fun defer() {
        write(progress.copy(seen = true, floatingChoiceResolved = true))
        deferredThisVisit = ALL; manualHelp = false
        active = false; foregroundPending = false
        mutableState.value = SetupUi()
        latest?.let(::update)
    }
    fun deferKey() { deferredThisVisit = deferredThisVisit or KEY }
    fun manual() {
        deferredThisVisit = 0; manualHelp = true
        foregroundPending = true
        latest?.let(::update)
    }
    fun chooseFloating() { write(progress.copy(floatingChoiceResolved = true)) }
    fun acknowledgeBackground() {
        if (write(progress.copy(backgroundAcknowledged = true), required = true)) {
            mutableState.value = mutableState.value.copy(storageProblem = false)
            latest?.let(::update)
        }
    }
    fun beginPermission(permission: SetupPermission): Boolean {
        if (mutableState.value.pending != null) return false
        val bit = if (permission == SetupPermission.MICROPHONE) 1 else 2
        write(progress.copy(requested = progress.requested or bit))
        mutableState.value = mutableState.value.copy(pending = permission)
        return true
    }
    fun requested(permission: SetupPermission): Boolean = progress.requested and
        (if (permission == SetupPermission.MICROPHONE) 1 else 2) != 0
    fun beginNavigation(): Boolean {
        if (mutableState.value.navigationPending) return false
        mutableState.value = mutableState.value.copy(navigationPending = true)
        return true
    }
    fun navigationReturned() { mutableState.value = mutableState.value.copy(navigationPending = false) }
    fun permissionReturned(snapshot: SetupSnapshot) {
        mutableState.value = mutableState.value.copy(pending = null)
        update(snapshot)
    }
    private fun missing(snapshot: SetupSnapshot, includeLegacyHelp: Boolean): Int {
        val g = snapshot.grants
        var result = 0
        if (!snapshot.keyLoading && !snapshot.keyReady) result = result or KEY
        if (!g.microphone) result = result or MIC
        if ((!g.floatingWanted && !progress.floatingChoiceResolved) || (g.floatingWanted && !g.accessibility)) result = result or FLOATING
        if (g.floatingWanted && !g.notifications) result = result or NOTIFICATIONS
        if (g.floatingWanted && g.accessibility && g.notifications && !progress.backgroundAcknowledged &&
            (!progress.legacyReady || includeLegacyHelp)) result = result or BACKGROUND
        return result
    }
    companion object { private const val KEY = 1; private const val MIC = 2; private const val FLOATING = 4
        private const val NOTIFICATIONS = 8; private const val BACKGROUND = 16; private const val ALL = 31 }
}
