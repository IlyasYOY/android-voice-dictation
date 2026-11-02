package io.github.ilyasyoy.voicedictation.session

import io.github.ilyasyoy.voicedictation.data.*
import java.io.File
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DictationSessionTest {
    @Test fun `stale file-recorder limit cannot stop a subsequent recording`() = runTest {
        val callbacks = mutableListOf<() -> Unit>(); var stops = 0
        val recorder = object : AudioRecorder {
            override fun start(onLimit: () -> Unit) { callbacks += onLimit }
            override fun stop(): File { stops++; error("must stay recording") }
            override fun cancel() = Unit
        }
        val session = DictationSession(backgroundScope, recorder, Transcriber { _, _ -> "unused" })
        session.setKey("synthetic-key"); session.start(true); session.cancel(); session.start(true)
        callbacks.first().invoke(); runCurrent()
        assertEquals(Phase.RECORDING, session.state.value.phase); assertEquals(0, stops); session.close()
    }
    @Test fun `state diagnostics redact both credentials and transcript`() {
        val state = DictationState(key = "private-credential", text = "private dictation")
        assertFalse(state.toString().contains(state.key)); assertFalse(state.toString().contains(state.text))
    }
}
