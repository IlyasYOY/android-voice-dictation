package io.github.ilyasyoy.voicedictation.floating

import io.github.ilyasyoy.voicedictation.data.*
import io.github.ilyasyoy.voicedictation.session.*
import java.io.File
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import org.junit.rules.TemporaryFolder

/** Connect the shared session's final-result gate to the unchanged one-attempt editor writer. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProcessedInsertionTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun intermediateAndFailedTextNeverInsertAndSuccessfulProcessingWritesOnce() = runTest {
        for (fail in listOf(false, true)) {
            val response = CompletableDeferred<String>(); val writes = mutableListOf<String>()
            val editor = EditorToken("test.editor", 1, "body", 1, 7, 7, false, false, 0x20001, 0, -1, -1)
            val environment = { FloatingEnvironment(editor, true, true, true, true, true) }
            val session = DictationSession(backgroundScope, object : AudioRecorder {
                override fun start(onLimit: () -> Unit) = Unit
                override fun stop() = File(temporary.root, "synthetic-$fail.m4a").apply { writeText("synthetic") }
                override fun cancel() = Unit
            }, Transcriber { _, _ -> "Original must not insert" },
                processingSettings = { TextProcessingSettings(true) }, textProcessor = TextProcessor { _, _, _ -> response.await() })
            session.setKey("synthetic")
            var attempted = false
            val capture = FloatingCapture(backgroundScope, session, editor, environment, {}, { state ->
                if (state.phase == Phase.RESULT && !attempted) {
                    attempted = true
                    backgroundScope.launch {
                        FinalTextInsertion.insert(editor, state.text, object : EditorConnection {
                            override suspend fun surrounding(before: Int) = EditorSurrounding("", 7, 0, 0)
                            override fun commit(text: String) { writes += text }
                        }, environment) { true }
                    }
                }
            })
            capture.start(); runCurrent(); session.stop(); runCurrent()
            assertEquals(Phase.PROCESSING, session.state.value.phase); assertTrue(writes.isEmpty())
            repeat(3) { session.stop(); session.retry() }; runCurrent()
            if (fail) response.completeExceptionally(java.io.IOException()) else response.complete("Only edited text")
            runCurrent(); advanceTimeBy(300); runCurrent()
            assertEquals(if (fail) emptyList<String>() else listOf("Only edited text"), writes)
            capture.close()
        }
    }
}
