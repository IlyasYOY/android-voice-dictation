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
    @Test fun `both session owners keep recording on dictionary failure and explicit retry reads latest words`() = runTest {
        val root = java.nio.file.Files.createTempDirectory("session-vocabulary").toFile()
        val dictionary = VocabularyRepository(root)
        try {
            // Main and overlay use this same transport contract; ownership/lifecycle remain independent.
            for (owner in listOf("main", "overlay")) {
                val audio = File.createTempFile("vocabulary-$owner", ".m4a")
                val capture = object : AudioRecorder {
                    override fun start(onLimit: () -> Unit) { audio.writeText("synthetic-local-pcm") }
                    override fun stop() = audio
                    override fun cancel() = Unit
                }
                val snapshots = mutableListOf<List<String>>()
                var unavailable = true
                val session = DictationSession(backgroundScope, capture, Transcriber { _, _ ->
                    if (unavailable) throw VocabularyFailure(VocabularyIssue.STORAGE)
                    snapshots += dictionary.snapshot(); "Synthetic transcript"
                })
                try {
                    session.setKey("synthetic-key"); session.start(true); assertEquals(Phase.RECORDING, session.state.value.phase)
                    session.stop(); runCurrent()
                    assertEquals(Problem.LOCAL_STORAGE, session.state.value.problem)
                    assertTrue(session.state.value.canRetry); assertTrue(audio.exists())
                    dictionary.add("$owner new term"); unavailable = false; runCurrent()
                    assertTrue(snapshots.isEmpty()) // CRUD does not retry a failed upload.
                    session.retry(); runCurrent()
                    assertEquals(listOf(dictionary.snapshot()), snapshots); assertEquals(Phase.RESULT, session.state.value.phase)
                } finally { session.close(); audio.delete() }
            }
        } finally { root.deleteRecursively() }
    }
}
