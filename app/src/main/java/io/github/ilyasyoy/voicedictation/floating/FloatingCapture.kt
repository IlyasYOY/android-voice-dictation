package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.session.*
import kotlinx.coroutines.*

internal data class FloatingEnvironment(
    val editor: EditorToken?, val enabled: Boolean, val awake: Boolean, val keyboard: Boolean,
    val microphone: Boolean, val notifications: Boolean,
) {
    val visible get() = enabled && awake && keyboard
}

/** One explicit bubble tap owns this token until close; key loading cannot outlive that ownership. */
internal class FloatingCapture(
    scope: CoroutineScope,
    val session: DictationSession,
    private val origin: EditorToken,
    private val environment: () -> FloatingEnvironment,
    private val onInvalid: () -> Unit,
    private val onState: (DictationState) -> Unit,
) : AutoCloseable {
    private var closed = false
    private val observer = scope.launch(start = CoroutineStart.LAZY) {
        var launched = false
        session.state.collect { state ->
            if (closed) return@collect
            if (!launched) {
                launched = true
                // Recheck the captured editor immediately before local audio start; no key/network wait.
                if (!eligible(environment())) { onInvalid(); return@collect }
                session.start(true)
            }
            // Starting may synchronously replace the collected idle/storage-error state.
            if (!closed) onState(session.state.value)
        }
    }
    fun start() { if (!closed) observer.start() }
    fun eligible(now: FloatingEnvironment): Boolean = !closed && now.visible &&
        now.microphone && now.notifications && EditorPolicy.allowed(origin) && now.editor?.let { current ->
            EditorPolicy.allowed(current) && origin.sameEditor(current) &&
                origin.selectionStart == current.selectionStart && origin.selectionEnd == current.selectionEnd
        } == true
    override fun close() {
        if (closed) return
        closed = true; observer.cancel(); session.close()
    }
}
