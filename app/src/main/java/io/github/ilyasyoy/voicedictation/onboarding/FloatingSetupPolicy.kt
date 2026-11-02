package io.github.ilyasyoy.voicedictation.onboarding

internal enum class FloatingSetupStep { ENABLE, MICROPHONE, ACCESSIBILITY, NOTIFICATIONS, BACKGROUND, READY }

/** Same order as onboarding once the optional capability has been chosen. No key/network prerequisite. */
internal object FloatingSetupPolicy {
    fun step(grants: SetupGrants, progress: SetupProgress): FloatingSetupStep = when {
        !grants.floatingWanted -> FloatingSetupStep.ENABLE
        !grants.microphone -> FloatingSetupStep.MICROPHONE
        !grants.accessibility -> FloatingSetupStep.ACCESSIBILITY
        !grants.notifications -> FloatingSetupStep.NOTIFICATIONS
        !progress.backgroundAcknowledged && !progress.legacyReady -> FloatingSetupStep.BACKGROUND
        else -> FloatingSetupStep.READY
    }
}
