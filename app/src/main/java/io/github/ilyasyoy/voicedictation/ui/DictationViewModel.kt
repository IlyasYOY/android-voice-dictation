package io.github.ilyasyoy.voicedictation.ui

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.createSavedStateHandle
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import io.github.ilyasyoy.voicedictation.data.ExampleTranscriptRepository
import io.github.ilyasyoy.voicedictation.data.LocalExampleTranscriptRepository
import io.github.ilyasyoy.voicedictation.domain.TranscriptDraft
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class DictationViewModel(
    private val savedStateHandle: SavedStateHandle,
    private val examples: ExampleTranscriptRepository,
) : ViewModel() {
    private val mutableDraft = MutableStateFlow(
        TranscriptDraft(
            text = savedStateHandle[TEXT_KEY] ?: "",
            isExample = savedStateHandle[EXAMPLE_KEY] ?: false,
        ),
    )
    val draft = mutableDraft.asStateFlow()

    fun edit(text: String) = update(mutableDraft.value.edit(text))

    fun loadExample() = update(TranscriptDraft(examples.loadExample(), isExample = true))

    fun clear() = update(mutableDraft.value.clear())

    private fun update(draft: TranscriptDraft) {
        mutableDraft.value = draft
        savedStateHandle[TEXT_KEY] = draft.text
        savedStateHandle[EXAMPLE_KEY] = draft.isExample
    }

    companion object {
        private const val TEXT_KEY = "draft.text"
        private const val EXAMPLE_KEY = "draft.isExample"

        val Factory = viewModelFactory {
            initializer {
                DictationViewModel(createSavedStateHandle(), LocalExampleTranscriptRepository)
            }
        }
    }
}
