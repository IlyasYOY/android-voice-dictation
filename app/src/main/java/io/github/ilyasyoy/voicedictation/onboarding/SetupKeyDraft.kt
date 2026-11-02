package io.github.ilyasyoy.voicedictation.onboarding

/** ViewModel memory only; never put this draft in SavedState or preferences. */
internal data class SetupKeyDraft(val text: String = "", val active: Boolean = false, val confirmed: Boolean = false) {
    val valid get() = text.isNotEmpty() && text.all { it.code in 33..126 }
    override fun toString() = "SetupKeyDraft(text=<redacted>, active=$active, confirmed=$confirmed)"
}
