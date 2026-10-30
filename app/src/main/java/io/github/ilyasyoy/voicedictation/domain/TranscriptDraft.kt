package io.github.ilyasyoy.voicedictation.domain

/** UI-independent draft state. This is text editing, not a recording session. */
data class TranscriptDraft(
    val text: String = "",
    val isExample: Boolean = false,
) {
    val canClear: Boolean get() = text.isNotEmpty()

    fun edit(value: String): TranscriptDraft = copy(text = value, isExample = false)

    fun clear(): TranscriptDraft = TranscriptDraft()
}
