package io.github.ilyasyoy.voicedictation

import org.junit.rules.ExternalResource

/** Existing feature scenarios isolate their own UI; OnboardingRuntimeTest covers first-run navigation. */
internal class ExistingFeatureSetupRule : ExternalResource() {
    override fun before() { MainActivity.testSkipOnboarding = true }
    override fun after() { MainActivity.testSkipOnboarding = false }
}
