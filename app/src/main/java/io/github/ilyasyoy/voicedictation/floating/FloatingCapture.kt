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
    private val onScreenOn: (Boolean) -> Unit = {},
) : AutoCloseable {
    private var closed = false
    var keepsScreenOn = false
        private set
    private fun screenOn(keep: Boolean) {
        val next = keep && !closed
        if (keepsScreenOn == next) return
        keepsScreenOn = next
        onScreenOn(next)
    }
    private val observer = scope.launch(start = CoroutineStart.LAZY) {
        var launched = false
        session.state.collect { state ->
            if (closed) return@collect
            if (!launched) {
                launched = true
                // Recheck the captured editor immediately before local audio start; no key/network wait.
                if (!eligible(environment())) { onInvalid(); return@collect }
                // Cover local recorder startup too; a failed start settles the request below.
                screenOn(true)
                session.start(true)
            }
            // Starting may synchronously replace the collected idle/storage-error state.
            if (!closed) {
                val current = session.state.value
                // One continuous lease across RECORDING -> TRANSCRIBING, including key waits.
                screenOn(current.audioBusy)
                onState(current)
            }
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
        closed = true
        try { screenOn(false) } finally { observer.cancel(); session.close() }
    }
}
