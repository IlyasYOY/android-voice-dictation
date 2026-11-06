package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Explicit text-only history action. Has no recorder, audio upload or editor connection. */
internal suspend fun processSavedText(repository: RecordingRepository, id: String,
    settings: TextProcessingSettings, loadKey: suspend () -> String, processor: TextProcessor): String? {
    val token = repository.beginProcessing(id, settings) ?: return null
    try {
        val original = checkNotNull(repository.record(id)).transcript
        val key = loadKey()
        currentCoroutineContext().ensureActive()
        val text = processor.process(original, key, settings)
        currentCoroutineContext().ensureActive()
        if (!repository.completeProcessing(token, text)) return null
        return text
    } catch (error: Exception) {
        runCatching { repository.endProcessing(token, error is CancellationException) }
        throw error
    }
}
